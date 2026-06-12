package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.SearchHelper;
import java.util.ArrayList;
import java.util.List;

public class MessageSearchActivity extends BaseActivity {

    private EditText         etQuery;
    private RecyclerView     recyclerView;
    private TextView         tvEmpty;
    private SearchResultsAdapter adapter;
    private String           conversationId;

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
        toolbar.setNavigationOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = prefs.getString("conversation_id", null);

        etQuery     = findViewById(R.id.etSearchQuery);
        recyclerView = findViewById(R.id.searchRecycler);
        tvEmpty     = findViewById(R.id.tvEmpty);

        adapter = new SearchResultsAdapter(new ArrayList<>());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        etQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim();
                if (q.length() >= 2) runSearch(q);
                else {
                    adapter.setMessages(new ArrayList<>());
                    tvEmpty.setVisibility(View.GONE);
                }
            }
        });
    }

    private void runSearch(String query) {
        if (conversationId == null) return;
        SearchHelper.runSearch(this, conversationId, query, results -> {
            runOnUiThread(() -> {
                adapter.setMessages(results);
                tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }
}
