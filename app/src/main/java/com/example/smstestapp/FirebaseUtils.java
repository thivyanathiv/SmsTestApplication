package com.example.smstestapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class FirebaseUtils {

    public static String getFirebaseToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("firebase_prefs", Context.MODE_PRIVATE);
        return prefs.getString("firebase_token", "");
    }

    public static void sendPingResponse(Context context, JSONObject data) {
        // Context must be passed now
        HttpHelper.sendToServer(context, data, new HttpHelper.Callback() {
            @Override
            public void onSuccess() {
                // Optional: Log or do something
            }

            @Override
            public void onError(Exception e) {
                e.printStackTrace(); // Optional: Log error
            }
        });
    }


}


