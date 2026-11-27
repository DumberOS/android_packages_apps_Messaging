package com.android.messaging.dumber

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import chat.dumber.sdk.*

/** Number comparison is scoped to a newly approved browser; no device-list actions. */
class ChatPairingActivity : ChatActivity() {
    private val timer = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }
    private var active = false
    private var busy = false
    private var pendingAction: VerificationAction? = null
    @Volatile private var approved = false
    private var flow: String? = null
    private var verified = false
    private var shareSms: Boolean? = null
    private var failure: String? = null
    private var dialog: AlertDialog? = null
    private var showingNumbers: List<Int>? = null
    private var choiceShown = false
    private val accountId get() = intent.getStringExtra("account") ?: error("Missing account")
    private val code get() = intent.getStringExtra("code") ?: error("Missing pairing code")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "Link browser"
        approved = state?.getBoolean("approved") ?: false
        flow = state?.getString("flow")
        verified = state?.getBoolean("verified") ?: false
        if (state?.containsKey("shareSms") == true) shareSms = state.getBoolean("shareSms")
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("approved", approved); state.putString("flow", flow)
        state.putBoolean("verified", verified); shareSms?.let { state.putBoolean("shareSms", it) }
        super.onSaveInstanceState(state)
    }
    override fun onResume() { active = true; super.onResume() }
    override fun onPause() { active = false; timer.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { dialog?.dismiss(); super.onDestroy() }
    private fun account(): ChatAccount = ChatStore.get(this).account(accountId)?.takeIf { it.enabled }
        ?: error("Account disconnected. Link the browser again.")
    override fun refresh() {
        if (!active || busy || failure != null) return
        if (verified) { showSmsChoice(); return }
        busy = true
        if (flow == null) showText("Waiting for your browser to start verification…")
        DumberChat.task(this, { runCatching {
            val api = ChatAccounts.client(this, account())
            if (!approved) { api.approvePairing(code); approved = true }
            flow?.let { api.pairingVerification(it) } ?: api.startPairingVerification(code)
        } }) { result ->
            busy = false
            pendingAction?.let { action -> pendingAction = null; act(action); return@task }
            result.fold(::showVerification, ::failed)
            timer.removeCallbacks(poll)
            if (active && !verified && failure == null) timer.postDelayed(poll, 1000)
        }
    }
    private fun showText(text: String) { content.removeAllViews(); content.label(text) }
    private fun showVerification(value: PairingVerification) {
        if (verified || failure != null) return
        flow = value.id ?: flow
        if (value.state == "verified") {
            verified = true; dialog?.dismiss(); dialog = null; showingNumbers = null
            if (active) showSmsChoice()
            return
        }
        if (value.state in listOf("cancelled", "expired")) { failed(IllegalStateException("Verification ${value.state}. Link the browser again.")); return }
        if (!active) return
        if (value.state == "numbers" && value.numbers.size == 3 && showingNumbers != value.numbers) {
            showingNumbers = value.numbers
            dialog?.dismiss()
            dialog = AlertDialog.Builder(this)
                .setTitle("Verify browser")
                .setMessage("Compare these numbers with your browser:\n\n" + value.numbers.joinToString("  ") { it.toString().padStart(4, '0') } + "\n\nConfirm on both screens only if they match.")
                .setPositiveButton("Numbers match") { _, _ -> act(VerificationAction.CONFIRM) }
                .setNegativeButton("They don’t match") { _, _ -> act(VerificationAction.MISMATCH) }
                .setOnCancelListener { cancelAndFinish() }
                .show()
        }
        showText(if (value.state == "confirming") "Waiting for confirmation in your browser…" else "Compare the verification numbers on both screens.")
        content.button("Cancel linking") { cancelAndFinish() }
    }
    private fun act(action: VerificationAction) {
        val id = flow ?: return
        if (busy) { pendingAction = action; return }
        busy = true
        timer.removeCallbacks(poll)
        // Serialize UI actions with polls; the SDK also protects each flow.
        DumberChat.task(this, { runCatching { ChatAccounts.client(this, account()).pairingVerification(id, action) } }) { result ->
            busy = false
            result.fold(::showVerification, ::failed)
            timer.removeCallbacks(poll)
            if (active && !verified && failure == null) timer.postDelayed(poll, 1000)
        }
    }
    private fun failed(error: Throwable) {
        failure = error.message ?: "Verification interrupted. Link the browser again."
        timer.removeCallbacks(poll); dialog?.dismiss(); dialog = null
        showText(failure!!); content.button("Close") { cancelAndFinish() }
    }
    private fun cancelAndFinish() {
        val id = flow
        if (id != null && !verified) DumberChat.workers.execute {
            runCatching { ChatAccounts.client(this, account()).pairingVerification(id, VerificationAction.CANCEL) }
        }
        finish()
    }
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() { cancelAndFinish() }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { cancelAndFinish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showSmsChoice() {
        if (shareSms != null) { finishPairing(); return }
        if (choiceShown) return
        choiceShown = true
        showText("Browser verified.")
        dialog = AlertDialog.Builder(this)
            .setTitle("Enable SMS in the linked browser?")
            .setMessage("Share message history and SIM choices with linked browsers. They can send using any available SIM, with encryption when supported.")
            .setPositiveButton("Yes") { _, _ -> shareSms = true; finishPairing() }
            .setNegativeButton("No") { _, _ -> shareSms = false; finishPairing() }
            .setCancelable(false).show()
    }
    private fun finishPairing() {
        if (busy || !verified || shareSms == null) return
        busy = true; showText("Finishing browser pairing…")
        DumberChat.task(this, { runCatching {
            val a = account(); val store = ChatStore.get(this); val api = ChatAccounts.client(this, a)
            if (store.browserHistory().any { it.account == a.id && it.code == code }) ChatBrowserHistory.retry(this, a, true)
            else ChatBrowserHistory.link(this, a, code, api.reviewPairing(code))
            val pairingId = flow ?: error("Verification interrupted. Link again.")
            ChatSmsSync.configure(this, a, pairingId, shareSms == true)
        } }) { result -> busy = false; result.fold({ finish() }, ::failed) }
    }
}
