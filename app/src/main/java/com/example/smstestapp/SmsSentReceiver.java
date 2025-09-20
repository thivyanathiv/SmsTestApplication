package com.example.smstestapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONObject;

public class SmsSentReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String msgId      = intent.getStringExtra("id");
        String sim        = intent.getStringExtra("sim");
        String callbackUrl= intent.getStringExtra("callbackUrl");

        if (msgId == null) {
            Log.w("SmsSentReceiver", "Missing msg id; ignoring");
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("type", "acknowledgement");
            json.put("id", msgId);
            json.put("sim", sim != null ? sim : "");

            int rc = getResultCode();
            if (rc == Activity.RESULT_OK) {
                json.put("status", "sent");
            } else {
                json.put("status", "failed (" + mapSendError(rc) + ")");
//                json.put("error", mapSendError(rc));
            }

            if (callbackUrl != null && !callbackUrl.isEmpty()) {
                json.put("callbackUrl", callbackUrl); // lets StatusPoster override target
            }

            StatusPoster.postJson(ctx, json);
            Log.d("SmsSentReceiver", "SENT posted: " + json);

        } catch (Exception e) {
            Log.e("SmsSentReceiver", "Error building SENT JSON", e);
        }
    }

    private String mapSendError(int rc) {
        switch (rc) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "generic_failure";
            case SmsManager.RESULT_ERROR_NO_SERVICE:      return "no_service";
            case SmsManager.RESULT_ERROR_NULL_PDU:        return "null_pdu";
            case SmsManager.RESULT_ERROR_RADIO_OFF:       return "radio_off";
            default:                                      return String.valueOf(rc);
        }
    }
}
