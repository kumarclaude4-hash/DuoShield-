package com.duoshield.app.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageViewModel extends AndroidViewModel {

    private final MutableLiveData<List<Message>> messages    = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String>         errorMsg    = new MutableLiveData<>();
    private final ExecutorService                 executor    = Executors.newSingleThreadExecutor();
    private ListenerRegistration                  listener;

    public MessageViewModel(@NonNull Application application) { super(application); }

    public LiveData<List<Message>> getMessages()  { return messages; }
    public LiveData<String>        getErrorMsg()   { return errorMsg; }

    public void startListening(String conversationId) {
        listener = FirebaseFirestore.getInstance()
            .collection("conversations").document(conversationId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener((snap, e) -> {
                if (e != null) { errorMsg.postValue(e.getMessage()); return; }
                if (snap == null) return;
                List<Message> current = messages.getValue();
                if (current == null) current = new ArrayList<>();
                for (DocumentChange dc : snap.getDocumentChanges()) {
                    com.google.firebase.firestore.DocumentSnapshot doc = dc.getDocument();
                    Message m = docToMessage(doc, conversationId);
                    switch (dc.getType()) {
                        case ADDED:   current.add(m); break;
                        case MODIFIED:
                            for (int i = 0; i < current.size(); i++) {
                                if (current.get(i).getId().equals(m.getId())) {
                                    current.set(i, m); break;
                                }
                            }
                            break;
                        case REMOVED:
                            current.removeIf(msg -> msg.getId().equals(m.getId()));
                            break;
                    }
                }
                messages.postValue(new ArrayList<>(current));
            });
    }

    private Message docToMessage(com.google.firebase.firestore.DocumentSnapshot doc, String convId) {
        Message m = new Message();
        m.setId(doc.getId());
        m.setConversationId(convId);
        m.setSender(doc.getString("sender"));
        m.setText(doc.getString("text"));
        Object ts = doc.get("timestamp");
        m.setTimestamp(ts instanceof com.google.firebase.Timestamp
            ? ((com.google.firebase.Timestamp) ts).toDate().getTime()
            : ts instanceof Long ? (Long) ts : 0L);
        m.setMediaUrl(doc.getString("mediaUrl"));
        m.setMediaType(doc.getString("type"));
        m.setReaction(doc.getString("reaction"));
        m.setReplyToId(doc.getString("replyToId"));
        m.setReplyPreview(doc.getString("replyToText"));
        Object da = doc.get("destructAt");
        m.setExpiresAt(da instanceof Long ? (Long) da : 0L);
        Boolean edited = doc.getBoolean("edited");
        m.setEdited(Boolean.TRUE.equals(edited));
        m.setStatus(doc.getString("status"));
        return m;
    }

    public void stopListening() {
        if (listener != null) { listener.remove(); listener = null; }
    }

    @Override protected void onCleared() {
        super.onCleared();
        stopListening();
        executor.shutdown();
    }
}
