package com.android.messaging.dumber;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;
import chat.dumber.sdk.*;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Collections;

/** Runs on Android: exercises the actual SQLite transactions, Keystore, and JNI library. */
public class ChatStorageTest extends AndroidTestCase {
    private ChatStore store;
    @Override protected void setUp() throws Exception {
        super.setUp();
        final File directory = new File(getContext().getCacheDir(), "chat-test-" + System.nanoTime());
        assertTrue(directory.mkdirs());
        Context isolated = new ContextWrapper(getContext()) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        Constructor<ChatStore> constructor = ChatStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        store = constructor.newInstance(isolated);
    }
    @Override protected void tearDown() throws Exception {
        if (store != null) {
            String path = store.getReadableDatabase().getPath();
            store.close();
            android.database.sqlite.SQLiteDatabase.deleteDatabase(new File(path));
            new File(path).getParentFile().delete();
        }
        super.tearDown();
    }
    public void testEncryptedAccountRoundTrip() {
        Session session = new Session("secret-credential", "account", "device", "+46123456789");
        ChatAccount account = new ChatAccount(session, "+46123456789", 3, 42, null, "SIM",
                "crypto", "store-password", 123L, AccountStatus.CONNECTED);
        store.save(account);
        assertEquals(account, store.account("account"));
        try (android.database.Cursor c = store.getReadableDatabase().rawQuery(
                "SELECT payload FROM records WHERE kind='account'", null)) {
            assertTrue(c.moveToFirst());
            String raw = new String(c.getBlob(0), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(raw.contains("secret-credential"));
            assertFalse(raw.contains("store-password"));
        }
    }
    public void testSimRemovalPreservesConnectionAndRemembersChoice() {
        Session session = new Session("credential", "account", "device", "+46123456789");
        ChatAccount account = new ChatAccount(session, "+46123456789", 3, 42, null, "SIM",
                "crypto", "password", 123L, AccountStatus.CONNECTED);
        store.save(account);
        assertTrue(SimRemovalChoices.INSTANCE.observe(store, Collections.emptyList()));
        assertTrue(store.account("account").getEnabled());
        assertTrue(store.logouts().isEmpty());
        SimRemovalChoice first = store.simChoices().get(0);
        assertFalse(first.getKeep());
        assertFalse(SimRemovalChoices.INSTANCE.observe(store, Collections.emptyList()));
        store.save(new SimRemovalChoice(first.getAccount(), first.getNonce(), true));
        assertFalse(SimRemovalChoices.INSTANCE.observe(store, Collections.emptyList()));
        assertTrue(store.simChoices().get(0).getKeep());
        assertTrue(store.account("account").getEnabled());
        assertTrue(store.logouts().isEmpty());
        Sim sim = new Sim(3, 42, "SIM", null, null);
        assertTrue(SimRemovalChoices.INSTANCE.observe(store, Collections.singletonList(sim)));
        assertTrue(store.simChoices().isEmpty());
        assertTrue(SimRemovalChoices.INSTANCE.observe(store, Collections.emptyList()));
        assertFalse(first.getNonce().equals(store.simChoices().get(0).getNonce()));
    }
    public void testGroupDeleteKeepsReplayTombstones() {
        Conversation info = new Conversation("room", ConversationKind.GROUP, "Group",
                Collections.emptyList(), new GroupCapabilities(true, true), Collections.emptyList(),
                Membership.JOINED);
        GroupState group = new GroupState("account", info, 0L, "", 0, false, false, false, false);
        store.save(group);
        ReceivedText event = new ReceivedText("account", "room", new Message("event",
                new Contact("other", "+46987654321"), 100L, new MessageContent.Text("Hello 🌍"), true, null));
        store.enqueue(event);
        assertTrue(store.groupEvent(event));
        assertFalse(store.groupEvent(event));
        assertEquals(1, store.messages(group.getKey()).size());
        assertEquals(1, store.group(group.getKey()).getUnread());
        store.deleteGroup(group.getKey());
        store.enqueue(event);
        assertTrue(store.handled(event));
        assertTrue(store.received().isEmpty());
        assertTrue(store.messages(group.getKey()).isEmpty());
        assertFalse(store.groupEvent(event));
        assertTrue(store.group(group.getKey()).getHidden());
    }
    public void testTypedNativeBridgeLoadsAndReportsValidationError() {
        assertTrue(ChatClient.isAvailable());
        try {
            new ChatClient(new ChatConfig("https://dumber.chat", null, null)).recipientStatus("+46123456789");
            fail("Expected a missing-session validation error");
        } catch (ChatException expected) {
            assertEquals(ErrorKind.INVALID_INPUT, expected.getKind());
        }
    }
}
