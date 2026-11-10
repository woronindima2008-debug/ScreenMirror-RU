package ru.screenmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import android.widget.TextView
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MirrorService : Service() {
    @Volatile private var running = false
    @Volatile private var videoEnabled = true
    @Volatile private var audioEnabled = true
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var panel: View? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        startForeground(7, notification())
        val ip = intent?.getStringExtra("ip") ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = projectionIntent(intent)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        thread(name = "screenmirror-video") { stream(ip, resultCode, data) }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projectionData", Intent::class.java)
        else intent.getParcelableExtra("projectionData")

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("mirror", "ScreenMirror RU", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mirror") else Notification.Builder(this)
        return builder.setContentTitle("ScreenMirror RU").setContentText("Идёт трансляция экрана")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    private fun createPanel() {
        if (Build.VERSION.SDK_INT < 26 || !Settings.canDrawOverlays(this)) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xEE101827.toInt())
        }
        val head = Button(this).apply { text = "ScreenMirror RU  ⋮"; setTextColor(Color.WHITE); setBackgroundColor(0xFF17345B.toInt()) }
        val sound = Button(this).apply { text = "♫ Звук: ВКЛ"; setTextColor(Color.WHITE) }
        val picture = Button(this).apply { text = "▣ Изображение: ВКЛ"; setTextColor(Color.WHITE) }
        val finish = Button(this).apply { text = "Завершить трансляцию"; setTextColor(Color.WHITE) }
        sound.setOnClickListener {
            audioEnabled = !audioEnabled
            sound.text = if (audioEnabled) "♫ Звук: ВКЛ" else "♫ Звук: ВЫКЛ"
            sendControl(if (audioEnabled) 2 else 3)
        }
        picture.setOnClickListener {
            videoEnabled = !videoEnabled
            picture.text = if (videoEnabled) "▣ Изображение: ВКЛ" else "▣ Изображение: ВЫКЛ"
            sendControl(if (videoEnabled) 4 else 5)
            if (videoEnabled) requestKeyFrame()
        }
        finish.setOnClickListener { stopSelf() }
        root.addView(head); root.addView(sound); root.addView(picture); root.addView(finish)
        var compact = false
        head.setOnClickListener {
            compact = !compact
            sound.visibility = if (compact) View.GONE else View.VISIBLE
            picture.visibility = if (compact) View.GONE else View.VISIBLE
            finish.visibility = if (compact) View.GONE else View.VISIBLE
            head.text = if (compact) "SM  •  Развернуть" else "ScreenMirror RU  ⋮"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 120 }
        head.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (downX - event.rawX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        try { wm?.updateViewLayout(root, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        panel = root
        try { wm?.addView(root, params) } catch (e: Exception) { android.util.Log.e("ScreenMirrorRU", "Overlay unavailable", e); panel = null }
    }

    private fun sendControl(command: Int) {
        thread {
            try { synchronized(this) { controlSocket?.getOutputStream()?.let { DataOutputStream(it).apply { writeInt(command); flush() } } } }
            catch (e: Exception) { android.util.Log.w("ScreenMirrorRU", "Control channel error", e) }
        }
    }

    private fun requestKeyFrame() {
        try { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        catch (_: Exception) {}
    }

    private fun stream(ip: String, resultCode: Int, data: Intent) {
        try {
            videoSocket = Socket().apply { connect(InetSocketAddress(ip, 8988), 8000); tcpNoDelay = true; sendBufferSize = 2 * 1024 * 1024 }
            controlSocket = Socket().apply { connect(InetSocketAddress(ip, 8989), 8000); tcpNoDelay = true }
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val sourceW = metrics.widthPixels.coerceAtLeast(2)
            val sourceH = metrics.heightPixels.coerceAtLeast(2)
            val scale = minOf(1f, 3840f / sourceW, 2160f / sourceH)
            val width = ((sourceW * scale).toInt() / 2) * 2
            val height = ((sourceH * scale).toInt() / 2) * 2
            val refresh = metricsRefreshRate()
            val fps = if (refresh >= 55f) 60 else 30
            val pixels = width.toLong() * height
            val bitrate = when {
                pixels >= 3840L * 2160L -> 30_000_000
                pixels >= 1920L * 1080L -> 16_000_000
                else -> 10_000_000
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder!!.createInputSurface()
            encoder!!.start()
            projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running = false; try { videoSocket?.close() } catch (_: Exception) {} }
            }, Handler(Looper.getMainLooper()))
            display = projection!!.createVirtualDisplay("ScreenMirrorRU", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null)
            running = true
            main.post { createPanel() }
            thread(name = "screenmirror-audio") { captureAudio(ip) }
            val out = DataOutputStream(videoSocket!!.getOutputStream())
            out.writeInt(width); out.writeInt(height); out.flush()
            val info = MediaCodec.BufferInfo()
            var formatSent = false
            while (running) {
                val index = encoder!!.dequeueOutputBuffer(info, 5000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = encoder!!.outputFormat
                        val sps = f.getByteBuffer("csd-0") ?: ByteBuffer.allocate(0)
                        val pps = f.getByteBuffer("csd-1") ?: ByteBuffer.allocate(0)
                        val a = ByteArray(sps.remaining()); sps.get(a)
                        val b = ByteArray(pps.remaining()); pps.get(b)
                        out.writeInt(a.size); out.write(a); out.writeInt(b.size); out.write(b); out.flush()
                        formatSent = true
                    }
                    index >= 0 -> {
                        val buffer = encoder!!.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && formatSent && videoEnabled) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            out.writeInt(bytes.size); out.write(bytes)
                        }
                        encoder!!.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) { android.util.Log.e("ScreenMirrorRU", "Video stream failed", e) }
        finally {
            running = false
            main.post { try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}; panel = null }
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
            try { display?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { videoSocket?.close(); controlSocket?.close(); audioSocket?.close() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun metricsRefreshRate(): Float = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate

    private var audioSocket: Socket? = null
    private fun captureAudio(ip: String) {
        if (Build.VERSION.SDK_INT < 29) return
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            audioSocket = Socket().apply { connect(InetSocketAddress(ip, 8990), 8000); tcpNoDelay = true }
            val mp = projection ?: return
            val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build()
            val audioFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(16384)
            record = AudioRecord.Builder().setAudioFormat(audioFormat).setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(config).build()
            audioRecord = record
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, min)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioEncoder = codec
            val out = DataOutputStream(audioSocket!!.getOutputStream())
            out.writeInt(48000); out.writeInt(2); out.flush()
            record.startRecording()
            val pcm = ByteArray(min)
            val info = MediaCodec.BufferInfo()
            var configSent = false
            while (running && !audioSocket!!.isClosed) {
                val n = record.read(pcm, 0, pcm.size)
                if (n > 0) {
                    val ix = codec.dequeueInputBuffer(10000)
                    if (ix >= 0) {
                        val input = codec.getInputBuffer(ix)!!
                        input.clear(); input.put(pcm, 0, n)
                        codec.queueInputBuffer(ix, 0, n, System.nanoTime() / 1000, 0)
                    }
                }
                var oi = codec.dequeueOutputBuffer(info, 0)
                while (oi >= 0) {
                    val buf = codec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val packet = ByteArray(info.size); buf.get(packet)
                        if (audioEnabled) { out.writeInt(packet.size); out.write(packet) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    oi = codec.dequeueOutputBuffer(info, 0)
                }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !configSent) {
                    val csd = codec.outputFormat.getByteBuffer("csd-0")
                    if (csd != null) {
                        val configBytes = ByteArray(csd.remaining()); csd.get(configBytes)
                        out.writeInt(configBytes.size); out.write(configBytes); out.flush()
                    } else { out.writeInt(0); out.flush() }
                    configSent = true
                }
            }
        } catch (e: Exception) { android.util.Log.w("ScreenMirrorRU", "Audio capture unavailable: ${e.message}") }
        finally {
            try { record?.stop(); record?.release() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            audioRecord = null; audioEncoder = null
            try { audioSocket?.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { videoSocket?.close(); controlSocket?.close(); audioSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
