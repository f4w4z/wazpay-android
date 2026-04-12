package com.zeny.wazpay.logic

sealed class UssdScreen {
    object SendMoneyMenu : UssdScreen()
    object RecipientInput : UssdScreen()
    object AmountInput : UssdScreen()
    object RemarkInput : UssdScreen()
    object Confirmation : UssdScreen()
    object PinInput : UssdScreen()
    data class Success(val refId: String?) : UssdScreen()
    data class Error(val message: String) : UssdScreen()
    object ExitDialog : UssdScreen()
    object Feedback : UssdScreen()
    object WelcomeDialog : UssdScreen()
    object IfscInput : UssdScreen()
    object Unknown : UssdScreen()
}
