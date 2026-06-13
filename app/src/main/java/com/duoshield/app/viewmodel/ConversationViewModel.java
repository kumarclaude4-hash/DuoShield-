package com.duoshield.app.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.duoshield.app.models.Conversation;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;

public class ConversationViewModel extends AndroidViewModel {

    private final MutableLiveData<List<Conversation>> conversations = new MutableLiveData<>();
    private final MutableLiveData<String>             errorMessage  = new MutableLiveData<>();
    private ListenerRegistration listener;

    public ConversationViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<Conversation>> getConversations() { return conversations; }
    public LiveData<String>            getErrorMessage()   { return errorMessage; }

    public void startListening(String conversationId, String myUid, String partnerUid) {
        if (conversationId == null) {
            conversations.postValue(new ArrayList<>());
            return;
        }
        listener = FirebaseFirestore.getInstance()
            .collection("chats").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (e != null) { errorMessage.postValue(e.getMessage()); return; }
                if (snap == null || !snap.exists()) return;

                Conversation conv = new Conversation();
                conv.id = conversationId;
                conv.partnerUid = partnerUid;
                Object pn = snap.get("partnerName_" + myUid);
                conv.partnerName  = pn != null ? pn.toString() : "Partner";
                Object last = snap.get("lastMessage");
                conv.lastMessage  = last != null ? last.toString() : "";
                Object ts = snap.get("lastMessageTs");
                conv.lastMessageTs = ts instanceof com.google.firebase.Timestamp
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime() : 0;
                Object unread = snap.get("unread_" + myUid);
                conv.unreadCount  = unread instanceof Long ? ((Long) unread).intValue() : 0;
                Object typing = snap.get("typing_" + partnerUid);
                conv.isTyping     = Boolean.TRUE.equals(typing);
                Object online = snap.get("online_" + partnerUid);
                conv.isOnline     = Boolean.TRUE.equals(online);

                List<Conversation> list = new ArrayList<>();
                list.add(conv);
                conversations.postValue(list);
            });
    }

    public void stopListening() {
        if (listener != null) { listener.remove(); listener = null; }
    }

    @Override protected void onCleared() {
        super.onCleared();
        stopListening();
    }
}
