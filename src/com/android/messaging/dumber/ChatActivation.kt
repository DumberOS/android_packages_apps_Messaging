package com.android.messaging.dumber

internal enum class ActivationStep {
    PERMISSION,
    SIM_PERMISSION,
    NO_SIM,
    CHOOSE_NUMBER,
    ENTER_PHONE,
    WAITING,
    ENTER_CODE,
    EXPIRED,
    ACCOUNTS
}

/** Presentation decisions only; registration and SMS handling remain in ChatAccounts/DumberChat. */
internal object ChatActivation {
    fun step(
        available: List<Sim>?,
        connected: Boolean,
        verification: Verification?,
        canReadNumbers: Boolean,
        manualNumber: Boolean,
        selectedSubscription: Int,
        manualCodeNonce: String?,
        activating: Boolean,
        now: Long
    ): ActivationStep {
        if (verification != null)
            return when {
                verification.expiresAt <= now -> ActivationStep.EXPIRED
                manualCodeNonce == verification.nonce -> ActivationStep.ENTER_CODE
                else -> ActivationStep.WAITING
            }
        if (connected && !activating) return ActivationStep.ACCOUNTS
        if (!canReadNumbers && !manualNumber) return ActivationStep.PERMISSION
        if (available == null) return ActivationStep.SIM_PERMISSION
        if (available.isEmpty()) return ActivationStep.NO_SIM
        if (
            available.any { it.subscription == selectedSubscription } ||
                (available.size == 1 && (manualNumber || available.single().number == null))
        )
            return ActivationStep.ENTER_PHONE
        return ActivationStep.CHOOSE_NUMBER
    }
}
