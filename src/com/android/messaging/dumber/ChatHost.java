package com.android.messaging.dumber;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import com.android.messaging.Factory;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.SyncManager;
import com.android.messaging.datamodel.action.InsertNewMessageAction;

/** Bugle owns provider synchronization and the carrier send queue. */
public final class ChatHost {
    private ChatHost() {}
    private static final java.util.concurrent.atomic.AtomicBoolean notificationPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    public static void onProviderSynced() {
        if (notificationPending.getAndSet(false)) {
            com.android.messaging.datamodel.BugleNotifications.update(false,
                    com.android.messaging.datamodel.BugleNotifications.UPDATE_MESSAGES);
        }
    }
    /** Repair only confirmed successes from the retired direct-submit browser path.
     * Reconcile terminal success without entering Messaging's carrier send queue. */
    public static void reconcileSmsStatuses() {
        reconcileSmsStatuses(Factory.get().getApplicationContext(), DataModel.get().getDatabase());
    }
    static void reconcileSmsStatuses(Context context, DatabaseWrapper db) {
        final java.util.Set<String> confirmed = ChatSmsSync.legacySentUris(context);
        if (confirmed.isEmpty()) return;
        boolean changed = false;
        final String failed = MessageColumns.PROTOCOL + "=" + MessageData.PROTOCOL_SMS
                + " AND " + MessageColumns.STATUS + "=" + MessageData.BUGLE_STATUS_OUTGOING_FAILED;
        try (Cursor messages = db.query(DatabaseHelper.MESSAGES_TABLE,
                new String[]{MessageColumns._ID, MessageColumns.SMS_MESSAGE_URI},
                failed, null, null, null, null)) {
            while (messages.moveToNext()) {
                final String uri = messages.getString(1);
                if (!confirmed.contains(uri)) continue;
                try (Cursor sms = context.getContentResolver().query(Uri.parse(uri),
                        new String[]{Telephony.Sms.TYPE, Telephony.Sms.STATUS}, null, null, null)) {
                    if (sms == null || !sms.moveToFirst()
                            || sms.getInt(0) != Telephony.Sms.MESSAGE_TYPE_SENT
                            || sms.getInt(1) >= Telephony.Sms.STATUS_FAILED) continue;
                    ContentValues values = new ContentValues();
                    values.put(MessageColumns.STATUS, sms.getInt(1) == Telephony.Sms.STATUS_COMPLETE
                            ? MessageData.BUGLE_STATUS_OUTGOING_DELIVERED
                            : MessageData.BUGLE_STATUS_OUTGOING_COMPLETE);
                    // Do not overwrite another action which has already changed this row.
                    changed |= db.update(DatabaseHelper.MESSAGES_TABLE, values,
                            MessageColumns._ID + "=? AND " + failed,
                            new String[]{messages.getString(0)}) > 0;
                }
            }
        }
        if (changed) MessagingContentProvider.notifyEverythingChanged();
    }
    public static void install(Context context) {
        DumberChat.install(context, new DumberChat.CarrierGateway() {
            @Override public void send(String id, int subscription, String recipient, String body) {
                InsertNewMessageAction.insertCarrierMessage(id, subscription, recipient, body);
            }
            @Override public void refreshMessages(boolean incoming) {
                if (incoming) notificationPending.set(true);
                SyncManager.immediateSync();
            }
            @Override public boolean blocked(String number) {
                return BugleDatabaseOperations.isBlockedDestination(DataModel.get().getDatabase(), number);
            }
        });
    }
}
