package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.SearchHelper;
import java.util.ArrayList;
import java.util.List;

public class MessageSearchActivity extends BaseActivity {

    private EditText             svSearch;
    private RecyclerView         recyclerView;
    private View                 tvEmpty;
    private SearchResultsAdapter adapter;
    private String               conversationId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_search);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Search Messages");
        }
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = prefs.getString("conversation_id", null);

        svSearch     = findViewById(R.id.sv_search);
        recyclerView = findViewById(R.id.rv_results);
        tvEmpty      = findViewById(R.id.tv_empty);

        adapter = new SearchResultsAdapter(new ArrayList<>());
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }

        if (svSearch != null) {
            svSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString().trim();
                    if (q.length() >= 2) runSearch(q);
                    else {
                        adapter.setMessages(new ArrayList<>());
                        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void runSearch(String query) {
        if (conversationId == null) return;
        SearchHelper.runSearch(this, conversationId, query, results -> runOnUiThread(() -> {
            adapter.setMessages(results);
            if (tvEmpty != null) tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        }));
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }
}
