package com.duoshield.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.util.AppLockManager;

public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);
    }

    // Bug A fix: check shouldLock() in onStart() so it runs BEFORE any subclass
    // onStart() override can call onAppForegrounded() and zero-out bgTs.
    // LockScreenActivity extends AppCompatActivity directly and is unaffected.
    //
    // Bug 10 fix: reset the lock timer in the else branch so navigating between
    // activities derived from BaseActivity (Settings, KeyFingerprint, etc.) does not
    // accumulate background time and trigger a spurious lock on return.
    // Previously only ConversationListActivity called onAppForegrounded(); any other
    // screen held an uncorrected bgTs and could trigger the lock after 3 minutes.
    @Override
    protected void onStart() {
        super.onStart();
        if (AppLockManager.shouldLock(this)) {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } else {
            // Reset the background timestamp — keeps the 3-minute window anchored to
            // the last time the user interacted with ANY activity, not just the list.
            AppLockManager.onAppForegrounded(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLockManager.onAppBackgrounded(this);
    }

    protected void navigateTo(Class<?> dest) {
        startActivity(new Intent(this, dest));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    protected void navigateBack() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
