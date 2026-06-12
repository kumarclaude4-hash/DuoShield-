package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.util.List;
import java.util.concurrent.Executors;

public class MessagePaginator {

    private static final int PAGE_SIZE = 30;
    private int offset = 0;
    private boolean loading = false;
    private boolean exhausted = false;

    public interface PageCallback { void onPage(List<Message> page); }

    public void loadMore(Context ctx, String convId, PageCallback cb) {
        if (loading || exhausted) return;
        loading = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Message> page = AppDatabase.getInstance(ctx)
                .messageDao().getMessagesPage(convId, PAGE_SIZE, offset);
            loading = false;
            if (page.size() < PAGE_SIZE) exhausted = true;
            offset += page.size();
            cb.onPage(page);
        });
    }

    public void reset() { offset = 0; loading = false; exhausted = false; }
}
