package com.ps5tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("ServiceRestart", "Restart alarm fired - ensuring service is running")

        val prefs = context.getSharedPreferences("ps5tracker", Context.MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""

        if (botToken.isEmpty() || chatId.isEmpty()) return

        val serviceIntent = Intent(context, TelegramCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
