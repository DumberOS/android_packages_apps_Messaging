package com.android.messaging.ui;

import android.graphics.Rect;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.text.Layout;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.android.messaging.R;
import java.util.ArrayList;

/** Keyboard focus for custom action views. Never consume the first touch to gain focus. */
public final class DpadNavigation {
    private DpadNavigation() {}

    public static void focusable(View view) {
        view.setFocusableInTouchMode(false);
        view.setFocusable(true);
        view.setForeground(view.getContext().getDrawable(R.drawable.dpad_focus));
    }

    /** Find the toolbar's actual overflow control, including AppCompat's focus barrier. */
    public static View overflowButton(View toolbar) {
        if (toolbar == null) return null;
        final ArrayList<View> candidates = new ArrayList<>();
        toolbar.findViewsWithText(candidates,
                toolbar.getContext().getString(R.string.abc_action_menu_overflow_description),
                View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        for (View candidate : candidates) {
            if (!candidate.isShown() || !candidate.isEnabled()) continue;
            focusable(candidate);
            View ancestor = candidate;
            while (ancestor != toolbar && ancestor.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) ancestor.getParent();
                parent.setTouchscreenBlocksFocus(false);
                parent.setKeyboardNavigationCluster(false);
                ancestor = parent;
            }
            return candidate;
        }
        return null;
    }

    /** Explicit paths between history, the editor and the toolbar/composer controls. */
    public static boolean navigateChatControls(KeyEvent event, View focus, EditText editor,
            View send, View overflow, View call, RecyclerView history) {
        if (editor == null || focus == null || event.getAction() != KeyEvent.ACTION_DOWN
                || !event.hasNoModifiers()) return false;
        final boolean canFocusSend = send != null && send.isShown() && send.isEnabled();
        final boolean canFocusCall = call != null && call.isShown() && call.isEnabled();
        final int key = event.getKeyCode();
        final View message = history == null ? null : history.findContainingItemView(focus);
        View target = null;
        if (message != null && (key == KeyEvent.KEYCODE_DPAD_RIGHT
                || (key == KeyEvent.KEYCODE_DPAD_UP
                        && history.getChildAdapterPosition(message) == 0))) {
            // Use the oldest adapter row, not merely the first visible message.
            target = overflow;
        } else if (focus == editor && key == KeyEvent.KEYCODE_DPAD_RIGHT
                && editorExitDirection(editor, key, event) == View.FOCUS_RIGHT) {
            target = canFocusSend ? send : overflow;
        } else if (focus == send) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) target = overflow;
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) target = editor;
        } else if (focus == overflow) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) target = canFocusSend ? send : editor;
            else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (canFocusCall) focusable(call);
                target = canFocusCall ? call : editor;
            }
        } else if (focus == call && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            target = overflow;
        }
        return target != null && target.isShown() && target.isEnabled() && target.requestFocus();
    }

    /** Navigate history by whole rows, before selectable text consumes vertical keys. */
    public static boolean moveBetweenMessages(ViewGroup row, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || !event.hasNoModifiers()
                || !row.hasFocus()) return false;
        final int direction;
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            direction = View.FOCUS_UP;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            direction = View.FOCUS_DOWN;
        } else {
            return false;
        }
        // The full-width row is the search origin, so incoming/outgoing alignment and
        // other controls within this message cannot add stops or skip the next message.
        View next;
        RecyclerView list = row.getParent() instanceof RecyclerView
                ? (RecyclerView) row.getParent() : null;
        int position = list == null ? RecyclerView.NO_POSITION
                : list.getChildAdapterPosition(row);
        if (position != RecyclerView.NO_POSITION && list.getAdapter() != null) {
            int adjacent = position + (direction == View.FOCUS_UP ? -1 : 1);
            if (adjacent >= 0 && adjacent < list.getAdapter().getItemCount()) {
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(adjacent);
                if (holder == null) {
                    // Let RecyclerView lay out the next off-screen row. Its geometric
                    // choice can be a toolbar control, so focus the adjacent position.
                    list.focusSearch(row, direction);
                    holder = list.findViewHolderForAdapterPosition(adjacent);
                }
                if (holder != null) {
                    // Finish the scroll before focusing selectable text. Otherwise its
                    // caret scrolling can race RecyclerView and recycle the focused row.
                    View target = holder.itemView;
                    list.stopScroll();
                    list.requestChildRectangleOnScreen(target,
                            new Rect(0, 0, target.getWidth(), target.getHeight()), true);
                    target.requestFocus(direction);
                }
                return true;
            }
        }
        boolean atEdge = position != RecyclerView.NO_POSITION && list.getAdapter() != null
                && (direction == View.FOCUS_UP ? position == 0
                        : position == list.getAdapter().getItemCount() - 1);
        if (atEdge && row.getRootView() instanceof ViewGroup) {
            // A partially visible row may extend beyond the composer or toolbar. Search
            // from the viewport edge instead, so the first/last message can leave history.
            ViewGroup root = (ViewGroup) row.getRootView();
            Rect edge = new Rect();
            list.getDrawingRect(edge);
            root.offsetDescendantRectToMyCoords(list, edge);
            if (direction == View.FOCUS_UP) edge.bottom = edge.top;
            else edge.top = edge.bottom;
            next = FocusFinder.getInstance().findNextFocusFromRect(root, edge, direction);
        } else {
            next = row.focusSearch(direction);
        }
        if (next != null && next != row) next.requestFocus(direction);
        return true;
    }

    /** Leave an editor only at a text boundary; keep normal cursor/selection keys inside it. */
    public static boolean leaveEditor(EditText editor, int keyCode, KeyEvent event) {
        int direction = editorExitDirection(editor, keyCode, event);
        if (direction == 0) return false;
        View next = editor.focusSearch(direction);
        return next != null && next != editor && next.requestFocus(direction);
    }

    private static int editorExitDirection(EditText editor, int keyCode, KeyEvent event) {
        if (!event.hasNoModifiers() || editor.getSelectionStart() != editor.getSelectionEnd()) {
            return 0;
        }
        int caret = editor.getSelectionStart();
        Layout layout = editor.getLayout();
        if (caret < 0 || layout == null) return 0;
        int line = layout.getLineForOffset(caret);
        int direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (line != 0) return 0;
                direction = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (line != layout.getLineCount() - 1) return 0;
                direction = View.FOCUS_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                boolean right = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                boolean atEnd = right == (layout.getParagraphDirection(line) == Layout.DIR_LEFT_TO_RIGHT);
                if (caret != (atEnd ? editor.length() : 0)) return 0;
                direction = right ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
                break;
            default:
                return 0;
        }
        return direction;
    }
}
