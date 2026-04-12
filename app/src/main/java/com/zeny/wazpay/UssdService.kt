package com.zeny.wazpay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

            // 1. PIN Entry
            if (pendingPin != null && isActuallyPinPrompt(allTexts)) {
                findInputNode(rootNode)?.let {
                    autoFillAndSend(it, pendingPin)
                    return
                }
            }

            // 2. Menu Navigation
            if (isSendMoneyMenu(allTexts)) {
                val isMobile = pendingRecipient?.all { it.isDigit() } == true && pendingRecipient.length >= 10
                val searchKeywords = if (isMobile) listOf("Mobile Number", "Mobile", "Phon") else listOf("UPI ID", "VPA", "Virtual ID")
                
                val option = findMenuOption(allTexts, searchKeywords)
                if (option != null) {
                    findInputNode(rootNode)?.let {
                        autoFillAndSend(it, option)
                        return
                    }
                }
            }

            // 3. Recipient Entry
            if (pendingRecipient != null && isRecipientInputPrompt(allTexts)) {
                findInputNode(rootNode)?.let {
                    autoFillAndSend(it, pendingRecipient)
                    return
                }
            }

            // 4. Amount Entry
            if (pendingAmount != null && isAmountInputPrompt(allTexts)) {
                findInputNode(rootNode)?.let {
                    autoFillAndSend(it, pendingAmount)
                    return
                }
            }

            // 5. Remark Prompt
            if (isRemarkInputPrompt(allTexts)) {
                findInputNode(rootNode)?.let {
                    autoFillAndSend(it, "1")
                    return
                }
            }

            // 6. Confirmation
            if (isConfirmationPrompt(allTexts)) {
                findInputNode(rootNode)?.let {
                    autoFillAndSend(it, "1")
                    return
                }
            }

            // 7. Success/Feedback Detection
            if (isSuccessMessage(allTexts)) {
                sharedPreferences.edit { 
                    putBoolean("last_payment_success", true)
                    remove("pending_recipient")
                    remove("pending_amount")
                    remove("pending_pin")
                }
                extractSuccessData(allTexts)
            }

            // Handle Exit Prompt
            if (isExitDialog(allTexts)) {
                findInputNode(rootNode)?.let {
                    Log.i(TAG, "Exit Dialog Detected - Replying 2")
                    autoFillAndSend(it, "2")
                    return
                }
            }

            if (isFeedbackPrompt(allTexts)) {
                handleFeedback(rootNode, allTexts, sharedPreferences)
                return
            }

            // Error handling
            if (isErrorMessage(allTexts) && !isActuallyPinPrompt(allTexts) && !isRecipientInputPrompt(allTexts)) {
                sharedPreferences.edit { putBoolean("transaction_in_progress", false) }
                clickSendOrOk()
                return
            }

        } catch (e: Exception) { Log.e(TAG, "Service Error", e) }
    }

    private fun handleFeedback(rootNode: AccessibilityNodeInfo, allTexts: List<String>, prefs: android.content.SharedPreferences) {
        Log.i(TAG, "Dismissing final dialog")
        val cancelButton = findClickableWithKeywords(rootNode, listOf("cancel", "exit", "close", "dismiss"))
        if (cancelButton != null) {
            cancelButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            clickSendOrOk()
        }
        prefs.edit { putBoolean("transaction_in_progress", false) }
        bringAppToForeground(delay = 600)
    }

    private fun isFeedbackPrompt(texts: List<String>): Boolean {
        val fullText = texts.joinToString(" ").lowercase()
        return fullText.contains("thank you for using our services")
    }

    private fun isActuallyPinPrompt(texts: List<String>): Boolean {
        val fullText = texts.joinToString("\n")
        return fullText.contains("Enter UPI Pin", true) || fullText.contains("Enter Pin", true) || fullText.contains("MPIN", true)
    }

    private fun isSendMoneyMenu(texts: List<String>): Boolean {
        val fullText = texts.joinToString("\n")
        return (fullText.contains("Send Money", true) || fullText.contains("Transfer", true)) && 
                Regex("\\d\\.?\\s*(Mobile|UPI|VPA)", RegexOption.IGNORE_CASE).containsMatchIn(fullText)
    }

    private fun isRecipientInputPrompt(texts: List<String>): Boolean {
        val fullText = texts.joinToString("\n")
        return (fullText.contains("Enter", true) || fullText.contains("Mobile", true) || fullText.contains("UPI", true)) && 
               !fullText.contains("PIN", true) && !fullText.contains("Amount", true) && 
               !fullText.contains("Remark", true) && !isSendMoneyMenu(texts)
    }

    private fun isRemarkInputPrompt(texts: List<String>): Boolean = texts.any { it.contains("Remark", true) }

    private fun isExitDialog(texts: List<String>): Boolean = texts.any { it.contains("2 to exit", true) || it.contains("2. Exit", true) }

    private fun isAmountInputPrompt(texts: List<String>): Boolean = texts.any { it.contains("Enter Amount", true) }

    private fun isConfirmationPrompt(texts: List<String>): Boolean = texts.any { it.contains("Confirm", true) || it.contains("Proceed", true) }

    private fun isSuccessMessage(texts: List<String>): Boolean {
        val fullText = texts.joinToString(" ").lowercase()
        return (fullText.contains("success") || fullText.contains("completed") || 
                fullText.contains("sent to") || fullText.contains("paid to")) && !fullText.contains("1.confirm")
    }

    private fun isErrorMessage(texts: List<String>): Boolean = texts.any { it.contains("failed", true) || it.contains("invalid", true) }

    private fun findMenuOption(texts: List<String>, keywords: List<String>): String? {
        val fullText = texts.joinToString("\n")
        for (k in keywords) { Regex("(\\d)\\.?\\s*$k", RegexOption.IGNORE_CASE).find(fullText)?.let { return it.groupValues[1] } }
        return null
    }

    private fun findAllTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.let { texts.add(it.toString()) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findAllTextNodes(it, texts) }
    }

    private fun autoFillAndSend(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({ clickSendOrOk() }, 800)
    }

    private fun findInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { findInputNode(it)?.let { found -> return found } }
        return null
    }

    private fun clickSendOrOk() {
        val root = rootInActiveWindow ?: return
        
        // 1. Try to find by positive keywords (Best)
        val button = findClickableWithKeywords(root, listOf("send", "ok", "submit", "accept", "reply", "answer", "done", "confirm"))
        if (button != null) {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // 2. Fallback to finding the last unlabeled button (Safest for custom dialogs)
        val unlabeledButtons = mutableListOf<AccessibilityNodeInfo>()
        findAllUnlabeledButtons(root, unlabeledButtons)
        
        if (unlabeledButtons.isNotEmpty()) {
            // Pick the last one (usually the right-most 'Send' button)
            val positiveButton = unlabeledButtons.last()
            Log.i(TAG, "Clicking unlabeled button (fallback)")
            positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun findClickableWithKeywords(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (keywords.any { text.contains(it) || contentDesc.contains(it) }) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return AccessibilityNodeInfo.obtain(current)
                current = current.parent
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findClickableWithKeywords(child, keywords)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findAllUnlabeledButtons(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        // If it's a clickable button
        if (node.isClickable && node.className?.contains("Button", true) == true) {
            // EXCLUDE nodes that are clearly 'Cancel' or 'Exit'
            if (!text.contains("cancel") && !text.contains("exit") && !text.contains("close") &&
                !desc.contains("cancel") && !desc.contains("exit") && !desc.contains("close")) {
                list.add(AccessibilityNodeInfo.obtain(node))
            }
        }
        
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findAllUnlabeledButtons(it, list) }
        }
    }

    private fun extractSuccessData(texts: List<String>) {
        val fullText = texts.joinToString(" ")
        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
        val refRegex = Regex("(?:RefId|Ref|Txn|Reference|ID|Id is)[:\\s]*([A-Z\\d]{8,})", RegexOption.IGNORE_CASE)
        sharedPreferences.edit { 
            refRegex.find(fullText)?.let { putString("last_ref_id", it.groupValues[1]) }
        }
    }

    private fun bringAppToForeground(delay: Long) {
        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java).apply { 
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) 
            })
        }, delay)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "WazPay-USSD"
        private val telecomPackages = setOf("com.android.phone", "com.android.server.telecom", "com.google.android.dialer", "com.samsung.android.incallui", "com.android.systemui")
    }
}
