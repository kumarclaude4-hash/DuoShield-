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

    @Override
    protected void onResume() {
        super.onResume();
        if (AppLockManager.shouldLock(this)) {
            Intent intent = new Intent(this, LockScreenActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
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
