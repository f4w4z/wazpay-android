package com.zeny.wazpay

import android.accessibilityservice.AccessibilityService
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit

class UssdService : AccessibilityService() {

    private val TAG = "WazPay-USSD"
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        
        val rootNode = rootInActiveWindow ?: return
        val allTexts = findAllTextNodes(rootNode)
        if (allTexts.isEmpty()) return

        Log.d(TAG, "USSD Text Detected: ${allTexts.joinToString(" | ")}")

        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
        val pendingRecipient = sharedPreferences.getString("pending_recipient", null)
        val pendingAmount = sharedPreferences.getString("pending_amount", null)
        val pendingPin = sharedPreferences.getString("pending_pin", null)

        // 1. Handle Recipient Entry
        if (pendingRecipient != null && isRecipientInputPrompt(allTexts)) {
            Log.i(TAG, "Step: Recipient Input Detected")
            findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()?.let {
                autoFillAndSend(it, pendingRecipient)
                sharedPreferences.edit { remove("pending_recipient") }
                return
            }
        }

        // 2. Handle Amount Entry
        if (pendingAmount != null && isAmountInputPrompt(allTexts)) {
            Log.i(TAG, "Step: Amount Input Detected")
            findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()?.let {
                autoFillAndSend(it, pendingAmount)
                sharedPreferences.edit { remove("pending_amount") }
                return
            }
        }

        // 3. Handle Remark Prompt
        if (isRemarkInputPrompt(allTexts)) {
            Log.i(TAG, "Step: Remark Prompt - Skipping")
            findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()?.let {
                autoFillAndSend(it, "1")
                return
            }
        }

        // 4. Handle PIN Entry
        if (pendingPin != null && isActuallyPinPrompt(allTexts)) {
            Log.i(TAG, "Step: PIN Input Detected")
            findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()?.let {
                autoFillAndSend(it, pendingPin)
                sharedPreferences.edit { remove("pending_pin") }
                return 
            }
        }

        // 5. Handle Post-Payment Success/Exit Screen
        if (isExitDialog(allTexts)) {
            Log.i(TAG, "Step: Exit Dialog Detected")
            val isSuccess = isSuccessMessage(allTexts)
            if (isSuccess) {
                Log.i(TAG, "Marking payment as success based on Exit Dialog content")
                sharedPreferences.edit { putBoolean("last_payment_success", true) }
            }
            extractSuccessData(allTexts)
            
            val editTexts = findNodesByClassName(rootNode, "android.widget.EditText")
            if (editTexts.isNotEmpty()) {
                autoFillAndSend(editTexts.first(), "2")
            } else {
                clickSendOrOk()
            }
            
            if (isSuccess) bringAppToForeground(delay = 1000)
            return
        }

        // 6. Handle Feedback Screen
        if (isFeedbackPrompt(allTexts)) {
            Log.i(TAG, "Step: Feedback Prompt - Replying")
            findNodesByClassName(rootNode, "android.widget.EditText").firstOrNull()?.let {
                autoFillAndSend(it, "1")
                return
            }
        }

        // 7. Handle Thank You / Final Screen
        if (isThankYouDialog(allTexts)) {
            Log.i(TAG, "Step: Final Success Screen Detected")
            sharedPreferences.edit { putBoolean("last_payment_success", true) }
            clickSendOrOk()
            bringAppToForeground(delay = 500)
        }
    }

    private fun extractSuccessData(texts: List<String>) {
        val fullText = texts.joinToString(" ")
        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)

        val refRegex = Regex("(?:Ref\\s*Id|Txn\\s*Id|Reference|ID)[:\\s]*([\\dA-Z]+)", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("(?:payment to|Sent to|Paid to|Transfer to|to)[:\\s]+([^\\d\\n,]+)", RegexOption.IGNORE_CASE)

        sharedPreferences.edit {
            refRegex.find(fullText)?.let { 
                val refId = it.groupValues[1]
                Log.i(TAG, "Extracted Ref ID: $refId")
                putString("last_ref_id", refId)
            }
            nameRegex.find(fullText)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotEmpty() && !name.equals("Exit", true) && !name.equals("Cancel", true)) {
                    Log.i(TAG, "Extracted Recipient Name: $name")
                    putString("last_recipient_name", name)
                }
            }
        }
    }

    private fun isRecipientInputPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Enter UPI ID", true) || it.contains("VPA", true) || it.contains("Enter Mobile Number", true) || it.contains("Enter Number", true) || it.contains("Beneficiary", true) }

    private fun isAmountInputPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Enter amount", true) }

    private fun isRemarkInputPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Remark", true) || it.contains("1 to skip", true) }

    private fun isFeedbackPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Feedback", true) || it.contains("comment", true) || it.contains("rate", true) }

    private fun isActuallyPinPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Enter UPI Pin", true) || it.contains("Enter Pin", true) }

    private fun isExitDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("2 to exit", true) || it.contains("2. Exit", true) || it.contains("00. Exit", true) || it.contains("press 2", true) }

    private fun isThankYouDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("Thank you", true) || it.contains("Payment Sent", true) || it.contains("completed", true) || it.contains("Request processed", true) }

    private fun isSuccessMessage(texts: List<String>): Boolean {
        val fullText = texts.joinToString(" ").lowercase()
        return fullText.contains("success") || 
               fullText.contains("sent") || 
               fullText.contains("successful") || 
               fullText.contains("transfer") || 
               fullText.contains("₹") ||
               fullText.contains("paid")
    }

    private fun findAllTextNodes(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        try {
            if (node.text != null) texts.add(node.text.toString())
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { texts.addAll(findAllTextNodes(it)) }
            }
        } catch (_: Exception) {}
        return texts
    }

    private fun autoFillAndSend(editText: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Action: Auto-filling text: $text")
            val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            handler.postDelayed({ clickSendOrOk() }, 600)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-fill", e)
        }
    }

    private fun clickSendOrOk() {
        handler.post {
            try {
                val currentRoot = rootInActiveWindow ?: return@post
                val buttons = findNodesByClassName(currentRoot, "android.widget.Button")
                val submitButton = buttons.find { 
                    val btnText = it.text?.toString()?.lowercase() ?: ""
                    btnText == "send" || btnText == "ok" || btnText == "dismiss" || btnText == "answer" || btnText == "submit" || btnText == "accept"
                }
                if (submitButton != null) {
                    Log.d(TAG, "Action: Clicking ${submitButton.text}")
                    submitButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to click button", e)
            }
        }
    }

    private fun findNodesByClassName(node: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val foundNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (node.className?.toString()?.contains(className) == true) foundNodes.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { foundNodes.addAll(findNodesByClassName(it, className)) }
            }
        } catch (_: Exception) {}
        return foundNodes
    }

    private fun bringAppToForeground(delay: Long) {
        handler.postDelayed({
            try {
                Log.i(TAG, "Action: Bringing app to foreground")
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bring app to foreground", e)
            }
        }, delay)
    }

    override fun onInterrupt() {}
}
