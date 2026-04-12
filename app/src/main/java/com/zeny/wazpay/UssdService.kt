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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UssdService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val TAG = "WazPay-USSD"
    private val handler = Handler(Looper.getMainLooper())

    private val TELECOM_PACKAGES = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.android.systemui",
        "com.android.settings",
        "com.android.incallui"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Performance fix: Only process relevant telecom packages
        if (packageName !in TELECOM_PACKAGES) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val allTexts = mutableListOf<String>()
            findAllTextNodes(rootNode, allTexts)
            if (allTexts.isEmpty()) return

            Log.d(TAG, "USSD Text [${packageName}]: ${allTexts.joinToString(" | ")}")

            val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
            val pendingRecipient = sharedPreferences.getString("pending_recipient", null)
            val pendingAmount = sharedPreferences.getString("pending_amount", null)
            val pendingPin = sharedPreferences.getString("pending_pin", null)

            // 1. PIN Entry (High Priority)
            if (pendingPin != null && isActuallyPinPrompt(allTexts)) {
                Log.i(TAG, "Step: PIN Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingPin)
                    it.recycle()
                    return
                }
            }

            // 2. Confirmation Prompt (High Priority - before Success check)
            if (isConfirmationPrompt(allTexts)) {
                Log.i(TAG, "Step: Confirmation Detected - Replying 1")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, "1")
                    it.recycle()
                    return
                }
            }

            // 3. Recipient Entry
            if (pendingRecipient != null && isRecipientInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Recipient Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingRecipient)
                    it.recycle()
                    return
                }
            }

            // 4. Amount Entry
            if (pendingAmount != null && isAmountInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Amount Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingAmount)
                    it.recycle()
                    return
                }
            }

            // 5. Remark Prompt
            if (isRemarkInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Remark Prompt - Skipping")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, "1")
                    it.recycle()
                    return
                }
            }

            // 6. Feedback Screen
            if (isFeedbackPrompt(allTexts)) {
                Log.i(TAG, "Step: Feedback Prompt - Replying with brand message")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, "payed with wazpay!")
                    it.recycle()
                    return
                }
            }

            // 7. Exit Dialog / Success Screen
            if (isExitDialog(allTexts)) {
                Log.i(TAG, "Step: Exit Dialog Detected")
                val isSuccess = isSuccessMessage(allTexts)
                if (isSuccess) {
                    Log.i(TAG, "Definitive Success. Clearing pending data.")
                    sharedPreferences.edit { 
                        putBoolean("last_payment_success", true)
                        remove("pending_recipient")
                        remove("pending_amount")
                        remove("pending_pin")
                    }
                    saveTransaction(sharedPreferences, "SUCCESS")
                }
                extractSuccessData(allTexts)

                val editText = findNodeByClassName(rootNode, "android.widget.EditText")
                if (editText != null) {
                    autoFillAndSend(editText, "2")
                    editText.recycle()
                } else {
                    clickSendOrOk()
                }

                if (isSuccess) bringAppToForeground(delay = 1000)
                return
            }

            // 8. Thank You / Final Screen
            if (isThankYouDialog(allTexts)) {
                Log.i(TAG, "Step: Final Success Screen Detected")
                sharedPreferences.edit { 
                    putBoolean("last_payment_success", true)
                    remove("pending_recipient")
                    remove("pending_amount")
                    remove("pending_pin")
                }
                saveTransaction(sharedPreferences, "SUCCESS")
                clickSendOrOk()
                bringAppToForeground(delay = 500)
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun extractSuccessData(texts: List<String>) {
        val fullText = texts.joinToString(" ")
        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)

        val refRegex = Regex("(?:Ref\\s*Id|Txn\\s*Id|Reference|ID|ID is)[:\\s]*([\\dA-Z]+)", RegexOption.IGNORE_CASE)
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

    private fun isConfirmationPrompt(texts: List<String>): Boolean =
        texts.any { (it.contains("1.Confirm", true) || it.contains("1. Confirm", true) || it.contains("1.Send", true) || it.contains("1. Send", true)) && it.contains("₹", true) }

    private fun isFeedbackPrompt(texts: List<String>): Boolean = 
        texts.any { 
            it.contains("Feedback", true) || it.contains("comment", true) || it.contains("rate", true) || 
            it.contains("thank", true) && it.contains("using", true) || it.contains("Experience", true) ||
            it.contains("Quality", true) || it.contains("Service", true) || 
            it.contains("Opinion", true) || (it.contains("UPI", true) && it.contains("Transaction", true))
        }

    private fun isActuallyPinPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Enter UPI Pin", true) || it.contains("Enter Pin", true) }

    private fun isExitDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("2 to exit", true) || it.contains("2. Exit", true) || it.contains("00. Exit", true) || it.contains("press 2", true) }

    private fun isThankYouDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("Thank you", true) || it.contains("Payment Sent", true) || it.contains("completed", true) || it.contains("Request processed", true) }

    private fun isSuccessMessage(texts: List<String>): Boolean {
        val fullText = texts.joinToString(" ").lowercase()
        return (fullText.contains("success") || 
               fullText.contains("sent") || 
               fullText.contains("successful") || 
               fullText.contains("request processed") ||
               fullText.contains("ref id") ||
               fullText.contains("txn id")) &&
               !fullText.contains("1.confirm") &&
               !fullText.contains("1. confirm")
    }

    private fun findAllTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>, depth: Int = 0) {
        if (depth > 20) return
        if (node.text != null) texts.add(node.text.toString())
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllTextNodes(child, texts, depth + 1)
                child.recycle()
            }
        }
    }

    private fun autoFillAndSend(editText: AccessibilityNodeInfo, text: String) {
        try {
            Log.d(TAG, "Action: Auto-filling text: $text")
            val arguments = Bundle().apply { 
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) 
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            handler.postDelayed({ clickSendOrOk() }, 600)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-fill", e)
        }
    }

    private fun clickSendOrOk() {
        handler.post {
            val currentRoot = rootInActiveWindow ?: return@post
            try {
                val button = findButton(currentRoot)
                if (button != null) {
                    Log.d(TAG, "Action: Clicking ${button.text}")
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    button.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to click button", e)
            } finally {
                currentRoot.recycle()
            }
        }
    }

    private fun findButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("android.widget.Button") == true) {
            val btnText = node.text?.toString()?.lowercase() ?: ""
            if (btnText in listOf("send", "ok", "dismiss", "answer", "submit", "accept")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findButton(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains(className) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByClassName(child, className)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
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

    private fun saveTransaction(prefs: android.content.SharedPreferences, status: String) {
        // Prevent duplicate saves for the same session if possible
        val recipient = prefs.getString("last_recipient_name", "Unknown") ?: "Unknown"
        val amount = prefs.getString("pending_amount", "0") ?: "0"
        val refId = prefs.getString("last_ref_id", null)

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.transactionDao().insert(
                    Transaction(
                        recipient = recipient,
                        amount = amount,
                        refId = refId,
                        status = status
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save transaction to Room", e)
            }
        }
    }

    override fun onInterrupt() {}
}
