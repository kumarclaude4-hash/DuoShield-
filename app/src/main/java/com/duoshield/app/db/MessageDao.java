package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
import com.duoshield.app.models.Message;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Message message);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    List<Message> getMessages(String conversationId);

    @Query("DELETE FROM messages WHERE timestamp < :expiry")
    void deleteExpired(long expiry);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteAll(String conversationId);
}
