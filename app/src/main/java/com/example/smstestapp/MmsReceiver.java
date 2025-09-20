package com.example.smstestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class MmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // Just log for now (system expects this class to exist)
        Log.d("MmsReceiver", "MMS received (WAP_PUSH_DELIVER)");
    }
}
