package com.example.smstestapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmsResendWorker extends Worker {

    private static final String TAG = "SmsResendWorker";
    private static final String PREFS = "offline_sms";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_SINGLE = "single_payload";

    // Unique work name for flushing saved items
    private static final String UNIQUE_FLUSH_WORK = "flush_offline_sms";

    // Tunables
    private static final int SEND_TIMEOUT_SECONDS = 20;   // per HTTP attempt
    private static final int MAX_QUEUE_BATCH = 50;        // safety cap per run

    /** Enqueue a one-off send for a single payload (runs only when online). */
    public static void enqueueSinglePayload(Context ctx, String json) {
        Data data = new Data.Builder().putString(KEY_SINGLE, json).build();

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SmsResendWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(data)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(req);
        Log.d(TAG, "Enqueued single payload worker");
    }

    /** Enqueue a unique worker to flush any saved offline items when network is available. */
    public static void enqueueFlushPending(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SmsResendWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_FLUSH_WORK, ExistingWorkPolicy.KEEP, req);
        Log.d(TAG, "Enqueued flush worker (unique)");
    }

    public SmsResendWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        final String single = getInputData().getString(KEY_SINGLE);

        try {
            // Fast path: send the single payload that the receiver just handed us
            if (single != null) {
                Log.d(TAG, "doWork: sending single payload");
                boolean ok = sendJsonBlocking(context, new JSONObject(single));
                return ok ? Result.success() : Result.retry();
            }

            // Fallback: flush any saved offline items
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> backed = prefs.getStringSet(KEY_PENDING, new HashSet<>());
            Set<String> queue = new HashSet<>(backed); // copy before mutate

            if (queue.isEmpty()) {
                Log.d(TAG, "doWork: no pending items; success");
                return Result.success();
            }

            Log.d(TAG, "doWork: pending items count = " + queue.size());
            Iterator<String> it = queue.iterator();
            int sentCount = 0;

            while (it.hasNext() && sentCount < MAX_QUEUE_BATCH) {
                String raw = it.next();
                boolean ok = sendJsonBlocking(context, new JSONObject(raw));
                if (ok) {
                    it.remove();
                    sentCount++;
                } else {
                    // Stop on first failure and retry the whole worker later
                    Log.w(TAG, "doWork: send failed; will retry later");
                    // Persist current trimmed queue before returning
                    prefs.edit().putStringSet(KEY_PENDING, queue).apply();
                    return Result.retry();
                }
            }

            // Save the reduced queue
            prefs.edit().putStringSet(KEY_PENDING, queue).apply();
            Log.d(TAG, "doWork: flushed " + sentCount + " items; remaining=" + queue.size());
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "doWork: exception", e);
            return Result.retry();
        }
    }

    /** Blocks up to SEND_TIMEOUT_SECONDS while HttpHelper posts the JSON. */
    private boolean sendJsonBlocking(Context context, JSONObject obj) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);

        try {
            HttpHelper.sendToServer(context, obj, new HttpHelper.Callback() {
                @Override public void onSuccess() { ok.set(true); latch.countDown(); }
                @Override public void onError(Exception e) {
                    Log.w(TAG, "sendJsonBlocking: send error", e);
                    latch.countDown();
                }
            });

            boolean completed = latch.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                Log.w(TAG, "sendJsonBlocking: timed out after " + SEND_TIMEOUT_SECONDS + "s");
            }
            return completed && ok.get();

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "sendJsonBlocking: interrupted");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "sendJsonBlocking: exception", e);
            return false;
        }
    }
}
