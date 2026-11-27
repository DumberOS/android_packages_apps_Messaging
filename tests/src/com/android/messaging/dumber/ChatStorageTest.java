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
    public void testRevocationNoticePersistsAndReconnectKeepsHistory() {
        Session session = new Session("old-credential", "old-account", "device", "+46123456789");
        ChatAccount account = new ChatAccount(session, "+46123456789", 3, 42, null, "SIM",
                "crypto", "password", 123L, AccountStatus.REVOKED);
        store.save(account);
        assertTrue(store.sessionNotices().isEmpty());
        store.markSessionNotice(account);
        store.close();
        assertTrue(store.sessionNotices().contains(ChatSessionAlerts.INSTANCE.key(account)));
        store.resolveRevocations("+46987654321");
        assertEquals(AccountStatus.REVOKED, store.account("old-account").getStatus());
        store.resolveRevocations("+46123456789");
        assertEquals(AccountStatus.LOGGED_OUT, store.account("old-account").getStatus());
        assertEquals("crypto", store.account("old-account").getStoreName());
        assertEquals(session, store.account("old-account").getSession());
        assertTrue(ChatSessionAlerts.INSTANCE.pending(store.accounts()).isEmpty());
        ChatAccount next = new ChatAccount(new Session("new-credential", "old-account", "device",
                "+46123456789"), "+46123456789", 3, 42, null, "SIM", "crypto", "password",
                456L, AccountStatus.REVOKED);
        store.save(next);
        assertFalse(store.sessionNotices().contains(ChatSessionAlerts.INSTANCE.key(next)));
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
    public void testBrowserSnapshotSurvivesReopenAndUsesSmallEncryptedRows() {
        Session session = new Session("credential", "account", "device", "+46123456789");
        ChatAccount account = new ChatAccount(session, "+46123456789", 3, 42, null, "SIM",
                "crypto", "password", 123L, AccountStatus.CONNECTED);
        store.save(account);
        // Larger than Android's usual CursorWindow: a single BLOB cannot be read back.
        String envelope = String.join("", Collections.nCopies(3 * 1024 * 1024, "x"));
        PreparedHistory prepared = new PreparedHistory("pair", envelope);
        BrowserHistoryTransfer transfer = new BrowserHistoryTransfer(account.getId(),
                ChatHistoryTransfer.INSTANCE.sessionKey(account), "12345678", 1000L, 10000L,
                prepared, true, false, 0L, "");
        store.save(transfer);
        store.close();
        assertEquals(transfer, store.browserHistory(true).get(0));
        assertNull(store.browserHistory().get(0).getPrepared());
        try (android.database.Cursor c = store.getReadableDatabase().rawQuery(
                "SELECT length(payload) FROM records WHERE kind='browser_history_chunk'", null)) {
            int chunks = 0;
            while (c.moveToNext()) { assertTrue(c.getLong(0) < 128 * 1024); chunks++; }
            assertTrue(chunks > 1);
        }
        store.save(new ChatAccount(new Session("replacement", "account", "device2", null),
                "+46123456789", 3, 42, null, "SIM", "crypto2", "password2", 456L, AccountStatus.CONNECTED));
        assertTrue(store.browserHistory(true).isEmpty());
        try (android.database.Cursor c = store.getReadableDatabase().rawQuery(
                "SELECT count(*) FROM records WHERE kind='browser_history_chunk'", null)) {
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0));
        }
    }
    public void testSmsClaimSurvivesRestartConcurrentReplayAndRegistrationReplacement() throws Exception {
        Session session = new Session("sms-credential", "sms-account", "sms-device", "+46123456789");
        ChatAccount account = new ChatAccount(session, "+46123456789", 3, 42, null, "SIM", "sms-crypto", "password", 123L, AccountStatus.CONNECTED);
        store.save(account); store.enableSms(account, "browser-link");
        SmsRequest request = new SmsRequest("event", "12345678-1234-1234-1234-123456789012", "send", "+46987654321", "test", 1000L, 1000L, "", "sms", "browser-link");
        SmsAttempt attempt = new SmsAttempt(account.getId(), ChatHistoryTransfer.INSTANCE.sessionKey(account), request, "unknown", "", Collections.emptyList());
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int i=0; i<12; i++) { Thread worker = new Thread(() -> { if (store.claimSms(attempt)) claims.incrementAndGet(); }); workers.add(worker); worker.start(); }
        for (Thread worker : workers) worker.join();
        assertEquals(1, claims.get());
        store.close(); // Reopen the actual SQLite database, as after process death.
        assertFalse(store.claimSms(attempt));
        assertEquals("unknown", store.smsAttempts().get(0).getState());
        assertEquals("browser-link", store.smsAttempts().get(0).getRequest().getPairingId());
        store.disableSms(account);
        assertFalse(store.smsEnabled(account));
        assertFalse(store.claimSms(attempt));
        store.enableSms(account,"fresh-link");
        assertFalse(store.claimSms(attempt));
        assertEquals(1,store.smsAttempts().size()); // Unpairing retains replay tombstones.
        store.save(new ChatAccount(new Session("replacement", "sms-account", "new-device", null), "+46123456789", 3, 42, null, "SIM", "new-crypto", "password", 456L, AccountStatus.CONNECTED));
        assertFalse(store.claimSms(attempt));
        assertEquals(1, store.smsAttempts().size());
    }

}
