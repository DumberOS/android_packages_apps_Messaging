package com.android.messaging.dumber

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import com.android.messaging.R

internal object ChatNotifications {
    private fun manager(c: Context): NotificationManager =
        c.getSystemService(NotificationManager::class.java).also {
            if (Build.VERSION.SDK_INT >= 26) {
                it.createNotificationChannel(
                    NotificationChannel(
                        "dumber_connection",
                        "dumber.chat connection",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
                it.createNotificationChannel(
                    NotificationChannel(
                        "dumber_messages",
                        "Encrypted messages",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    private fun open(c: Context, key: String? = null): PendingIntent =
        PendingIntent.getActivity(
            c,
            key?.hashCode() ?: 0,
            Intent(
                    c,
                    if (key == null) ChatSettingsActivity::class.java
                    else ChatGroupActivity::class.java
                )
                .putExtra("group", key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    fun connection(c: Context): Notification {
        manager(c)
        return NotificationCompat.Builder(c, "dumber_connection")
            .setSmallIcon(R.drawable.ic_sms_light)
            .setContentTitle("dumber.chat")
            .setContentText("Receiving encrypted messages for connected numbers")
            .setContentIntent(open(c))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    fun pending(c: Context, p: PendingText) {
        val key = if (p.group) groupKey(p.account, p.conversation.orEmpty()) else null
        manager(c)
            .notify(
                p.id,
                8092,
                NotificationCompat.Builder(c, "dumber_messages")
                    .setSmallIcon(R.drawable.ic_sms_light)
                    .setContentTitle("Encrypted message needs attention")
                    .setContentText("Open Messaging to retry or review delivery")
                    .setContentIntent(open(c, key))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
            )
    }
    fun group(c: Context, g: GroupState) {
        if (ChatGroupActivity.visibleKey == g.key) return
        manager(c)
            .notify(
                g.key,
                8092,
                NotificationCompat.Builder(c, "dumber_messages")
                    .setSmallIcon(R.drawable.ic_sms_light)
                    .setContentTitle(g.conversation.name ?: "Encrypted group")
                    .setContentText("New encrypted message")
                    .setContentIntent(open(c, g.key))
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
            )
    }
    /** Serialize the snapshot, posting and acknowledgement with account changes. */
    fun sessionEnded(c: Context) {
        runCatching {
            val store = ChatStore.get(c)
            store.atomic {
                // Also reconcile accounts re-registered before this version was installed.
                store.accounts().filter { it.enabled }.forEach { store.resolveRevocations(it.phone) }
                val pending = ChatSessionAlerts.pending(store.accounts())
                val manager = c.getSystemService(NotificationManager::class.java)
                val tag = "dumber_session_ended"
                if (pending.isEmpty()) {
                    manager.cancel(tag, 8092)
                    return@atomic
                }
                val notified = store.sessionNotices()
                if (pending.all { ChatSessionAlerts.key(it) in notified }) return@atomic
                val channel = "dumber_account_alerts"
                manager.createNotificationChannel(NotificationChannel(channel,
                    c.getString(R.string.dumber_account_alerts), NotificationManager.IMPORTANCE_HIGH))
                // A blocked notification must remain eligible after permission is granted.
                if (!manager.areNotificationsEnabled() ||
                    manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE)
                    return@atomic
                manager.notify(tag, 8092, NotificationCompat.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_sms_light)
                    .setContentTitle(c.getString(R.string.dumber_session_ended_title))
                    .setContentText(c.getString(R.string.dumber_session_ended_text))
                    .setStyle(NotificationCompat.BigTextStyle()
                        .bigText(c.getString(R.string.dumber_session_ended_text)))
                    .setContentIntent(open(c))
                    .setAutoCancel(true).setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).build())
                pending.forEach { store.markSessionNotice(it) }
            }
        }
    }
    fun cancel(c: Context, id: String) {
        manager(c).cancel(id, 8092)
    }
}
