package com.example.rnshello

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RnsService : Service() {

    private val binder = RnsBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val btService = BluetoothService()

    var isRnsStarted = false
        private set

    var myAddress = ""
        private set

    // Callbacks to notify bound activity
    var onRnsStarted: ((address: String) -> Unit)? = null
    var onRnsError:   ((error: String)   -> Unit)? = null

    inner class RnsBinder : Binder() {
        fun getService(): RnsService = this@RnsService
    }

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        startForeground(NOTIFICATION_ID, buildNotification("RNS Ready — radio listening"))
        Log.i(TAG, "RnsService created")
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // restart if killed by OS
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        btService.disconnect()
        Log.i(TAG, "RnsService destroyed")
    }

    // ── Connect + start RNS ────────────────────────────────────────────────────

    fun stopAndDisconnect() {
        btService.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun connectAndStart(address: String) {
        scope.launch {
            val connected = withContext(Dispatchers.IO) { btService.connect(address) }
            if (!connected) {
                onRnsError?.invoke("BT connection failed")
                return@launch
            }
            updateNotification("RNS Starting...")
            val addr = withContext(Dispatchers.IO) { RNSBridge.start(btService) }
            if (addr.startsWith("Error") || addr == "Timeout") {
                onRnsError?.invoke("RNS error: $addr")
                updateNotification("RNS Error — tap to retry")
            } else {
                isRnsStarted = true
                myAddress = addr
                updateNotification("RNS Active — $addr")
                onRnsStarted?.invoke(addr)
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RNS Hello")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RNS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps RNS radio running in background"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG           = "RnsService"
        private const val CHANNEL_ID    = "rns_service_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
