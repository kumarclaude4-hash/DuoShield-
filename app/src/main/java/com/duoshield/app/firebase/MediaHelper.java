package com.duoshield.app.firebase;

import android.net.Uri;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class MediaHelper {
    private final StorageReference storageRef = FirebaseStorage.getInstance().getReference();

    public void uploadMedia(Uri fileUri, String path) {
        StorageReference fileRef = storageRef.child(path);
        fileRef.putFile(fileUri);
    }

    public StorageReference getMedia(String path) {
        return storageRef.child(path);
    }
}