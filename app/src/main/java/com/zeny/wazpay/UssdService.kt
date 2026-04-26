package com.zeny.wazpay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zeny.wazpay.logic.PreferenceManager
import com.zeny.wazpay.logic.UssdParser
import com.zeny.wazpay.logic.UssdScreen

class UssdService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val submitAction = Runnable { clickSendOrOk() }
    private lateinit var prefs: PreferenceManager
    private var lastHandledSignature: String? = null
    private var lastHandledAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!prefs.transactionInProgress) {
            resetProcessingGuards()
            return
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val allTexts = mutableListOf<String>()
            findAllTextNodes(rootNode, allTexts)
            if (allTexts.isEmpty()) return
            if (!shouldHandleWindow(packageName)) return

            Log.d(TAG, "USSD Text [${packageName}]: ${allTexts.joinToString(" | ")}")

            val screen = UssdParser.parse(allTexts)
            if (shouldSkipDuplicate(screen, allTexts)) return
            Log.d(TAG, "Detected Screen: ${screen::class.simpleName}")

            when (screen) {
                is UssdScreen.WelcomeDialog -> {
                    clickSendOrOk()
                }
                is UssdScreen.IfscInput -> {
                    prefs.bankIfsc?.let { ifsc ->
                        findInputNode(rootNode)?.let { autoFillAndSend(it, ifsc) }
                    }
                }
                is UssdScreen.PinInput -> {
                    prefs.pendingPin?.let { pin ->
                        findInputNode(rootNode)?.let { autoFillAndSend(it, pin) }
                    }
                }
                is UssdScreen.SendMoneyMenu -> {
                    val recipient = prefs.pendingRecipient
                    val isMobile = recipient?.all { it.isDigit() } == true && recipient.length >= 10
                    UssdParser.findMenuOption(allTexts.joinToString("\n"), isMobile)?.let { option ->
                        findInputNode(rootNode)?.let { autoFillAndSend(it, option) }
                    }
                }
                is UssdScreen.RecipientInput -> {
                    prefs.pendingRecipient?.let { recipient ->
                        findInputNode(rootNode)?.let { autoFillAndSend(it, recipient) }
                    }
                }
                is UssdScreen.AmountInput -> {
                    prefs.pendingAmount?.let { amount ->
                        findInputNode(rootNode)?.let { autoFillAndSend(it, amount) }
                    }
                }
                is UssdScreen.RemarkInput, is UssdScreen.Confirmation -> {
                    val fullText = allTexts.joinToString("\n")
                    val option = UssdParser.findConfirmationOption(fullText) ?: "1"
                    findInputNode(rootNode)?.let { autoFillAndSend(it, option) }
                }
                is UssdScreen.Success -> {
                    prefs.lastPaymentSuccess = true
                    prefs.lastRefId = screen.refId
                    prefs.pendingRecipient = null
                    prefs.pendingAmount = null
                    prefs.pendingPin = null
                    
                    // If the success screen has an "Exit" option, use it immediately
                    screen.exitOption?.let { option ->
                        Log.d(TAG, "Success screen contains exit option: $option. Sending it.")
                        findInputNode(rootNode)?.let { autoFillAndSend(it, option) }
                    }
                }
                is UssdScreen.ExitDialog -> {
                    val exitOption = UssdParser.findExitOption(allTexts.joinToString("\n")) ?: "2"
                    findInputNode(rootNode)?.let { autoFillAndSend(it, exitOption) }
                }
                is UssdScreen.Feedback -> {
                    handleFeedback(rootNode)
                }
                is UssdScreen.Error -> {
                    prefs.transactionInProgress = false
                    prefs.lastError = screen.message
                    resetProcessingGuards()
                    clickSendOrOk()
                }
                UssdScreen.Unknown -> {
                    // Possible system dialog or intermediate step
                }
            }

        } catch (e: Exception) { Log.e(TAG, "Service Error", e) }
    }

    private fun handleFeedback(rootNode: AccessibilityNodeInfo) {
        Log.i(TAG, "Dismissing final dialog")
        val cancelButton = findClickableWithKeywords(rootNode, listOf("cancel", "exit", "close", "dismiss"))
        if (cancelButton != null) {
            cancelButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            clickSendOrOk()
        }
        prefs.transactionInProgress = false
        resetProcessingGuards()
        bringAppToForeground(delay = 600)
    }

    private fun findAllTextNodes(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.let { texts.add(it.toString()) }
        node.contentDescription?.let { texts.add(it.toString()) }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findAllTextNodes(it, texts) }
    }

    private fun autoFillAndSend(node: AccessibilityNodeInfo, text: String) {
        Log.i(TAG, "Auto-filling text: '$text'")
        
        // Ensure the node is focused before setting text
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        
        val args = Bundle().apply { 
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) 
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "Set text result: $success")
        
        if (!success) {
            // Fallback for some older devices or custom implementations
            val clipboard = Bundle().apply { 
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) 
            }
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE, clipboard)
        }

        handler.removeCallbacks(submitAction)
        handler.postDelayed(submitAction, 800)
    }

    private fun shouldHandleWindow(packageName: String): Boolean {
        if (packageName in telecomPackages) return true
        val normalizedPackage = packageName.lowercase()
        return telecomPackageHints.any { normalizedPackage.contains(it) }
    }

    private fun shouldSkipDuplicate(screen: UssdScreen, texts: List<String>): Boolean {
        val signature = "${screen::class.java.name}|${texts.joinToString("\n")}"
        val now = SystemClock.uptimeMillis()
        if (signature == lastHandledSignature && now - lastHandledAtMs < DUPLICATE_WINDOW_COOLDOWN_MS) {
            Log.d(TAG, "Skipping duplicate USSD window for ${screen::class.simpleName}")
            return true
        }
        lastHandledSignature = signature
        lastHandledAtMs = now
        return false
    }

    private fun resetProcessingGuards() {
        handler.removeCallbacks(submitAction)
        lastHandledSignature = null
        lastHandledAtMs = 0L
    }

    private fun findInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Some devices use EditText, some just marked as editable, some have SET_TEXT action
        if (node.isEditable || 
            node.className?.contains("EditText", true) == true ||
            (node.actions and AccessibilityNodeInfo.ACTION_SET_TEXT != 0)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findInputNode(it)?.let { found -> return found } }
        }
        return null
    }

    private fun clickSendOrOk() {
        val root = rootInActiveWindow ?: return
        
        // Strategy 1: Find by common "Positive" keywords!
        val positiveKeywords = listOf("send", "ok", "submit", "accept", "reply", "answer", "done", "confirm", "call", "dial", "proceed")
        val positiveButton = findClickableWithKeywords(root, positiveKeywords)
        if (positiveButton != null) {
            Log.d(TAG, "Clicking positive button found by keyword: ${positiveButton.text}")
            positiveButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // Strategy 2: Search for ANY node with "clickable" set to true that isn't the input field
        // Some manufacturers don't use the "Button" class properly
        val allClickables = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodes(root, allClickables)
        
        if (allClickables.isNotEmpty()) {
            // Filter out clearly "Negative" buttons
            val filtered = allClickables.filter { node ->
                val text = (node.text ?: node.contentDescription ?: "").toString().lowercase()
                !text.contains("cancel") && !text.contains("exit") && !text.contains("close") && !text.contains("dismiss")
            }
            
            if (filtered.isNotEmpty()) {
                // In USSD dialogs, the "Action" button is almost always the last one in the hierarchy (the right-most)
                val target = filtered.last() 
                Log.d(TAG, "Clicking best-guess clickable node: ${target.text ?: target.className ?: "unlabeled"}")
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        // Strategy 3: Fallback - if there is only one clickable node, click it
        if (allClickables.size == 1) {
            Log.d(TAG, "Clicking the only available clickable node")
            allClickables[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        
        Log.e(TAG, "Could not find a button to click!")
    }

    private fun findAllClickableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && !node.isEditable) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findAllClickableNodes(it, list) }
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
            node.getChild(i)?.let { findClickableWithKeywords(it, keywords)?.let { found -> return found } }
        }
        return null
    }


    private fun bringAppToForeground(delay: Long) {
        handler.postDelayed({
            val intent = Intent(this, MainActivity::class.java).apply { 
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) 
            }
            startActivity(intent)
        }, delay)
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "WazPay-USSD"
        private val telecomPackages = setOf("com.android.phone", "com.android.server.telecom", "com.google.android.dialer", "com.samsung.android.incallui", "com.android.systemui")
        private val telecomPackageHints = setOf("dialer", "telecom", "phone", "telephony", "incall", "callui", "systemui")
        private const val DUPLICATE_WINDOW_COOLDOWN_MS = 1200L
    }
}
