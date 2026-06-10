package com.duoshield.app.models;

  import androidx.room.Entity;
  import androidx.room.PrimaryKey;
  import androidx.room.ColumnInfo;
  import androidx.annotation.NonNull;

  @Entity(tableName = "messages")
  public class Message {

      @PrimaryKey @NonNull @ColumnInfo(name = "id")      private String id;
      @ColumnInfo(name = "conversationId")               private String conversationId;
      @ColumnInfo(name = "sender")                       private String sender;
      @ColumnInfo(name = "text")                         private String text;
      @ColumnInfo(name = "timestamp")                    private long   timestamp;
      @ColumnInfo(name = "isEncrypted")                  private boolean isEncrypted;
      @ColumnInfo(name = "mediaUrl")                     private String mediaUrl;
      @ColumnInfo(name = "mediaType")                    private String mediaType;
      @ColumnInfo(name = "delivered")                    private boolean delivered;
      @ColumnInfo(name = "seen")                         private boolean seen;
      // F3: reply
      @ColumnInfo(name = "replyToId")                    private String replyToId;
      @ColumnInfo(name = "replyPreview")                 private String replyPreview;
      // F4: disappearing
      @ColumnInfo(name = "expiresAt")                    private long   expiresAt;
      // F5: reactions
      @ColumnInfo(name = "reaction")                     private String reaction;

      public Message() {}

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted) {
          this.id = id; this.conversationId = conversationId; this.sender = sender;
          this.text = text; this.timestamp = timestamp; this.isEncrypted = isEncrypted;
      }

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted, String mediaUrl) {
          this(id, conversationId, sender, text, timestamp, isEncrypted);
          this.mediaUrl = mediaUrl; this.mediaType = "image";
      }

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted,
                     String mediaUrl, String mediaType) {
          this(id, conversationId, sender, text, timestamp, isEncrypted);
          this.mediaUrl = mediaUrl; this.mediaType = mediaType;
      }

      @NonNull public String getId()          { return id; }
      public String  getConversationId()      { return conversationId; }
      public String  getSender()              { return sender; }
      public String  getText()                { return text; }
      public long    getTimestamp()           { return timestamp; }
      public boolean isEncrypted()            { return isEncrypted; }
      public String  getMediaUrl()            { return mediaUrl; }
      public String  getMediaType()           { return mediaType; }
      public boolean isDelivered()            { return delivered; }
      public boolean isSeen()                 { return seen; }
      public String  getReplyToId()           { return replyToId; }
      public String  getReplyPreview()        { return replyPreview; }
      public long    getExpiresAt()           { return expiresAt; }
      public String  getReaction()            { return reaction; }

      public void setId(@NonNull String v)    { id = v; }
      public void setConversationId(String v) { conversationId = v; }
      public void setSender(String v)         { sender = v; }
      public void setText(String v)           { text = v; }
      public void setTimestamp(long v)        { timestamp = v; }
      public void setEncrypted(boolean v)     { isEncrypted = v; }
      public void setMediaUrl(String v)       { mediaUrl = v; }
      public void setMediaType(String v)      { mediaType = v; }
      public void setDelivered(boolean v)     { delivered = v; }
      public void setSeen(boolean v)          { seen = v; }
      public void setReplyToId(String v)      { replyToId = v; }
      public void setReplyPreview(String v)   { replyPreview = v; }
      public void setExpiresAt(long v)        { expiresAt = v; }
      public void setReaction(String v)       { reaction = v; }
  }