package com.duoshield.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private final List<Message> messageList;

    public MessageAdapter(List<Message> messageList) {
        this.messageList = messageList;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message msg = messageList.get(position);
        boolean isMedia = msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty();

        if (isMedia) {
            // Image message — show ImageView, hide TextView
            holder.textView.setVisibility(View.GONE);
            holder.imageView.setVisibility(View.VISIBLE);
            Glide.with(holder.imageView.getContext())
                 .load(msg.getMediaUrl())
                 .placeholder(android.R.drawable.ic_menu_gallery)
                 .error(android.R.drawable.ic_menu_report_image)
                 .into(holder.imageView);
        } else {
            // Text message — show TextView, hide ImageView
            holder.imageView.setVisibility(View.GONE);
            holder.textView.setVisibility(View.VISIBLE);
            holder.textView.setText(msg.getText());
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView  textView;
        ImageView imageView;

        MessageViewHolder(View itemView) {
            super(itemView);
            textView  = itemView.findViewById(R.id.messageText);
            imageView = itemView.findViewById(R.id.messageImage);
        }
    }
}
