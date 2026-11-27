package com.android.messaging.datamodel;
public final class DataModel {
 public static final DataModel model=new DataModel();
 public DatabaseWrapper db;
 public static DataModel get(){return model;}
 public DatabaseWrapper getDatabase(){return db;}
}
