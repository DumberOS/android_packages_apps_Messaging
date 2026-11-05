package chat.dumber.calls;

import android.os.Parcel;
import android.os.Parcelable;

public final class CallSetup implements Parcelable {
  public String conversationId;
  public String[] uris;
  public String username;
  public String password;
  public long ttl;

  public CallSetup() {}

  private CallSetup(Parcel p) {
    conversationId = p.readString();
    uris = p.createStringArray();
    username = p.readString();
    password = p.readString();
    ttl = p.readLong();
  }

  public void writeToParcel(Parcel p, int flags) {
    p.writeString(conversationId);
    p.writeStringArray(uris);
    p.writeString(username);
    p.writeString(password);
    p.writeLong(ttl);
  }

  public int describeContents() {
    return 0;
  }

  public static final Creator<CallSetup> CREATOR =
      new Creator<CallSetup>() {
        public CallSetup createFromParcel(Parcel p) {
          return new CallSetup(p);
        }

        public CallSetup[] newArray(int n) {
          return new CallSetup[n];
        }
      };
}
