package chat.dumber.calls;
import chat.dumber.calls.CallAccount;
import chat.dumber.calls.CallSetup;
import chat.dumber.calls.CallSignal;
import chat.dumber.calls.ICallListener;
interface ICallBroker {
 int version();
 CallAccount[] accounts();
 void subscribe(ICallListener listener);
 boolean supportsCall(String accountId, String phone);
 CallSetup prepare(String accountId, String phone, String conversationId);
 void send(String accountId, String conversationId, in CallSignal signal, String transactionId);
 void acknowledge(String accountId, String eventId);
}
