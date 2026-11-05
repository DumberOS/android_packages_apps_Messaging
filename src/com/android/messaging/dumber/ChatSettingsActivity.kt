package com.android.messaging.dumber

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import chat.dumber.sdk.*
import com.android.messaging.ui.UIIntents

class ChatSettingsActivity : ChatActivity() {
    private var rendered: Any? = null
    private var generation = 0
    private var activating = false
    private var manualNumber = false
    private var selectedSubscription = -1
    private var manualCodeNonce: String? = null
    private var phoneDraft = ""
    private var codeDraft = ""
    private var working: String? = null
    private var error: String? = null
    private val timer = Handler(Looper.getMainLooper())
    private val expire = Runnable { redraw() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "dumber.chat"
        state?.let {
            activating = it.getBoolean("activating")
            manualNumber = it.getBoolean("manualNumber")
            selectedSubscription = it.getInt("subscription", -1)
            manualCodeNonce = it.getString("codeNonce")
            phoneDraft = it.getString("phoneDraft").orEmpty()
            codeDraft = it.getString("codeDraft").orEmpty()
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("activating", activating)
        state.putBoolean("manualNumber", manualNumber)
        state.putInt("subscription", selectedSubscription)
        state.putString("codeNonce", manualCodeNonce)
        state.putString("phoneDraft", phoneDraft)
        state.putString("codeDraft", codeDraft)
        super.onSaveInstanceState(state)
    }

    override fun onPause() {
        timer.removeCallbacks(expire)
        generation++
        super.onPause()
    }

    private fun redraw() {
        rendered = null
        refresh()
    }

    override fun refresh() {
        val request = ++generation
        DumberChat.task(
            this,
            {
                val store = ChatStore.get(this)
                val sims = ChatAccounts.sims(this)
                store.atomic {
                    SettingsState(
                        sims,
                        store.accounts(),
                        store.verification(),
                        store.pending().filter { !it.group },
                        ChatClient.isAvailable(),
                        checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
            }
        ) { state ->
            if (request != generation) return@task
            val available =
                state.sims?.filter { sim ->
                    state.accounts.none { it.enabled && it.subscription == sim.subscription }
                }
            if (
                available != null &&
                    selectedSubscription >= 0 &&
                    available.none { it.subscription == selectedSubscription }
            ) {
                selectedSubscription = -1
                phoneDraft = ""
            }
            val step =
                ChatActivation.step(
                    available,
                    state.accounts.any { it.enabled },
                    state.verification,
                    state.canReadNumbers,
                    manualNumber,
                    selectedSubscription,
                    manualCodeNonce,
                    activating,
                    System.currentTimeMillis()
                )
            // Expiry changes the step even when no SMS or sync broadcast arrives.
            timer.removeCallbacks(expire)
            state.verification?.let {
                val delay = it.expiresAt - System.currentTimeMillis()
                if (delay > 0) timer.postDelayed(expire, delay + 1)
            }
            if (step == ActivationStep.ACCOUNTS) error = null
            val isDefaultSmsApp = ChatAccounts.isDefault(this)
            val key = listOf(state, step, working, error, isDefaultSmsApp)
            if (rendered == key) return@task
            rendered = key
            content.removeAllViews()
            if (!state.native) {
                content.label("Encrypted messaging is unavailable in this build.")
                return@task
            }
            if (!isDefaultSmsApp) {
                content.button("Set Messaging as the default SMS app") {
                    startActivityForResult(UIIntents.get().getChangeDefaultSmsAppIntent(this), 72)
                }
                return@task
            }
            working?.let {
                content.label(it)
                return@task
            }
            error?.let { content.label(it) }
            when (step) {
                ActivationStep.PERMISSION -> {
                    content.label(
                        "Use your SIM's phone number to activate dumber.chat, or enter it manually."
                    )
                    content.button("Detect phone number") {
                        requestPermissions(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.READ_PHONE_NUMBERS
                            ),
                            70
                        )
                    }
                    content.button("Enter manually") {
                        manualNumber = true
                        error = null
                        if (
                            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                                PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 71)
                        }
                        redraw()
                    }
                }
                ActivationStep.SIM_PERMISSION -> {
                    content.label(
                        "Allow SIM access to choose which SIM to activate and receive its activation SMS."
                    )
                    if (
                        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        content.button("Allow SIM access") {
                            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 71)
                        }
                    } else {
                        content.label("SIM information is temporarily unavailable.")
                        content.button("Try again") { redraw() }
                    }
                }
                ActivationStep.NO_SIM -> {
                    content.label(
                        "No available SIMs. Insert or enable a SIM to activate dumber.chat."
                    )
                    content.button("Try again") { redraw() }
                }
                ActivationStep.CHOOSE_NUMBER -> {
                    content.label(
                        if (manualNumber) "Choose the SIM to activate"
                        else "Choose the number to activate"
                    )
                    available.orEmpty().forEach { sim ->
                        val number = sim.number?.takeUnless { manualNumber }
                        content.label(sim.name)
                        content.button(number?.let { "Activate $it" } ?: "Enter phone number") {
                            selectedSubscription = sim.subscription
                            phoneDraft = sim.displayNumber.orEmpty()
                            error = null
                            if (number != null) begin(sim, number) else redraw()
                        }
                    }
                    if (!manualNumber)
                        content.button("Enter manually") {
                            manualNumber = true
                            selectedSubscription = -1
                            error = null
                            redraw()
                        }
                }
                ActivationStep.ENTER_PHONE -> {
                    val sim =
                        available.orEmpty().find { it.subscription == selectedSubscription }
                            ?: available.orEmpty().single()
                    if (selectedSubscription != sim.subscription) {
                        phoneDraft = sim.displayNumber.orEmpty()
                        selectedSubscription = sim.subscription
                    }
                    content.label("Activate dumber.chat · ${sim.name}")
                    if (!manualNumber && sim.number == null)
                        content.label(
                            "The phone number isn't available from this SIM. Enter it below."
                        )
                    val phone =
                        content.input("Phone number (+country code)", InputType.TYPE_CLASS_PHONE)
                    phone.setText(phoneDraft)
                    phone.track { phoneDraft = it }
                    content.button("Activate") {
                        val number =
                            ChatAccounts.normalize(this, phone.text.toString(), sim.subscription)
                        if (number == null) phone.error = "Enter an international phone number"
                        else begin(sim, number)
                    }
                    if (available.orEmpty().size > 1)
                        content.button("Choose another SIM") {
                            selectedSubscription = -1
                            phoneDraft = ""
                            error = null
                            redraw()
                        }
                }
                ActivationStep.WAITING,
                ActivationStep.ENTER_CODE,
                ActivationStep.EXPIRED -> {
                    val v = state.verification!!
                    content.label(
                        if (step == ActivationStep.EXPIRED) "Activation SMS expired"
                        else if (step == ActivationStep.ENTER_CODE) "Enter activation code"
                        else "Waiting for activation SMS"
                    )
                    content.label(v.phone)
                    if (step == ActivationStep.WAITING) {
                        content.label("Activation will finish automatically when the SMS arrives.")
                        content.button("Enter manually") {
                            manualCodeNonce = v.nonce
                            codeDraft = ""
                            error = null
                            redraw()
                        }
                    } else if (step == ActivationStep.ENTER_CODE) {
                        val code = content.input("Six-digit SMS code", InputType.TYPE_CLASS_NUMBER)
                        code.setText(codeDraft)
                        code.track { codeDraft = it }
                        content.button("Activate") {
                            val value = code.text.toString().trim()
                            if (!Regex("[0-9]{6}").matches(value)) {
                                code.error = "Enter the six-digit SMS code"
                            } else
                                activationOperation("Activating dumber.chat…") {
                                    synchronized(ChatAccounts) {
                                        // An arriving SMS may have completed this same request
                                        // already.
                                        if (ChatStore.get(this).verification()?.nonce == v.nonce)
                                            ChatAccounts.verify(this, v.nonce, value)
                                    }
                                }
                        }
                        content.button("Wait for SMS") {
                            manualCodeNonce = null
                            codeDraft = ""
                            error = null
                            redraw()
                        }
                    } else {
                        content.button("Send another SMS") {
                            val sim =
                                available.orEmpty().find {
                                    it.subscription == v.subscription &&
                                        (v.cardId < 0 || it.cardId < 0 || it.cardId == v.cardId)
                                }
                            if (sim == null) {
                                error = "The selected SIM is unavailable. Choose a number again."
                                redraw()
                            } else begin(sim, v.phone)
                        }
                    }
                    content.button("Change number") {
                        activating = true
                        selectedSubscription = -1
                        phoneDraft = ""
                        manualCodeNonce = null
                        codeDraft = ""
                        activationOperation("Changing number…") {
                            val store = ChatStore.get(this)
                            store.atomic {
                                if (store.verification()?.nonce == v.nonce)
                                    store.clearVerification()
                            }
                        }
                    }
                }
                ActivationStep.ACCOUNTS -> showAccounts(state)
            }
            if (
                step != ActivationStep.ACCOUNTS &&
                    state.verification == null &&
                    state.accounts.any { it.enabled }
            )
                content.button("Back to accounts") {
                    activating = false
                    error = null
                    redraw()
                }
        }
    }

