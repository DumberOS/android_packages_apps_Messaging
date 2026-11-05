package chat.dumber.calls;

import android.os.Parcel;
import android.os.Parcelable;

public final class CallEvent implements Parcelable {
  public String accountId;
  public String conversationId;
  public String phone;
  public String eventId;
  public long expiresAtMs;
  public CallSignal signal;

  public CallEvent() {}

  private CallEvent(Parcel p) {
    accountId = p.readString();
    conversationId = p.readString();
    phone = p.readString();
    eventId = p.readString();
    expiresAtMs = p.readLong();
    signal = p.readTypedObject(CallSignal.CREATOR);
  }

  public void writeToParcel(Parcel p, int flags) {
    p.writeString(accountId);
    p.writeString(conversationId);
    p.writeString(phone);
    p.writeString(eventId);
    p.writeLong(expiresAtMs);
    p.writeTypedObject(signal, flags);
  }

  public int describeContents() {
    return 0;
  }

  public static final Creator<CallEvent> CREATOR =
      new Creator<CallEvent>() {
        public CallEvent createFromParcel(Parcel p) {
          return new CallEvent(p);
        }

        public CallEvent[] newArray(int n) {
          return new CallEvent[n];
        }
      };
}
