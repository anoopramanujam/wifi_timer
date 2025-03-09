package com.example.wifitimerilu

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import android.content.Context.RECEIVER_NOT_EXPORTED

class MainActivity : AppCompatActivity() {
    private lateinit var wifiNameEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var resetButton: Button
    private lateinit var preferences: SharedPreferences
    private lateinit var timerUpdateReceiver: BroadcastReceiver

    // Add handler for active UI updates
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerUpdateRunnable = object : Runnable {
        override fun run() {
            updateUIFromPreferences()
            // Run this every 500ms to ensure UI is always up-to-date
            timerHandler.postDelayed(this, 500)
        }
    }

    companion object {
        private const val TAG = "WifiTimerMain"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        wifiNameEditText = findViewById(R.id.wifi_name_edittext)
        saveButton = findViewById(R.id.save_button)
        timerTextView = findViewById(R.id.timer_textview)
        statusTextView = findViewById(R.id.status_textview)
        resetButton = findViewById(R.id.reset_button)

        // Get shared preferences
        preferences = getSharedPreferences("WifiTimerPrefs", MODE_PRIVATE)

        // Load saved wifi name and time
        loadSavedData()

        // Set up button listeners
        saveButton.setOnClickListener {
            saveWifiName()
        }

        resetButton.setOnClickListener {
            resetTimer()
        }

        // Initialize the broadcast receiver
        initTimerUpdateReceiver()

        // Request necessary permissions
        requestRequiredPermissions()
    }

    private fun initTimerUpdateReceiver() {
        timerUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.example.wifitimerilu.TIMER_UPDATE") {
                    // Force immediate UI refresh
                    updateUIFromPreferences()

                    // Get state information for status updates
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    val wifiName = preferences.getString("wifi_name", "") ?: ""

                    // Update status based on timer state
                    if (isRunning) {
                        updateStatus("Connected to: $wifiName")
                    } else {
                        updateStatus("Waiting for: $wifiName")
                    }

                    Log.d(TAG, "Received broadcast update, isRunning: $isRunning")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Start periodic UI updates
        timerHandler.post(timerUpdateRunnable)

        // Register for timer updates
        val intentFilter = IntentFilter("com.example.wifitimerilu.TIMER_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerUpdateReceiver, intentFilter)
        }

        // Force an immediate update
        updateUIFromPreferences()
    }

    override fun onPause() {
        super.onPause()

        // Remove the timer update handler
        timerHandler.removeCallbacks(timerUpdateRunnable)

        // Unregister receiver when activity is not visible
        try {
            unregisterReceiver(timerUpdateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    private fun loadSavedData() {
        val savedWifiName = preferences.getString("wifi_name", "")

        // Display saved WiFi name if exists
        if (!savedWifiName.isNullOrEmpty()) {
            wifiNameEditText.setText(savedWifiName)
            updateStatus("Monitoring for: $savedWifiName")
        }

        // Initial UI update
        updateUIFromPreferences()
    }

    private fun updateUIFromPreferences() {
        // Get the total saved time
        val totalTimeMillis = preferences.getLong("total_time", 0)

        // If timer is running, add the current session time
        val currentSessionTime = preferences.getLong("current_session_time", 0)
        val isServiceRunning = isServiceRunning(WifiTimerService::class.java)

        // Only add session time if service is running
        val displayTime = if (isServiceRunning) totalTimeMillis + currentSessionTime else totalTimeMillis

        // Format and display the time
        val hours = TimeUnit.MILLISECONDS.toHours(displayTime)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(displayTime) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(displayTime) % 60

        val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        timerTextView.text = timeFormatted

        Log.d(TAG, "Updated UI with time: $timeFormatted (base: $totalTimeMillis, session: $currentSessionTime)")
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun saveWifiName() {
        val wifiName = wifiNameEditText.text.toString().trim()
        if (wifiName.isEmpty()) {
            Toast.makeText(this, "Please enter a WiFi network name", Toast.LENGTH_SHORT).show()
            return
        }

        // Save the WiFi name
        preferences.edit().apply {
            putString("wifi_name", wifiName)
            apply()
        }

        updateStatus("Monitoring for: $wifiName")
        Toast.makeText(this, "WiFi name saved", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Saved WiFi name: $wifiName")

        // Make sure service is running
        startWifiTimerService()
    }

    private fun resetTimer() {
        Log.d(TAG, "Reset button pressed - sending reset command to service")

        // First, update the UI immediately to show 00:00:00
        timerTextView.text = "00:00:00"

        // Reset timer to zero in preferences
        preferences.edit().apply {
            putLong("total_time", 0)
            putLong("current_session_time", 0)
            putBoolean("timer_reset", true)
            apply()
        }

        // Create a direct Intent for the service with the reset action
        val intent = Intent(this, WifiTimerService::class.java)
        intent.action = "com.example.wifitimerilu.TIMER_RESET"

        // Try both sending a broadcast and a direct service command
        try {
            // Try to start service with the reset action
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Also send broadcast as backup
            sendBroadcast(intent)
            Log.d(TAG, "Reset command sent to service")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reset command: ${e.message}", e)
        }

        Toast.makeText(this, "Timer reset to 00:00:00", Toast.LENGTH_SHORT).show()

        // Force an immediate UI update
        updateUIFromPreferences()
    }

    private fun updateStatus(status: String) {
        statusTextView.text = status
    }

    private fun requestRequiredPermissions() {
        val neededPermissions = mutableListOf<String>()

        // Add permission for notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Location permissions are required for WiFi scanning
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Request permissions if needed
        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                neededPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // Permissions already granted, start service
            startWifiTimerService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all permissions were granted
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // All permissions granted, start service
                startWifiTimerService()
            } else {
                // Some permissions denied
                Toast.makeText(
                    this,
                    "Location permission is required for WiFi scanning",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startWifiTimerService() {
        try {
            val serviceIntent = Intent(this, WifiTimerService::class.java)
            serviceIntent.putExtra("START_AS_FOREGROUND", true)

            Log.d(TAG, "Starting WiFi Timer Service")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "WiFi Timer service started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}