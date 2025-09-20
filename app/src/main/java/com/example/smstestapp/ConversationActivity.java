package com.example.smstestapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class ConversationActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // You don’t need UI – this is just to satisfy default SMS app requirements
        //Log.d("ConversationActivity", "ConversationActivity launched for sms/mms intent");
        finish(); // close immediately
    }
}
