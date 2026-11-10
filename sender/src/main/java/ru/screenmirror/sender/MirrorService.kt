package ru.screenmirror.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class MirrorService : Service() {
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        startForeground(7, notification())
        val ip = intent?.getStringExtra("ip") ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projectionData", Intent::class.java)
                   else @Suppress("DEPRECATION") intent.getParcelableExtra("projectionData")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        Thread { stream(ip, resultCode, data) }.start()
        return START_NOT_STICKY
    }

    private fun createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mirror", "ScreenMirror RU", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mirror") else Notification.Builder(this)
        return builder.setContentTitle("ScreenMirror RU")
            .setContentText("Идёт трансляция экрана")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun stream(ip: String, resultCode: Int, data: Intent) {
        try {
            socket = Socket().apply { connect(InetSocketAddress(ip, 8988), 8000); tcpNoDelay = true }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels.coerceAtMost(1920) / 2) * 2
            val height = (metrics.heightPixels.coerceAtMost(1080) / 2) * 2
            val density = metrics.densityDpi
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val inputSurface = encoder!!.createInputSurface()
            encoder!!.start()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            display = projection!!.createVirtualDisplay("ScreenMirrorRU", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null)
            running = true
            val out = DataOutputStream(socket!!.getOutputStream())
            out.writeInt(width); out.writeInt(height); out.flush()
            val info = MediaCodec.BufferInfo()
            var formatSent = false
            while (running) {
                val index = encoder!!.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = encoder!!.outputFormat
                        val sps = outputFormat.getByteBuffer("csd-0") ?: ByteBuffer.allocate(0)
                        val pps = outputFormat.getByteBuffer("csd-1") ?: ByteBuffer.allocate(0)
                        val a = ByteArray(sps.remaining()); sps.get(a)
                        val b = ByteArray(pps.remaining()); pps.get(b)
                        out.writeInt(a.size); out.write(a); out.writeInt(b.size); out.write(b); out.flush()
                        formatSent = true
                    }
                    index >= 0 -> {
                        val buffer = encoder!!.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && formatSent) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            out.writeInt(bytes.size); out.write(bytes)
                        }
                        encoder!!.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (_: Exception) {
            // Connection errors are reflected by the foreground notification ending.
        } finally {
            running = false
            try { display?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}