package com.android.messaging.dumber

private val firstSim = Sim(3, 42, "SIM 1", "+46123456789")
private val secondSim = Sim(4, 43, "SIM 2", "+46987654321")
private val verification = Verification("request", firstSim.number!!, 3, 42, 100, 1000)

fun main() {
    var count = 0
    fun test(name: String, run: () -> Unit) {
        run()
        println("PASS $name")
        count++
    }
    fun step(
        sims: List<Sim>? = listOf(firstSim),
        connected: Boolean = false,
        pending: Verification? = null,
        permission: Boolean = true,
        manual: Boolean = false,
        selected: Int = -1,
        codeNonce: String? = null,
        activating: Boolean = false,
        now: Long = 500
    ) =
        ChatActivation.step(
            sims,
            connected,
            pending,
            permission,
            manual,
            selected,
            codeNonce,
            activating,
            now
        )

    test("permission is the only initial step even if a SIM number is cached") {
        check(step(permission = false) == ActivationStep.PERMISSION)
        check(step(sims = null, permission = false) == ActivationStep.PERMISSION)
    }
    test("manual number entry bypasses number permission but still needs SIM access") {
        check(step(permission = false, manual = true) == ActivationStep.ENTER_PHONE)
        check(step(sims = null, permission = false, manual = true) == ActivationStep.SIM_PERMISSION)
    }
    test("a detected number is presented for activation without a text field") {
        check(step() == ActivationStep.CHOOSE_NUMBER)
    }
    test("missing numbers prompt for phone entry while missing SIMs cannot activate") {
        check(step(sims = listOf(firstSim.copy(number = null))) == ActivationStep.ENTER_PHONE)
        check(step(sims = emptyList()) == ActivationStep.NO_SIM)
        check(step(sims = null) == ActivationStep.SIM_PERMISSION)
    }
    test("multiple SIMs require a choice including when manually entering a number") {
        val sims = listOf(firstSim, secondSim)
        check(step(sims = sims) == ActivationStep.CHOOSE_NUMBER)
        check(step(sims = sims, manual = true) == ActivationStep.CHOOSE_NUMBER)
        check(step(sims = sims, manual = true, selected = 4) == ActivationStep.ENTER_PHONE)
        check(step(sims = sims, selected = 999) == ActivationStep.CHOOSE_NUMBER)
    }
    test("pending registration resumes waiting without exposing manual inputs") {
        check(step(pending = verification) == ActivationStep.WAITING)
        check(
            step(pending = verification, permission = false, selected = 3) == ActivationStep.WAITING
        )
        check(step(pending = verification, connected = true) == ActivationStep.WAITING)
    }
    test("manual code entry is tied to the current request and can return to waiting") {
        check(step(pending = verification, codeNonce = "request") == ActivationStep.ENTER_CODE)
        check(step(pending = verification, codeNonce = "old") == ActivationStep.WAITING)
        check(step(pending = verification, codeNonce = null) == ActivationStep.WAITING)
    }
    test("expiry overrides both automatic waiting and manual code entry") {
        check(step(pending = verification, now = 1000) == ActivationStep.EXPIRED)
        check(
            step(pending = verification, codeNonce = "request", now = 1001) ==
                ActivationStep.EXPIRED
        )
    }
    test("successful registration returns to connected accounts despite an old manual selection") {
        check(
            step(connected = true, manual = true, selected = 3, codeNonce = "request") ==
                ActivationStep.ACCOUNTS
        )
        check(step(connected = true, permission = false) == ActivationStep.ACCOUNTS)
    }
    test("activating another SIM hides account management until registration completes") {
        check(
            step(sims = listOf(secondSim), connected = true, activating = true) ==
                ActivationStep.CHOOSE_NUMBER
        )
        check(
            step(
                sims = listOf(secondSim),
                connected = true,
                activating = true,
                permission = false
            ) == ActivationStep.PERMISSION
        )
        check(step(sims = emptyList(), connected = true) == ActivationStep.ACCOUNTS)
    }
    println("$count activation tests passed")
}
