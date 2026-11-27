package com.android.messaging.dumber;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.MessageData;

/** A Matrix request owns one local message. Only Bugle's queue submits it to the carrier. */
public final class BrowserSmsQueue {
    private BrowserSmsQueue() {}
    public static void install(SQLiteDatabase db) {
        boolean column = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(messages)", null)) {
            while (c.moveToNext()) if ("browser_sms_key".equals(c.getString(1))) column = true;
        }
        if (!column) db.execSQL("ALTER TABLE messages ADD COLUMN browser_sms_key TEXT");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS browser_sms_message_key ON messages(browser_sms_key)");
        // No foreign key: deletion must not erase the replay tombstone.
        db.execSQL("CREATE TABLE IF NOT EXISTS browser_sms_handoffs (request_key TEXT PRIMARY KEY NOT NULL, message_id TEXT NOT NULL, uri TEXT NOT NULL, attempted INTEGER NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0, stopped TEXT NOT NULL DEFAULT '')");
    }
    public static String[] handoff(DatabaseWrapper db, String key) {
        try (Cursor c = db.rawQuery("SELECT message_id,uri FROM browser_sms_handoffs WHERE request_key=?", new String[]{key})) {
            return c.moveToFirst() ? new String[]{c.getString(0),c.getString(1)} : null;
        }
    }
    public static boolean replay(Context context, String key) {
        String[] existing = handoff(DataModel.get().getDatabase(),key);
        if (existing == null) return false;
        ChatSmsSync.linked(context,key,existing[0],existing[1]);
        return true;
    }
    /** Must run in the same transaction as local message creation/adoption. */
    public static void bind(DatabaseWrapper db, String key, MessageData message) {
        db.execSQL("UPDATE messages SET browser_sms_key=? WHERE _id=?",new String[]{key,message.getMessageId()});
        db.execSQL("INSERT INTO browser_sms_handoffs(request_key,message_id,uri) VALUES(?,?,?)",
                new String[]{key,message.getMessageId(),message.getSmsMessageUri().toString()});
    }
    private static String[] request(DatabaseWrapper db, MessageData message) {
        try (Cursor c = db.rawQuery("SELECT h.request_key,h.attempted,h.manual,h.stopped FROM browser_sms_handoffs h JOIN messages m ON m.browser_sms_key=h.request_key WHERE m._id=? AND m.sms_message_uri=?",
                new String[]{message.getMessageId(),message.getSmsMessageUri() == null ? "" : message.getSmsMessageUri().toString()})) {
            return c.moveToFirst() ? new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)} : null;
        }
    }
    public static String requestKey(MessageData message) {
        String[] r = request(DataModel.get().getDatabase(),message);
        return r == null ? null : r[0];
    }
    public static boolean allow(Context context, MessageData message, boolean submitting) {
        return allow(context,message,submitting,null);
    }
    /** Checks both queue selection and the final boundary immediately before Android submission. */
    public static boolean allow(Context context, MessageData message, boolean submitting, String expectedKey) {
        DatabaseWrapper db = DataModel.get().getDatabase();
        String[] r = request(db,message);
        if (r == null) return expectedKey == null; // A deleted queued browser message must not submit.
        if (expectedKey != null && !expectedKey.equals(r[0])) return false;
        String stop = r[3];
        if (stop.isEmpty()) stop = ChatSmsSync.queuePermission(context,r[0],"1".equals(r[2]));
        if (!stop.isEmpty()) {
            if ("expired".equals(stop) && "1".equals(r[1])) stop = "failed";
            db.execSQL("UPDATE browser_sms_handoffs SET stopped=? WHERE request_key=?",new String[]{stop,r[0]});
            return false;
        }
        if (submitting) db.execSQL("UPDATE browser_sms_handoffs SET attempted=1 WHERE request_key=?",new String[]{r[0]});
        return true;
    }
    public static void manualRetry(MessageData message) {
        DatabaseWrapper db = DataModel.get().getDatabase();
        String[] r = request(db,message);
        if (r != null) db.execSQL("UPDATE browser_sms_handoffs SET manual=1,stopped='' WHERE request_key=?",new String[]{r[0]});
    }
    public static String status(String key) {
        return status(DataModel.get().getDatabase(),key);
    }
    static String status(DatabaseWrapper db, String key) {
        try (Cursor c = db.rawQuery("SELECT m.message_status,h.stopped FROM browser_sms_handoffs h LEFT JOIN messages m ON m.browser_sms_key=h.request_key WHERE h.request_key=?",new String[]{key})) {
            if (!c.moveToFirst()) return null;
            if (c.isNull(0)) return "deleted";
            switch (c.getInt(0)) {
                case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
                case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED: return "sent";
                default:
                    if (!c.getString(1).isEmpty()) return c.getString(1);
                    switch(c.getInt(0)) {
                        case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
                        case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                        case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
                        case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY: return "sending";
                        default: return "failed";
                    }
            }
        }
    }
}
