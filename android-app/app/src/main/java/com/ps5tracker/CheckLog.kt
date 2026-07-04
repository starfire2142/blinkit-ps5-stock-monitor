package com.ps5tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CheckLog {

    private const val LOG_FILE = "check_log.json"
    private const val MAX_ENTRIES = 200

    data class LogEntry(
        val timestamp: Long,
        val pincode: String,
        val inStock: Boolean,
        val inventory: Int,
        val error: String?,
        val success: Boolean
    )

    data class Analytics(
        val totalChecks: Int,
        val successfulChecks: Int,
        val failedChecks: Int,
        val inStockCount: Int,
        val outOfStockCount: Int,
        val lastCheckTime: Long,
        val avgIntervalMs: Long,
        val perPincode: Map<String, PincodeStats>
    )

    data class PincodeStats(
        val totalChecks: Int,
        val inStockCount: Int,
        val outOfStockCount: Int,
        val errors: Int,
        val lastInventory: Int,
        val lastStatus: String
    )

    fun addEntries(context: Context, results: List<StockChecker.StockResult>) {
        val log = loadLog(context)
        val now = System.currentTimeMillis()

        for (result in results) {
            val entry = JSONObject().apply {
                put("ts", now)
                put("pincode", result.pincode)
                put("in_stock", result.inStock)
                put("inventory", result.inventory)
                put("error", result.error ?: "")
                put("success", result.error == null)
            }
            log.put(entry)
        }

        while (log.length() > MAX_ENTRIES) {
            log.remove(0)
        }

        saveLog(context, log)
    }

    fun getAnalytics(context: Context): Analytics {
        val log = loadLog(context)
        if (log.length() == 0) {
            return Analytics(0, 0, 0, 0, 0, 0, 0, emptyMap())
        }

        var totalChecks = 0
        var successfulChecks = 0
        var failedChecks = 0
        var inStockCount = 0
        var outOfStockCount = 0
        var lastCheckTime = 0L
        val pincodeMap = mutableMapOf<String, MutableList<LogEntry>>()
        val checkTimes = mutableListOf<Long>()

        for (i in 0 until log.length()) {
            val obj = log.getJSONObject(i)
            val entry = LogEntry(
                timestamp = obj.getLong("ts"),
                pincode = obj.getString("pincode"),
                inStock = obj.getBoolean("in_stock"),
                inventory = obj.getInt("inventory"),
                error = obj.getString("error").ifEmpty { null },
                success = obj.getBoolean("success")
            )

            totalChecks++
            if (entry.success) successfulChecks++ else failedChecks++
            if (entry.inStock) inStockCount++ else outOfStockCount++
            if (entry.timestamp > lastCheckTime) lastCheckTime = entry.timestamp

            pincodeMap.getOrPut(entry.pincode) { mutableListOf() }.add(entry)
            checkTimes.add(entry.timestamp)
        }

        val uniqueTimes = checkTimes.distinct().sorted()
        val avgInterval = if (uniqueTimes.size > 1) {
            (uniqueTimes.last() - uniqueTimes.first()) / (uniqueTimes.size - 1)
        } else 0L

        val perPincode = pincodeMap.map { (pincode, entries) ->
            val lastEntry = entries.maxByOrNull { it.timestamp }!!
            pincode to PincodeStats(
                totalChecks = entries.size,
                inStockCount = entries.count { it.inStock },
                outOfStockCount = entries.count { !it.inStock && it.success },
                errors = entries.count { !it.success },
                lastInventory = lastEntry.inventory,
                lastStatus = when {
                    lastEntry.error != null -> "Error: ${lastEntry.error}"
                    lastEntry.inStock -> "IN STOCK (${lastEntry.inventory})"
                    else -> "Out of stock"
                }
            )
        }.toMap()

        return Analytics(
            totalChecks, successfulChecks, failedChecks,
            inStockCount, outOfStockCount, lastCheckTime, avgInterval, perPincode
        )
    }

    fun getRecentEntries(context: Context, limit: Int = 20): List<LogEntry> {
        val log = loadLog(context)
        val entries = mutableListOf<LogEntry>()
        val start = maxOf(0, log.length() - limit)
        for (i in start until log.length()) {
            val obj = log.getJSONObject(i)
            entries.add(LogEntry(
                timestamp = obj.getLong("ts"),
                pincode = obj.getString("pincode"),
                inStock = obj.getBoolean("in_stock"),
                inventory = obj.getInt("inventory"),
                error = obj.getString("error").ifEmpty { null },
                success = obj.getBoolean("success")
            ))
        }
        return entries.reversed()
    }

    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss dd-MMM", Locale.getDefault()).format(Date(timestamp))
    }

    fun clearLog(context: Context) {
        saveLog(context, JSONArray())
    }

    private fun loadLog(context: Context): JSONArray {
        val file = File(context.filesDir, LOG_FILE)
        return try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveLog(context: Context, log: JSONArray) {
        val file = File(context.filesDir, LOG_FILE)
        file.writeText(log.toString())
    }
}
