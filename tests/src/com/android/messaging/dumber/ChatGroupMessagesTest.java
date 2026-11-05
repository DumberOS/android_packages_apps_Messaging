package com.android.messaging.dumber;

import android.test.AndroidTestCase;
import chat.dumber.sdk.*;
import java.util.Collections;

/** Pending-to-confirmed reconciliation must not duplicate or hide a group message. */
public class ChatGroupMessagesTest extends AndroidTestCase {
    private GroupState group() {
        Conversation info = new Conversation("room", ConversationKind.GROUP, "Friends",
                Collections.emptyList(), new GroupCapabilities(true, true), Collections.emptyList(),
                Membership.JOINED);
        return new GroupState("self", info, 100L, "", 0, false, false, false, false);
    }
    private PendingText pending() {
        return new PendingText("transaction", "self", 3, "", "Hello", "", "room", true,
                DeliveryState.UNCERTAIN, null, "", false, 100L, "device");
    }
    private ReceivedText message(String sender) {
        return new ReceivedText("self", "room", new Message("event", new Contact(sender, "+46123456789"),
                100L, new MessageContent.Text("Hello"), true, "transaction"));
    }
    public void testOwnEchoReplacesPendingBubble() {
        ChatGroupMessages adapter = new ChatGroupMessages(p -> kotlin.Unit.INSTANCE);
        adapter.update(group(), Collections.emptyList(), Collections.singletonList(pending()));
        assertEquals(1, adapter.getItemCount());
        adapter.update(group(), Collections.singletonList(message("self")), Collections.singletonList(pending()));
        assertEquals(1, adapter.getItemCount());
        adapter.update(group(), Collections.singletonList(message("self")), Collections.emptyList());
        assertEquals(1, adapter.getItemCount());
    }
    public void testOtherSendersTransactionCannotHideOurPendingMessage() {
        ChatGroupMessages adapter = new ChatGroupMessages(p -> kotlin.Unit.INSTANCE);
        adapter.update(group(), Collections.singletonList(message("other")), Collections.singletonList(pending()));
        assertEquals(2, adapter.getItemCount());
    }
}
