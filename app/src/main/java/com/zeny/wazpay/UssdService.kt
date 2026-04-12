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

            // 5. Remark Prompt (New)
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

            // 7. Success/Feedback Detection (Final Steps)
            if (isSuccessMessage(allTexts)) {
                sharedPreferences.edit { 
                    putBoolean("last_payment_success", true)
                    remove("pending_recipient")
                    remove("pending_amount")
                    remove("pending_pin")
                }
                extractSuccessData(allTexts)
            }

            // Handle Exit Prompt specifically (New)
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
        val cancelButton = findCancelButton(rootNode) ?: findButtonByText(rootNode, "cancel")
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

    private fun isSuccessMessage(texts: List<String>): Boolean = texts.any { it.contains("success", true) || it.contains("completed", true) }

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
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({ clickSendOrOk() }, 600)
    }

    private fun findInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { findInputNode(it)?.let { found -> return found } }
        return null
    }

    private fun clickSendOrOk() {
        rootInActiveWindow?.let { root -> findButton(root)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun findButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val txt = node.text?.toString()?.lowercase() ?: ""
            if (txt in listOf("send", "ok", "submit", "accept")) return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findButton(it)?.let { found -> return found } }
        return null
    }

    private fun findCancelButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val txt = node.text?.toString()?.lowercase() ?: ""
            if (txt == "cancel" || txt == "exit") return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findCancelButton(it)?.let { found -> return found } }
        return null
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.isClickable && node.text?.toString()?.equals(text, true) == true) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { findButtonByText(it, text)?.let { found -> return found } }
        return null
    }

    private fun findAnyClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && !node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { findAnyClickable(it)?.let { found -> return found } }
        return null
    }

    private fun extractSuccessData(texts: List<String>) {
        val fullText = texts.joinToString(" ")
        val sharedPreferences = getSharedPreferences("wazpay_prefs", MODE_PRIVATE)
        val refRegex = Regex("(?:Ref|Txn)[:\\s]*([\\dA-Z]+)", RegexOption.IGNORE_CASE)
        sharedPreferences.edit { refRegex.find(fullText)?.let { putString("last_ref_id", it.groupValues[1]) } }
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
