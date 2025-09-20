package com.example.smstestapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

public class SmsDeliveredReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String msgId = intent.getStringExtra("id");
        String sim = intent.getStringExtra("sim");
        String callbackUrl = intent.getStringExtra("callbackUrl");

        try {
            JSONObject json = new JSONObject();
            json.put("type", "delivery_report");
            json.put("id", msgId);
            json.put("sim", sim != null ? sim : "");

            if (getResultCode() == Activity.RESULT_OK) {
                json.put("status", "delivered");
            } else {
                json.put("status", "undelivered");
            }

            if (callbackUrl != null) json.put("callbackUrl", callbackUrl);

            StatusPoster.postJson(ctx, json);
            Log.d("SmsDeliveredReceiver", "DLR posted: " + json);

        } catch (Exception e) {
            Log.e("SmsDeliveredReceiver", "Error building DLR JSON", e);
        }
    }
}

