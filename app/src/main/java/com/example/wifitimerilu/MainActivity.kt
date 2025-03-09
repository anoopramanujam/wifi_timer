package com.example.wifitimerilu

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var wifiNameEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var timerTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var resetButton: Button
    private lateinit var preferences: SharedPreferences

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

        // Request necessary permissions
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Update timer display with latest value
        updateTimerDisplay()
    }

    private fun loadSavedData() {
        val savedWifiName = preferences.getString("wifi_name", "")
        val totalTimeMillis = preferences.getLong("total_time", 0)

        // Display saved WiFi name if exists
        if (!savedWifiName.isNullOrEmpty()) {
            wifiNameEditText.setText(savedWifiName)
            updateStatus("Monitoring for: $savedWifiName")
        }

        // Display saved timer value
        updateTimerDisplay()
    }

    private fun updateTimerDisplay() {
        val totalTimeMillis = preferences.getLong("total_time", 0)
        val hours = TimeUnit.MILLISECONDS.toHours(totalTimeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(totalTimeMillis) % 60

        val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        timerTextView.text = timeFormatted
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
        // Reset timer to zero
        preferences.edit().apply {
            putLong("total_time", 0)
            apply()
        }

        updateTimerDisplay()
        Toast.makeText(this, "Timer reset to 00:00:00", Toast.LENGTH_SHORT).show()
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