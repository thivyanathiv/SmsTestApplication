package com.example.smstestapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;

public final class NetUtils {
    private NetUtils() {}

    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
            return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        }
    }

    public static int getBatteryLevel(Context ctx){

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm !=null){
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }

        }
        return -1;

    }
}
