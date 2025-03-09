package com.example.wifitimerilu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.d("WifiTimerBoot", "Device boot completed, starting WiFi Timer service")

            // Check if we have a configured WiFi name
            val preferences = context.getSharedPreferences("WifiTimerPrefs", Context.MODE_PRIVATE)
            val wifiName = preferences.getString("wifi_name", "")

            if (!wifiName.isNullOrEmpty()) {
                val serviceIntent = Intent(context, WifiTimerService::class.java)
                serviceIntent.putExtra("START_AS_FOREGROUND", true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d("WifiTimerBoot", "Service started on boot for WiFi: $wifiName")
            } else {
                Log.d("WifiTimerBoot", "No WiFi configured, skipping service start")
            }
        }
    }
}