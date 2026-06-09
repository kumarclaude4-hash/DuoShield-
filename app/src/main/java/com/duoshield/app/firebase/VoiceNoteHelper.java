package com.duoshield.app.firebase;

import android.net.Uri;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class VoiceNoteHelper {
    private final StorageReference storageRef = FirebaseStorage.getInstance().getReference();

    public void uploadVoiceNote(Uri fileUri, String path) {
        StorageReference fileRef = storageRef.child(path);
        fileRef.putFile(fileUri);
    }

    public StorageReference getVoiceNote(String path) {
        return storageRef.child(path);
    }
}