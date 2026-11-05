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
    fun cancel(c: Context, id: String) {
        manager(c).cancel(id, 8092)
    }
}
