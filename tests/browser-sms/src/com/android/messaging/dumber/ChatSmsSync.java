package com.android.messaging.dumber;
import android.content.Context;
/** Controlled account/SIM/clock boundary. No SmsManager or real message providers. */
public final class ChatSmsSync {
 static String permission="";static int links;
 public static String queuePermission(Context c,String key,boolean manual){return manual&&permission.equals("expired")?"":permission;}
 public static void linked(Context c,String key,String id,String uri){links++;}
}
