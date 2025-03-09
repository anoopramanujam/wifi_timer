package com.example.wifitimerilu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

class WifiTimerService : Service() {
    private lateinit var wifiManager: WifiManager
    private lateinit var preferences: SharedPreferences
    private lateinit var handler: Handler
    private lateinit var timerHandler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager

    private var startTimeMillis: Long = 0
    private var totalTimeMillis: Long = 0
    private var isTimerRunning: Boolean = false

    // Broadcast receiver for WiFi scan results
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            Log.d(TAG, "Wifi scan results received. Success: $success")

            if (success) {
                checkForTargetNetwork()
            } else {
                // Even if system reports failure, try to check anyway
                checkForTargetNetwork()
            }
        }
    }

    // Timer runnable to update time
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isTimerRunning) {
                val currentTimeMillis = System.currentTimeMillis()
                val sessionTimeMillis = currentTimeMillis - startTimeMillis
                val currentTotalTime = totalTimeMillis + sessionTimeMillis

                val formattedTime = formatTime(currentTotalTime)
                updateNotification("Timer running: $formattedTime")

                // IMPORTANT: Save the current session time to preferences
                preferences.edit().apply {
                    putLong("current_session_time", sessionTimeMillis)
                    apply()
                }

                // Broadcast timer update every second
                val intent = Intent("com.example.wifitimerilu.TIMER_UPDATE")
                intent.putExtra("total_time", currentTotalTime)
                intent.putExtra("is_running", isTimerRunning)
                intent.putExtra("current_time", formattedTime)
                sendBroadcast(intent)

                timerHandler.postDelayed(this, 1000) // Update every second
            }
        }
    }

    // Scanner runnable to initiate scans
    private val scanRunnable = object : Runnable {
        override fun run() {
            startWifiScan()
            handler.postDelayed(this, SCAN_INTERVAL)
        }
    }

    companion object {
        private const val TAG = "WifiTimerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "WifiTimerChannel"
        private const val SCAN_INTERVAL: Long = 10000 // Scan every 10 seconds
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        // Initialize components
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        preferences = getSharedPreferences("WifiTimerPrefs", Context.MODE_PRIVATE)
        handler = Handler(Looper.getMainLooper())
        timerHandler = Handler(Looper.getMainLooper())
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel
        createNotificationChannel()

        // Load saved timer value
        totalTimeMillis = preferences.getLong("total_time", 0)
        Log.d(TAG, "Loaded saved time: ${formatTime(totalTimeMillis)}")

        // Clear any existing session time
        preferences.edit().putLong("current_session_time", 0).apply()

        // Register for WiFi scan results
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // Acquire wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WifiTimer:WakeLock"
        )
        wakeLock.acquire(30 * 60 * 1000L) // 30 minutes

        // Start periodic scanning
        handler.post(scanRunnable)

        Log.d(TAG, "Service created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        if (intent?.getBooleanExtra("START_AS_FOREGROUND", false) == true) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                Log.d(TAG, "Started as foreground service")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        // Stop handlers
        handler.removeCallbacks(scanRunnable)
        timerHandler.removeCallbacks(timerRunnable)

        // If timer is running, save the time
        if (isTimerRunning) {
            val endTimeMillis = System.currentTimeMillis()
            val sessionTimeMillis = endTimeMillis - startTimeMillis
            totalTimeMillis += sessionTimeMillis
        }

        // Save total time and clear session time
        preferences.edit().apply {
            putLong("total_time", totalTimeMillis)
            putLong("current_session_time", 0) // Clear session time on destroy
            apply()
        }

        // Unregister receiver
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        // Release wake lock
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun startWifiScan() {
        Log.d(TAG, "Starting WiFi scan")

        if (!wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi is disabled, can't scan")
            return
        }

        try {
            val success = wifiManager.startScan()
            Log.d(TAG, "Scan initiated, success: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WiFi scan", e)
        }
    }

    private fun checkForTargetNetwork() {
        val targetWifiName = preferences.getString("wifi_name", "") ?: ""
        if (targetWifiName.isEmpty()) {
            Log.d(TAG, "No target WiFi configured")
            updateNotification("No WiFi network configured")
            return
        }

        Log.d(TAG, "Checking for target network: $targetWifiName")

        try {
            val scanResults = wifiManager.scanResults
            Log.d(TAG, "Found ${scanResults.size} networks")

            // Print all found networks
            if (scanResults.isNotEmpty()) {
                val networkNames = scanResults.map { it.SSID }
                Log.d(TAG, "Available networks: $networkNames")
            }

            // Check if target network is available
            val isTargetAvailable = scanResults.any { it.SSID == targetWifiName }
            Log.d(TAG, "Target network available: $isTargetAvailable, Timer running: $isTimerRunning")

            // Start timer if target found and not already running
            if (isTargetAvailable && !isTimerRunning) {
                Log.d(TAG, "STARTING TIMER for network: $targetWifiName")
                startTimer()
            }
            // Stop timer if target not found but timer running
            else if (!isTargetAvailable && isTimerRunning) {
                Log.d(TAG, "STOPPING TIMER - network no longer available: $targetWifiName")
                stopTimer()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi networks", e)
        }
    }

    private fun startTimer() {
        startTimeMillis = System.currentTimeMillis()
        isTimerRunning = true
        timerHandler.post(timerRunnable)
        updateNotification("Timer started")

        // Reset current session time when starting
        preferences.edit().putLong("current_session_time", 0).apply()

        // Broadcast timer state change
        broadcastTimerUpdate()
    }

    private fun stopTimer() {
        // Calculate elapsed time
        val endTimeMillis = System.currentTimeMillis()
        val sessionTimeMillis = endTimeMillis - startTimeMillis
        totalTimeMillis += sessionTimeMillis

        // Save total time and reset session time
        preferences.edit().apply {
            putLong("total_time", totalTimeMillis)
            putLong("current_session_time", 0) // Clear session time when timer stops
            apply()
        }

        isTimerRunning = false
        timerHandler.removeCallbacks(timerRunnable)
        updateNotification("Timer stopped - Total: ${formatTime(totalTimeMillis)}")

        // Broadcast timer state change
        broadcastTimerUpdate()
    }

    private fun broadcastTimerUpdate() {
        // Send broadcast to update UI
        val intent = Intent("com.example.wifitimerilu.TIMER_UPDATE")
        intent.putExtra("total_time", totalTimeMillis)
        intent.putExtra("is_running", isTimerRunning)
        intent.putExtra("current_time", formatTime(totalTimeMillis))
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent: isRunning=$isTimerRunning, time=${formatTime(totalTimeMillis)}")
    }

    private fun formatTime(timeMillis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMillis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WiFi Timer Service"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val targetWifiName = preferences.getString("wifi_name", "") ?: ""
        val text = if (targetWifiName.isEmpty()) {
            "No WiFi network configured"
        } else if (isTimerRunning) {
            "Monitoring $targetWifiName - Timer running"
        } else {
            "Waiting for $targetWifiName"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Timer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Timer")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}