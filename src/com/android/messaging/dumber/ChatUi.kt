package com.android.messaging.dumber

import android.app.*
import android.content.*
import android.os.Bundle
import android.text.InputType
import android.widget.*

/** Small native controls remain usable with touch and a DPAD. */
internal fun LinearLayout.label(text: String): TextView =
    TextView(context).also {
        it.text = text
        it.textSize = 16f
        it.setPadding(8, 12, 8, 12)
        addView(it)
    }

internal fun LinearLayout.button(text: String, action: () -> Unit): Button =
    Button(context).also {
        it.text = text
        it.isAllCaps = false
        it.setOnClickListener { action() }
        addView(it)
    }

internal fun LinearLayout.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(context).also {
        it.hint = hint
        it.inputType = type
        it.isSingleLine = true
        addView(it)
    }

internal fun Activity.confirm(title: String, message: String, action: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ -> action() }
        .show()
}

internal fun Activity.prompt(title: String, initial: String = "", action: (String) -> Unit) {
    val input =
        EditText(this).apply {
            setText(initial)
            isSingleLine = true
        }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ -> action(input.text.toString().trim()) }
        .show()
}

abstract class ChatActivity : Activity() {
    protected lateinit var content: LinearLayout
    private var busy = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                refresh()
            }
        }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 16)
            }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(DumberChat.CHANGED), Context.RECEIVER_NOT_EXPORTED)
        DumberChat.resume(this)
        refresh()
    }
    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    protected abstract fun refresh()
    protected fun operation(work: () -> Unit) {
        if (busy) return
        busy = true
        DumberChat.task(this, { runCatching(work) }) { result ->
            busy = false
            result.exceptionOrNull()?.let {
                Toast.makeText(this, it.message ?: "Chat operation failed", Toast.LENGTH_LONG)
                    .show()
            }
            refresh()
        }
    }
}

internal fun pendingControls(activity: Activity, box: LinearLayout, p: PendingText) {
    box.label(
        p.body +
            "\n" +
            when (p.state) {
                DeliveryState.CONFIRMED -> "Sent encrypted; saving locally"
                DeliveryState.CARRIER_QUEUED -> "Handed to SMS"
                DeliveryState.UNCERTAIN ->
                    "Delivery is uncertain. Retrying will reuse the same message ID."
                else -> "Waiting to send encrypted"
            } +
            if (p.error.isNotBlank()) "\n${p.error}" else ""
    )
    fun run(work: () -> Unit) {
        DumberChat.task(activity, work) { DumberChat.changed(activity) }
    }
    if (p.state == DeliveryState.CARRIER_QUEUED)
        box.button("Resume SMS handoff") { run { DumberChat.resumeCarrier(activity, p.id) } }
    if (p.state != DeliveryState.CARRIER_QUEUED)
        box.button("Retry") { run { DumberChat.retry(activity, p.id) } }
    if (p.state in listOf(DeliveryState.PENDING, DeliveryState.UNCERTAIN)) {
        if (!p.group)
            box.button("Send as SMS instead") {
                activity.confirm(
                    "Send unencrypted SMS?",
                    "This sends from the original SIM. Carrier charges may apply." +
                        if (p.state == DeliveryState.UNCERTAIN)
                            " The encrypted message may already have arrived; the recipient could receive both."
                        else ""
                ) {
                    run { DumberChat.fallback(activity, p.id) }
                }
            }
        box.button("Discard") {
            activity.confirm(
                "Discard queued message?",
                if (p.state == DeliveryState.UNCERTAIN)
                    "This cannot recall a message that may already have arrived."
                else "Remove this unsent message?"
            ) {
                run { DumberChat.discard(activity, p.id) }
            }
        }
    }
}
