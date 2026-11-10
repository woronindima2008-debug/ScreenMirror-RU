package ru.screenmirror.receiver

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private val alive = AtomicBoolean(true)
    @Volatile private var renderSurface: Surface? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var decoder: MediaCodec? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF080B12.toInt()) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "ScreenMirror RU — ТВ\nОжидание подключения телефона…"
            textSize = 20f; gravity = Gravity.CENTER; setPadding(16, 12, 16, 12)
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        Thread({ listen() }, "screenmirror-listener").start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderSurface = holder.surface
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderSurface = holder.surface
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderSurface = null
    }

    private val surfaceLock = java.lang.Object()
    private fun awaitSurface(): Surface? {
        synchronized(surfaceLock) {
            var remaining = 20_000L
            while (alive.get() && (renderSurface == null || renderSurface?.isValid != true) && remaining > 0) {
                val before = System.currentTimeMillis()
                surfaceLock.wait(remaining)
                remaining -= (System.currentTimeMillis() - before).coerceAtLeast(1)
            }
            return renderSurface?.takeIf { it.isValid }
        }
    }

    private fun listen() {
        try {
            val listening = ServerSocket(8988)
            server = listening
            while (alive.get()) {
                val client = listening.accept()
                runOnUiThread { status.text = "Телефон подключён. Подготовка изображения…" }
                handle(client)
            }
        } catch (_: Exception) {
            if (alive.get()) runOnUiThread { status.text = "Не удалось запустить приём. Закройте и снова откройте ScreenMirror RU." }
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handle(peer: Socket) {
        peer.use { socket ->
            var codec: MediaCodec? = null
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream())
                val width = input.readInt()
                val height = input.readInt()
                require(width in 2..3840 && height in 2..2160) { "Некорректное разрешение потока" }
                val sps = readPacket(input)
                val pps = readPacket(input)
                val surface = awaitSurface() ?: throw IllegalStateException("Экран телевизора ещё не готов")
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder = codec
                codec.configure(format, surface, null, 0)
                codec.start()
                runOnUiThread { status.text = "ScreenMirror RU\nИдёт трансляция" }
                val info = MediaCodec.BufferInfo()
                while (alive.get() && !socket.isClosed) {
                    val packet = readPacket(input)
                    var inIndex = codec.dequeueInputBuffer(10_000)
                    while (inIndex < 0 && alive.get()) inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: throw IllegalStateException("Видеодекодер недоступен")
                        buffer.clear()
                        require(packet.size <= buffer.remaining()) { "Слишком большой видеокадр" }
                        buffer.put(packet)
                        codec.queueInputBuffer(inIndex, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                    var outIndex = codec.dequeueOutputBuffer(info, 0)
                    while (outIndex >= 0) {
                        codec.releaseOutputBuffer(outIndex, true)
                        outIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            } catch (e: Exception) {
                if (alive.get()) runOnUiThread { status.text = "Соединение завершено. Ожидание телефона…" }
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                if (decoder === codec) decoder = null
            }
        }
    }

    private fun readPacket(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..8_000_000) { "Некорректный размер пакета" }
        return ByteArray(size).also { input.readFully(it) }
    }

    override fun onDestroy() {
        alive.set(false)
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
        try { server?.close() } catch (_: Exception) {}
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
        super.onDestroy()
    }
}
