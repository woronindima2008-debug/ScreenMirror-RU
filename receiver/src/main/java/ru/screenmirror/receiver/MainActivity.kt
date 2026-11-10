package ru.screenmirror.receiver

import android.app.Activity
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var nsd: NsdManager
    private lateinit var egl: EglBase
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var session: WebRtcReceiverSession
    private lateinit var signaling: TvSignalingServer
    private lateinit var audioReceiver: PlaybackAudioReceiver
    private var registration: NsdManager.RegistrationListener? = null
    private val serviceType = "_screenmirror._tcp."
    private val token = UUID.randomUUID().toString().replace("-", "").take(8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        renderer = SurfaceViewRenderer(this)
        root.addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 12, 24, 12) }
        qr = ImageView(this).apply { adjustViewBounds = true }
        footer.addView(qr, LinearLayout.LayoutParams(180, 180))
        status = TextView(this).apply { textSize = 20f; setPadding(24, 0, 0, 0); text = "Запуск ScreenMirror RU…" }
        footer.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        egl = EglBase.create()
        session = WebRtcReceiverSession(this, egl, renderer, { signaling.sendToPhone(it) }) { message ->
            runOnUiThread { status.text = message }
        }
        audioReceiver = PlaybackAudioReceiver(token).also { it.start() }

        val ip = findLocalIpv4()
        if (ip == null) {
            status.text = "Не удалось определить IP телевизора. Подключите ТВ к Wi-Fi или Ethernet."
            return
        }
        showQr("$ip|8989|$token")
        status.text = "ScreenMirror RU — ТВ\nСканируйте QR-код телефоном или используйте автоматический поиск.\nКод: $token"

        signaling = TvSignalingServer(8989, token, { message ->
            try { session.handleSignal(JSONObject(message)) } catch (_: Exception) {
                runOnUiThread { status.text = "Получено некорректное сообщение от телефона." }
            }
        }, { connected ->
            runOnUiThread { if (connected) status.text = "Телефон подключён. Согласование видеопотока…" else status.text = "Ожидание подключения телефона…\nКод: $token" }
        })
        try {
            signaling.start()
            advertise(ip)
        } catch (e: Exception) {
            status.text = "Ошибка запуска приёмника: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }

    private fun showQr(value: String) {
        try {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
            val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            for (x in 0 until 320) for (y in 0 until 320) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            qr.setImageBitmap(bitmap)
        } catch (_: Exception) { status.text = "Не удалось создать QR-код." }
    }

    private fun advertise(ip: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "ScreenMirror-RU-TV"
            serviceType = this@MainActivity.serviceType
            port = 8989
            setAttribute("token", token)
            setAttribute("ip", ip)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { status.text = "Приём работает по QR-коду, но автообнаружение недоступно (код $errorCode)." }
                registration = null
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registration = null }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun findLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) return address.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        try { signaling.stop() } catch (_: Exception) {}
        try { audioReceiver.stop() } catch (_: Exception) {}
        try { session.release() } catch (_: Exception) {}
        try { egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
