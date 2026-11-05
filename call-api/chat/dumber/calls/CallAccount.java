package chat.dumber.calls;

import android.os.Parcel;
import android.os.Parcelable;

public final class CallAccount implements Parcelable {
  public String id;
  public String phone;
  public String partyId;
  public int subscription;

  public CallAccount() {}

  private CallAccount(Parcel p) {
    id = p.readString();
    phone = p.readString();
    partyId = p.readString();
    subscription = p.readInt();
  }

  public void writeToParcel(Parcel p, int flags) {
    p.writeString(id);
    p.writeString(phone);
    p.writeString(partyId);
    p.writeInt(subscription);
  }

  public int describeContents() {
    return 0;
  }

  public static final Creator<CallAccount> CREATOR =
      new Creator<CallAccount>() {
        public CallAccount createFromParcel(Parcel p) {
          return new CallAccount(p);
        }

        public CallAccount[] newArray(int n) {
          return new CallAccount[n];
        }
      };
}
