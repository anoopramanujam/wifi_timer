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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
                    // Force immediate UI refresh
                    updateUIFromPreferences()

                    // Get basic state information
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    val wifiName = preferences.getString("wifi_name", "") ?: ""

                    // Check for explicit connection events
                    val connectionStarted = intent.getBooleanExtra("connection_started", false)
                    val connectionEnded = intent.getBooleanExtra("connection_ended", false)

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

                        // Update the log entry if duration is at least one minute
                        if (connectionDuration >= 60000) {
                            updateConnectionEndTime(connectionEndTime)
                        } else {
                            // Remove the log entry if duration is less than one minute
                            Log.d(TAG, "Connection duration < 1 minute, removing entry")
                            if (currentRowView != null) {
                                logEntriesContainer.removeView(currentRowView)
                                currentRowView = null
                            }
                        }
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

                        // Create and add the log entry row
                        addLogEntryRow(startTime, endTime, duration)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading log entries: ${e.message}")
            }
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

    // Connection log management methods
    private fun addNewConnectionToLog() {
        connectionStartTime = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val startTimeStr = timeFormat.format(Date(connectionStartTime))

        Log.d(TAG, "Adding new connection to log at time: $startTimeStr")

        // Create a new row for the log entry
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

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

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val endTimeStr = timeFormat.format(Date(connectionEndTime))

        // Calculate duration
        val durationMillis = connectionEndTime - connectionStartTime

        // Skip if less than one minute
        if (durationMillis < 60000) {
            // Remove the current row if duration is less than one minute
            Log.d(TAG, "Duration < 1 minute (${durationMillis/1000}s), removing row")
            runOnUiThread {
                logEntriesContainer.removeView(currentRowView)
                currentRowView = null
            }
            return
        }

        // Format duration (HH:mm)
        val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
        val durationStr = String.format("%02d:%02d", hours, minutes)

        Log.d(TAG, "Updating log entry: Out=$endTimeStr, Duration=$durationStr")

        // Update the "Out" and "Duration" columns
        runOnUiThread {
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

            currentRowView = null
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