package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Conversation;
import com.duoshield.app.ui.ConversationAdapter;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.EncryptionHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;

public class ConversationListActivity extends BaseActivity {

    private RecyclerView        recyclerView;
    private ConversationAdapter adapter;
    private LinearLayout        emptyState;
    private LinearLayout        searchBar;
    private EditText            etSearch;

    private FirebaseFirestore    db;
    private ListenerRegistration listener;
    private String               myUid;
    private String               conversationId;
    private List<Conversation>   allConversations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_list);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid = prefs.getString("my_uid", null);
        if (myUid == null) {
            com.google.firebase.auth.FirebaseUser fu =
                    FirebaseAuth.getInstance().getCurrentUser();
            if (fu != null) myUid = fu.getUid();
        }
        conversationId = prefs.getString("conversation_id", null);

        recyclerView = findViewById(R.id.recyclerConversations);
        emptyState   = findViewById(R.id.tvEmpty);
        searchBar    = findViewById(R.id.searchBar);
        etSearch     = findViewById(R.id.etSearch);

        adapter = new ConversationAdapter(new ConversationAdapter.OnConversationClickListener() {
            @Override public void onClick(Conversation conv)     { openChat(conv); }
            @Override public void onLongClick(Conversation conv) { /* future: archive/mute */ }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        db = FirebaseFirestore.getInstance();

        // Search toggle
        ImageView btnSearchToggle = findViewById(R.id.btn_search_toggle);
        ImageView btnCloseSearch  = findViewById(R.id.btn_close_search);
        btnSearchToggle.setOnClickListener(v -> {
            searchBar.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
        });
        btnCloseSearch.setOnClickListener(v -> {
            searchBar.setVisibility(View.GONE);
            etSearch.setText("");
            filterConversations("");
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterConversations(s.toString().trim());
            }
        });

        // Overflow menu
        ImageView btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Settings");
            popup.getMenu().add(0, 2, 0, "Key Fingerprint");
            popup.getMenu().add(0, 3, 0, "New Chat");
            popup.getMenu().add(0, 4, 0, "Wipe & Exit");
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) { startActivity(new Intent(this, com.duoshield.app.ui.SettingsActivity.class)); return true; }
                if (id == 2) { startActivity(new Intent(this, KeyFingerprintActivity.class)); return true; }
                if (id == 3) { startActivity(new Intent(this, com.duoshield.app.ui.PairingActivity.class)); return true; }
                if (id == 4) { com.duoshield.app.util.WipeHelper.wipeAll(this); return true; }
                return false;
            });
            popup.show();
        });

        // NOTE: DO NOT call listenForConversation() here.
        // It is attached in onStart() so it is properly detached/re-attached
        // across the activity lifecycle without leaking a second registration.
    }

    private void listenForConversation() {
        if (conversationId == null) { showEmpty(true); return; }

        listener = db.collection("chats").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (snap == null || !snap.exists()) return;
                SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
                String partnerUid = prefs.getString("partner_uid", "");

                Conversation conv = new Conversation();
                conv.id          = conversationId;
                conv.partnerUid  = partnerUid;

                Object pName = snap.get("partnerName_" + myUid);
                conv.partnerName = pName != null ? pName.toString() : "Partner";

                Object last = snap.get("lastMessage");
                if (last != null && !last.toString().isEmpty()) {
                    try {
                        conv.lastMessage = EncryptionHelper.decrypt(
                            ConversationListActivity.this, last.toString());
                    } catch (Exception ignored) {
                        conv.lastMessage = last.toString(); // fallback: show raw if decrypt fails
                    }
                } else {
                    conv.lastMessage = "";
                }

                Object ts = snap.get("lastMessageTs");
                conv.lastMessageTs = ts instanceof com.google.firebase.Timestamp
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime() : 0;

                Object unread = snap.get("unread_" + myUid);
                conv.unreadCount = unread instanceof Long ? ((Long) unread).intValue() : 0;

                Object typing = snap.get("typing_" + partnerUid);
                conv.isTyping = Boolean.TRUE.equals(typing);

                Object online = snap.get("online_" + partnerUid);
                conv.isOnline = Boolean.TRUE.equals(online);

                Object muted = snap.get("muted_" + myUid);
                conv.isMuted = Boolean.TRUE.equals(muted);

                Object photoUrl = snap.get("partnerPhotoUrl_" + myUid);
                conv.avatarUrl = photoUrl != null ? photoUrl.toString() : null;

                allConversations.clear();
                allConversations.add(conv);
                filterConversations(etSearch.getText().toString().trim());
            });
    }

    private void filterConversations(String query) {
        if (query.isEmpty()) {
            adapter.setConversations(new ArrayList<>(allConversations));
        } else {
            List<Conversation> filtered = new ArrayList<>();
            for (Conversation c : allConversations) {
                String name = c.partnerName != null ? c.partnerName.toLowerCase() : "";
                if (name.contains(query.toLowerCase())) filtered.add(c);
            }
            adapter.setConversations(filtered);
        }
        showEmpty(adapter.getItemCount() == 0);
    }

    private void openChat(Conversation conv) {
        startActivity(new Intent(this, ChatMediaActivity.class));
    }

    private void showEmpty(boolean empty) {
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ── Lifecycle: attach listener on start, detach on stop ──────────────────

    @Override protected void onStart() {
        super.onStart();
        AppLockManager.onAppForegrounded(this);
        // Only register if not already attached (guards against duplicate from onCreate)
        if (listener == null) listenForConversation();
    }

    @Override protected void onStop() {
        super.onStop();
        if (listener != null) { listener.remove(); listener = null; }
    }

    @Override protected void onResume() {
        super.onResume();
    }
}
