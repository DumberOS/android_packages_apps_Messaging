package com.android.messaging.ui;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.android.messaging.dumber.ChatGroupMessages;
import com.android.messaging.dumber.GroupState;
import com.android.messaging.dumber.ReceivedText;
import chat.dumber.sdk.*;
import java.util.ArrayList;
import java.util.Collections;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ContactListItemData;
import com.android.messaging.datamodel.data.TestDataFactory;
import com.android.messaging.ui.contact.AllContactsListViewHolder;
import com.android.messaging.ui.contact.ContactListItemView;

/** Real input dispatch: performClick alone cannot catch the two-tap regression. */
public class DpadNavigationTest extends android.test.ActivityInstrumentationTestCase2<TestActivity> {
    private int clicks;
    private View send;
    private EditText entry;
    private ListView contacts;

    public DpadNavigationTest() { super(TestActivity.class); }

    private void ui(Runnable action) {
        getInstrumentation().runOnMainSync(action);
        getInstrumentation().waitForIdleSync();
    }

    private void key(int code) {
        getInstrumentation().sendKeyDownUpSync(code);
        getInstrumentation().waitForIdleSync();
        if (history != null) {
            long deadline = SystemClock.uptimeMillis() + 2000;
            while (history.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
                    && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20);
                getInstrumentation().waitForIdleSync();
            }
        }
    }

    private void tap(View view) {
        int[] xy = new int[2];
        ui(() -> view.getLocationOnScreen(xy));
        long now = SystemClock.uptimeMillis();
        float x = xy[0] + view.getWidth() / 2f;
        float y = xy[1] + view.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        getInstrumentation().sendPointerSync(down);
        getInstrumentation().sendPointerSync(up);
        down.recycle();
        up.recycle();
        getInstrumentation().waitForIdleSync();
    }

    private void composer() {
        getActivity();
        ui(() -> {
            getActivity().setContentView(R.layout.dumber_group_chat);
            getActivity().findViewById(R.id.group_composer).setVisibility(View.VISIBLE);
            entry = getActivity().findViewById(R.id.group_message_entry);
            entry.setShowSoftInputOnFocus(false);
            entry.setText("Keyboard test");
            entry.setSelection(entry.length());
            send = getActivity().findViewById(R.id.group_send);
            send.setOnClickListener(v -> clicks++);
        });
        getInstrumentation().setInTouchMode(false);
        ui(() -> entry.requestFocus());
    }

    public void testComposerRightThenCenterActivatesOnce() {
        composer();
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue("Right at the end of the draft reaches Send", send.hasFocus());
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, clicks);
    }

    public void testFirstTouchAfterKeyboardActivatesSend() {
        composer();
        assertFalse(send.isFocusableInTouchMode());
        tap(send);
        assertEquals("The first tap must send, not only focus", 1, clicks);
    }

    public void testArrowsInsideDraftStillMoveCursor() {
        composer();
        ui(() -> entry.setSelection(2));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(entry.hasFocus());
        assertEquals(3, entry.getSelectionStart());
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(2, entry.getSelectionStart());
    }

    public void testDownLeavesRecipientToolbar() {
        getActivity();
        ui(() -> {
            getActivity().setContentView(R.layout.contact_picker_fragment);
            CustomHeaderViewPager pager = getActivity().findViewById(R.id.contact_pager);
            pager.setViewHolders(new CustomHeaderPagerViewHolder[] {
                    new AllContactsListViewHolder(getActivity(), null) });
            entry = getActivity().findViewById(R.id.recipient_text_view);
            entry.setShowSoftInputOnFocus(false);
        });
        getInstrumentation().setInTouchMode(false);
        ui(() -> entry.requestFocus());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertFalse("D-pad must escape the toolbar without a Tab key", entry.hasFocus());
        assertTrue(getActivity().findViewById(R.id.tab_strip).hasFocus());
    }

    private void contactPicker() {
        getActivity();
        ui(() -> {
            AllContactsListViewHolder holder = new AllContactsListViewHolder(getActivity(),
                    new ContactListItemView.HostInterface() {
                        public void onContactListItemClicked(ContactListItemData item,
                                ContactListItemView view) { clicks++; }
                        public boolean isContactSelected(ContactListItemData item) { return false; }
                    });
            View page = holder.getView((ViewGroup) getActivity().findViewById(android.R.id.content));
            getActivity().setContentView(page);
            holder.onContactsCursorUpdated(TestDataFactory.getAllContactListCursor());
            contacts = page.findViewById(R.id.all_contacts_list);
        });
        getInstrumentation().setInTouchMode(false);
        ui(() -> { contacts.requestFocus(); contacts.setSelection(0); });
    }

    public void testContactCenterUsesRowAction() {
        contactPicker();
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, clicks);
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(2, clicks);
    }

    public void testContactSingleTapAfterKeyboardSelection() {
        contactPicker();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        tap(contacts.getChildAt(0));
        assertEquals(1, clicks);
    }
    private RecyclerView history;

    private void messageHistory() {
        getActivity();
        ui(() -> {
            getActivity().setContentView(R.layout.dumber_group_chat);
            getActivity().findViewById(R.id.group_composer).setVisibility(View.VISIBLE);
            entry = getActivity().findViewById(R.id.group_message_entry);
            entry.setShowSoftInputOnFocus(false);
            getActivity().findViewById(R.id.group_send).setEnabled(false);
            history = getActivity().findViewById(R.id.group_messages);
            LinearLayoutManager manager = new LinearLayoutManager(getActivity());
            manager.setStackFromEnd(true);
            history.setLayoutManager(manager);
            ChatGroupMessages adapter = new ChatGroupMessages(p -> kotlin.Unit.INSTANCE);
            history.setAdapter(adapter);
            Conversation info = new Conversation("room", ConversationKind.GROUP, "Test",
                    Collections.emptyList(), new GroupCapabilities(true, true),
                    Collections.emptyList(), Membership.JOINED);
            GroupState group = new GroupState("self", info, 0L, "", 0, false, false, false, false);
            ArrayList<ReceivedText> messages = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                StringBuilder body = new StringBuilder("Message " + i + "\nSecond line\nThird line");
                if (i == 5) {
                    for (int line = 0; line < 30; line++) body.append("\nLong message line");
                }
                messages.add(new ReceivedText("self", "room", new Message("event" + i,
                        new Contact(i % 2 == 0 ? "self" : "other", "+46123456789"), i * 1000L,
                        new MessageContent.Text(body.toString()),
                        true, null)));
            }
            adapter.update(group, messages, Collections.emptyList());
            manager.scrollToPositionWithOffset(6, 0);
        });
        getInstrumentation().setInTouchMode(false);
        ui(() -> {
            ChatGroupMessages.Holder holder = (ChatGroupMessages.Holder)
                    history.findViewHolderForAdapterPosition(6);
            assertNotNull(holder);
            assertTrue(holder.getBody().requestFocus());
        });
    }

    private void assertMessageFocused(int position) {
        int[] actual = { RecyclerView.NO_POSITION };
        ui(() -> {
            View focused = history.findFocus();
            View row = focused == null ? null : history.findContainingItemView(focused);
            if (row != null) actual[0] = history.getChildAdapterPosition(row);
        });
        assertEquals("Focused message; actual view: " + getActivity().getCurrentFocus(),
                position, actual[0]);
    }

    public void testHistoryMovesOneMessagePerPressIncludingOffscreenRows() {
        messageHistory();
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertMessageFocused(7);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertMessageFocused(6);
        for (int i = 7; i < 12; i++) {
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            assertMessageFocused(i);
        }
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("Down from the last message reaches the composer; actual: "
                + getActivity().getCurrentFocus(), entry.hasFocus());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertMessageFocused(11);
        for (int i = 10; i >= 0; i--) {
            key(KeyEvent.KEYCODE_DPAD_UP);
            assertMessageFocused(i);
        }
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("Up from the first message reaches the toolbar",
                getActivity().findViewById(R.id.group_toolbar).hasFocus());
    }

    public void testHistoryRetainsTouchTextSelection() {
        messageHistory();
        TextView body = ((ChatGroupMessages.Holder)
                history.findViewHolderForAdapterPosition(6)).getBody();
        int[] xy = new int[2];
        ui(() -> body.getLocationOnScreen(xy));
        long now = SystemClock.uptimeMillis();
        float x = xy[0] + body.getWidth() / 2f;
        float y = xy[1] + body.getLineHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        getInstrumentation().sendPointerSync(down);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 200);
        MotionEvent up = MotionEvent.obtain(now, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y, 0);
        getInstrumentation().sendPointerSync(up);
        down.recycle();
        up.recycle();
        getInstrumentation().waitForIdleSync();
        assertTrue("Long touch must still select text", body.hasSelection());
    }

}
