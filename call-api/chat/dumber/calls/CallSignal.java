package chat.dumber.calls;

import android.os.Parcel;
import android.os.Parcelable;

public final class CallSignal implements Parcelable {
  public String kind;
  public String callId;
  public String partyId;
  public long sentAtMs;
  public String sdp;
  public String descriptionType;
  public String selectedPartyId;
  public String reason;
  public String[] candidates;
  public String[] mids;
  public int[] lines;

  public CallSignal() {}

  private CallSignal(Parcel p) {
    kind = p.readString();
    callId = p.readString();
    partyId = p.readString();
    sentAtMs = p.readLong();
    sdp = p.readString();
    descriptionType = p.readString();
    selectedPartyId = p.readString();
    reason = p.readString();
    candidates = p.createStringArray();
    mids = p.createStringArray();
    lines = p.createIntArray();
  }

  public void writeToParcel(Parcel p, int flags) {
    p.writeString(kind);
    p.writeString(callId);
    p.writeString(partyId);
    p.writeLong(sentAtMs);
    p.writeString(sdp);
    p.writeString(descriptionType);
    p.writeString(selectedPartyId);
    p.writeString(reason);
    p.writeStringArray(candidates);
    p.writeStringArray(mids);
    p.writeIntArray(lines);
  }

  public int describeContents() {
    return 0;
  }

  public static final Creator<CallSignal> CREATOR =
      new Creator<CallSignal>() {
        public CallSignal createFromParcel(Parcel p) {
          return new CallSignal(p);
        }

        public CallSignal[] newArray(int n) {
          return new CallSignal[n];
        }
      };
}
