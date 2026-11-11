package ru.screenmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
    @Volatile private var audioSender: PlaybackAudioSender? = null
    @Volatile private var stopped = false
    @Volatile private var audioEnabled = true
    @Volatile private var videoEnabled = true
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var targetIp = ""
    @Volatile private var pairToken = ""
    private var egl: EglBase? = null
    private var panel: View? = null
    private var soundButton: Button? = null
    private var pictureButton: Button? = null
    private var windowManager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)
        val ip = intent?.getStringExtra("ip") ?: return fail("Не указан адрес телевизора")
        val token = intent.getStringExtra("token") ?: return fail("Не указан код сопряжения")
        val data = projectionIntent(intent) ?: return fail("Не получено разрешение на захват экрана")
        val width = intent.getIntExtra("width", 1280)
        val height = intent.getIntExtra("height", 720)
        val fps = intent.getIntExtra("fps", 30)
        val withAudio = intent.getBooleanExtra("audioEnabled", true)
        targetIp = ip
        pairToken = token
        audioEnabled = withAudio
        connect(ip, token, data, width, height, fps, withAudio)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projectionData", Intent::class.java)
        else intent.getParcelableExtra("projectionData")

    private fun connect(ip: String, token: String, data: Intent, width: Int, height: Int, fps: Int, withAudio: Boolean) {
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
                    projection = sender.mediaProjection()
                    if (withAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startAudioSender()
                    main.post { createPanel() }
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

    private fun startAudioSender() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !audioEnabled || stopped) return
        val activeProjection = projection ?: return
        try {
            PlaybackAudioSender(this, activeProjection, targetIp, pairToken).also { audioSender = it; it.start() }
        } catch (e: Exception) {
            updateStatus("Не удалось запустить передачу звука: ${e.localizedMessage ?: "ошибка"}")
        }
    }

    private fun setAudioEnabled(enabled: Boolean) {
        if (audioEnabled == enabled) return
        audioEnabled = enabled
        if (enabled) startAudioSender() else {
            try { audioSender?.stop() } catch (_: Exception) {}
            audioSender = null
        }
        sendControl("audio", enabled)
        soundButton?.text = if (enabled) "♫  Звук: ВКЛ" else "♫  Звук: ВЫКЛ"
        updateStatus(if (enabled) "Звук включён" else "Звук выключен")
    }

    private fun setVideoEnabled(enabled: Boolean) {
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        session?.setVideoEnabled(enabled)
        sendControl("video", enabled)
        pictureButton?.text = if (enabled) "▣  Изображение: ВКЛ" else "▣  Изображение: ВЫКЛ"
        updateStatus(if (enabled) "Передача изображения включена" else "Передача изображения приостановлена")
    }

    private fun sendControl(action: String, enabled: Boolean) {
        webSocket?.send(JSONObject().put("kind", "control").put("action", action).put("enabled", enabled).toString())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Restored from the earlier working overlay panel, with the current WebRTC controls wired in.
    private fun createPanel() {
        if (panel != null) return
        if (Build.VERSION.SDK_INT < 26 || !Settings.canDrawOverlays(this)) {
            updateStatus("Для плавающей панели разрешите DmitryTranslation отображение поверх других окон")
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xEE101827.toInt())
        }
        val head = Button(this).apply {
            text = "DmitryTranslation  ⋮"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF17345B.toInt())
            isAllCaps = false
        }
        val sound = Button(this).apply {
            text = if (audioEnabled) "♫  Звук: ВКЛ" else "♫  Звук: ВЫКЛ"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { setAudioEnabled(!audioEnabled) }
        }
        val picture = Button(this).apply {
            text = if (videoEnabled) "▣  Изображение: ВКЛ" else "▣  Изображение: ВЫКЛ"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { setVideoEnabled(!videoEnabled) }
        }
        soundButton = sound
        pictureButton = picture
        val finish = Button(this).apply {
            text = "Завершить трансляцию"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { stopSession() }
        }
        root.addView(head)
        root.addView(sound)
        root.addView(picture)
        root.addView(finish)
        var compact = false
        head.setOnClickListener {
            compact = !compact
            sound.visibility = if (compact) View.GONE else View.VISIBLE
            picture.visibility = if (compact) View.GONE else View.VISIBLE
            finish.visibility = if (compact) View.GONE else View.VISIBLE
            head.text = if (compact) "DT  •  Развернуть" else "DmitryTranslation  ⋮"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(20); y = dp(120) }
        head.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragged = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - downX) > dp(8) || kotlin.math.abs(event.rawY - downY) > dp(8)) dragged = true
                        params.x = startX + (downX - event.rawX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if (!dragged) head.performClick(); return true }
                }
                return false
            }
        })
        panel = root
        try { windowManager?.addView(root, params) } catch (e: Exception) {
            panel = null
            soundButton = null
            pictureButton = null
            android.util.Log.e("DmitryTranslation", "Не удалось показать плавающую панель", e)
            updateStatus("Не удалось открыть плавающую панель. Проверьте разрешение «Поверх других окон».")
        }
    }

    private fun updateStatus(message: String) {
        getSystemService(NotificationManager::class.java).notify(7, notification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("mirror", "DmitryTranslation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(message: String = "Подключение и передача экрана"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mirror") else Notification.Builder(this)
        return builder.setContentTitle("DmitryTranslation").setContentText(message)
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
        try { audioSender?.stop() } catch (_: Exception) {}
        audioSender = null
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { egl?.release() } catch (_: Exception) {}
        egl = null
        projection = null
        main.post {
            try { panel?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            panel = null
            soundButton = null
            pictureButton = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}