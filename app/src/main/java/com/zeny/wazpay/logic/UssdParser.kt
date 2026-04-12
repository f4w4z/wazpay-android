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
            isSuccessMessage(fullTextLower) -> UssdScreen.Success(extractRefId(fullText))
            isExitDialog(fullTextLower) -> UssdScreen.ExitDialog
            isFeedbackPrompt(fullTextLower) -> UssdScreen.Feedback
            isErrorMessage(fullTextLower) -> UssdScreen.Error(fullText)
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
        (text.contains("Enter", true) || text.contains("Mobile", true) || text.contains("UPI", true)) &&
                !text.contains("PIN", true) && !text.contains("Amount", true) &&
                !text.contains("Remark", true) && !text.contains("Exit", true) &&
                !text.contains("to exit", true) && !text.contains("2.", true) &&
                !isSendMoneyMenu(text)

    private fun isAmountPrompt(text: String): Boolean = text.contains("Enter Amount", true)

    private fun isRemarkPrompt(text: String): Boolean = text.contains("Remark", true)

    private fun isConfirmationPrompt(text: String): Boolean = 
        text.contains("Confirm", true) || text.contains("Proceed", true)

    private fun isSuccessMessage(text: String): Boolean =
        (text.contains("success") || text.contains("completed") ||
                text.contains("sent to") || text.contains("paid to")) && !text.contains("1.confirm")

    fun isExitDialog(text: String): Boolean = findExitOption(text) != null

    fun findExitOption(text: String): String? {
        return findOptionForKeywords(text, listOf("Exit", "Quit", "Close"))
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
            // Match "1. Keyword" or "1 Keyword" or "1.Keyword"
            val pattern1 = Regex("(\\d)[\\.\\s]*${Regex.escape(keyword)}", RegexOption.IGNORE_CASE)
            pattern1.find(text)?.let { return it.groupValues[1] }

            // Match "Keyword 1" or "Keyword. 1"
            val pattern2 = Regex("${Regex.escape(keyword)}[\\.\\s]*(\\d)", RegexOption.IGNORE_CASE)
            pattern2.find(text)?.let { return it.groupValues[1] }
            
            // Match "1 to Keyword" (e.g., 1 to confirm)
            val pattern3 = Regex("(\\d)\\s*to\\s*${Regex.escape(keyword)}", RegexOption.IGNORE_CASE)
            pattern3.find(text)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun isFeedbackPrompt(text: String): Boolean = 
        text.contains("thank you for using our services")

    private fun isErrorMessage(text: String): Boolean = 
        text.contains("failed") || text.contains("invalid")

    private fun extractRefId(text: String): String? {
        val refRegex = Regex("(?:RefId|Ref|Txn|Reference|ID|Id is)[:\\s]*([A-Z\\d]{8,})", RegexOption.IGNORE_CASE)
        return refRegex.find(text)?.groupValues?.get(1)
    }
}
