package com.android.messaging.dumber

import chat.dumber.sdk.Availability

/** Presentation of the next message, using the same availability rule as the send path. */
enum class ChatComposeState(val hint: String, val label: String, val encrypted: Boolean = false,
                            val carrier: Boolean = false) {
    ENCRYPTED("Encrypted message", "Encrypted", encrypted = true),
    SMS("Text message", "SMS", carrier = true),
    MMS("Text message", "MMS", carrier = true),
    CHECKING("Checking encryption…", "Checking encryption…"),
    UNSUPPORTED("Remove attachments to send encrypted", "Attachments unsupported");

    companion object {
        @JvmStatic
        fun forRecipient(connected: Boolean, availability: Availability?, carrierOnly: Boolean): ChatComposeState =
            when {
                !connected || availability == Availability.UNAVAILABLE -> if (carrierOnly) MMS else SMS
                carrierOnly -> UNSUPPORTED
                availability == Availability.AVAILABLE -> ENCRYPTED
                else -> CHECKING
            }
    }
}
