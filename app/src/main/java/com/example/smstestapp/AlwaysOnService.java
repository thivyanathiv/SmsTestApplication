package com.example.smstestapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class AlwaysOnService extends Service {

    public static final String CHANNEL_ID = "core";
    public static final int NOTIF_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // (debug) ping-reason or other kicks
        String reason = intent != null ? intent.getStringExtra("reason") : null;
        Log.d("AlwaysOnService", "started; reason=" + reason);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Service running")
                .setContentText("Sync & resend active")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n);

        // ---- Offload real work to WorkManager ----
        Constraints net = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // One immediate pass
        WorkManager.getInstance(getApplicationContext()).enqueue(
                new OneTimeWorkRequest.Builder(SmsResendWorker.class)
                        .setConstraints(net)
                        .build()
        );

        // Periodic safety resend every 15 min (unique)
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                SmsResendWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build();

        WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(
                "sms-resend",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
        );

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Try to re-start after user swipes app from recents
        ContextCompat.startForegroundService(
                this,
                new Intent(this, AlwaysOnService.class).putExtra("reason", "task_removed")
        );
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Core background",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Keeps resend/sync active");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** Safe helper to start FGS from anywhere */
    public static void ensureRunning(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, AlwaysOnService.class).putExtra("reason", "ensure_running"));
    }
}
