package com.example.smstestapp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * This service is required for default SMS apps on Android (KitKat and above).
 * It normally runs in the background when another app (like the dialer)
 * wants to send an SMS via your app.
 */
public class HeadlessSmsSendService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // No binding needed, just return null
        return null;
    }
}
