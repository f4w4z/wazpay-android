package com.zeny.wazpay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit

class UssdService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in telecomPackages) return

        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
        val isTransactionActive = sharedPreferences.getBoolean("transaction_in_progress", false)
        if (!isTransactionActive) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val allTexts = mutableListOf<String>()
            findAllTextNodes(rootNode, allTexts)
            if (allTexts.isEmpty()) return

            Log.d(TAG, "USSD Text [${packageName}]: ${allTexts.joinToString(" | ")}")

            val pendingRecipient = sharedPreferences.getString("pending_recipient", null)
            val pendingAmount = sharedPreferences.getString("pending_amount", null)
            val pendingPin = sharedPreferences.getString("pending_pin", null)

            // --- Error Detection Logic ---
            if (isErrorMessage(allTexts)) {
                Log.e(TAG, "USSD Error detected: ${allTexts.joinToString(" | ")}")
                sharedPreferences.edit { 
                    putBoolean("transaction_in_progress", false)
                    putString("last_error", allTexts.firstOrNull { it.contains("invalid", true) || it.contains("valid", true) } ?: "Service not supported by your SIM/Bank")
                }
                // Try to dismiss the error dialog
                clickSendOrOk()
                bringAppToForeground(delay = 1000)
                return
            }

            // --- Success Detection Logic (Always check) ---
            val isSuccess = isSuccessMessage(allTexts)
            if (isSuccess && pendingRecipient != null) {
                Log.i(TAG, "Success Detected! Saving data.")
                sharedPreferences.edit { 
                    putBoolean("last_payment_success", true)
                    // Set fallbacks from pending data
                    putString("last_recipient_name", pendingRecipient)
                    putString("last_amount", pendingAmount)
                    
                    remove("pending_recipient")
                    remove("pending_amount")
                    remove("pending_pin")
                }
                extractSuccessData(allTexts)
            }

            // 1. PIN Entry
            if (pendingPin != null && isActuallyPinPrompt(allTexts)) {
                Log.i(TAG, "Step: PIN Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingPin)
                    return
                }
            }

            // 2. Confirmation Prompt
            if (isConfirmationPrompt(allTexts)) {
                Log.i(TAG, "Step: Confirmation Detected - Replying 1")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, "1")
                    return
                }
            }

            // --- Dynamic Menu Navigation (New) ---
            if (isSendMoneyMenu(allTexts)) {
                val isMobile = pendingRecipient?.all { it.isDigit() } == true && pendingRecipient.length >= 10
                val searchKeywords = if (isMobile) listOf("Mobile Number", "Mobile", "Phon") else listOf("UPI ID", "VPA", "Virtual ID")
                
                val option = findMenuOption(allTexts, searchKeywords)
                if (option != null) {
                    Log.i(TAG, "Step: Navigating Menu - Found $option for ${if (isMobile) "Mobile" else "UPI"}")
                    findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                        autoFillAndSend(it, option)
                        return
                    }
                }
            }

            // 3. Recipient Entry
            if (pendingRecipient != null && isRecipientInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Recipient Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingRecipient)
                    return
                }
            }

            // 4. Amount Entry
            if (pendingAmount != null && isAmountInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Amount Input Detected")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, pendingAmount)
                    return
                }
            }

            // 5. Remark Prompt
            if (isRemarkInputPrompt(allTexts)) {
                Log.i(TAG, "Step: Remark Prompt - Skipping")
                findNodeByClassName(rootNode, "android.widget.EditText")?.let {
                    autoFillAndSend(it, "1")
                    return
                }
            }

            // 6. Feedback Screen (Click Cancel)
            if (isFeedbackPrompt(allTexts)) {
                Log.i(TAG, "Step: Feedback Prompt - Clicking Cancel")
                val isSuccessFeedback = isSuccessMessage(allTexts)
                if (isSuccessFeedback && pendingRecipient != null) {
                    sharedPreferences.edit { 
                        putBoolean("last_payment_success", true)
                        putString("last_recipient_name", pendingRecipient)
                        putString("last_amount", pendingAmount)
                        
                        remove("pending_recipient")
                        remove("pending_amount")
                        remove("pending_pin")
                    }
                    extractSuccessData(allTexts)
                }
                
                val cancelButton = findButtonByText(rootNode, "cancel")
                if (cancelButton != null) {
                    Log.d(TAG, "Action: Clicking Cancel Button specifically")
                    cancelButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    clickSendOrOk()
                }
                if (isSuccess || isSuccessFeedback) bringAppToForeground(delay = 1000)
                return
            }

            // 7. Exit Dialog
            if (isExitDialog(allTexts)) {
                Log.i(TAG, "Step: Exit Dialog Detected")
                sharedPreferences.edit { putBoolean("transaction_in_progress", false) }
                val editText = findNodeByClassName(rootNode, "android.widget.EditText")
                if (editText != null) {
                    autoFillAndSend(editText, "2")
                } else {
                    clickSendOrOk()
                }
                if (isSuccess) bringAppToForeground(delay = 1000)
                return
            }

            // 8. Thank You / Final Screen
            if (isThankYouDialog(allTexts)) {
                Log.i(TAG, "Step: Final Success Screen Detected")
                sharedPreferences.edit { putBoolean("transaction_in_progress", false) }
                clickSendOrOk()
                bringAppToForeground(delay = 500)
                return
            }
        } finally {
            // No cleanup needed
        }
    }

    private fun extractSuccessData(texts: List<String>) {
        val fullText = texts.joinToString(" ")
        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)

        val refRegex = Regex("(?:Ref\\s*Id|Txn\\s*Id|Reference|ID|ID is)[:\\s]*([\\dA-Z]+)", RegexOption.IGNORE_CASE)
        // More specific name regex: removed naked "to" to avoid matching promo sentences
        val nameRegex = Regex("(?:payment to|Sent to|Paid to|Transfer to)[:\\s]+([^\\d\\n,]{2,35})", RegexOption.IGNORE_CASE)
        val amountRegex = Regex("(?:RS|INR|₹)\\s*([\\d,.]+)", RegexOption.IGNORE_CASE)

        sharedPreferences.edit {
            refRegex.find(fullText)?.let { 
                val refId = it.groupValues[1]
                Log.i(TAG, "Extracted Ref ID: $refId")
                putString("last_ref_id", refId)
            }
            nameRegex.find(fullText)?.let {
                val name = it.groupValues[1].trim()
                // Extra validation: Names shouldn't usually have many spaces or special chars
                if (name.isNotEmpty() && name.length <= 30 && !name.contains("...") && 
                    !name.equals("Exit", true) && !name.equals("Cancel", true)) {
                    Log.i(TAG, "Extracted Recipient Name: $name")
                    putString("last_recipient_name", name)
                }
            }
            amountRegex.find(fullText)?.let {
                val amountValue = it.groupValues[1].replace(",", "")
                Log.i(TAG, "Extracted Amount: $amountValue")
                putString("last_amount", amountValue)
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

    private fun isSendMoneyMenu(texts: List<String>): Boolean =
        texts.any { it.contains("Send Money", true) } && (texts.any { it.contains("UPI ID", true) || it.contains("VPA", true) || it.contains("Mobile", true) })

    private fun findMenuOption(texts: List<String>, keywords: List<String>): String? {
        val fullText = texts.joinToString("\n")
        for (keyword in keywords) {
            val regex = Regex("(\\d)\\.?\\s*${keyword}", RegexOption.IGNORE_CASE)
            regex.find(fullText)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun isFeedbackPrompt(texts: List<String>): Boolean = 
        texts.any { 
            it.contains("Feedback", true) || it.contains("comment", true) || it.contains("rate", true) || 
            (it.contains("thank", true) && it.contains("using", true)) || it.contains("Experience", true) ||
            it.contains("Quality", true) || it.contains("Service", true) || 
            it.contains("Opinion", true)
        }

    private fun isActuallyPinPrompt(texts: List<String>): Boolean = 
        texts.any { it.contains("Enter UPI Pin", true) || it.contains("Enter Pin", true) }

    private fun isExitDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("2 to exit", true) || it.contains("2. Exit", true) || it.contains("00. Exit", true) || it.contains("press 2", true) }

    private fun isThankYouDialog(texts: List<String>): Boolean = 
        texts.any { it.contains("Thank you", true) || it.contains("Payment Sent", true) || it.contains("completed", true) || it.contains("Request processed", true) }

    private fun isErrorMessage(texts: List<String>): Boolean = 
        texts.any { 
            it.contains("invalid", true) || it.contains("not a valid", true) || 
            it.contains("error", true) || it.contains("failed", true) || 
            it.contains("wrong", true) || it.contains("denied", true) ||
            it.contains("limit", true) || it.contains("insufficient", true) ||
            it.contains("unable", true) || it.contains("not supported", true)
        }

    private fun isSuccessMessage(texts: List<String>): Boolean {
        // Safety check: if it's a PIN prompt or Input prompt, it's not a success message yet
        if (isActuallyPinPrompt(texts) || isRecipientInputPrompt(texts) || isAmountInputPrompt(texts)) return false

        val fullText = texts.joinToString(" ").lowercase()
        return (fullText.contains("success") || 
               fullText.contains("sent") || 
               fullText.contains("successful") || 
               fullText.contains("completed") ||
               fullText.contains("processed") ||
               fullText.contains("request processed") ||
               fullText.contains("ref id") ||
               fullText.contains("txn id") ||
               fullText.contains("thank you")) &&
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to click button", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("android.widget.Button") == true) {
            val btnText = node.text?.toString()?.lowercase() ?: ""
            // Exclude "cancel" from default button finding to avoid clicking it in PIN dialogs
            if (btnText in listOf("send", "ok", "dismiss", "answer", "submit", "accept")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findButton(child)
                if (found != null) return found
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findButtonByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("android.widget.Button") == true) {
            if (node.text?.toString()?.equals(text, true) == true) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findButtonByText(child, text)
                if (found != null) return found
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains(className) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByClassName(child, className)
                if (found != null) return found
            }
        }
        return null
    }

    private fun bringAppToForeground(delay: Long) {
        handler.postDelayed({
            try {
                val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
                val wasUserInitiated = sharedPreferences.getBoolean("transaction_in_progress", false)
                
                if (!wasUserInitiated) {
                    Log.w(TAG, "Blocking unsolicited foreground jump. App was not expecting a transaction.")
                    return@postDelayed
                }

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

    companion object {
        private const val TAG = "WazPay-USSD"
        private val telecomPackages = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.android.systemui",
            "com.android.settings",
            "com.android.incallui"
        )
    }
}
