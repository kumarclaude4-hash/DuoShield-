package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;
import com.duoshield.app.models.Message;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Message message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Message> messages);

    @Update
    void updateMessage(Message message);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    List<Message> getMessages(String conversationId);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    List<Message> getMessagesPage(String conversationId, int limit, int offset);

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    Message getMessageById(String messageId);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND (status = 'pending' OR status IS NULL) AND sender = :myUid")
    List<Message> getUndeliveredMessages(String conversationId, String myUid);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    List<Message> searchMessages(String conversationId, String query);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    List<Message> getLatestMessages(String conversationId, int limit);

    @Query("UPDATE messages SET seen = 1, status = 'read' WHERE conversationId = :conversationId AND sender != :myUid")
    void markAllRead(String conversationId, String myUid);

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    void updateStatus(String messageId, String status);

    @Query("DELETE FROM messages WHERE id = :messageId")
    void deleteMessage(String messageId);

    @Query("DELETE FROM messages WHERE expiresAt > 0 AND expiresAt < :now")
    void deleteExpired(long now);

    @Query("DELETE FROM messages WHERE timestamp < :cutoff")
    void deleteOlderThan(long cutoff);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteAll(String conversationId);
}
