package com.duoshield.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.TimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class SearchResultsAdapter extends RecyclerView.Adapter<SearchResultsAdapter.VH> {

    private List<Message> messages = new ArrayList<>();

    public SearchResultsAdapter(List<Message> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Message> oldList = messages;
        final List<Message> finalNew = newList;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                String oid = oldList.get(o).getId();
                String nid = finalNew.get(n).getId();
                return oid != null && oid.equals(nid);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                String a = oldList.get(o).getText();
                String b = finalNew.get(n).getText();
                return (a == null && b == null) || (a != null && a.equals(b));
            }
        });
        messages = finalNew;
        diff.dispatchUpdatesTo(this);
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
            tvText = v.findViewById(R.id.tv_text);
            tvTime = v.findViewById(R.id.tv_time);
        }
    }
}
