package com.android.messaging.datamodel.data;
import android.net.Uri;
/** Minimal message identity adapter; status values match Messaging's MessageData. */
public final class MessageData {
 public static final int BUGLE_STATUS_OUTGOING_COMPLETE=1, BUGLE_STATUS_OUTGOING_DELIVERED=2,
  BUGLE_STATUS_OUTGOING_YET_TO_SEND=4, BUGLE_STATUS_OUTGOING_SENDING=5,
  BUGLE_STATUS_OUTGOING_RESENDING=6, BUGLE_STATUS_OUTGOING_AWAITING_RETRY=7;
 private final String id; private final Uri uri;
 public MessageData(String id,String uri){this.id=id;this.uri=Uri.parse(uri);}
 public String getMessageId(){return id;}
 public Uri getSmsMessageUri(){return uri;}
}
