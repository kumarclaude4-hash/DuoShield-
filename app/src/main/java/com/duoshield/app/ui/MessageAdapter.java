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
  import androidx.cardview.widget.CardView;
  import androidx.core.content.ContextCompat;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.duoshield.app.R;
  import com.duoshield.app.models.Message;
  import java.util.HashSet;
  import java.util.List;
  import java.util.Set;

  public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

      public interface OnVoicePlayListener       { void onVoicePlay(Message m); }
      public interface OnMessageLongPressListener { void onLongPress(Message m, View anchor); }

      private final List<Message>               messages;
      private final String                      myUid;
      private final OnVoicePlayListener         voiceListener;
      private final OnMessageLongPressListener  longPressListener;
      private final Set<String>                 pinnedIds = new HashSet<>();

      public MessageAdapter(List<Message> messages, String myUid,
                            OnVoicePlayListener vl, OnMessageLongPressListener ll) {
          this.messages = messages; this.myUid = myUid;
          this.voiceListener = vl; this.longPressListener = ll;
      }

      /** Called from ChatMediaActivity whenever the pinned set changes. */
      public void updatePinnedIds(Set<String> ids) {
          pinnedIds.clear();
          if (ids != null) pinnedIds.addAll(ids);
          notifyDataSetChanged();
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

          h.senderLabel.setText(mine ? "You" : "Partner");

          // Pin indicator
          h.pinIndicator.setVisibility(pinnedIds.contains(msg.getId()) ? View.VISIBLE : View.GONE);

          // Bubble side
          LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubbleCard.getLayoutParams();
          lp.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
          h.bubbleCard.setCardBackgroundColor(ContextCompat.getColor(h.itemView.getContext(),
              mine ? R.color.bubble_mine : R.color.bubble_theirs));
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
              Glide.with(h.itemView.getContext()).load(msg.getMediaUrl())
                   .placeholder(R.drawable.ic_play_video).centerCrop().into(h.videoThumbnail);
              h.videoContainer.setOnClickListener(v -> {
                  Intent i = new Intent(Intent.ACTION_VIEW);
                  i.setDataAndType(Uri.parse(msg.getMediaUrl()), "video/*");
                  i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  v.getContext().startActivity(Intent.createChooser(i, "Play video"));
              });
          } else if ("contact_card".equals(type)) {
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

          // Ticks
          if (mine) {
              h.tickIcon.setVisibility(View.VISIBLE);
              if      (msg.isSeen())      h.tickIcon.setImageResource(R.drawable.ic_tick_double_blue);
              else if (msg.isDelivered()) h.tickIcon.setImageResource(R.drawable.ic_tick_double);
              else                        h.tickIcon.setImageResource(R.drawable.ic_tick_single);
          } else {
              h.tickIcon.setVisibility(View.GONE);
          }

          // Reaction
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
          TextView  senderLabel, textView, cardName, cardUid, replyPreviewText, reactionText, pinIndicator;
          ImageView imageView, videoThumbnail, videoPlayBtn, tickIcon;
          CardView  bubbleCard;
          View      videoContainer, contactCardContainer, replyPreviewContainer;
          Button    cardCopyBtn;

          MessageViewHolder(View v) {
              super(v);
              senderLabel           = v.findViewById(R.id.senderLabel);
              pinIndicator          = v.findViewById(R.id.pinIndicator);
              bubbleCard            = v.findViewById(R.id.messageBubble);
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
          }
      }
  }