    private fun begin(sim: Sim, number: String) {
        // A completed request returns to account management, including activation of a second SIM.
        activating = false
        if (!manualNumber && sim.number == number) selectedSubscription = -1
        manualCodeNonce = null
        codeDraft = ""
        activationOperation(
            "Requesting activation SMS for $number…",
            failed = { activating = true }
        ) {
            val current = ChatAccounts.selected(this, sim.subscription)
            check(
                current != null &&
                    (sim.cardId < 0 || current.cardId < 0 || sim.cardId == current.cardId)
            ) {
                "The selected SIM is unavailable"
            }
            ChatAccounts.begin(this, current, number)
        }
    }

    private fun activationOperation(message: String, failed: () -> Unit = {}, work: () -> Unit) {
        if (working != null) return
        working = message
        error = null
        // Disable the previous step immediately, before the asynchronous state read completes.
        content.removeAllViews()
        content.label(message)
        redraw()
        DumberChat.task(
            this,
            {
                try {
                    runCatching(work)
                } finally {
                    DumberChat.changed(this)
                }
            }
        ) { result ->
            working = null
            result.exceptionOrNull()?.let {
                failed()
                error = it.message ?: "Activation failed. Please try again."
            }
            redraw()
        }
    }

    private fun EditText.track(changed: (String) -> Unit) {
        changed(text.toString())
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    changed(s.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    private fun showAccounts(state: SettingsState) {
        content.button(if (ChatCalls.enabled(this)) "Encrypted calls: on" else "Encrypted calls: off") {
            ChatCalls.setEnabled(this, !ChatCalls.enabled(this)); redraw()
        }
        manualCodeNonce = null
        codeDraft = ""
        state.accounts
            .filter { it.enabled }
            .forEach { account ->
                content.label("${account.simLabel} · ${account.phone} · Connected")
                content.button("Link a browser") { pair(account.id) }
                content.button("Devices and fingerprints") { devices(this, account.id, null) }
                content.button("Disconnect ${account.phone}") {
                    confirm(
                        "Disconnect?",
                        "This stops encrypted messaging for this number and signs out this device."
                    ) {
                        operation {
                            ChatAccounts.disconnect(this, account.id, AccountStatus.LOGGED_OUT)
                        }
                    }
                }
            }
        if (
            state.sims == null ||
                state.sims.any { sim ->
                    state.accounts.none { it.enabled && it.subscription == sim.subscription }
                }
        )
            content.button("Activate another number") {
                activating = true
                manualNumber = false
                selectedSubscription = -1
                phoneDraft = ""
                error = null
                redraw()
            }
        state.accounts
            .filter { !it.enabled }
            .forEach { account ->
                content.label(
                    "${account.phone}: " +
                        when (account.status) {
                            AccountStatus.REVOKED -> "Session revoked; reconnect to continue"
                            AccountStatus.SIM_REMOVED -> "SIM removed or changed"
                            else -> "Disconnected"
                        }
                )
            }
        state.pending.forEach { pendingControls(this, content, it) }
    }

    private fun pair(id: String) {
        prompt("Eight-digit browser code") { code ->
            DumberChat.task(
                this,
                {
                    require(Regex("[0-9]{8}").matches(code)) {
                        "Enter the eight-digit browser code"
                    }
                    val a =
                        ChatStore.get(this).account(id)?.takeIf { it.enabled }
                            ?: error("Account disconnected")
                    ChatAccounts.client(this, a).reviewPairing(code)
                }
            ) { review ->
                confirm(
                    "Approve browser?",
                    review.description + "\nOnly approve the browser you are linking."
                ) {
                    operation {
                        val a =
                            ChatStore.get(this).account(id)?.takeIf { it.enabled }
                                ?: error("Account disconnected")
                        check(review.expiresAt > System.currentTimeMillis()) { "Request expired" }
                        ChatAccounts.client(this, a).approvePairing(code)
                    }
                }
            }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        redraw()
    }
    private data class SettingsState(
        val sims: List<Sim>?,
        val accounts: List<ChatAccount>,
        val verification: Verification?,
        val pending: List<PendingText>,
        val native: Boolean,
        val canReadNumbers: Boolean
    )
}

internal fun devices(activity: android.app.Activity, account: String, phone: String?) {
    DumberChat.task(
        activity,
        {
            val a =
                ChatStore.get(activity).account(account)?.takeIf { it.enabled }
                    ?: error("Account disconnected")
            ChatAccounts.client(activity, a).devices(phone)
        }
    ) { list ->
        android.app.AlertDialog.Builder(activity)
            .setTitle("Devices and fingerprints")
            .setMessage(
                list.devices.joinToString("\n\n") {
                    "${it.name ?: it.deviceId}\n${if(it.verified) "Verified" else "Unverified"}\n${it.fingerprint ?: "Fingerprint unavailable"}"
                }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
