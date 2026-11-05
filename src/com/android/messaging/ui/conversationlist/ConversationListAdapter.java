/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.ui.conversationlist;

import android.content.Context;
import android.database.Cursor;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.messaging.R;
import com.android.messaging.ui.CursorRecyclerAdapter;
import com.android.messaging.ui.conversationlist.ConversationListItemView.HostInterface;

/**
 * Provides an interface to expose Conversation List Cursor data to a UI widget like a ListView.
 */
public class ConversationListAdapter
        extends CursorRecyclerAdapter<ConversationListAdapter.ConversationListViewHolder> {

    private final ConversationListItemView.HostInterface mClivHostInterface;
    private java.util.List<com.android.messaging.dumber.ChatGroupRow> groups = java.util.Collections.emptyList();
    private final java.util.List<Row> rows = new java.util.ArrayList<>();
    private static class Row {
        int position;
        long id, date;
        com.android.messaging.dumber.ChatGroupRow group;
    }
    public void setGroups(java.util.List<com.android.messaging.dumber.ChatGroupRow> value) {
        groups = value; rebuildRows(); notifyDataSetChanged();
    }
    private void rebuildRows() {
        rows.clear();
        if (mCursor != null && !mCursor.isClosed()) {
            final int old = mCursor.getPosition();
            for (int i = 0; i < mCursor.getCount(); i++) {
                mCursor.moveToPosition(i);
                Row row = new Row(); row.position = i;
                row.id = mCursor.getLong(mRowIDColumn);
                row.date = mCursor.getLong(mCursor.getColumnIndexOrThrow("sort_timestamp"));
                rows.add(row);
            }
            mCursor.moveToPosition(old);
        }
        for (com.android.messaging.dumber.ChatGroupRow group : groups) {
            Row row = new Row(); row.group = group; row.date = group.getTimestamp();
            // Group IDs occupy a distinct namespace from positive provider conversation IDs.
            row.id = Long.MIN_VALUE | Long.parseUnsignedLong(group.getKey().substring(0, 15), 16);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            boolean ap = a.group != null && a.group.getPinned();
            boolean bp = b.group != null && b.group.getPinned();
            int pinned = Boolean.compare(bp, ap);
            return pinned != 0 ? pinned : Long.compare(b.date, a.date);
        });
    }
    @Override public Cursor swapCursor(Cursor cursor) {
        Cursor old = super.swapCursor(cursor); rebuildRows(); notifyDataSetChanged(); return old;
    }
    @Override public int getItemCount() { return rows == null ? 0 : rows.size(); }
    @Override public long getItemId(int position) { return rows.get(position).id; }
    @Override public int getItemViewType(int position) { return rows.get(position).group == null ? 0 : 1; }
    @Override public void onBindViewHolder(ConversationListViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.group != null) {
            com.android.messaging.dumber.ChatGroups.bind(holder.itemView, row.group);
        } else {
            mCursor.moveToPosition(row.position);
            bindViewHolder(holder, mContext, mCursor);
        }
    }
    @Override public Object getItem(int position) {
        Row row = rows.get(position);
        if (row.group != null) return row.group;
        mCursor.moveToPosition(row.position); return mCursor;
    }

    public ConversationListAdapter(final Context context, final Cursor cursor,
            final ConversationListItemView.HostInterface clivHostInterface) {
        super(context, cursor, 0);
        mClivHostInterface = clivHostInterface;
        setHasStableIds(true);
        rebuildRows();
    }

    /**
     * @see com.android.messaging.ui.CursorRecyclerAdapter#bindViewHolder(
     * androidx.recyclerview.widget.RecyclerView.ViewHolder, android.content.Context,
     * android.database.Cursor)
     */
    @Override
    public void bindViewHolder(final ConversationListViewHolder holder, final Context context,
            final Cursor cursor) {
        final ConversationListItemView conversationListItemView = holder.mView;
        conversationListItemView.bind(cursor, mClivHostInterface);
    }

    @Override
    public ConversationListViewHolder createViewHolder(final Context context,
            final ViewGroup parent, final int viewType) {
        if (viewType == 1) {
            return new ConversationListViewHolder(LayoutInflater.from(context).inflate(
                    R.layout.dumber_group_list_item, parent, false));
        }
        final LayoutInflater layoutInflater = LayoutInflater.from(context);
        final ConversationListItemView itemView =
                (ConversationListItemView) layoutInflater.inflate(
                        R.layout.conversation_list_item_view, null);
        return new ConversationListViewHolder(itemView);
    }

    /**
     * ViewHolder that holds a ConversationListItemView.
     */
    public static class ConversationListViewHolder extends RecyclerView.ViewHolder {
        final ConversationListItemView mView;

        public ConversationListViewHolder(final android.view.View itemView) {
            super(itemView);
            mView = itemView instanceof ConversationListItemView ? (ConversationListItemView) itemView : null;
        }
    }
}
