package ru.screenmirror.receiver

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
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
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var footer: LinearLayout
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.tv_pairing_bg)
            setPadding(dp(28), dp(10), dp(28), dp(14))
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER }
        header.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        brand.addView(TextView(this).apply { text = "ScreenMirror RU"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD) })
        brand.addView(TextView(this).apply { text = "Беспроводная трансляция экрана"; textSize = 14f; setTextColor(Color.WHITE) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        renderer = SurfaceViewRenderer(this)
        renderer.setBackgroundColor(Color.TRANSPARENT)
        renderer.visibility = View.INVISIBLE
        root.addView(renderer, LinearLayout.LayoutParams(-1, 0, 1f))

        footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        qr = ImageView(this).apply { adjustViewBounds = true; contentDescription = "QR-код для подключения телефона" }
        footer.addView(qr, LinearLayout.LayoutParams(dp(148), dp(148)))
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(20), 0, 0, 0)
            text = "Запуск приёмника…"
        }
        footer.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        egl = EglBase.create()
        session = WebRtcReceiverSession(this, egl, renderer, { signaling.sendToPhone(it) }) { message ->
            runOnUiThread {
                status.text = message
                val streaming = message.contains("идёт трансляция", true) || message.contains("видео подключено", true)
                renderer.visibility = if (streaming) View.VISIBLE else View.INVISIBLE
                renderer.setBackgroundColor(if (streaming) Color.BLACK else Color.TRANSPARENT)
                header.visibility = if (streaming) View.GONE else View.VISIBLE
                footer.visibility = if (streaming) View.GONE else View.VISIBLE
                if (streaming) {
                    root.setBackgroundColor(Color.BLACK)
                    root.setPadding(0, 0, 0, 0)
                } else {
                    root.setBackgroundResource(R.drawable.tv_pairing_bg)
                    root.setPadding(dp(28), dp(10), dp(28), dp(14))
                }
                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }
        audioReceiver = PlaybackAudioReceiver(token).also { it.start() }

        val ip = findLocalIpv4()
        if (ip == null) {
            status.text = "Не удалось определить IP телевизора. Подключите ТВ к Wi-Fi или Ethernet."
            return
        }
        showQr("$ip|8989|$token")
        status.text = "Ожидаю телефон…\n\nОткройте ScreenMirror RU на телефоне и выберите этот телевизор.\n\nКод: $token"

        signaling = TvSignalingServer(8989, token, { message ->
            try { session.handleSignal(JSONObject(message)) } catch (_: Exception) {
                runOnUiThread { status.text = "Ошибка обработки сообщения от телефона. Перезапустите подключение." }
            }
        }, { connected ->
            runOnUiThread {
                status.text = if (connected) {
                    "Телефон подключён!\nСогласовываем видеопоток…"
                } else {
                    "Ожидаю телефон…\n\nОткройте ScreenMirror RU на телефоне и выберите этот телевизор.\n\nКод: $token"
                }
            }
        })
        try {
            signaling.start()
            advertise(ip)
        } catch (e: Exception) {
            status.text = "Не удалось запустить приёмник: ${e.localizedMessage ?: "ошибка сети"}"
        }
    }

    private fun showQr(value: String) {
        try {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
            val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            for (x in 0 until 320) for (y in 0 until 320) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
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
                runOnUiThread { status.text = "Автопоиск недоступен (код $errorCode). Подключитесь по QR-коду." }
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
        if (::signaling.isInitialized) try { signaling.stop() } catch (_: Exception) {}
        if (::audioReceiver.isInitialized) try { audioReceiver.stop() } catch (_: Exception) {}
        if (::session.isInitialized) try { session.release() } catch (_: Exception) {}
        if (::egl.isInitialized) try { egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}