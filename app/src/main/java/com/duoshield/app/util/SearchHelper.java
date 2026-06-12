package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.util.List;
import java.util.concurrent.Executors;

public class SearchHelper {

    public interface Callback { void onResults(List<Message> results); }

    public static void runSearch(Context ctx, String convId, String query, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Message> results = AppDatabase.getInstance(ctx)
                .messageDao().searchMessages(convId, query);
            cb.onResults(results);
        });
    }

    public static void clearSearch() {}
}
