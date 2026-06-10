package com.duoshield.app.db;

  import androidx.room.Database;
  import androidx.room.Room;
  import androidx.room.RoomDatabase;
  import androidx.room.migration.Migration;
  import androidx.sqlite.db.SupportSQLiteDatabase;
  import android.content.Context;
  import com.duoshield.app.models.Message;

  @Database(entities = {Message.class}, version = 4)
  public abstract class AppDatabase extends RoomDatabase {

      private static volatile AppDatabase instance;

      public abstract MessageDao messageDao();

      public static synchronized AppDatabase getInstance(Context context) {
          if (instance == null) {
              instance = Room.databaseBuilder(
                      context.getApplicationContext(), AppDatabase.class, "duoshield_db")
              .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
              .build();
          }
          return instance;
      }

      static final Migration MIGRATION_1_2 = new Migration(1, 2) {
          @Override public void migrate(SupportSQLiteDatabase db) {
              db.execSQL("DROP TABLE IF EXISTS Message");
              db.execSQL("CREATE TABLE IF NOT EXISTS messages (" +
                  "id TEXT NOT NULL PRIMARY KEY, conversationId TEXT, sender TEXT, " +
                  "text TEXT, timestamp INTEGER NOT NULL DEFAULT 0, " +
                  "isEncrypted INTEGER NOT NULL DEFAULT 0)");
          }
      };

      static final Migration MIGRATION_2_3 = new Migration(2, 3) {
          @Override public void migrate(SupportSQLiteDatabase db) {
              db.execSQL("ALTER TABLE messages ADD COLUMN mediaUrl TEXT");
          }
      };

      static final Migration MIGRATION_3_4 = new Migration(3, 4) {
          @Override public void migrate(SupportSQLiteDatabase db) {
              db.execSQL("ALTER TABLE messages ADD COLUMN mediaType TEXT");
              db.execSQL("ALTER TABLE messages ADD COLUMN delivered INTEGER NOT NULL DEFAULT 0");
              db.execSQL("ALTER TABLE messages ADD COLUMN seen INTEGER NOT NULL DEFAULT 0");
          }
      };
  }