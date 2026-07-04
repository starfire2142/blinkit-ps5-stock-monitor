package com.ps5tracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var botTokenInput: EditText
    private lateinit var chatIdInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        botTokenInput = findViewById(R.id.botTokenInput)
        chatIdInput = findViewById(R.id.chatIdInput)

        val saveButton: Button = findViewById(R.id.saveButton)
        val checkNowButton: Button = findViewById(R.id.checkNowButton)

        // Load saved credentials
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        botTokenInput.setText(prefs.getString("bot_token", ""))
        chatIdInput.setText(prefs.getString("chat_id", ""))

        saveButton.setOnClickListener {
            saveCredentials()
        }

        checkNowButton.setOnClickListener {
            runImmediateCheck()
        }

        requestPermissions()
        disableBatteryOptimization()
        schedulePeriodicWork()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun saveCredentials() {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("bot_token", botTokenInput.text.toString().trim())
            .putString("chat_id", chatIdInput.text.toString().trim())
            .apply()
        Toast.makeText(this, "Credentials saved! Monitoring started.", Toast.LENGTH_SHORT).show()
        schedulePeriodicWork()
    }

    private fun runImmediateCheck() {
        statusText.text = "Checking now..."
        Thread {
            val results = StockChecker.checkAllPincodes()
            val status = results.joinToString("\n") { result ->
                when {
                    result.error != null -> "${result.pincode}: ERROR - ${result.error}"
                    result.inStock -> "${result.pincode}: ✅ IN STOCK! (${result.inventory})"
                    else -> "${result.pincode}: ❌ Out of stock"
                }
            }
            runOnUiThread {
                statusText.text = "Last check: ${currentTime()}\n\n$status"

                val available = results.filter { it.inStock }.map { it.pincode }
                if (available.isNotEmpty()) {
                    val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
                    val token = prefs.getString("bot_token", "") ?: ""
                    val chatId = prefs.getString("chat_id", "") ?: ""
                    if (token.isNotEmpty() && chatId.isNotEmpty()) {
                        Thread { StockChecker.sendTelegramAlert(token, chatId, available) }.start()
                        Toast.makeText(this, "IN STOCK! Telegram alert sent!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<StockCheckWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ps5_stock_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_time", 0)
        val lastStatus = prefs.getString("last_check_status", "No checks yet")

        if (lastCheck > 0) {
            val time = SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date(lastCheck))
            statusText.text = "Last check: $time\n\n$lastStatus\n\n⏰ Checking every 15 minutes"
        } else {
            statusText.text = "Monitoring not started yet.\nSave your Telegram credentials to begin."
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun disableBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun currentTime(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
