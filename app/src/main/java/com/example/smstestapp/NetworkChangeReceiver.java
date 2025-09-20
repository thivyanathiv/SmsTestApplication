package com.example.smstestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class NetworkChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (isInternetAvailable(context)) {
            Log.d("NetworkChange", "Internet available. Triggering WorkManager...");
            triggerSmsResendWorker(context);
            SmsResendWorker.enqueueFlushPending(context.getApplicationContext());

        } else {
            Log.d("NetworkChange", "Internet NOT available.");
        }
    }

    private boolean isInternetAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void triggerSmsResendWorker(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(SmsResendWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueue(workRequest);
    }
}
