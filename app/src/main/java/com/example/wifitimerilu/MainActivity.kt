package com.example.wifitimerilu

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.content.Context.RECEIVER_NOT_EXPORTED

class MainActivity : AppCompatActivity() {
    private lateinit var wifiNameEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var resetButton: Button
    private lateinit var logEntriesContainer: LinearLayout
    private lateinit var preferences: SharedPreferences
    private lateinit var timerUpdateReceiver: BroadcastReceiver

    // For tracking connection time
    private var connectionStartTime: Long = 0
    private var currentRowView: LinearLayout? = null

    // Time format for logs using HH:mm:ss
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 101
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
        logEntriesContainer = findViewById(R.id.log_entries_container)

        // Get shared preferences
        preferences = getSharedPreferences("WifiTimerPrefs", MODE_PRIVATE)

        // Load saved wifi name and time
        loadSavedData()

        // Set up button listeners
        saveButton.setOnClickListener {
            saveWifiName()
        }

        resetButton.setOnClickListener {
            showResetConfirmationDialog()
        }

        // Initialize the broadcast receiver
        initTimerUpdateReceiver()

        // Request necessary permissions
        requestRequiredPermissions()

        // Load existing log entries if any
        loadLogEntries()
    }

    private fun initTimerUpdateReceiver() {
        timerUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.example.wifitimerilu.TIMER_UPDATE") {
                    // Log all extras for debugging
                    val extras = intent.extras
                    if (extras != null) {
                        Log.d(TAG, "Received broadcast with extras: ${extras.keySet().joinToString()}")
                    }

                    // Force immediate UI refresh
                    updateUIFromPreferences()

                    // Get basic state information
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    val wifiName = preferences.getString("wifi_name", "") ?: ""

                    // Check for explicit connection events
                    val connectionStarted = intent.getBooleanExtra("connection_started", false)
                    val connectionEnded = intent.getBooleanExtra("connection_ended", false)

                    Log.d(TAG, "Received broadcast - connectionStarted: $connectionStarted, connectionEnded: $connectionEnded, isRunning: $isRunning")

                    if (connectionStarted) {
                        // A new connection has started
                        Log.d(TAG, "Received connection_started broadcast")
                        connectionStartTime = intent.getLongExtra("connection_time", System.currentTimeMillis())
                        updateStatus("Connected to: $wifiName")

                        // Create a new row in the log
                        addNewConnectionToLog()
                    } else if (connectionEnded) {
                        // A connection has ended
                        Log.d(TAG, "Received connection_ended broadcast")
                        updateStatus("Waiting for: $wifiName")

                        // Get connection details
                        connectionStartTime = intent.getLongExtra("connection_start_time", 0)
                        val connectionEndTime = intent.getLongExtra("connection_end_time", 0)
                        val connectionDuration = intent.getLongExtra("connection_duration", 0)

                        Log.d(TAG, "Connection details - start: $connectionStartTime, end: $connectionEndTime, duration: ${connectionDuration/1000}s")

                        // Update the log entry without duration limitation
                        updateConnectionEndTime(connectionEndTime)
                    } else {
                        // Regular update, just update the status
                        if (isRunning) {
                            updateStatus("Connected to: $wifiName")
                        } else {
                            updateStatus("Waiting for: $wifiName")
                        }
                    }
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

        // Check if log container is visible and has correct layout
        Log.d(TAG, "Log container is visible: ${logEntriesContainer.visibility == View.VISIBLE}")
        Log.d(TAG, "Log container width: ${logEntriesContainer.width}, height: ${logEntriesContainer.height}")
        Log.d(TAG, "Log container parent is ScrollView: ${logEntriesContainer.parent is ScrollView}")
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

    private fun loadLogEntries() {
        // Load saved log entries from preferences if any
        val logEntries = preferences.getString("connection_log", "")
        if (!logEntries.isNullOrEmpty()) {
            try {
                val entries = logEntries.split("|")
                Log.d(TAG, "Loading ${entries.size} saved log entries")

                for (entry in entries) {
                    val parts = entry.split(",")
                    if (parts.size == 3) {
                        val startTime = parts[0]
                        val endTime = parts[1]
                        val duration = parts[2]

                        // Skip empty entries
                        if (startTime.isEmpty() || endTime.isEmpty() || duration.isEmpty()) {
                            continue
                        }

                        Log.d(TAG, "Loading log entry: $startTime to $endTime, duration: $duration")
                        // Create and add the log entry row
                        addLogEntryRow(startTime, endTime, duration)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading log entries: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "No saved log entries found")
        }
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

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Timer")
            .setMessage("Are you sure you want to reset the timer and clear the connection log?")
            .setPositiveButton("Reset") { _, _ ->
                resetTimer()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            // Clear the log entries
            putString("connection_log", "")
            apply()
        }

        // Clear the log UI
        logEntriesContainer.removeAllViews()
        currentRowView = null

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

        Toast.makeText(this, "Timer and log reset", Toast.LENGTH_SHORT).show()

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

        // Background location permission for Android 10 (Q) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
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
            val grantedPermissions = mutableListOf<String>()
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.add(permissions[i])
                } else {
                    deniedPermissions.add(permissions[i])
                }
            }

            // Check if all essential permissions were granted
            val hasLocationPermission = grantedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    grantedPermissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (hasLocationPermission) {
                // For Android 10+, we need to request background location permission separately
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (!grantedPermissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                        requestBackgroundLocationPermission()
                    } else {
                        // All permissions granted, start service
                        startWifiTimerService()
                    }
                } else {
                    // For pre-Android 10, we can start service with just location permission
                    startWifiTimerService()
                }
            } else {
                // Essential permissions denied
                Toast.makeText(
                    this,
                    "Location permission is required for WiFi scanning",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            // This is from the separate background location request
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWifiTimerService()
            } else {
                Toast.makeText(
                    this,
                    "Background location permission is needed for scanning WiFi in the background",
                    Toast.LENGTH_LONG
                ).show()
                // We can still start the service, but it may not work well in the background
                startWifiTimerService()
            }
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location Permission Needed")
                .setMessage("This app needs background location permission to detect WiFi networks when the app is in the background. Please grant this permission to allow the app to work properly.")
                .setPositiveButton("OK") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        BACKGROUND_LOCATION_REQUEST_CODE
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    Toast.makeText(
                        this,
                        "App will have limited functionality without background location permission",
                        Toast.LENGTH_LONG
                    ).show()
                    // Start service anyway, but it may not work in background
                    startWifiTimerService()
                }
                .create()
                .show()
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

    // Connection log management methods
    private fun addNewConnectionToLog() {
        connectionStartTime = System.currentTimeMillis()
        val startTimeStr = timeFormat.format(Date(connectionStartTime))

        Log.d(TAG, "Adding new connection to log at time: $startTimeStr")

        // Create a new row for the log entry
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Set a background color to make it visible
        row.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_200))

        // "In" column
        val inColumn = TextView(this)
        inColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        inColumn.text = startTimeStr
        inColumn.gravity = Gravity.CENTER
        inColumn.setPadding(8, 8, 8, 8)
        row.addView(inColumn)

        // "Out" column (empty for now)
        val outColumn = TextView(this)
        outColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        outColumn.text = "..."
        outColumn.gravity = Gravity.CENTER
        outColumn.setPadding(8, 8, 8, 8)
        row.addView(outColumn)

        // "Duration" column (empty for now)
        val durationColumn = TextView(this)
        durationColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        durationColumn.text = "..."
        durationColumn.gravity = Gravity.CENTER
        durationColumn.setPadding(8, 8, 8, 8)
        row.addView(durationColumn)

        // Add the row to the container
        runOnUiThread {
            logEntriesContainer.addView(row, 0) // Add at the top
            currentRowView = row
            Log.d(TAG, "New row added to log container with 'In' time: $startTimeStr")
        }
    }

    private fun updateConnectionEndTime(connectionEndTime: Long = System.currentTimeMillis()) {
        if (currentRowView == null) {
            Log.d(TAG, "updateConnectionEndTime: No current row view to update")
            return
        }

        Log.d(TAG, "Updating connection end time. Start: $connectionStartTime, End: $connectionEndTime")

        val endTimeStr = timeFormat.format(Date(connectionEndTime))

        // Calculate duration
        val durationMillis = connectionEndTime - connectionStartTime

        // Format duration (HH:mm:ss)
        val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
        val durationStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        Log.d(TAG, "Updating log entry: Out=$endTimeStr, Duration=$durationStr")

        // Update the "Out" and "Duration" columns
        runOnUiThread {
            try {
                val outColumn = currentRowView!!.getChildAt(1) as TextView
                outColumn.text = endTimeStr

                val durationColumn = currentRowView!!.getChildAt(2) as TextView
                durationColumn.text = durationStr

                // Save the log entry
                saveLogEntry(
                    timeFormat.format(Date(connectionStartTime)),
                    endTimeStr,
                    durationStr
                )

                // Set currentRowView to null to indicate this entry is complete
                currentRowView = null
            } catch (e: Exception) {
                Log.e(TAG, "Error updating log entry: ${e.message}", e)
            }
        }
    }

    private fun addLogEntryRow(startTime: String, endTime: String, duration: String) {
        // Create a new row for the log entry
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Set background color for visibility
        row.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))

        // "In" column
        val inColumn = TextView(this)
        inColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        inColumn.text = startTime
        inColumn.gravity = Gravity.CENTER
        inColumn.setPadding(8, 8, 8, 8)
        row.addView(inColumn)

        // "Out" column
        val outColumn = TextView(this)
        outColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        outColumn.text = endTime
        outColumn.gravity = Gravity.CENTER
        outColumn.setPadding(8, 8, 8, 8)
        row.addView(outColumn)

        // "Duration" column
        val durationColumn = TextView(this)
        durationColumn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        durationColumn.text = duration
        durationColumn.gravity = Gravity.CENTER
        durationColumn.setPadding(8, 8, 8, 8)
        row.addView(durationColumn)

        // Add the row to the container
        runOnUiThread {
            logEntriesContainer.addView(row, 0) // Add at the top
            Log.d(TAG, "Added saved log entry: $startTime to $endTime, duration: $duration")
        }
    }

    private fun saveLogEntry(startTime: String, endTime: String, duration: String) {
        // Get existing log entries
        val existingLog = preferences.getString("connection_log", "") ?: ""

        // Add new entry at the beginning
        val newEntry = "$startTime,$endTime,$duration"
        val updatedLog = if (existingLog.isEmpty()) {
            newEntry
        } else {
            "$newEntry|$existingLog"
        }

        // Save to preferences
        preferences.edit().putString("connection_log", updatedLog).apply()
        Log.d(TAG, "Saved log entry: $newEntry to preferences")
    }
}