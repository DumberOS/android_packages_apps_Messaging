package com.android.messaging.dumber;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.android.messaging.datamodel.*;
import com.android.messaging.datamodel.data.MessageData;

/** Runs the production queue class against an isolated Android database, never a SIM. */
public final class BrowserSmsQueueTest {
 static DatabaseWrapper db;
 static void check(boolean value){if(!value)throw new AssertionError();}
 static MessageData insert(String key,int id){
  db.sql.beginTransaction();
  try {
   db.sql.execSQL("INSERT INTO messages(_id,sms_message_uri,message_status) VALUES(?,?,4)",new Object[]{id,"content://sms/"+id});
   MessageData m=new MessageData(""+id,"content://sms/"+id);BrowserSmsQueue.bind(db,key,m);
   db.sql.setTransactionSuccessful();return m;
  }finally{db.sql.endTransaction();}
 }
 static void status(int id,int status){db.sql.execSQL("UPDATE messages SET message_status=? WHERE _id=?",new Object[]{status,id});}
 static void expect(String key,String status){check(status.equals(BrowserSmsQueue.status(key)));}
 static int count(String table){try(Cursor c=db.sql.rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getInt(0);}}
 public static void main(String[] args)throws Exception{
  Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init",String.class).invoke(null,"");
  String path=args[0];SQLiteDatabase sql=SQLiteDatabase.openOrCreateDatabase(path,null);
  db=new DatabaseWrapper(sql);DataModel.model.db=db;
  sql.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sms_message_uri TEXT,message_status INTEGER)");
  sql.execSQL("INSERT INTO messages VALUES(100,'content://sms/100',1)");
  BrowserSmsQueue.install(sql);BrowserSmsQueue.install(sql); // Existing database, idempotent migration.
  MessageData first=insert("request-a",1);expect("request-a","sending");
  check(BrowserSmsQueue.replay(null,"request-a"));check(count("messages")==2);
  check(BrowserSmsQueue.allow(null,first,false));check(BrowserSmsQueue.allow(null,first,true,"request-a"));
  status(1,5);expect("request-a","sending");status(1,7);expect("request-a","sending");
  check(BrowserSmsQueue.allow(null,first,true,"request-a"));status(1,1);expect("request-a","sent");status(1,2);expect("request-a","sent");
  MessageData expiring=insert("request-b",2);ChatSmsSync.permission="expired";
  check(!BrowserSmsQueue.allow(null,expiring,false));status(2,8);expect("request-b","expired");
  // Explicit phone retry lifts the original browser deadline but still validates SIM/account.
  BrowserSmsQueue.manualRetry(expiring);check(BrowserSmsQueue.allow(null,expiring,true,"request-b"));
  ChatSmsSync.permission="failed";check(!BrowserSmsQueue.allow(null,expiring,true,"request-b"));expect("request-b","failed");
  ChatSmsSync.permission="";MessageData retry=insert("request-c",3);check(BrowserSmsQueue.allow(null,retry,true,"request-c"));
  ChatSmsSync.permission="expired";check(!BrowserSmsQueue.allow(null,retry,false));status(3,8);expect("request-c","failed"); // Never claim not-sent after attempting.
  ChatSmsSync.permission="";
  // Crash rollback leaves neither an orphan local row nor a consumed request key.
  sql.beginTransaction();try{sql.execSQL("INSERT INTO messages VALUES(4,'content://sms/4',4,NULL)");BrowserSmsQueue.bind(db,"request-d",new MessageData("4","content://sms/4"));}finally{sql.endTransaction();}
  check(BrowserSmsQueue.handoff(db,"request-d")==null);insert("request-d",4);
  // Restart after commit, before the external ledger link/ack.
  sql.close();sql=SQLiteDatabase.openOrCreateDatabase(path,null);db=new DatabaseWrapper(sql);DataModel.model.db=db;
  BrowserSmsQueue.install(sql);check(BrowserSmsQueue.replay(null,"request-d"));expect("request-d","sending");
  // Duplicate insertion rolls back even if the new local row was already created.
  int before=count("messages");boolean rejected=false;try{insert("request-d",5);}catch(Exception expected){rejected=true;}
  check(rejected);check(count("messages")==before);
  sql.execSQL("DELETE FROM messages WHERE _id=4");expect("request-d","deleted");
  check(!BrowserSmsQueue.allow(null,new MessageData("4","content://sms/4"),true,"request-d"));
  check(BrowserSmsQueue.replay(null,"request-d"));check(count("messages")==before-1);
  // SQLite may reuse IDs; old requests still cannot attach to a new message.
  insert("request-e",4);expect("request-d","deleted");expect("request-e","sending");
  check(!BrowserSmsQueue.allow(null,new MessageData("4","content://sms/4"),true,"request-d"));
  check(BrowserSmsQueue.allow(null,new MessageData("100","content://sms/100"),true,null));
  check(BrowserSmsQueue.status("legacy-no-queue-record")==null);
  sql.close();System.out.println("PASS: Android SQLite migration, atomic handoff/rollback, restart/replay, normal queue statuses and retries, deadline before/after submission, manual retry, SIM rejection, deletion/ID reuse and legacy isolation; no SMS sent");
 }
}
