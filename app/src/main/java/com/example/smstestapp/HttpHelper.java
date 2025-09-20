package com.example.smstestapp;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpHelper {

    public interface Callback {
        void onSuccess();
        void onError(Exception e);
    }

    // NOTE: Context added to parameter list
    public static void sendToServer(Context ctx, JSONObject json, Callback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                //  Get unique Android ID (Device ID)
                String deviceId = Settings.Secure.getString(
                        ctx.getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );

                // Build URL with dynamic deviceId
                URL url = new URL("https://sendreceivesms.com/api/post/?device=" + deviceId);

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                byte[] input = json.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(input.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(input);
                }

                int responseCode = conn.getResponseCode();
                Log.d("HTTP", "Response Code: " + responseCode + " device=" + deviceId);

                if (responseCode == 200) {
                    if (callback != null) callback.onSuccess();
                } else {
                    if (callback != null) {
                        callback.onError(new Exception("HTTP error code: " + responseCode));
                    }
                }
            } catch (Exception e) {
                Log.e("HTTP", "Error sending to server", e);
                if (callback != null) callback.onError(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
