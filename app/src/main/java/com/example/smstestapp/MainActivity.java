
package com.example.smstestapp;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    Button pingButton;
    String fcmToken = "";
    private static final int SMS_PERMISSION_CODE = 100;

    // Auto-register guard (duplicate POST)
    private static final String PREF_REG = "reg_prefs";
    private static final String KEY_LAST_TOKEN = "last_token";
    private static final String KEY_REGISTERED_ONCE = "registered_once";

    // App foreground visibility (for ping JSON)
    private static volatile boolean sVisible = false;
    public static boolean isAppVisible() { return sVisible; }

    private ActivityResultLauncher<Intent> roleRequestLauncher;

    @Override protected void onStart() { super.onStart(); sVisible = true; }
    @Override protected void onStop()  { super.onStop();  sVisible = false; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if(getSupportActionBar() != null) getSupportActionBar().hide();

        // Android 10+ role request result callback
        roleRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (isDefaultSmsApp(this)) {
                        Toast.makeText(this, "Default SMS app set ", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Not set as default ", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // App open system chooser  (if doesn't already default It will show)
        ensureDefaultSmsApp();

        // ---- Permissions ----
        String[] permissions = {
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE
        };
        boolean needPermission = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needPermission = true; break;
            }
        }
        if (needPermission) {
            ActivityCompat.requestPermissions(this, permissions, SMS_PERMISSION_CODE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, 123);
        }

        // ---- UI ----
        pingButton = findViewById(R.id.pingButton);
        pingButton.setOnClickListener(v -> {
            try {
                JSONObject ping = new JSONObject();
                ping.put("type", "ping");
                ping.put("default", isDefaultSmsApp(this) ? "yes" : "no");
                ping.put("screen", MainActivity.isAppVisible() ? "on" : "off");
                ping.put("battery", NetUtils.getBatteryLevel(getApplicationContext()));

                FirebaseUtils.sendPingResponse(this, ping);
                Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e("PING", "Failed to send test", e);
                Toast.makeText(this, "failed", Toast.LENGTH_SHORT).show();
            }
        });

        // ---- FCM token → auto-register ----
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                fcmToken = task.getResult();
                Log.d("FCM", "Token: " + fcmToken);
                attemptRegisterIfNeeded();
            } else {
                Log.e("FCM Token", "Token fetch failed", task.getException());
            }
        });

        // ---- Pending offline posts ----
        if (isInternetAvailable()) {
            resendOfflineSms();
        }
    }

    /** Default SMS app chooser (Android 10+: RoleManager, 9↓: legacy intent) */
    private void ensureDefaultSmsApp() {
        if (isDefaultSmsApp(this)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                Intent i = rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
                roleRequestLauncher.launch(i); // system chooser
            }
        } else {
            Intent i = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            i.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
            startActivity(i); // legacy chooser
        }
    }

    /** Check if this app is default SMS handler */
    public static boolean isDefaultSmsApp(Context context) {
        String myPackageName = context.getPackageName();
        String defaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context);
        return myPackageName.equals(defaultSmsApp);
    }

    /** Skip if already registered with same token */
    private void attemptRegisterIfNeeded() {
        SharedPreferences sp = getSharedPreferences(PREF_REG, MODE_PRIVATE);
        String lastToken = sp.getString(KEY_LAST_TOKEN, null);
        boolean already = sp.getBoolean(KEY_REGISTERED_ONCE, false);

        if (fcmToken == null || fcmToken.isEmpty()) {
            Log.w("Register", "FCM token empty; will try later");
            return;
        }
        if (already && fcmToken.equals(lastToken)) {
            Log.d("Register", "Already registered with same token. Skip.");
            return;
        }
        doRegister(fcmToken);
    }

    private void doRegister(String token) {
        try {
            Context context = getApplicationContext();

            JSONObject json = new JSONObject();
            json.put("type", "registration");
            json.put("token", token);
            json.put("project_id", "smstestapplication");

            HttpHelper.sendToServer(context, json, new HttpHelper.Callback() {
                @Override public void onSuccess() {
                    Log.d("Register", "Successfully Registration");
                    SharedPreferences sp = getSharedPreferences(PREF_REG, MODE_PRIVATE);
                    sp.edit()
                            .putString(KEY_LAST_TOKEN, token)
                            .putBoolean(KEY_REGISTERED_ONCE, true)
                            .apply();
                }
                @Override public void onError(Exception e) {
                    Log.e("Register", "Not Success Registration JSON ", e);
                }
            });

        } catch (Exception e) {
            Log.e("Register JSON", "Error sending registration", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == SMS_PERMISSION_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                String status = grantResults[i] == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
                Log.d("Permission", permissions[i] + " = " + status);
            }
            attemptRegisterIfNeeded();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void resendOfflineSms() {
        SharedPreferences prefs = getSharedPreferences("offline_sms", Context.MODE_PRIVATE);
        Set<String> pendingSet = new HashSet<>(prefs.getStringSet("pending", new HashSet<>()));

        for (String json : pendingSet) {
            try {
                JSONObject obj = new JSONObject(json);

                HttpHelper.sendToServer(MainActivity.this, obj, new HttpHelper.Callback() {
                    @Override public void onSuccess() {
                        Log.d("MainActivity", "Offline SMS sent: " + json);
                        SharedPreferences.Editor editor = prefs.edit();
                        Set<String> currentSet = new HashSet<>(prefs.getStringSet("pending", new HashSet<>()));
                        currentSet.remove(json);
                        editor.putStringSet("pending", currentSet);
                        editor.apply();
                    }
                    @Override public void onError(Exception e) {
                        Log.e("MainActivity", "Failed to send SMS: " + json, e);
                    }
                });
            } catch (Exception e) {
                Log.e("MainActivity", "Invalid JSON in offline SMS", e);
            }
        }
    }
}

