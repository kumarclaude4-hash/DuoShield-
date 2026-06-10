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
  import java.util.List;

  public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

      public interface OnVoicePlayListener {
          void onVoicePlay(Message message);
      }
      public interface OnMessageLongPressListener {
          void onLongPress(Message message, View anchor);
      }

      private final List<Message>              messages;
      private final String                     myUid;
      private final OnVoicePlayListener        voiceListener;
      private final OnMessageLongPressListener longPressListener;

      public MessageAdapter(List<Message> messages, String myUid,
                            OnVoicePlayListener voiceListener,
                            OnMessageLongPressListener longPressListener) {
          this.messages          = messages;
          this.myUid             = myUid;
          this.voiceListener     = voiceListener;
          this.longPressListener = longPressListener;
      }

      @NonNull @Override
      public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(parent.getContext())
                                 .inflate(R.layout.item_message, parent, false);
          return new MessageViewHolder(v);
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

          h.senderLabel.setText(mine ? "You" : "Partner");

          // Bubble side alignment
          LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.bubbleCard.getLayoutParams();
          if (mine) {
              h.bubbleCard.setCardBackgroundColor(
                  ContextCompat.getColor(h.itemView.getContext(), R.color.bubble_mine));
              lp.gravity = android.view.Gravity.END;
          } else {
              h.bubbleCard.setCardBackgroundColor(
                  ContextCompat.getColor(h.itemView.getContext(), R.color.bubble_theirs));
              lp.gravity = android.view.Gravity.START;
          }
          h.bubbleCard.setLayoutParams(lp);

          if ("video".equals(type)) {
              h.videoContainer.setVisibility(View.VISIBLE);
              Glide.with(h.itemView.getContext())
                   .load(msg.getMediaUrl())
                   .placeholder(R.drawable.ic_play_video)
                   .centerCrop()
                   .into(h.videoThumbnail);
              h.videoContainer.setOnClickListener(v -> {
                  Intent i = new Intent(Intent.ACTION_VIEW);
                  i.setDataAndType(Uri.parse(msg.getMediaUrl()), "video/*");
                  i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  v.getContext().startActivity(Intent.createChooser(i, "Play video"));
              });
          } else if ("contact_card".equals(type)) {
              h.contactCardContainer.setVisibility(View.VISIBLE);
              String text   = msg.getText() != null ? msg.getText() : "";
              String[] parts = text.split("\\|", 2);
              h.cardName.setText(parts.length > 0 ? parts[0] : "DuoShield User");
              String uid = parts.length > 1 ? parts[1] : "";
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
              Glide.with(h.itemView.getContext())
                   .load(msg.getMediaUrl())
                   .placeholder(android.R.drawable.ic_menu_gallery)
                   .into(h.imageView);
          } else {
              h.textView.setVisibility(View.VISIBLE);
              h.textView.setText(msg.getText());
          }

          if (mine) {
              h.tickIcon.setVisibility(View.VISIBLE);
              if (msg.isSeen())           h.tickIcon.setImageResource(R.drawable.ic_tick_double_blue);
              else if (msg.isDelivered()) h.tickIcon.setImageResource(R.drawable.ic_tick_double);
              else                        h.tickIcon.setImageResource(R.drawable.ic_tick_single);
          } else {
              h.tickIcon.setVisibility(View.GONE);
          }

          h.bubbleCard.setOnLongClickListener(v -> {
              if (longPressListener != null) longPressListener.onLongPress(msg, v);
              return true;
          });
      }

      @Override public int getItemCount() { return messages.size(); }

      static class MessageViewHolder extends RecyclerView.ViewHolder {
          TextView  senderLabel, textView, cardName, cardUid;
          ImageView imageView, videoThumbnail, videoPlayBtn, tickIcon;
          CardView  bubbleCard;
          View      videoContainer, contactCardContainer;
          Button    cardCopyBtn;

          MessageViewHolder(View v) {
              super(v);
              senderLabel          = v.findViewById(R.id.senderLabel);
              bubbleCard           = v.findViewById(R.id.messageBubble);
              textView             = v.findViewById(R.id.messageText);
              imageView            = v.findViewById(R.id.messageImage);
              videoContainer       = v.findViewById(R.id.videoContainer);
              videoThumbnail       = v.findViewById(R.id.videoThumbnail);
              videoPlayBtn         = v.findViewById(R.id.videoPlayBtn);
              contactCardContainer = v.findViewById(R.id.contactCardContainer);
              cardName             = v.findViewById(R.id.cardName);
              cardUid              = v.findViewById(R.id.cardUid);
              cardCopyBtn          = v.findViewById(R.id.cardCopyBtn);
              tickIcon             = v.findViewById(R.id.tickIcon);
          }
      }
  }