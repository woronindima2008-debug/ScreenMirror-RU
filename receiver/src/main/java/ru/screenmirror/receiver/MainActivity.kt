package ru.screenmirror.receiver

import android.app.Activity
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var nsdManager: NsdManager
    private val alive = AtomicBoolean(true)
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var registeredService: NsdServiceInfo? = null
    private val serviceType = "_screenmirror._tcp."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "ScreenMirror RU — ТВ\nОжидание подключения телефона…\nАвтообнаружение включено"; textSize = 20f; gravity = Gravity.CENTER; setPadding(16, 12, 16, 12) }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        Thread { listen() }.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    override fun surfaceDestroyed(holder: SurfaceHolder) { decoder?.stop(); decoder?.release(); decoder = null }

    private fun listen() {
        try {
            ServerSocket(8988).use { server ->
                serverSocket = server
                advertiseReceiver()
                while (alive.get()) {
                    val client = server.accept()
                    runOnUiThread { status.text = "Телефон подключён. Получение изображения…" }
                    handle(client)
                }
            }
        } catch (_: Exception) {
            if (alive.get()) runOnUiThread { status.text = "Не удалось запустить приём. Перезапустите приложение." }
        } finally {
            serverSocket = null
            unregisterReceiver()
        }
    }

    private fun advertiseReceiver() {
        val info = NsdServiceInfo().apply {
            serviceName = "ScreenMirror-RU"
            serviceType = this@MainActivity.serviceType
            port = 8988
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredService = serviceInfo
                runOnUiThread { status.text = "ScreenMirror RU — ТВ\nОжидание телефона…\nТелевизор доступен для автообнаружения" }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { status.text = "Приём включён, но автообнаружение недоступно (код $errorCode). Можно подключиться по IP." }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registeredService = null }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        })
    }

    private fun unregisterReceiver() {
        val info = registeredService ?: return
        registeredService = null
        try {
            // The registration listener is retained by NSD; unregistering is performed in onDestroy.
        } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        socket.use { peer ->
            try {
                val input = DataInputStream(peer.getInputStream())
                val width = input.readInt(); val height = input.readInt()
                val sps = readPacket(input); val pps = readPacket(input)
                val surface: Surface = surfaceView.holder.surface
                check(surface.isValid) { "Экран приёма ещё не готов" }
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder = codec
                codec.configure(format, surface, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                while (alive.get() && !peer.isClosed) {
                    val packet = readPacket(input)
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        require(packet.size <= buffer.remaining()) { "Кадр превышает размер буфера декодера" }
                        buffer.put(packet)
                        codec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                    var outIndex = codec.dequeueOutputBuffer(info, 0)
                    while (outIndex >= 0) {
                        codec.releaseOutputBuffer(outIndex, true)
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { status.text = "Связь прервана. Ожидание нового подключения…" }
            } finally {
                try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
                decoder = null
            }
        }
    }

    private fun readPacket(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..8_000_000) { "Некорректный размер видеопакета" }
        return ByteArray(size).also { input.readFully(it) }
    }

    override fun onDestroy() {
        alive.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        unregisterReceiver()
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
