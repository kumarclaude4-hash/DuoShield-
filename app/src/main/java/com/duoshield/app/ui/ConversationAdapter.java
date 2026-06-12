package com.duoshield.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.R;
import com.duoshield.app.models.Conversation;
import com.duoshield.app.util.GlideHelper;
import com.duoshield.app.util.TimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ConversationAdapter
        extends RecyclerView.Adapter<ConversationAdapter.ViewHolder> {

    public interface OnConversationClickListener {
        void onClick(Conversation conv);
        void onLongClick(Conversation conv);
    }

    private List<Conversation> items = new ArrayList<>();
    private final String myUid;
    private final OnConversationClickListener listener;

    public ConversationAdapter(String myUid, OnConversationClickListener listener) {
        this.myUid    = myUid;
        this.listener = listener;
    }

    public void setConversations(List<Conversation> newList) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return newList.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return safeEquals(items.get(o).id, newList.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                Conversation a = items.get(o), b = newList.get(n);
                return safeEquals(a.lastMessage, b.lastMessage)
                    && a.lastMessageTs == b.lastMessageTs
                    && a.unreadCount == b.unreadCount;
            }
        });
        items = new ArrayList<>(newList);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        Conversation c = items.get(pos);
        h.name.setText(c.partnerName != null ? c.partnerName : "Unknown");
        h.preview.setText(c.lastMessage != null ? c.lastMessage : "");
        h.time.setText(TimeFormatter.format(c.lastMessageTs));

        int unread = c.unreadCount;
        if (unread > 0) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        if (c.avatarUrl != null && !c.avatarUrl.isEmpty()) {
            GlideHelper.loadAvatar(h.avatar.getContext(), c.avatarUrl, h.avatar);
        } else {
            h.avatar.setImageResource(R.drawable.ic_person);
        }

        h.itemView.setOnClickListener(v -> listener.onClick(c));
        h.itemView.setOnLongClickListener(v -> { listener.onLongClick(c); return true; });
    }

    @Override public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView  name, preview, time, badge;
        ViewHolder(@NonNull View v) {
            super(v);
            avatar  = v.findViewById(R.id.iv_avatar);
            name    = v.findViewById(R.id.tv_name);
            preview = v.findViewById(R.id.tv_preview);
            time    = v.findViewById(R.id.tv_time);
            badge   = v.findViewById(R.id.tv_badge);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
