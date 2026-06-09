package com.duoshield.app.db;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;
import com.duoshield.app.models.Message;

@Database(entities = {Message.class}, version = 3)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract MessageDao messageDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(
                    context.getApplicationContext(),
                    AppDatabase.class,
                    "duoshield_db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build();
        }
        return instance;
    }

    // v1 → v2: rebuild table with proper schema
    static final androidx.room.migration.Migration MIGRATION_1_2 =
            new androidx.room.migration.Migration(1, 2) {
                @Override
                public void migrate(androidx.sqlite.db.SupportSQLiteDatabase database) {
                    database.execSQL("DROP TABLE IF EXISTS Message");
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS messages (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "conversationId TEXT, " +
                        "sender TEXT, " +
                        "text TEXT, " +
                        "timestamp INTEGER NOT NULL DEFAULT 0, " +
                        "isEncrypted INTEGER NOT NULL DEFAULT 0)"
                    );
                }
            };

    // v2 → v3: add mediaUrl column for image/media messages
    static final androidx.room.migration.Migration MIGRATION_2_3 =
            new androidx.room.migration.Migration(2, 3) {
                @Override
                public void migrate(androidx.sqlite.db.SupportSQLiteDatabase database) {
                    database.execSQL(
                        "ALTER TABLE messages ADD COLUMN mediaUrl TEXT"
                    );
                }
            };
}
