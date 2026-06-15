package com.wife.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Initialize our central debugging engine first
        WifeLogger.init(this);
        WifeLogger.log("SplashActivity", "Wife offline system booting up. Splash Activity instantiated.");

        // Pre-warm local singleton processes
        RoomDatabaseManager.getInstance(this);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (PermissionManager.hasAllPermissions(this)) {
                WifeLogger.log("SplashActivity", "All necessary platform permissions verified. Transitioning to MainActivity.");
                navigateToMain();
            } else {
                WifeLogger.log("SplashActivity", "Required permissions are missing. Instantiating permission request wizard.");
                PermissionManager.requestPermissions(this);
            }
        }, 2000);
    }

    private void navigateToMain() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionManager.PERMISSION_REQUEST_CODE) {
            WifeLogger.log("SplashActivity", "onRequestPermissionsResult callback received from OS. Loading main panel.");
            // Settle inside MainActivity regardless to keep UX fluid, or ask permissions inside main
            navigateToMain();
        }
    }
}