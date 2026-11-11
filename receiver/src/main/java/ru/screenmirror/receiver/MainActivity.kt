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
import android.os.Build
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
    private lateinit var backgroundView: ImageView
    private lateinit var backgroundShade: View
    private lateinit var status: TextView
    private lateinit var pausedLayer: TextView
    private lateinit var qrView: ImageView
    private lateinit var pairingPanel: View
    private lateinit var rootLayout: FrameLayout
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


    @Volatile private var streamActive = false
    @Volatile private var activityVisible = false

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        if (streamActive) {
            // Never place decorative artwork behind a live stream.
            backgroundView.visibility = View.GONE
            backgroundShade.visibility = View.GONE
            surfaceView.visibility = View.VISIBLE
            pairingPanel.visibility = View.GONE
            status.visibility = View.GONE
            pausedLayer.visibility = if (videoPaused) View.VISIBLE else View.GONE
        } else {
            backgroundView.visibility = View.VISIBLE
            backgroundShade.visibility = View.VISIBLE
            surfaceView.visibility = View.GONE
            pairingPanel.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            pausedLayer.visibility = View.GONE
        }
    }

    override fun onPause() {
        activityVisible = false
        pairingPanel.visibility = View.GONE
        status.visibility = View.GONE
        pausedLayer.visibility = View.GONE
        super.onPause()
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        rootLayout = root
        backgroundView = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.tv_pairing_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Фон DmitryTranslation"
        }
        root.addView(backgroundView, FrameLayout.LayoutParams(-1, -1))
        backgroundShade = View(this).apply { setBackgroundColor(0x33030A18) }
        root.addView(backgroundShade, FrameLayout.LayoutParams(-1, -1))
        surfaceView = SurfaceView(this).apply { visibility = View.GONE }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        pausedLayer = TextView(this@MainActivity).apply {
            text = "Передача приостановлена"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF080B12.toInt())
            visibility = View.GONE
        }
        root.addView(pausedLayer, FrameLayout.LayoutParams(-1, -1))
        qrView = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(14, 14, 14, 14); setBackgroundColor(Color.WHITE) }
        status = TextView(this@MainActivity).apply {
            text = "DmitryTranslation\nОжидание подключения телефона…"
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFEAF0FA.toInt())
            setPadding(24, 12, 24, 12)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val info = TextView(this@MainActivity).apply {
            text = "ОТСКАНИРУЙТЕ QR-КОД НА ТЕЛЕФОНЕ\nОба устройства должны быть в одной сети Wi-Fi"
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFEAF0FA.toInt())
            setPadding(20, 10, 20, 10)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Эмблема DmitryTranslation"
            }, android.widget.LinearLayout.LayoutParams(76, 76))
            addView(TextView(this@MainActivity).apply {
                text = "DmitryTranslation"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(0xFFF4F8FF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setShadowLayer(14f, 0f, 0f, 0xFF398DFF.toInt())
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@MainActivity).apply {
                text = "КОСМИЧЕСКАЯ СТАНЦИЯ  •  БЕСПРОВОДНОЕ ПОДКЛЮЧЕНИЕ"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFFBBD7FF.toInt())
                setPadding(0, 4, 0, 12)
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(qrView, android.widget.LinearLayout.LayoutParams(260, 260))
            addView(info, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        pairingPanel = column
        root.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = 24; rightMargin = 24; bottomMargin = 52 })
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
                serviceName = "DmitryTranslation"
                serviceType = "_screenmirror._tcp."
                port = 8988
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { android.util.Log.w("DmitryTranslation", "NSD registration failed: $errorCode") }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            registration = listener
            nsdManager?.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) { android.util.Log.w("DmitryTranslation", "Discovery unavailable", e) }
        val ip = localIpv4()
        if (ip != null) {
            try {
                val matrix = MultiFormatWriter().encode("screenmirror://$ip:8988", BarcodeFormat.QR_CODE, 420, 420)
                val pixels = IntArray(420 * 420)
                for (y in 0 until 420) for (x in 0 until 420) pixels[y * 420 + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                qrView.setImageBitmap(Bitmap.createBitmap(pixels, 420, 420, Bitmap.Config.ARGB_8888))
                status.text = "DmitryTranslation\nАдрес телевизора: $ip"
            } catch (e: Exception) { android.util.Log.w("DmitryTranslation", "QR generation failed", e) }
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

    private fun fitSurfaceToAspect(videoWidth: Int, videoHeight: Int) {
        val ready = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try {
                val screenWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val screenHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                // Fit the complete phone frame inside the TV screen. Keep the source aspect ratio;
                // the root background is black, so only the smallest required letterbox bars remain.
                val scale = minOf(
                    screenWidth.toFloat() / videoWidth.toFloat(),
                    screenHeight.toFloat() / videoHeight.toFloat()
                )
                val fittedWidth = (videoWidth * scale).toInt().coerceAtLeast(2)
                val fittedHeight = (videoHeight * scale).toInt().coerceAtLeast(2)
                surfaceView.holder.setFixedSize(videoWidth, videoHeight)
                surfaceView.layoutParams = FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER)
            } finally {
                ready.countDown()
            }
        }
        ready.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

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
                                2 -> { audioEnabled = true; runOnUiThread { if (activityVisible) status.text = "DmitryTranslation\\nЗвук включён" } }
                                3 -> { audioEnabled = false; runOnUiThread { if (activityVisible) status.text = "DmitryTranslation\\nЗвук выключен" } }
                                4 -> { videoPaused = false; runOnUiThread { if (activityVisible) { pausedLayer.visibility = View.GONE; status.visibility = View.GONE } } }
                                5 -> { videoPaused = true; runOnUiThread { if (activityVisible) { pausedLayer.visibility = View.VISIBLE; status.visibility = View.GONE } } }
                            }
                        }
                    }
                }, "screenmirror-control-client").start()
            }
        } catch (e: Exception) { if (alive.get()) android.util.Log.e("DmitryTranslation", "control listener", e) }
    }

    private fun listenVideo() {
        try {
            server = ServerSocket(8988)
            while (alive.get()) {
                val client = server!!.accept()
                streamActive = true
                runOnUiThread {
                    backgroundView.visibility = View.GONE
                    backgroundShade.visibility = View.GONE
                    surfaceView.visibility = View.VISIBLE
                    if (activityVisible) { pairingPanel.visibility = View.GONE; status.visibility = View.GONE }
                }
                handle(client)
            }
        } catch (e: Exception) { if (alive.get()) runOnUiThread { if (activityVisible) status.text = "Не удалось запустить приём. Перезапустите DmitryTranslation." } }
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
                fitSurfaceToAspect(width, height)
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
                runOnUiThread { status.visibility = View.GONE }
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
                streamActive = false
                if (alive.get()) runOnUiThread {
                    if (activityVisible) {
                        backgroundView.visibility = View.VISIBLE
                        backgroundShade.visibility = View.VISIBLE
                        surfaceView.visibility = View.GONE
                        pairingPanel.visibility = View.VISIBLE
                        status.visibility = View.VISIBLE
                        status.text = "Соединение завершено. Ожидание телефона…"
                    }
                }
            } finally {
                streamActive = false
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                if (decoder === codec) decoder = null
                if (alive.get()) runOnUiThread { if (activityVisible) {
                    backgroundView.visibility = View.VISIBLE
                    backgroundShade.visibility = View.VISIBLE
                    surfaceView.visibility = View.GONE
                    pairingPanel.visibility = View.VISIBLE
                    status.visibility = View.VISIBLE
                    status.text = "DmitryTranslation\nОжидание подключения телефона…"
                } }
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
        } catch (e: Exception) { if (alive.get()) android.util.Log.e("DmitryTranslation", "audio listener", e) }
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
        } catch (e: Exception) { if (alive.get()) android.util.Log.w("DmitryTranslation", "Audio stream ended: ${e.message}") }
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
