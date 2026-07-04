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
    private lateinit var pincodesInput: EditText
    private lateinit var totalChecksText: TextView
    private lateinit var successChecksText: TextView
    private lateinit var failedChecksText: TextView
    private lateinit var inStockCountText: TextView
    private lateinit var outOfStockCountText: TextView
    private lateinit var avgIntervalText: TextView
    private lateinit var pincodeStatsText: TextView
    private lateinit var recentLogText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        botTokenInput = findViewById(R.id.botTokenInput)
        chatIdInput = findViewById(R.id.chatIdInput)
        pincodesInput = findViewById(R.id.pincodesInput)
        totalChecksText = findViewById(R.id.totalChecksText)
        successChecksText = findViewById(R.id.successChecksText)
        failedChecksText = findViewById(R.id.failedChecksText)
        inStockCountText = findViewById(R.id.inStockCountText)
        outOfStockCountText = findViewById(R.id.outOfStockCountText)
        avgIntervalText = findViewById(R.id.avgIntervalText)
        pincodeStatsText = findViewById(R.id.pincodeStatsText)
        recentLogText = findViewById(R.id.recentLogText)

        val saveButton: Button = findViewById(R.id.saveButton)
        val checkNowButton: Button = findViewById(R.id.checkNowButton)
        val clearLogButton: Button = findViewById(R.id.clearLogButton)

        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        botTokenInput.setText(prefs.getString("bot_token", ""))
        chatIdInput.setText(prefs.getString("chat_id", ""))
        pincodesInput.setText(prefs.getString("pincodes", "411040,411045,411060"))

        saveButton.setOnClickListener { saveCredentials() }
        checkNowButton.setOnClickListener { runImmediateCheck() }
        clearLogButton.setOnClickListener {
            CheckLog.clearLog(this)
            refreshAnalytics()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }

        requestPermissions()
        disableBatteryOptimization()
        schedulePeriodicWork()
        startTelegramCommandService()
        updateStatus()
        refreshAnalytics()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshAnalytics()
    }

    private fun saveCredentials() {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("bot_token", botTokenInput.text.toString().trim())
            .putString("chat_id", chatIdInput.text.toString().trim())
            .putString("pincodes", pincodesInput.text.toString().trim())
            .apply()
        Toast.makeText(this, "Credentials saved! Monitoring started.", Toast.LENGTH_SHORT).show()
        schedulePeriodicWork()
        startTelegramCommandService()
    }

    private fun runImmediateCheck() {
        statusText.text = "Checking now..."
        val pincodes = pincodesInput.text.toString().trim()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Thread {
            val results = StockChecker.checkPincodes(pincodes)

            CheckLog.addEntries(this, results)

            val status = results.joinToString("\n") { result ->
                when {
                    result.error != null -> "${result.pincode}: ERROR - ${result.error}"
                    result.inStock -> "${result.pincode}: ✅ IN STOCK! (${result.inventory})"
                    else -> "${result.pincode}: ❌ Out of stock"
                }
            }
            runOnUiThread {
                statusText.text = "Last check: ${currentTime()}\n\n$status"
                refreshAnalytics()

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

    private fun refreshAnalytics() {
        val analytics = CheckLog.getAnalytics(this)

        totalChecksText.text = "Total: ${analytics.totalChecks}"
        successChecksText.text = "✅ Success: ${analytics.successfulChecks}"
        failedChecksText.text = "❌ Failed: ${analytics.failedChecks}"
        inStockCountText.text = "🟢 In Stock: ${analytics.inStockCount}"
        outOfStockCountText.text = "🔴 Out of Stock: ${analytics.outOfStockCount}"

        avgIntervalText.text = if (analytics.avgIntervalMs > 0) {
            val mins = analytics.avgIntervalMs / 60000
            "⏱ Interval: ${mins}m"
        } else {
            "⏱ Interval: --"
        }

        if (analytics.perPincode.isNotEmpty()) {
            val sb = StringBuilder()
            for ((pincode, stats) in analytics.perPincode) {
                sb.appendLine("📍 $pincode")
                sb.appendLine("   Checks: ${stats.totalChecks} | In: ${stats.inStockCount} | Out: ${stats.outOfStockCount} | Err: ${stats.errors}")
                sb.appendLine("   Last: ${stats.lastStatus}")
                sb.appendLine()
            }
            pincodeStatsText.text = sb.toString().trimEnd()
        } else {
            pincodeStatsText.text = "No data yet"
        }

        val recentEntries = CheckLog.getRecentEntries(this, 15)
        if (recentEntries.isNotEmpty()) {
            val sb = StringBuilder()
            for (entry in recentEntries) {
                val time = CheckLog.formatTime(entry.timestamp)
                val status = when {
                    entry.error != null -> "ERR"
                    entry.inStock -> "IN STOCK(${entry.inventory})"
                    else -> "OUT"
                }
                sb.appendLine("$time | ${entry.pincode} | $status")
            }
            recentLogText.text = sb.toString().trimEnd()
        } else {
            recentLogText.text = "No checks recorded yet"
        }
    }

    private fun startTelegramCommandService() {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (botToken.isEmpty() || chatId.isEmpty()) return

        val intent = Intent(this, TelegramCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
