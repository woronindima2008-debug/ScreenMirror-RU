package ru.screenmirror.receiver

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var pausedLayer: TextView
    private lateinit var qrView: ImageView
    private val alive = AtomicBoolean(true)
    @Volatile private var renderSurface: Surface? = null
    @Volatile private var videoPaused = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var controlServer: ServerSocket? = null
    @Volatile private var audioServer: ServerSocket? = null
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var audioEnabled = true
    private val surfaceLock = java.lang.Object()
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080B12.toInt()) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        pausedLayer = TextView(this).apply {
            text = "Передача приостановлена"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF080B12.toInt())
            visibility = View.GONE
        }
        root.addView(pausedLayer, FrameLayout.LayoutParams(-1, -1))
        qrView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply {
            text = "ScreenMirror RU\nОжидание подключения телефона…"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xAA080B12.toInt())
        }
        val info = TextView(this).apply {
            text = "Подключите телефон к этой сети Wi-Fi и отсканируйте QR-код"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA080B12.toInt())
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(qrView, android.widget.LinearLayout.LayoutParams(320, 320))
            addView(info, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        Thread({ listenVideo() }, "screenmirror-video-listener").start()
        Thread({ listenControl() }, "screenmirror-control-listener").start()
        Thread({ listenAudio() }, "screenmirror-audio-listener").start()
        registerDiscovery()
    }

    private fun registerDiscovery() {
        try {
            nsdManager = getSystemService(NSD_SERVICE) as NsdManager
            val service = NsdServiceInfo().apply {
                serviceName = "ScreenMirror RU"
                serviceType = "_screenmirror._tcp."
                port = 8988
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { android.util.Log.w("ScreenMirrorRU", "NSD registration failed: $errorCode") }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            registration = listener
            nsdManager?.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) { android.util.Log.w("ScreenMirrorRU", "Discovery unavailable", e) }
        val ip = localIpv4()
        if (ip != null) {
            try {
                val matrix = MultiFormatWriter().encode("screenmirror://$ip:8988", BarcodeFormat.QR_CODE, 420, 420)
                val pixels = IntArray(420 * 420)
                for (y in 0 until 420) for (x in 0 until 420) pixels[y * 420 + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                qrView.setImageBitmap(Bitmap.createBitmap(pixels, 420, 420, Bitmap.Config.ARGB_8888))
                status.text = "ScreenMirror RU\nАдрес телевизора: $ip"
            } catch (e: Exception) { android.util.Log.w("ScreenMirrorRU", "QR generation failed", e) }
        }
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    override fun surfaceCreated(holder: SurfaceHolder) { renderSurface = holder.surface; synchronized(surfaceLock) { surfaceLock.notifyAll() } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { renderSurface = holder.surface }
    override fun surfaceDestroyed(holder: SurfaceHolder) { renderSurface = null }

    private fun awaitSurface(): Surface? {
        synchronized(surfaceLock) {
            var remaining = 20000L
            while (alive.get() && (renderSurface == null || renderSurface?.isValid != true) && remaining > 0) {
                val before = System.currentTimeMillis()
                surfaceLock.wait(remaining)
                remaining -= (System.currentTimeMillis() - before).coerceAtLeast(1)
            }
            return renderSurface?.takeIf { it.isValid }
        }
    }

    private fun listenControl() {
        try {
            controlServer = ServerSocket(8989)
            while (alive.get()) {
                val peer = controlServer!!.accept()
                Thread({
                    peer.use { s ->
                        val input = DataInputStream(s.getInputStream())
                        while (alive.get()) {
                            when (input.readInt()) {
                                2 -> { audioEnabled = true; runOnUiThread { status.text = "ScreenMirror RU\nЗвук включён" } }
                                3 -> { audioEnabled = false; runOnUiThread { status.text = "ScreenMirror RU\nЗвук выключен" } }
                                4 -> { videoPaused = false; runOnUiThread { pausedLayer.visibility = View.GONE; surfaceView.visibility = View.VISIBLE; status.text = "ScreenMirror RU\nИзображение включено" } }
                                5 -> { videoPaused = true; runOnUiThread { surfaceView.visibility = View.INVISIBLE; pausedLayer.visibility = View.VISIBLE; status.visibility = View.GONE } }
                            }
                        }
                    }
                }, "screenmirror-control-client").start()
            }
        } catch (e: Exception) { if (alive.get()) android.util.Log.e("ScreenMirrorRU", "control listener", e) }
    }

    private fun listenVideo() {
        try {
            server = ServerSocket(8988)
            while (alive.get()) {
                val client = server!!.accept()
                runOnUiThread { qrView.visibility = View.GONE; status.visibility = View.VISIBLE; status.text = "Телефон подключён. Подготовка изображения…" }
                handle(client)
            }
        } catch (e: Exception) { if (alive.get()) runOnUiThread { status.text = "Не удалось запустить приём. Перезапустите ScreenMirror RU." } }
        finally { try { server?.close() } catch (_: Exception) {} }
    }

    private fun handle(peer: Socket) {
        peer.use { socket ->
            var codec: MediaCodec? = null
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream())
                val width = input.readInt()
                val height = input.readInt()
                require(width in 2..3840 && height in 2..2160)
                val sps = readPacket(input)
                val pps = readPacket(input)
                val surface = awaitSurface() ?: throw IllegalStateException("Поверхность ТВ не готова")
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder = codec
                codec.configure(format, surface, null, 0)
                codec.start()
                runOnUiThread { status.visibility = View.VISIBLE; status.text = "ScreenMirror RU\nИдёт трансляция" }
                val info = MediaCodec.BufferInfo()
                while (alive.get() && !socket.isClosed) {
                    val packet = readPacket(input)
                    val ix = codec.dequeueInputBuffer(10000)
                    if (ix >= 0) {
                        val buffer = codec.getInputBuffer(ix) ?: throw IllegalStateException("Видеодекодер недоступен")
                        buffer.clear()
                        require(packet.size <= buffer.remaining())
                        buffer.put(packet)
                        codec.queueInputBuffer(ix, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                    var out = codec.dequeueOutputBuffer(info, 0)
                    while (out >= 0) {
                        codec.releaseOutputBuffer(out, !videoPaused)
                        out = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            } catch (e: Exception) {
                if (alive.get()) runOnUiThread { status.visibility = View.VISIBLE; status.text = "Соединение завершено. Ожидание телефона…" }
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                if (decoder === codec) decoder = null
            }
        }
    }

    private fun listenAudio() {
        try {
            audioServer = ServerSocket(8990)
            while (alive.get()) {
                val peer = audioServer!!.accept()
                Thread({ decodeAudio(peer) }, "screenmirror-audio-client").start()
            }
        } catch (e: Exception) { if (alive.get()) android.util.Log.e("ScreenMirrorRU", "audio listener", e) }
    }

    private fun decodeAudio(peer: Socket) {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            peer.use { socket ->
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream())
                val sampleRate = input.readInt()
                val channels = input.readInt()
                val audioCsdSize = input.readInt()
                require(audioCsdSize in 0..4096)
                val audioCsd = ByteArray(audioCsdSize).also { if (audioCsdSize > 0) input.readFully(it) }
                require(sampleRate in 8000..96000 && channels in 1..2)
                val channelOut = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val min = AudioTrack.getMinBufferSize(sampleRate, channelOut, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelOut).build())
                    .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    if (audioCsd.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(audioCsd))
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, 0)
                codec.start()
                track.play()
                val info = MediaCodec.BufferInfo()
                while (alive.get() && !socket.isClosed) {
                    val size = input.readInt()
                    require(size in 1..65536)
                    val packet = ByteArray(size)
                    input.readFully(packet)
                    val ix = codec.dequeueInputBuffer(10000)
                    if (ix >= 0) {
                        val buf = codec.getInputBuffer(ix)!!
                        buf.clear()
                        buf.put(packet)
                        codec.queueInputBuffer(ix, 0, packet.size, System.nanoTime() / 1000, 0)
                    }
                    var out = codec.dequeueOutputBuffer(info, 0)
                    while (out >= 0) {
                        val pcm = codec.getOutputBuffer(out)
                        if (pcm != null && info.size > 0 && audioEnabled) {
                            val bytes = ByteArray(info.size)
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            pcm.get(bytes)
                            track.write(bytes, 0, bytes.size)
                        }
                        codec.releaseOutputBuffer(out, false)
                        out = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            }
        } catch (e: Exception) { if (alive.get()) android.util.Log.w("ScreenMirrorRU", "Audio stream ended: ${e.message}") }
        finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
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
        try { server?.close(); controlServer?.close(); audioServer?.close() } catch (_: Exception) {}
        try { nsdManager?.unregisterService(registration) } catch (_: Exception) {}
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
        super.onDestroy()
    }
}
