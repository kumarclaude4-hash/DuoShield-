package com.duoshield.app.models;

  import androidx.room.Entity;
  import androidx.room.PrimaryKey;
  import androidx.room.ColumnInfo;
  import androidx.annotation.NonNull;

  @Entity(tableName = "messages")
  public class Message {

      @PrimaryKey @NonNull @ColumnInfo(name = "id")
      private String id;
      @ColumnInfo(name = "conversationId")  private String conversationId;
      @ColumnInfo(name = "sender")          private String sender;
      @ColumnInfo(name = "text")            private String text;
      @ColumnInfo(name = "timestamp")       private long   timestamp;
      @ColumnInfo(name = "isEncrypted")     private boolean isEncrypted;
      @ColumnInfo(name = "mediaUrl")        private String mediaUrl;
      @ColumnInfo(name = "mediaType")       private String mediaType;
      @ColumnInfo(name = "delivered")       private boolean delivered;
      @ColumnInfo(name = "seen")            private boolean seen;

      public Message() {}

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted) {
          this.id = id; this.conversationId = conversationId; this.sender = sender;
          this.text = text; this.timestamp = timestamp; this.isEncrypted = isEncrypted;
      }

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted, String mediaUrl) {
          this(id, conversationId, sender, text, timestamp, isEncrypted);
          this.mediaUrl = mediaUrl;
          this.mediaType = "image";
      }

      public Message(@NonNull String id, String conversationId, String sender,
                     String text, long timestamp, boolean isEncrypted,
                     String mediaUrl, String mediaType) {
          this(id, conversationId, sender, text, timestamp, isEncrypted);
          this.mediaUrl = mediaUrl;
          this.mediaType = mediaType;
      }

      @NonNull public String getId()             { return id; }
      public String  getConversationId()         { return conversationId; }
      public String  getSender()                 { return sender; }
      public String  getText()                   { return text; }
      public long    getTimestamp()              { return timestamp; }
      public boolean isEncrypted()               { return isEncrypted; }
      public String  getMediaUrl()               { return mediaUrl; }
      public String  getMediaType()              { return mediaType; }
      public boolean isDelivered()               { return delivered; }
      public boolean isSeen()                    { return seen; }

      public void setId(@NonNull String id)      { this.id = id; }
      public void setConversationId(String c)    { conversationId = c; }
      public void setSender(String s)            { sender = s; }
      public void setText(String t)              { text = t; }
      public void setTimestamp(long t)           { timestamp = t; }
      public void setEncrypted(boolean e)        { isEncrypted = e; }
      public void setMediaUrl(String u)          { mediaUrl = u; }
      public void setMediaType(String t)         { mediaType = t; }
      public void setDelivered(boolean d)        { delivered = d; }
      public void setSeen(boolean s)             { seen = s; }
  }