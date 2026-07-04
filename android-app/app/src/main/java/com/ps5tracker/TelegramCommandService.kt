package com.ps5tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TelegramCommandService : Service() {

    companion object {
        const val TAG = "TelegramCmdService"
        private const val CHANNEL_ID = "telegram_cmd_channel"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_INTERVAL_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private var isRunning = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            Thread { pollUpdates() }.start()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true
        handler.post(pollRunnable)
        Log.i(TAG, "Telegram command polling started")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "Telegram command polling stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollUpdates() {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""

        if (botToken.isEmpty() || chatId.isEmpty()) return

        val offset = prefs.getLong("telegram_update_offset", 0)

        try {
            val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=5"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return
            val json = JSONObject(body)

            if (!json.optBoolean("ok", false)) return

            val results = json.getJSONArray("result")
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                val updateId = update.getLong("update_id")

                val message = update.optJSONObject("message") ?: continue
                val from = message.optJSONObject("chat") ?: continue
                val senderId = from.getLong("id").toString()

                if (senderId != chatId) {
                    Log.w(TAG, "Ignoring message from unauthorized chat: $senderId")
                    prefs.edit().putLong("telegram_update_offset", updateId + 1).apply()
                    continue
                }

                val text = message.optString("text", "").trim()
                if (text.startsWith("/")) {
                    handleCommand(botToken, chatId, text)
                }

                prefs.edit().putLong("telegram_update_offset", updateId + 1).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll error: ${e.message}")
        }
    }

    private fun handleCommand(botToken: String, chatId: String, command: String) {
        val reply = when {
            command.startsWith("/status") -> buildStatusReply()
            command.startsWith("/analytics") -> buildAnalyticsReply()
            command.startsWith("/check") -> buildCheckReply()
            command.startsWith("/pincodes") -> buildPincodesReply()
            command.startsWith("/log") -> buildLogReply()
            command.startsWith("/help") -> buildHelpReply()
            else -> "Unknown command. Send /help for available commands."
        }
        sendReply(botToken, chatId, reply)
    }

    private fun buildStatusReply(): String {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_time", 0)
        val lastStatus = prefs.getString("last_check_status", "No checks yet") ?: "No checks yet"

        val sb = StringBuilder()
        sb.appendLine("📡 *PS5 Tracker Status*")
        sb.appendLine()
        sb.appendLine("*Monitoring:* Active ✅")

        if (lastCheck > 0) {
            val time = SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date(lastCheck))
            val agoMs = System.currentTimeMillis() - lastCheck
            val agoMin = agoMs / 60000
            sb.appendLine("*Last check:* $time (${agoMin}m ago)")
            sb.appendLine("*Schedule:* Every 15 minutes")
            sb.appendLine()
            sb.appendLine("*Last results:*")
            sb.appendLine("```")
            sb.appendLine(lastStatus)
            sb.appendLine("```")
        } else {
            sb.appendLine("*Last check:* Never")
        }

        return sb.toString()
    }

    private fun buildAnalyticsReply(): String {
        val analytics = CheckLog.getAnalytics(this)

        if (analytics.totalChecks == 0) {
            return "📊 *Analytics*\n\nNo data yet. Waiting for first check."
        }

        val sb = StringBuilder()
        sb.appendLine("📊 *Analytics*")
        sb.appendLine()
        sb.appendLine("*Total checks:* ${analytics.totalChecks}")
        sb.appendLine("*Successful:* ${analytics.successfulChecks}")
        sb.appendLine("*Failed:* ${analytics.failedChecks}")
        sb.appendLine("*In stock:* ${analytics.inStockCount}")
        sb.appendLine("*Out of stock:* ${analytics.outOfStockCount}")

        if (analytics.avgIntervalMs > 0) {
            val mins = analytics.avgIntervalMs / 60000
            sb.appendLine("*Avg interval:* ${mins}m")
        }

        if (analytics.lastCheckTime > 0) {
            sb.appendLine("*Last check:* ${CheckLog.formatTime(analytics.lastCheckTime)}")
        }

        if (analytics.perPincode.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("*Per-Pincode:*")
            sb.appendLine("```")
            for ((pincode, stats) in analytics.perPincode) {
                sb.appendLine("$pincode: ${stats.lastStatus}")
                sb.appendLine("  Checks:${stats.totalChecks} In:${stats.inStockCount} Out:${stats.outOfStockCount} Err:${stats.errors}")
            }
            sb.appendLine("```")
        }

        return sb.toString()
    }

    private fun buildCheckReply(): String {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val pincodesStr = prefs.getString("pincodes", "411040,411045,411060") ?: "411040,411045,411060"
        val pincodes = pincodesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val results = StockChecker.checkPincodes(pincodes)
        CheckLog.addEntries(this, results)

        prefs.edit()
            .putLong("last_check_time", System.currentTimeMillis())
            .putString("last_check_status", results.joinToString("\n") {
                "${it.pincode}: ${if (it.inStock) "IN STOCK" else it.error ?: "Out of stock"}"
            })
            .apply()

        val sb = StringBuilder()
        sb.appendLine("🔍 *Immediate Check Results*")
        sb.appendLine()
        for (result in results) {
            val status = when {
                result.error != null -> "❌ ERROR: ${result.error}"
                result.inStock -> "✅ IN STOCK (inventory: ${result.inventory})"
                else -> "🔴 Out of stock"
            }
            sb.appendLine("*${result.pincode}:* $status")
        }

        val available = results.filter { it.inStock }
        if (available.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("🎮 *PS5 AVAILABLE!* [Buy Now](https://blinkit.com/prn/x/prid/763266)")
        }

        return sb.toString()
    }

    private fun buildPincodesReply(): String {
        val prefs = getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val pincodesStr = prefs.getString("pincodes", "411040,411045,411060") ?: "411040,411045,411060"
        val pincodes = pincodesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val sb = StringBuilder()
        sb.appendLine("📍 *Configured Pincodes*")
        sb.appendLine()
        pincodes.forEachIndexed { index, pincode ->
            sb.appendLine("${index + 1}. `$pincode`")
        }
        sb.appendLine()
        sb.appendLine("_Total: ${pincodes.size} pincodes_")
        return sb.toString()
    }

    private fun buildLogReply(): String {
        val entries = CheckLog.getRecentEntries(this, 10)

        if (entries.isEmpty()) {
            return "📋 *Recent Log*\n\nNo checks recorded yet."
        }

        val sb = StringBuilder()
        sb.appendLine("📋 *Recent Log (last 10)*")
        sb.appendLine()
        sb.appendLine("```")
        for (entry in entries) {
            val time = CheckLog.formatTime(entry.timestamp)
            val status = when {
                entry.error != null -> "ERR"
                entry.inStock -> "IN(${entry.inventory})"
                else -> "OUT"
            }
            sb.appendLine("$time | ${entry.pincode} | $status")
        }
        sb.appendLine("```")
        return sb.toString()
    }

    private fun buildHelpReply(): String {
        return """
            🤖 *PS5 Stock Tracker Commands*
            
            /status - Check if monitoring is active
            /analytics - View check statistics
            /check - Run immediate stock check
            /pincodes - List configured pincodes
            /log - View last 10 check results
            /help - Show this message
        """.trimIndent()
    }

    private fun sendReply(botToken: String, chatId: String, text: String) {
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
            }
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram Commands",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listens for Telegram bot commands"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PS5 Tracker")
            .setContentText("Listening for Telegram commands")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
