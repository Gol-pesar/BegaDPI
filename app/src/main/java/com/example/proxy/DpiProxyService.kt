package com.example.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

class DpiProxyService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    companion object {
        private const val TAG = "DpiProxyService"
        private const val CHANNEL_ID = "BegaDpiProxyChannel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        // Live connection stats and logs
        private val _statusFlow = MutableStateFlow(false)
        val statusFlow: StateFlow<Boolean> = _statusFlow.asStateFlow()

        private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
        val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

        private val _statsFlow = MutableStateFlow(ProxyStats())
        val statsFlow: StateFlow<ProxyStats> = _statsFlow.asStateFlow()

        // Configuration
        var targetHost: String = "45.131.211.76"
        var targetPort: Int = 443
        var localPort: Int = 40446
        var bypassProbability: Float = 0.10f
        var byteModificationProbability: Float = 0.20f

        fun addLog(message: String, type: LogType = LogType.INFO) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val entry = LogEntry(timestamp, message, type)
            val currentList = _logsFlow.value.toMutableList()
            currentList.add(0, entry) // Newest first
            if (currentList.size > 200) {
                currentList.removeAt(currentList.size - 1)
            }
            _logsFlow.value = currentList
            Log.d(TAG, "[$type] $message")
        }

        fun updateStats(bytesSent: Long = 0, bytesReceived: Long = 0, activeConnectionsDelta: Int = 0) {
            val current = _statsFlow.value
            _statsFlow.value = ProxyStats(
                bytesSent = current.bytesSent + bytesSent,
                bytesReceived = current.bytesReceived + bytesReceived,
                activeConnections = (current.activeConnections + activeConnectionsDelta).coerceAtLeast(0)
            )
        }

        fun clearLogsAndStats() {
            _logsFlow.value = emptyList()
            _statsFlow.value = ProxyStats()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startProxy()
        } else if (action == ACTION_STOP) {
            stopProxy()
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        if (isRunning) return
        isRunning = true
        _statusFlow.value = true

        val notification = createNotification("BegaDPI is running")
        startForeground(NOTIFICATION_ID, notification)

        addLog("🚀 DPI Bypass - Starting Service", LogType.INFO)
        addLog("📡 Local: 127.0.0.1:$localPort -> Target: $targetHost:$targetPort", LogType.INFO)
        addLog("🔄 Method: Random byte modification (first packet only)", LogType.INFO)

        serviceScope.launch {
            try {
                serverSocket = ServerSocket(localPort)
                addLog("✅ Local server bound to port $localPort", LogType.SUCCESS)

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    serviceScope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    addLog("❌ Server socket error: ${e.message}", LogType.ERROR)
                }
            } finally {
                stopProxyInternal()
            }
        }
    }

    private fun stopProxy() {
        addLog("🛑 Stopping proxy service", LogType.WARNING)
        stopProxyInternal()
        stopSelf()
    }

    private fun stopProxyInternal() {
        isRunning = false
        _statusFlow.value = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun handleClient(clientSocket: Socket) {
        val clientAddress = clientSocket.remoteSocketAddress.toString()
        addLog("🔗 Client connected: $clientAddress", LogType.INFO)
        updateStats(activeConnectionsDelta = 1)

        var targetSocket: Socket? = null
        try {
            targetSocket = Socket(targetHost, targetPort)
            addLog("✅ Connected to target: $targetHost:$targetPort", LogType.SUCCESS)

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            coroutineScope {
                val clientToTarget = launch(Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(65536)
                        var firstPacket = true
                        while (isActive) {
                            val bytesRead = clientIn.read(buffer)
                            if (bytesRead == -1) break

                            val chunk = buffer.copyOf(bytesRead)
                            val dataToSend = if (firstPacket) {
                                firstPacket = false
                                val modified = obfuscate(chunk)
                                if (!modified.contentEquals(chunk)) {
                                    addLog("🔀 Modified first packet (DPI circumvention applied)", LogType.SUCCESS)
                                } else {
                                    addLog("ℹ️ No modification applied to first packet", LogType.INFO)
                                }
                                modified
                            } else {
                                chunk
                            }

                            targetOut.write(dataToSend)
                            targetOut.flush()
                            updateStats(bytesSent = dataToSend.size.toLong())
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Client to Target connection closed: ${e.message}")
                    } finally {
                        try { targetSocket?.shutdownOutput() } catch (e: Exception) {}
                    }
                }

                val targetToClient = launch(Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(65536)
                        while (isActive) {
                            val bytesRead = targetIn.read(buffer)
                            if (bytesRead == -1) break
                            clientOut.write(buffer, 0, bytesRead)
                            clientOut.flush()
                            updateStats(bytesReceived = bytesRead.toLong())
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Target to Client connection closed: ${e.message}")
                    } finally {
                        try { clientSocket.shutdownOutput() } catch (e: Exception) {}
                    }
                }

                // Wait for either direction to finish
                joinAll(clientToTarget, targetToClient)
            }

        } catch (e: Exception) {
            addLog("❌ Connection error: ${e.message}", LogType.ERROR)
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
            try { targetSocket?.close() } catch (e: Exception) {}
            updateStats(activeConnectionsDelta = -1)
            addLog("🔌 Client disconnected: $clientAddress", LogType.INFO)
        }
    }

    private fun obfuscate(data: ByteArray): ByteArray {
        if (data.size < 50 || data[0] != 0x16.toByte() || data[1] != 0x03.toByte()) {
            return data
        }

        // Apply bypass with configured probability
        if (Random.nextFloat() < bypassProbability) {
            val modifiedData = data.clone()
            var modified = false
            val endIdx = minOf(60, modifiedData.size - 2)
            for (i in 40 until endIdx) {
                // If current byte is 0x00 and next byte is 0x20 (space) or 0x00, we consider changing it
                if (modifiedData[i] == 0x00.toByte() && (modifiedData[i + 1] == 0x20.toByte() || modifiedData[i + 1] == 0x00.toByte())) {
                    if (Random.nextFloat() < byteModificationProbability) {
                        modifiedData[i] = Random.nextInt(1, 256).toByte()
                        modifiedData[i + 1] = Random.nextInt(1, 256).toByte()
                        modified = true
                    }
                }
            }
            if (modified) {
                return modifiedData
            }
        }
        return data
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BegaDPI Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call) // Safe fallback icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BegaDPI Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for local DPI proxy background service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyInternal()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

data class LogEntry(
    val timestamp: String,
    val message: String,
    val type: LogType
)

enum class LogType {
    INFO, SUCCESS, WARNING, ERROR
}

data class ProxyStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val activeConnections: Int = 0
)
