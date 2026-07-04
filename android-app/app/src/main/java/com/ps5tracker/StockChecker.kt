package com.ps5tracker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object StockChecker {

    private const val API_URL = "https://blinkit.com/v1/layout/product/763266"
    private const val PRODUCT_NAME = "PlayStation 5 Gaming Console Standard Edition E Chassis (White)"

    private val KNOWN_COORDS = mutableMapOf(
        "411040" to Pair("18.5992", "73.927"),
        "411045" to Pair("18.5599", "73.7793"),
        "411060" to Pair("18.4765", "73.9098")
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class StockResult(
        val pincode: String,
        val inStock: Boolean,
        val inventory: Int,
        val error: String? = null
    )

    fun checkPincodes(pincodes: List<String>): List<StockResult> {
        return pincodes.map { pincode ->
            val coords = getCoordinates(pincode)
            if (coords != null) {
                checkPincode(pincode, coords.first, coords.second)
            } else {
                StockResult(pincode, false, 0, "Could not geocode pincode")
            }
        }
    }

    private fun getCoordinates(pincode: String): Pair<String, String>? {
        KNOWN_COORDS[pincode]?.let { return it }

        return try {
            val url = "https://nominatim.openstreetmap.org/search?postalcode=$pincode&country=India&format=json&limit=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "PS5StockTracker/1.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val results = JSONArray(body)
            if (results.length() > 0) {
                val result = results.getJSONObject(0)
                val lat = result.getString("lat")
                val lon = result.getString("lon")
                KNOWN_COORDS[pincode] = Pair(lat, lon)
                Pair(lat, lon)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkPincode(pincode: String, lat: String, lon: String): StockResult {
        return try {
            val request = Request.Builder()
                .url(API_URL)
                .post("".toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .header("Origin", "https://blinkit.com")
                .header("Referer", "https://blinkit.com/prn/x/prid/763266")
                .header("lat", lat)
                .header("lon", lon)
                .header("app_client", "consumer_web")
                .header("device_id", UUID.randomUUID().toString())
                .header("session_uuid", UUID.randomUUID().toString())
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 403) {
                return StockResult(pincode, false, 0, "HTTP 403 - blocked")
            }

            val body = response.body?.string() ?: return StockResult(pincode, false, 0, "Empty response")
            val json = JSONObject(body)

            if (!json.optBoolean("is_success", false)) {
                return StockResult(pincode, false, 0, "API returned failure")
            }

            val snippets = json.getJSONObject("response").getJSONArray("snippets")
            for (i in 0 until snippets.length()) {
                val data = snippets.getJSONObject(i).optJSONObject("data") ?: continue
                if (data.has("is_sold_out")) {
                    val isSoldOut = data.getBoolean("is_sold_out")
                    val inventory = data.optInt("inventory", 0)
                    return StockResult(pincode, !isSoldOut && inventory > 0, inventory)
                }
            }

            StockResult(pincode, false, 0, "Could not parse stock status")
        } catch (e: Exception) {
            StockResult(pincode, false, 0, e.message)
        }
    }

    fun sendTelegramAlert(botToken: String, chatId: String, pincodes: List<String>) {
        val pincodesText = pincodes.joinToString(", ")
        val message = """
            🎮 *PS5 IN STOCK!*
            
            *Product:* $PRODUCT_NAME
            *Available in pincode(s):* $pincodesText
            
            🔗 [Buy Now](https://blinkit.com/prn/x/prid/763266)
        """.trimIndent()

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", message)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().close()
        } catch (_: Exception) {}
    }
}
