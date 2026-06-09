package com.duoshield.app.models;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.annotation.NonNull;

@Entity(tableName = "messages")
public class Message {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    private String id;

    @ColumnInfo(name = "conversationId")
    private String conversationId;

    @ColumnInfo(name = "sender")
    private String sender;

    @ColumnInfo(name = "text")
    private String text;

    @ColumnInfo(name = "timestamp")
    private long timestamp;

    @ColumnInfo(name = "isEncrypted")
    private boolean isEncrypted;

    /** Nullable — only set for image/media messages. Stored as Firebase Storage download URL. */
    @ColumnInfo(name = "mediaUrl")
    private String mediaUrl;

    // Required empty constructor for Firestore deserialization
    public Message() {}

    /** Text message constructor (mediaUrl defaults to null). */
    public Message(@NonNull String id, String conversationId, String sender,
                   String text, long timestamp, boolean isEncrypted) {
        this.id             = id;
        this.conversationId = conversationId;
        this.sender         = sender;
        this.text           = text;
        this.timestamp      = timestamp;
        this.isEncrypted    = isEncrypted;
        this.mediaUrl       = null;
    }

    /** Full constructor including mediaUrl. */
    public Message(@NonNull String id, String conversationId, String sender,
                   String text, long timestamp, boolean isEncrypted, String mediaUrl) {
        this.id             = id;
        this.conversationId = conversationId;
        this.sender         = sender;
        this.text           = text;
        this.timestamp      = timestamp;
        this.isEncrypted    = isEncrypted;
        this.mediaUrl       = mediaUrl;
    }

    // Getters
    @NonNull
    public String getId()             { return id; }
    public String getConversationId() { return conversationId; }
    public String getSender()         { return sender; }
    public String getText()           { return text; }
    public long   getTimestamp()      { return timestamp; }
    public boolean isEncrypted()      { return isEncrypted; }
    public String getMediaUrl()       { return mediaUrl; }

    // Setters
    public void setId(@NonNull String id)               { this.id = id; }
    public void setConversationId(String conversationId){ this.conversationId = conversationId; }
    public void setSender(String sender)                { this.sender = sender; }
    public void setText(String text)                    { this.text = text; }
    public void setTimestamp(long timestamp)            { this.timestamp = timestamp; }
    public void setEncrypted(boolean encrypted)         { isEncrypted = encrypted; }
    public void setMediaUrl(String mediaUrl)            { this.mediaUrl = mediaUrl; }
}
