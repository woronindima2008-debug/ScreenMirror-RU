package ru.screenmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.EglBase
import java.util.concurrent.TimeUnit

class MirrorService : Service() {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var session: WebRtcSenderSession? = null
    @Volatile private var stopped = false
    private var egl: EglBase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, notification)
        }
        val ip = intent?.getStringExtra("ip") ?: return fail("Не указан адрес телевизора")
        val token = intent.getStringExtra("token") ?: return fail("Не указан код сопряжения")
        val data = projectionIntent(intent) ?: return fail("Не получено разрешение на захват экрана")
        val width = intent.getIntExtra("width", 1280)
        val height = intent.getIntExtra("height", 720)
        val fps = intent.getIntExtra("fps", 30)
        connect(ip, token, data, width, height, fps)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projectionData", Intent::class.java)
        else intent.getParcelableExtra("projectionData")

    private fun connect(ip: String, token: String, data: Intent, width: Int, height: Int, fps: Int) {
        val request = Request.Builder().url("ws://$ip:8989/?token=$token").build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (stopped) { ws.close(1000, "Отменено"); return }
                try {
                    val base = EglBase.create()
                    egl = base
                    val sender = WebRtcSenderSession(this@MirrorService, base, { msg -> webSocket?.send(msg) }) { updateStatus(it) }
                    session = sender
                    sender.start(data, width, height, fps)
                } catch (e: Exception) {
                    updateStatus("Ошибка запуска трансляции: ${e.localizedMessage ?: "неизвестная ошибка"}")
                    stopSession()
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try { session?.handleSignal(JSONObject(text)) } catch (_: Exception) { updateStatus("Получено некорректное сообщение от телевизора") }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { updateStatus("Телевизор завершил соединение"); stopSession() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                updateStatus("Не удалось подключиться к телевизору: ${t.localizedMessage ?: "проверьте Wi-Fi"}")
                stopSession()
            }
        })
    }

    private fun updateStatus(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(7, notification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("mirror", "ScreenMirror RU", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String = "Подключение и передача экрана") : Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mirror") else Notification.Builder(this)
        return builder.setContentTitle("ScreenMirror RU").setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    private fun fail(message: String): Int {
        updateStatus(message)
        stopSession()
        return START_NOT_STICKY
    }

    private fun stopSession() {
        if (stopped) return
        stopped = true
        try { webSocket?.close(1000, "Трансляция завершена") } catch (_: Exception) {}
        webSocket = null
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { egl?.release() } catch (_: Exception) {}
        egl = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
