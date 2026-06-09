# ============================================================
# DuoShield ProGuard Rules — Step 8 Final
# ============================================================

# ── App crypto & security classes — never obfuscate ─────────
-keep class com.duoshield.app.crypto.**        { *; }
-keepclassmembers class com.duoshield.app.crypto.**    { *; }
-keep class com.duoshield.app.security.**      { *; }
-keepclassmembers class com.duoshield.app.security.**  { *; }

# ── Data models — required by Room and Firestore reflection ─
-keep class com.duoshield.app.models.**        { *; }
-keepclassmembers class com.duoshield.app.models.**    { *; }

# ── Firebase — all subpackages ───────────────────────────────
-keep class com.google.firebase.**             { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Google Auth Library (FCM OAuth2 token exchange) ─────────
-keep class com.google.auth.**                 { *; }
-keepclassmembers class com.google.auth.**     { *; }
-dontwarn com.google.auth.**

# ── Java crypto & security providers ────────────────────────
-keep class javax.crypto.**                    { *; }
-keep class javax.crypto.spec.**               { *; }
-keep class java.security.**                   { *; }
-keep class java.security.spec.**              { *; }
-dontwarn javax.crypto.**
-dontwarn java.security.**

# ── Room — preserve DAO + entity annotations ────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *            { *; }
-keep @androidx.room.Dao class *               { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ── WorkManager ──────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Firebase Messaging Service ───────────────────────────────
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService

# ── Biometric library ────────────────────────────────────────
-keep class androidx.biometric.**              { *; }
-dontwarn androidx.biometric.**

# ── Glide ────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ── Suppress warnings from google-auth transitive deps ───────
-dontwarn com.google.api.**
-dontwarn io.grpc.**
-dontwarn org.apache.http.**
-dontwarn com.google.android.gms.**

# ── Keep source file names for crash reporting ───────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
