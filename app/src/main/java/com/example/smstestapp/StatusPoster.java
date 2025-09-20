package com.example.smstestapp;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class StatusPoster {
    private static final String TAG = "StatusPoster";


    /** Core poster: posts JSON to callbackUrl (if provided), else to /api/post/?device=<id> */
    public static void postJson(final Context ctx, final JSONObject json) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // Android ID (stable per device+signing key)
                String deviceId = Settings.Secure.getString(
                        ctx.getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );
                if (deviceId == null) deviceId = "unknown";

                // If caller provided callbackUrl in the JSON, honor it; else use device endpoint
                String callbackUrl = json.optString("callbackUrl", "");
                String target = callbackUrl != null && !callbackUrl.isEmpty()
                        ? callbackUrl
                        : ("https://sendreceivesms.com/api/post/?device=" + deviceId);


                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL(target);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json, */*");
                conn.setRequestProperty("User-Agent", "SmsTestApp/1.0 (Android)");
                conn.setFixedLengthStreamingMode(body.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                String response = readBody(conn, code);
                if (code >= 200 && code < 300) {
                    Log.d(TAG, "POST OK (" + code + "): " + response);
                } else {
                    Log.e(TAG, "POST FAILED (" + code + "): " + response + " payload=" + json);
                }

            } catch (Exception e) {
                Log.e(TAG, "POST JSON failed: " + e.getMessage(), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** Convenience wrapper you had stubbed. Builds the JSON and calls postJson. */
    public static void post(Context ctx, String callbackUrl, String messageId, String status, String error) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "acknowledgement");
            json.put("id", messageId != null ? messageId : "");
            json.put("status", status != null ? status : "unknown");
            if (error != null && !error.isEmpty()) json.put("error", error);
            if (callbackUrl != null && !callbackUrl.isEmpty()) json.put("callbackUrl", callbackUrl);
            postJson(ctx, json);
        } catch (Exception e) {
            Log.e(TAG, "post(helper) build json failed", e);
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try {
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }  

}
