package com.duoshield.app.models;

public class Conversation {
    public String id;
    public String partnerName;
    public String partnerUid;
    public String lastMessage;
    public long   lastMessageTs;
    public int    unreadCount;
    public boolean isTyping;
    public boolean isOnline;
    public long   lastSeen;
    public String avatarUrl;
    public boolean isMuted;

    public Conversation() {}

    public Conversation(String id, String partnerName, String partnerUid) {
        this.id = id;
        this.partnerName = partnerName;
        this.partnerUid  = partnerUid;
    }
}
