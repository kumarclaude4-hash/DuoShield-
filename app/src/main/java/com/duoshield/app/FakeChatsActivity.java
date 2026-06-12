package com.duoshield.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.util.AppLockManager;
import java.util.Arrays;
import java.util.List;

/**
 * Decoy screen shown when a wrong PIN is entered.
 * Displays plausible-looking but completely fake conversations.
 * No real data is ever loaded or shown here.
 */
public class FakeChatsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fake_chats);

        Toolbar toolbar = findViewById(R.id.fakeChatsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("Messages");

        AppLockManager.onAppForegrounded(this);

        RecyclerView rv = findViewById(R.id.rvFakeChats);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new DecoyAdapter(buildFakeData()));
    }

    @Override public void onBackPressed() { super.onBackPressed(); finish(); }

    // ── Fake data ─────────────────────────────────────────────────────────────

    private static class FakeConv {
        final String name, preview, time, initials;
        FakeConv(String name, String preview, String time, String initials) {
            this.name = name; this.preview = preview;
            this.time = time; this.initials = initials;
        }
    }

    private List<FakeConv> buildFakeData() {
        return Arrays.asList(
            new FakeConv("Mom",          "Did you eat lunch? 😊",               "2:14 PM",  "M"),
            new FakeConv("Alex",         "Are you coming tonight?",             "11:42 AM", "A"),
            new FakeConv("Work Group",   "Meeting moved to 3 PM.",              "10:05 AM", "W"),
            new FakeConv("Sarah",        "Haha okay see you there 😄",          "Yesterday","S"),
            new FakeConv("James",        "Thanks for the help yesterday!",      "Yesterday","J"),
            new FakeConv("Netflix",      "Your bill is ready to view.",         "Mon",      "N"),
            new FakeConv("Priya",        "Let me know when you're free 🙂",     "Sun",      "P"),
            new FakeConv("Gym Buddy",    "Leg day tomorrow, don't skip!",       "Sat",      "G"),
            new FakeConv("Dad",          "Call me when you get a chance.",      "Fri",      "D"),
            new FakeConv("Emma",         "That sounds like a great idea! 👍",   "Thu",      "E")
        );
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class DecoyAdapter extends RecyclerView.Adapter<DecoyAdapter.VH> {

        private final List<FakeConv> items;
        DecoyAdapter(List<FakeConv> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_decoy_conversation, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            FakeConv c = items.get(position);
            h.tvInitials.setText(c.initials);
            h.tvName.setText(c.name);
            h.tvPreview.setText(c.preview);
            h.tvTime.setText(c.time);
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvInitials, tvName, tvPreview, tvTime;
            VH(View v) {
                super(v);
                tvInitials = v.findViewById(R.id.tvDecoyInitials);
                tvName     = v.findViewById(R.id.tvDecoyName);
                tvPreview  = v.findViewById(R.id.tvDecoyPreview);
                tvTime     = v.findViewById(R.id.tvDecoyTime);
            }
        }
    }
}
