package chat.dumber.calls;
import chat.dumber.calls.CallEvent;
oneway interface ICallListener { void onEvent(in CallEvent event); void onAccountsChanged(); }
