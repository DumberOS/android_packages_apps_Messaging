package com.android.messaging.datamodel;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
/** Test adapter: executes the production queue's SQL on Android SQLite. */
public final class DatabaseWrapper {
 public final SQLiteDatabase sql;
 public DatabaseWrapper(SQLiteDatabase sql){this.sql=sql;}
 public Cursor rawQuery(String query,String[] args){return sql.rawQuery(query,args);}
 public void execSQL(String query,String[] args){sql.execSQL(query,args);}
}
