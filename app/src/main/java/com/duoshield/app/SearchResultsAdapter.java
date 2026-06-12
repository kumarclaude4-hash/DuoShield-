package com.duoshield.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.TimeFormatter;
import java.util.List;

public class SearchResultsAdapter extends RecyclerView.Adapter<SearchResultsAdapter.VH> {

    private List<Message> messages;

    public SearchResultsAdapter(List<Message> messages) { this.messages = messages; }

    public void setMessages(List<Message> msgs) {
        this.messages = msgs;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_search_result, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m = messages.get(pos);
        h.tvText.setText(m.getText() != null ? m.getText() : "[media]");
        h.tvTime.setText(TimeFormatter.format(m.getTimestamp()));
    }

    @Override public int getItemCount() { return messages.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvText, tvTime;
        VH(View v) {
            super(v);
            tvText = v.findViewById(R.id.tvResultText);
            tvTime = v.findViewById(R.id.tvResultTime);
        }
    }
}
