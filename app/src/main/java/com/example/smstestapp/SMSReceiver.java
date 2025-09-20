package com.example.smstestapp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Iterator;

public class SMSReceiver extends BroadcastReceiver {

    private static final String PREFS = "offline_sms";
    private static final String KEY_PENDING = "pending";

    // ---- De-dup cache (recent keys) ----
    private static final Set<String> recent =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private static boolean seenRecently(String key) {
        synchronized (recent) {
            if (recent.contains(key)) return true;
            recent.add(key);
            // prune to ~150 items to keep memory small
            if (recent.size() > 200) {
                Iterator<String> it = recent.iterator();
                int remove = recent.size() - 150;
                while (remove-- > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) return;

        // Process only ONE action based on default state:
        //    - Default SMS app → handle SMS_DELIVER only
        //    - Non-default     → handle SMS_RECEIVED only
        boolean isDefault = MainActivity.isDefaultSmsApp(context);
        if (isDefault && !action.equals("android.provider.Telephony.SMS_DELIVER")) return;
        if (!isDefault && !action.equals("android.provider.Telephony.SMS_RECEIVED")) return;

        final PendingResult pr = goAsync(); // keep the receiver alive for async work
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e("SMSReceiver", "RECEIVE_SMS permission not granted");
                pr.finish();
                return;
            }

            Bundle extras = intent.getExtras();
            if (extras == null) { pr.finish(); return; }

            Object[] pdus = (Object[]) extras.get("pdus");
            if (pdus == null || pdus.length == 0) { pr.finish(); return; }

            String format = extras.getString("format");
            SmsMessage[] messages = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
                } else {
                    messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
                }
            }

            // Combine multi-part
            StringBuilder fullMessage = new StringBuilder();
            String from = null;
            String smsc = null;
            long timestampMs = 0;

            for (SmsMessage msg : messages) {
                if (msg == null) continue;
                CharSequence part = msg.getMessageBody();
                if (part != null) fullMessage.append(part);
                if (from == null) from = msg.getOriginatingAddress();
                if (smsc == null) smsc = msg.getServiceCenterAddress();
                if (timestampMs == 0) timestampMs = msg.getTimestampMillis();
            }
            if (timestampMs == 0) timestampMs = System.currentTimeMillis();

            // De-dup check before any network/storage work
            String key = (from == null ? "" : from) + "|" + fullMessage + "|" + (timestampMs / 1000);
            if (seenRecently(key)) {
                Log.d("SMSReceiver", "Duplicate SMS detected; dropping → " + key);
                pr.finish();
                return;
            }

            int simSlotInt = resolveSimSlot(context, intent);

            JSONObject json = new JSONObject();
            json.put("type", "sms");
            json.put("from", from != null ? from : "");
            json.put("text", fullMessage.toString());
            json.put("sim", String.valueOf(simSlotInt));
            json.put("smsc", smsc != null ? smsc : "");
            json.put("timestamp", String.valueOf(timestampMs / 1000));

            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestampMs));
            Log.d("SMSReceiver", "Prepared inbox JSON @ " + ts + " → " + json);

            // Hand off to background immediately; do not block on network inside receiver
            if (NetUtils.isOnline(context)) {
                // send this one now
                SmsResendWorker.enqueueSinglePayload(context.getApplicationContext(), json.toString());
                // also flush any previously pending items safely
                SmsResendWorker.enqueueFlushPending(context.getApplicationContext());
            } else {
                saveSmsOffline(context.getApplicationContext(), json.toString());
            }
        } catch (Exception e) {
            Log.e("SMSReceiver", "onReceive error", e);
        } finally {
            pr.finish(); // always
        }
    }

    private int resolveSimSlot(Context context, Intent intent) {
        try {
            int subId = intent.getIntExtra("subscription", -1);
            if (subId == -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1);
            }
            if (subId == -1) return 1;

            SubscriptionManager sm =
                    (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return 1;

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w("SMSReceiver", "READ_PHONE_STATE not granted; defaulting simSlot=1");
                return 1;
            }

            for (SubscriptionInfo si : sm.getActiveSubscriptionInfoList()) {
                if (si.getSubscriptionId() == subId) {
                    return si.getSimSlotIndex() + 1; // 1 or 2
                }
            }
        } catch (Exception e) {
            Log.w("SMSReceiver", "resolveSimSlot fallback to 1", e);
        }
        return 1;
    }

    /** Store JSON string in SharedPreferences for later resend by worker. */
    private void saveSmsOffline(Context context, String json) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> existing = prefs.getStringSet(KEY_PENDING, new HashSet<String>());
        Set<String> set = new HashSet<>(existing); // copy before mutate
        set.add(json);
        prefs.edit().putStringSet(KEY_PENDING, set).apply();
        Log.d("OfflineSMS", "SMS saved offline: " + json);

        // Schedule a unique flush so when network comes back, everything goes
        SmsResendWorker.enqueueFlushPending(context.getApplicationContext());
    }
}

