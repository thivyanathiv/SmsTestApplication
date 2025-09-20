package com.example.smstestapp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SmsSendHelper {

    public static final String ACTION_SMS_SENT = "com.example.smstestapp.SMS_SENT";
    public static final String ACTION_SMS_DELIVERED = "com.example.smstestapp.SMS_DELIVERED";

    /**
     * @param simSlotFromServer → JSON "sim" value (1 or 2)
     */
    public static void sendSms(Context ctx,
                               String to,
                               String body,
                               String messageId,
                               String callbackUrl,
                               Integer simSlotFromServer /* nullable: 1 or 2 */) {

        try {
            SmsManager sms;

            if (simSlotFromServer != null) {
                // Convert 1/2 → 0/1 slotIndex
                int slotIndex = simSlotFromServer - 1;

                SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();

                if (subs != null && slotIndex >= 0 && slotIndex < subs.size()) {
                    int realSubId = subs.get(slotIndex).getSubscriptionId();
                    sms = SmsManager.getSmsManagerForSubscriptionId(realSubId);
                    Log.d("SMS", "Using SIM slot=" + simSlotFromServer + " (subscriptionId=" + realSubId + ")");
                } else {
                    // fallback
                    sms = SmsManager.getDefault();
                    Log.w("SMS", "Invalid simSlot=" + simSlotFromServer + " → fallback default SIM");
                }
            } else {
                sms = SmsManager.getDefault();
                Log.d("SMS", "No SIM slot specified → using default SIM");
            }

            ArrayList<String> parts = sms.divideMessage(body);
            int total = parts.size();

            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();

            for (int i = 0; i < total; i++) {
                Intent sent = new Intent(ACTION_SMS_SENT)
                        .putExtra("msgId", messageId)
                        .putExtra("to", to)
                        .putExtra("callbackUrl", callbackUrl)
                        .putExtra("totalParts", total)
                        .putExtra("partIndex", i);

                Intent delivered = new Intent(ACTION_SMS_DELIVERED)
                        .putExtra("msgId", messageId)
                        .putExtra("to", to)
                        .putExtra("callbackUrl", callbackUrl)
                        .putExtra("totalParts", total)
                        .putExtra("partIndex", i);


                int reqCodeBase = (messageId.hashCode() ^ i ^ new Random().nextInt());

                PendingIntent sentPI = PendingIntent.getBroadcast(
                        ctx, reqCodeBase,
                        sent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                PendingIntent delPI = PendingIntent.getBroadcast(
                        ctx, reqCodeBase + 100000,
                        delivered,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                sentIntents.add(sentPI);
                deliveredIntents.add(delPI);
            }

            if (total > 1) {
                sms.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents);
            } else {
                sms.sendTextMessage(to, null, body, sentIntents.get(0), deliveredIntents.get(0));
            }

            Log.d("SMS", "Submitted " + total + " part(s) for msgId=" + messageId);

        } catch (SecurityException se) {
            Log.e("SMS", "SEND_SMS permission missing!", se);
            StatusPoster.post(ctx, callbackUrl, messageId, "sent_failed", "perm_denied");
        } catch (Exception e) {
            Log.e("SMS", "Error sending SMS", e);
            StatusPoster.post(ctx, callbackUrl, messageId, "sent_failed", "exception");
        }
    }
}
