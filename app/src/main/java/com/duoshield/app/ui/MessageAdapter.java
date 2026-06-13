package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    public interface OnVoicePlayListener       { void onVoicePlay(Message m); }
    public interface OnMessageLongPressListener { void onLongPress(Message m, View anchor); }

    private List<Message>                     messages;
    private final String                      myUid;
    private final OnVoicePlayListener         voiceListener;
    private final OnMessageLongPressListener  longPressListener;
    private final Set<String>                 pinnedIds = new HashSet<>();

    public MessageAdapter(List<Message> messages, String myUid,
                          OnVoicePlayListener vl, OnMessageLongPressListener ll) {
        this.messages = messages != null ? messages : new ArrayList<>();
        this.myUid = myUid;
        this.voiceListener = vl;
        this.longPressListener = ll;
    }

    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Message> oldList = messages;
        final List<Message> finalNew = newList;
        androidx.recyclerview.widget.DiffUtil.DiffResult diff =
            androidx.recyclerview.widget.DiffUtil.calculateDiff(
                new androidx.recyclerview.widget.DiffUtil.Callback() {
                    @Override public int getOldListSize() { return oldList.size(); }
                    @Override public int getNewListSize() { return finalNew.size(); }
                    @Override public boolean areItemsTheSame(int o, int n) {
                        return oldList.get(o).getId() != null
                            && oldList.get(o).getId().equals(finalNew.get(n).getId());
                    }
                    @Override public boolean areContentsTheSame(int o, int n) {
                        Message a = oldList.get(o), b = finalNew.get(n);
                        return safeEq(a.getText(), b.getText())
                            && safeEq(a.getStatus(), b.getStatus())
                            && a.isEdited() == b.isEdited()
                            && safeEq(a.getReaction(), b.getReaction());
                    }
                });
        messages = finalNew;
        diff.dispatchUpdatesTo(this);
    }

    public void updatePinnedIds(Set<String> ids) {
        pinnedIds.clear();
        if (ids != null) pinnedIds.addAll(ids);
        notifyDataSetChanged();
    }

    private static boolean safeEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @NonNull @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new MessageViewHolder(
            LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder h, int position) {
        Message msg  = messages.get(position);
        boolean mine = myUid != null && myUid.equals(msg.getSender());
        String  type = msg.getMediaType();

        h.textView.setVisibility(View.GONE);
        h.imageView.setVisibility(View.GONE);
        h.videoContainer.setVisibility(View.GONE);
        h.contactCardContainer.setVisibility(View.GONE);
        h.replyPreviewContainer.setVisibility(View.GONE);
        h.reactionText.setVisibility(View.GONE);

        // Sender label (#14)
        if (h.senderLabel != null) {
            h.senderLabel.setText(mine ? "You" : "Partner");
            h.senderLabel.setVisibility(View.VISIBLE);
        }

        // Pin indicator
        h.pinIndicator.setVisibility(pinnedIds.contains(msg.getId()) ? View.VISIBLE : View.GONE);

        // Bubble side and background (#12 — bubbleCard is now LinearLayout)
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubbleCard.getLayoutParams();
        lp.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        h.bubbleCard.setBackgroundResource(mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs);
        h.bubbleCard.setLayoutParams(lp);

        // Reply preview
        String rp = msg.getReplyPreview();
        if (rp != null && !rp.isEmpty()) {
            h.replyPreviewContainer.setVisibility(View.VISIBLE);
            h.replyPreviewText.setText(rp);
        }

        // Content
        if ("video".equals(type)) {
            h.videoContainer.setVisibility(View.VISIBLE);
            if (h.videoThumbnail != null) {
                Glide.with(h.itemView.getContext()).load(msg.getMediaUrl())
                     .placeholder(R.drawable.ic_play_video).centerCrop().into(h.videoThumbnail);
            }
            h.videoContainer.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(msg.getMediaUrl()), "video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                v.getContext().startActivity(Intent.createChooser(i, "Play video"));
            });
        } else if ("contact_card".equals(type)) {
            // Contact card views (#12)
            h.contactCardContainer.setVisibility(View.VISIBLE);
            String[] p = (msg.getText() != null ? msg.getText() : "").split("\\|", 2);
            h.cardName.setText(p.length > 0 ? p[0] : "DuoShield User");
            String uid = p.length > 1 ? p[1] : "";
            h.cardUid.setText(uid.isEmpty() ? "" : "ID: " + uid);
            h.cardCopyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) v.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("uid", uid));
                    Toast.makeText(v.getContext(), "UID copied", Toast.LENGTH_SHORT).show();
                }
            });
        } else if (msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty()) {
            h.imageView.setVisibility(View.VISIBLE);
            Glide.with(h.itemView.getContext()).load(msg.getMediaUrl())
                 .placeholder(android.R.drawable.ic_menu_gallery).into(h.imageView);
        } else {
            h.textView.setVisibility(View.VISIBLE);
            h.textView.setText(msg.getText());
        }

        if (h.editedLabel != null)
            h.editedLabel.setVisibility(msg.isEdited() ? View.VISIBLE : View.GONE);

        com.duoshield.app.util.MessageStatusHelper.bind(h.tickIcon, msg, myUid != null ? myUid : "");

        String reaction = msg.getReaction();
        if (reaction != null && !reaction.isEmpty()) {
            h.reactionText.setVisibility(View.VISIBLE);
            h.reactionText.setText(reaction);
        }

        h.bubbleCard.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(msg, v);
            return true;
        });
    }

    @Override public int getItemCount() { return messages.size(); }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView    senderLabel, textView, cardName, cardUid, replyPreviewText,
                    reactionText, editedLabel, timestampView;
        ImageView   imageView, videoThumbnail, videoPlayBtn, tickIcon, pinIndicator;
        LinearLayout bubbleCard;  // LinearLayout in XML, not CardView (#12)
        View        videoContainer, replyPreviewContainer;
        LinearLayout contactCardContainer;
        Button      cardCopyBtn;

        MessageViewHolder(View v) {
            super(v);
            senderLabel           = v.findViewById(R.id.senderLabel);
            pinIndicator          = v.findViewById(R.id.pinIndicator);
            bubbleCard            = v.findViewById(R.id.messageBubble);
            textView              = v.findViewById(R.id.messageText);
            imageView             = v.findViewById(R.id.messageImage);
            videoContainer        = v.findViewById(R.id.videoContainer);
            videoThumbnail        = null;
            videoPlayBtn          = null;
            contactCardContainer  = v.findViewById(R.id.contactCardContainer);
            cardName              = v.findViewById(R.id.cardName);
            cardUid               = v.findViewById(R.id.cardUid);
            cardCopyBtn           = v.findViewById(R.id.cardCopyBtn);
            tickIcon              = v.findViewById(R.id.tickIcon);
            replyPreviewContainer = v.findViewById(R.id.replyPreviewContainer);
            replyPreviewText      = v.findViewById(R.id.tvReplyPreview);
            reactionText          = v.findViewById(R.id.reactionText);
            editedLabel           = v.findViewById(R.id.editedLabel);
            timestampView         = v.findViewById(R.id.messageTimestamp);
        }
    }
}
