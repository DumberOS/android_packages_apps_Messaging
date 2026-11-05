package com.android.messaging.dumber;

import android.content.Context;
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
