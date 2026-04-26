package com.zeny.wazpay.logic

import android.util.Log

object UssdParser {
    private const val TAG = "UssdParser"

    fun parse(texts: List<String>): UssdScreen {
        val fullText = texts.joinToString("\n")
        val fullTextLower = fullText.lowercase()

        return when {
            isPinPrompt(fullText) -> UssdScreen.PinInput
            isIfscPrompt(fullText) -> UssdScreen.IfscInput
            isWelcomeDialog(fullTextLower) -> UssdScreen.WelcomeDialog
            isSendMoneyMenu(fullText) -> UssdScreen.SendMoneyMenu
            isRecipientPrompt(fullText) -> UssdScreen.RecipientInput
            isAmountPrompt(fullText) -> UssdScreen.AmountInput
            isRemarkPrompt(fullText) -> UssdScreen.RemarkInput
            isConfirmationPrompt(fullText) -> UssdScreen.Confirmation
            isSuccessMessage(fullTextLower) -> UssdScreen.Success(extractRefId(fullText), findExitOption(fullText))
            isFeedbackPrompt(fullTextLower) -> UssdScreen.Feedback
            isErrorMessage(fullTextLower) -> UssdScreen.Error(fullText, findExitOption(fullText))
            isExitDialog(fullTextLower) -> UssdScreen.ExitDialog
            else -> UssdScreen.Unknown
        }
    }

    private fun isPinPrompt(text: String): Boolean =
        text.contains("Enter UPI Pin", true) || text.contains("Enter Pin", true) || text.contains("MPIN", true)

    private fun isIfscPrompt(text: String): Boolean =
        text.contains("IFSC", true) && (text.contains("Enter", true) || text.contains("First 4", true))

    private fun isWelcomeDialog(text: String): Boolean =
        text.contains("welcome", true) && (text.contains("star 99", true) || text.contains("*99#", true))

    private fun isSendMoneyMenu(text: String): Boolean =
        (text.contains("Send Money", true) || text.contains("Transfer", true)) &&
                Regex("\\d\\.?\\s*(Mobile|UPI|VPA)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun isRecipientPrompt(text: String): Boolean =
        (text.contains("Enter", true) || text.contains("Mobile", true) || text.contains("UPI", true) || text.contains("Beneficiary", true)) &&
                !text.contains("PIN", true) && !text.contains("Amount", true) &&
                !text.contains("Remark", true) && 
                !text.contains("Success", true) &&
                !isExitDialog(text) &&
                !isSendMoneyMenu(text)

    private fun isAmountPrompt(text: String): Boolean = text.contains("Enter Amount", true)

    private fun isRemarkPrompt(text: String): Boolean = text.contains("Remark", true)

    private fun isConfirmationPrompt(text: String): Boolean = 
        text.contains("Confirm", true) || text.contains("Proceed", true)

    private fun isSuccessMessage(text: String): Boolean =
        (text.contains("success") || text.contains("completed") ||
                text.contains("sent to") || text.contains("paid to")) && !text.contains("1.confirm")

    fun isExitDialog(text: String): Boolean = 
        findExitOption(text) != null || text.contains("to exit", true) || 
                text.contains("2. exit", true) || text.contains("0. exit", true)

    fun findExitOption(text: String): String? {
        val option = findOptionForKeywords(text, listOf("Exit", "Quit", "Close", "Back", "Cancel"))
        if (option != null) return option
        
        // Fallback for very specific common patterns
        val exitRegex = Regex("(\\d+)\\s*[:.)\\s-]*\\s*(?:exit|back|cancel|quit)", RegexOption.IGNORE_CASE)
        exitRegex.find(text)?.let { return it.groupValues[1] }
        
        if (text.contains("to exit", true)) {
             Regex("(\\d+)\\s*to\\s*exit", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1] }
        }
        
        return null
    }

    fun findConfirmationOption(text: String): String? {
        return findOptionForKeywords(text, listOf("Confirm", "Proceed", "Yes", "Pay", "Ok"))
    }

    fun findMenuOption(text: String, isMobile: Boolean): String? {
        val keywords = if (isMobile) {
            listOf("Mobile Number", "Mobile", "Beneficiary", "Phone")
        } else {
            listOf("UPI ID", "VPA", "Virtual ID", "UPI")
        }
        return findOptionForKeywords(text, keywords)
    }

    fun findOptionForKeywords(text: String, keywords: List<String>): String? {
            for (keyword in keywords) {
                val escaped = Regex.escape(keyword)
                // matches '2. Exit', '2 Exit', '2) Exit', '2: Exit'
                val pattern1 = Regex("(\\d+)\\s*[:.)\\s-]*\\s*$escaped", RegexOption.IGNORE_CASE)
                pattern1.find(text)?.let { return it.groupValues[1] }

                // Match 'Exit 2', 'Exit. 2', 'Exit: 2'
                val pattern2 = Regex("$escaped\\s*[:.)\\s-]*\\s*(\\d+)", RegexOption.IGNORE_CASE)
                pattern2.find(text)?.let { return it.groupValues[1] }

                // Match '2 to Exit'
                val pattern3 = Regex("(\\d+)\\s*to\\s*$escaped", RegexOption.IGNORE_CASE)
                pattern3.find(text)?.let { return it.groupValues[1] }
            }
            
            // Only fallback to '2' if we are actually looking for an exit option
            if (keywords.any { it.equals("Exit", true) || it.equals("Quit", true) }) {
                if (text.contains("Exit", ignoreCase = true)) return "2"
            }
            return null
    }

    private fun isFeedbackPrompt(text: String): Boolean = 
        text.contains("thank you", true) || 
                text.contains("services", true) || 
                text.contains("feedback", true) ||
                text.contains("rate us", true)

    private fun isErrorMessage(text: String): Boolean = 
        text.contains("failed") || text.contains("invalid") || text.contains("error") || text.contains("unable")

    private fun extractRefId(text: String): String? {
        val refRegex = Regex("(?:RefId|Ref|Txn|Reference|ID|Id is)[:\\s]*([A-Z\\d]{8,})", RegexOption.IGNORE_CASE)
        return refRegex.find(text)?.groupValues?.get(1)
    }
}
