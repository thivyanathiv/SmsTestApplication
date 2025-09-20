package com.example.smstestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            Intent svc = new Intent(ctx, AlwaysOnService.class)
                    .putExtra("reason", action); // helpful for logs

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(ctx, svc);
                } else {
                    ctx.startService(svc);
                }
                Log.d(TAG, "Started AlwaysOnService after: " + action);
            } catch (Exception e) {
                Log.w(TAG, "Failed to start FGS on " + action + ", scheduling fallback", e);
            }
        }
    }
}
