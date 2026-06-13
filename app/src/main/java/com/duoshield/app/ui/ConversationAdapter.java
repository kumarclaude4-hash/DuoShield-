package com.duoshield.app.ui;

import android.graphics.Color;
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
    private final OnConversationClickListener listener;

    /** Teal-palette shades for avatar initials when no photo is available. */
    private static final int[] AVATAR_COLORS = {
        0xFF1A5C6E, 0xFF1E7B6A, 0xFF1E5C9E, 0xFF5C1E6E, 0xFF6E3A1E
    };

    public ConversationAdapter(OnConversationClickListener listener) {
        this.listener = listener;
    }

    public void setConversations(List<Conversation> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Conversation> oldList = items;
        final List<Conversation> finalNew = newList;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                String oid = oldList.get(o).getId();
                String nid = finalNew.get(n).getId();
                return oid != null && oid.equals(nid);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                Conversation a = oldList.get(o), b = finalNew.get(n);
                return safeEquals(a.getLastMessage(), b.getLastMessage())
                    && a.getLastMessageTs() == b.getLastMessageTs()
                    && a.getUnreadCount() == b.getUnreadCount()
                    && a.isOnline() == b.isOnline()
                    && a.isTyping() == b.isTyping();
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

        // Name
        String name = c.getPartnerName() != null ? c.getPartnerName() : "Unknown";
        h.name.setText(name);

        // Timestamp
        h.time.setText(TimeFormatter.format(c.getLastMessageTs()));

        // Preview vs typing
        if (c.isTyping()) {
            h.preview.setVisibility(View.GONE);
            h.typing.setVisibility(View.VISIBLE);
        } else {
            h.preview.setVisibility(View.VISIBLE);
            h.typing.setVisibility(View.GONE);
            String preview = c.getLastMessage();
            h.preview.setText(preview != null ? preview : "");
        }

        // Unread badge
        int unread = c.getUnreadCount();
        if (unread > 0 && !c.isMuted()) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        // Online dot
        h.onlineDot.setVisibility(c.isOnline() ? View.VISIBLE : View.GONE);

        // Avatar: photo → initials fallback
        String avatarUrl = c.getAvatarUrl();
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            h.initial.setVisibility(View.GONE);
            h.avatar.setVisibility(View.VISIBLE);
            com.duoshield.app.util.GlideHelper.loadAvatar(h.avatar.getContext(), avatarUrl, h.avatar);
        } else {
            h.avatar.setVisibility(View.INVISIBLE);
            h.initial.setVisibility(View.VISIBLE);
            String initial = name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
            h.initial.setText(initial);
            int color = AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
            h.initial.getBackground().setTint(color);
        }

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(c); });
        h.itemView.setOnLongClickListener(v -> { if (listener != null) listener.onLongClick(c); return true; });
    }

    @Override public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView  initial, name, preview, time, badge, typing;
        View      onlineDot;

        ViewHolder(@NonNull View v) {
            super(v);
            avatar    = v.findViewById(R.id.iv_avatar);
            initial   = v.findViewById(R.id.tv_avatar_initial);
            name      = v.findViewById(R.id.tv_name);
            preview   = v.findViewById(R.id.tv_preview);
            time      = v.findViewById(R.id.tv_time);
            badge     = v.findViewById(R.id.tv_badge);
            typing    = v.findViewById(R.id.tv_typing);
            onlineDot = v.findViewById(R.id.online_dot);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
