package com.ps5tracker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class StockCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "StockCheckWorker"
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""

        if (botToken.isEmpty() || chatId.isEmpty()) {
            Log.w(TAG, "Telegram credentials not configured")
            return Result.success()
        }

        Log.i(TAG, "Starting stock check...")

        val results = StockChecker.checkAllPincodes()
        val availablePincodes = results.filter { it.inStock }.map { it.pincode }

        results.forEach { result ->
            when {
                result.error != null -> Log.w(TAG, "[${result.pincode}] ERROR: ${result.error}")
                result.inStock -> Log.i(TAG, "[${result.pincode}] IN STOCK! (inventory: ${result.inventory})")
                else -> Log.i(TAG, "[${result.pincode}] Out of stock")
            }
        }

        if (availablePincodes.isNotEmpty()) {
            Log.i(TAG, "ALERT: Stock available in ${availablePincodes.joinToString()}")
            StockChecker.sendTelegramAlert(botToken, chatId, availablePincodes)

            // Save last alert time
            prefs.edit().putLong("last_alert_time", System.currentTimeMillis()).apply()
        }

        // Save last check time
        prefs.edit()
            .putLong("last_check_time", System.currentTimeMillis())
            .putString("last_check_status", results.joinToString("\n") { "${it.pincode}: ${if (it.inStock) "IN STOCK" else it.error ?: "Out of stock"}" })
            .apply()

        return Result.success()
    }
}
