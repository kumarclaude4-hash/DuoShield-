package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
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
import com.duoshield.app.util.DateHeaderHelper;
import com.duoshield.app.util.LinkPreviewFetcher;
import com.duoshield.app.util.LinkPreviewHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.util.SupabaseStorageHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnVoicePlayListener {
        void onVoicePlay(Message m, ImageView playPauseBtn, WaveformView waveform, TextView durationView);
    }
    public interface OnMessageLongPressListener {
        void onLongPress(Message m, View anchor);
    }

    private static final int TYPE_DATE = 0;
    private static final int TYPE_MSG  = 1;

    private List<Message>                    messages       = new ArrayList<>();
    private List<Object>                     displayItems   = new ArrayList<>(); // String | Message
    private final String                     myUid;
    private final OnVoicePlayListener        voiceListener;
    private final OnMessageLongPressListener longPressListener;
    private final Set<String>                pinnedIds      = new HashSet<>();
    private String                           playingMsgId   = null;

    public MessageAdapter(List<Message> messages, String myUid,
                          OnVoicePlayListener vl, OnMessageLongPressListener ll) {
        this.messages          = messages != null ? messages : new ArrayList<>();
        this.myUid             = myUid;
        this.voiceListener     = vl;
        this.longPressListener = ll;
        rebuildDisplay();
    }

    /** Replace entire list. */
    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        messages = newList;
        rebuildDisplay();
        notifyDataSetChanged();
    }

    /** Append a single message. */
    public void appendMessage(Message m) {
        messages.add(m);
        rebuildDisplay();
        notifyDataSetChanged();
    }

    /** Update a single message in-place (reaction, status, etc.) */
    public void updateMessage(String msgId, java.util.function.Consumer<Message> mutator) {
        for (int i = 0; i < messages.size(); i++) {
            if (msgId.equals(messages.get(i).getId())) {
                mutator.accept(messages.get(i));
                rebuildDisplay();
                notifyDataSetChanged();
                return;
            }
        }
    }

    /** Remove a message by id. */
    public void removeMessage(String msgId) {
        messages.removeIf(m -> msgId.equals(m.getId()));
        rebuildDisplay();
        notifyDataSetChanged();
    }

    public void updatePinnedIds(Set<String> ids) {
        pinnedIds.clear();
        if (ids != null) pinnedIds.addAll(ids);
        notifyDataSetChanged();
    }

    public void setPlayingMessageId(String msgId) {
        playingMsgId = msgId;
        notifyDataSetChanged();
    }

    public List<Message> getMessages() { return messages; }

    private void rebuildDisplay() {
        displayItems.clear();
        String lastDate = null;
        for (Message m : messages) {
            String label = DateHeaderHelper.getLabel(m.getTimestamp());
            if (!label.equals(lastDate)) {
                displayItems.add(label);
                lastDate = label;
            }
            displayItems.add(m);
        }
    }

    // ── Item counts & types ──────────────────────────────────────────

    @Override public int getItemCount() { return displayItems.size(); }

    @Override public int getItemViewType(int position) {
        return (displayItems.get(position) instanceof String) ? TYPE_DATE : TYPE_MSG;
    }

    // ── ViewHolder creation ──────────────────────────────────────────

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateViewHolder(inf.inflate(R.layout.item_date_header, parent, false));
        }
        return new MsgViewHolder(inf.inflate(R.layout.item_message, parent, false));
    }

    // ── Binding ──────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_DATE) {
            ((DateViewHolder) holder).label.setText((String) displayItems.get(position));
            return;
        }
        bindMessage((MsgViewHolder) holder, (Message) displayItems.get(position));
    }

    private void bindMessage(MsgViewHolder h, Message msg) {
        boolean mine = myUid != null && myUid.equals(msg.getSender());
        String  type = msg.getMediaType();
        Context ctx  = h.itemView.getContext();

        // Reset all content views
        h.textView.setVisibility(View.GONE);
        h.imageView.setVisibility(View.GONE);
        h.videoContainer.setVisibility(View.GONE);
        h.contactCardContainer.setVisibility(View.GONE);
        h.voiceNoteContainer.setVisibility(View.GONE);
        h.replyPreviewContainer.setVisibility(View.GONE);
        h.reactionText.setVisibility(View.GONE);
        h.senderLabel.setVisibility(View.GONE);
        h.pinIndicatorRow.setVisibility(View.GONE);
        h.linkPreviewCard.setVisibility(View.GONE);

        // ── Bubble alignment ────────────────────────────────────────
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
        lp.gravity = mine ? Gravity.END : Gravity.START;
        h.bubble.setLayoutParams(lp);

        // ── Bubble background ───────────────────────────────────────
        h.bubble.setBackground(ContextCompat.getDrawable(ctx,
            mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs));

        // ── Partner sender label ────────────────────────────────────
        if (!mine) {
            h.senderLabel.setVisibility(View.VISIBLE);
            h.senderLabel.setText("Partner");
        }

        // ── Pin indicator ───────────────────────────────────────────
        if (pinnedIds.contains(msg.getId())) {
            h.pinIndicatorRow.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams pinLp =
                (LinearLayout.LayoutParams) h.pinIndicatorRow.getLayoutParams();
            pinLp.gravity = mine ? Gravity.END : Gravity.START;
            h.pinIndicatorRow.setLayoutParams(pinLp);
        }

        // ── Reply preview ───────────────────────────────────────────
        String rp = msg.getReplyPreview();
        if (rp != null && !rp.isEmpty()) {
            h.replyPreviewContainer.setVisibility(View.VISIBLE);
            h.replyPreviewText.setText("↩ " + rp);
        }

        // ── Content ─────────────────────────────────────────────────
        if ("video".equals(type)) {
            h.videoContainer.setVisibility(View.VISIBLE);
            String vidRef = msg.getMediaUrl();
            if (SupabaseStorageHelper.isSupabasePath(vidRef)) {
                h.videoThumbnail.setTag(vidRef);
                Glide.with(ctx).load(R.drawable.ic_play_video).into(h.videoThumbnail);
                javax.crypto.SecretKey vidKey = CryptoInitializer.getSharedKey(ctx);
                // Thumbnail: decrypt bytes → Glide (Glide accepts byte[])
                SupabaseStorageHelper.loadMedia(vidRef, vidKey, new SupabaseStorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] plainBytes) {
                        if (vidRef.equals(h.videoThumbnail.getTag())) {
                            Glide.with(ctx).load(plainBytes)
                                 .placeholder(R.drawable.ic_play_video).centerCrop()
                                 .into(h.videoThumbnail);
                        }
                    }
                    @Override public void onError(Exception e) { /* keep play-icon placeholder */ }
                });
                // Playback: decrypt → write temp file → FileProvider → Intent
                h.videoContainer.setOnClickListener(v ->
                    SupabaseStorageHelper.loadMedia(vidRef, vidKey, new SupabaseStorageHelper.MediaCallback() {
                        @Override public void onLoaded(byte[] plainBytes) {
                            try {
                                java.io.File tmp = java.io.File.createTempFile(
                                        "vid_", ".mp4", ctx.getCacheDir());
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                                    fos.write(plainBytes);
                                }
                                tmp.deleteOnExit();
                                android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                                        ctx, ctx.getPackageName() + ".provider", tmp);
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setDataAndType(fileUri, "video/mp4");
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                ctx.startActivity(Intent.createChooser(i, "Play video"));
                            } catch (Exception e) {
                                Toast.makeText(ctx, "Couldn't play video", Toast.LENGTH_SHORT).show();
                            }
                        }
                        @Override public void onError(Exception e) {
                            Toast.makeText(ctx, "Couldn't load video", Toast.LENGTH_SHORT).show();
                        }
                    }));
            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).load(vidRef)
                     .placeholder(R.drawable.ic_play_video).centerCrop().into(h.videoThumbnail);
                h.videoContainer.setOnClickListener(v -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(vidRef), "video/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ctx.startActivity(Intent.createChooser(i, "Play video"));
                });
            }

        } else if ("voice".equals(type)) {
            h.voiceNoteContainer.setVisibility(View.VISIBLE);
            boolean playing = msg.getId() != null && msg.getId().equals(playingMsgId);
            h.voicePlayPauseBtn.setImageResource(
                playing ? android.R.drawable.ic_media_pause : R.drawable.ic_play_video);
            h.voicePlayPauseBtn.setOnClickListener(v -> {
                if (voiceListener != null)
                    voiceListener.onVoicePlay(msg, h.voicePlayPauseBtn,
                        h.voiceWaveform, h.voiceDuration);
            });

        } else if ("contact_card".equals(type)) {
            h.contactCardContainer.setVisibility(View.VISIBLE);
            String[] p = (msg.getText() != null ? msg.getText() : "").split("\\|", 2);
            h.cardName.setText(p.length > 0 ? p[0] : "DuoShield User");
            String uid = p.length > 1 ? p[1] : "";
            h.cardUid.setText(uid.isEmpty() ? "" : "ID: " + uid);
            h.cardCopyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("uid", uid));
                    Toast.makeText(ctx, "UID copied", Toast.LENGTH_SHORT).show();
                }
            });

        } else if (msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty()) {
            h.imageView.setVisibility(View.VISIBLE);
            String imgRef = msg.getMediaUrl();
            if (SupabaseStorageHelper.isSupabasePath(imgRef)) {
                // Tag guards against stale results in recycled ViewHolders
                h.imageView.setTag(imgRef);
                Glide.with(ctx).load(android.R.drawable.ic_menu_gallery).into(h.imageView);
                javax.crypto.SecretKey imgKey = CryptoInitializer.getSharedKey(ctx);
                // Decrypt bytes → Glide.load(byte[]) — no signed URL ever exposed to UI
                SupabaseStorageHelper.loadMedia(imgRef, imgKey, new SupabaseStorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] plainBytes) {
                        if (imgRef.equals(h.imageView.getTag())) {
                            Glide.with(ctx).load(plainBytes)
                                 .placeholder(android.R.drawable.ic_menu_gallery)
                                 .into(h.imageView);
                        }
                    }
                    @Override public void onError(Exception e) { /* keep placeholder */ }
                });
            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).load(imgRef)
                     .placeholder(android.R.drawable.ic_menu_gallery).into(h.imageView);
            }

        } else {
            // Plain text — show text and check for link preview
            h.textView.setVisibility(View.VISIBLE);
            h.textView.setText(msg.getText());
            bindLinkPreview(h, msg, ctx);
        }

        // ── Edited label ────────────────────────────────────────────
        h.editedLabel.setVisibility(msg.isEdited() ? View.VISIBLE : View.GONE);

        // ── Timestamp ───────────────────────────────────────────────
        long ts = msg.getTimestamp();
        if (ts > 0) {
            h.timestampView.setText(new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(ts)));
        }

        // ── Delivery ticks ──────────────────────────────────────────
        com.duoshield.app.util.MessageStatusHelper.bind(h.tickIcon, msg,
            myUid != null ? myUid : "");

        // ── Reaction ────────────────────────────────────────────────
        String reaction = msg.getReaction();
        if (reaction != null && !reaction.isEmpty()) {
            h.reactionText.setVisibility(View.VISIBLE);
            h.reactionText.setText(reaction);
            LinearLayout.LayoutParams rlp =
                (LinearLayout.LayoutParams) h.reactionText.getLayoutParams();
            rlp.gravity = mine ? Gravity.END : Gravity.START;
            h.reactionText.setLayoutParams(rlp);
        }

        h.bubble.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(msg, v);
            return true;
        });
    }

    // ── Link preview ──────────────────────────────────────────────────

    private void bindLinkPreview(MsgViewHolder h, Message msg, Context ctx) {
        String text = msg.getText();
        if (text == null || text.isEmpty()) return;

        String url = LinkPreviewHelper.extractFirstUrl(text);
        if (url == null) return;

        // Tag the card with the message id so stale async callbacks don't corrupt recycled views
        h.linkPreviewCard.setTag(msg.getId());

        LinkPreviewFetcher.fetch(url, preview -> {
            // Verify this view holder still belongs to the same message
            if (!msg.getId().equals(h.linkPreviewCard.getTag())) return;
            if (preview == null) return;

            h.linkPreviewCard.setVisibility(View.VISIBLE);

            // Domain
            h.linkPreviewDomain.setText(preview.domain != null ? preview.domain : "");

            // Title (hide row if empty)
            if (preview.title != null && !preview.title.isEmpty()) {
                h.linkPreviewTitle.setVisibility(View.VISIBLE);
                h.linkPreviewTitle.setText(preview.title);
            } else {
                h.linkPreviewTitle.setVisibility(View.GONE);
            }

            // OG image
            if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                h.linkPreviewImage.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(preview.imageUrl)
                     .centerCrop()
                     .placeholder(android.R.drawable.ic_menu_gallery)
                     .into(h.linkPreviewImage);
            } else {
                h.linkPreviewImage.setVisibility(View.GONE);
            }

            // Tap to open in browser
            h.linkPreviewCard.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(preview.url));
                ctx.startActivity(i);
            });
        });
    }

    // ── ViewHolders ──────────────────────────────────────────────────

    static class DateViewHolder extends RecyclerView.ViewHolder {
        TextView label;
        DateViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.tvDateLabel);
        }
    }

    static class MsgViewHolder extends RecyclerView.ViewHolder {
        TextView     senderLabel, textView, cardName, cardUid,
                     replyPreviewText, reactionText, editedLabel,
                     timestampView, voiceDuration,
                     linkPreviewDomain, linkPreviewTitle;
        ImageView    imageView, videoThumbnail, videoPlayBtn,
                     tickIcon, voicePlayPauseBtn, linkPreviewImage;
        LinearLayout bubble, voiceNoteContainer, pinIndicatorRow, linkPreviewCard;
        FrameLayout  bubbleWrapper;
        View         videoContainer, contactCardContainer, replyPreviewContainer;
        WaveformView voiceWaveform;
        Button       cardCopyBtn;

        MsgViewHolder(View v) {
            super(v);
            senderLabel           = v.findViewById(R.id.senderLabel);
            pinIndicatorRow       = v.findViewById(R.id.pinIndicatorRow);
            bubbleWrapper         = v.findViewById(R.id.bubbleWrapper);
            bubble                = v.findViewById(R.id.messageBubble);
            textView              = v.findViewById(R.id.messageText);
            imageView             = v.findViewById(R.id.messageImage);
            videoContainer        = v.findViewById(R.id.videoContainer);
            videoThumbnail        = v.findViewById(R.id.videoThumbnail);
            videoPlayBtn          = v.findViewById(R.id.videoPlayBtn);
            contactCardContainer  = v.findViewById(R.id.contactCardContainer);
            cardName              = v.findViewById(R.id.cardName);
            cardUid               = v.findViewById(R.id.cardUid);
            cardCopyBtn           = v.findViewById(R.id.cardCopyBtn);
            tickIcon              = v.findViewById(R.id.tickIcon);
            replyPreviewContainer = v.findViewById(R.id.replyPreviewContainer);
            replyPreviewText      = v.findViewById(R.id.replyPreviewText);
            reactionText          = v.findViewById(R.id.reactionText);
            editedLabel           = v.findViewById(R.id.editedLabel);
            timestampView         = v.findViewById(R.id.messageTimestamp);
            voiceNoteContainer    = v.findViewById(R.id.voiceNoteContainer);
            voicePlayPauseBtn     = v.findViewById(R.id.voicePlayPauseBtn);
            voiceWaveform         = v.findViewById(R.id.voiceWaveform);
            voiceDuration         = v.findViewById(R.id.voiceDuration);
            // Link preview
            linkPreviewCard       = v.findViewById(R.id.linkPreviewCard);
            linkPreviewImage      = v.findViewById(R.id.linkPreviewImage);
            linkPreviewDomain     = v.findViewById(R.id.linkPreviewDomain);
            linkPreviewTitle      = v.findViewById(R.id.linkPreviewTitle);
        }
    }

    /** Format milliseconds → "m:ss" */
    public static String formatDuration(int ms) {
        int secs = ms / 1000;
        return String.format(Locale.US, "%d:%02d", secs / 60, secs % 60);
    }
}

