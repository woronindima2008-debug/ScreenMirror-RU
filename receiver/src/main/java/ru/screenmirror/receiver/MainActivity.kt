package ru.screenmirror.receiver

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var root: FrameLayout
    private lateinit var backgroundView: ImageView
    private lateinit var backgroundShade: View
    private lateinit var pairingContent: LinearLayout
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var pausedLayer: TextView
    private lateinit var nsd: NsdManager
    private lateinit var egl: EglBase
    private lateinit var session: WebRtcReceiverSession
    private lateinit var signaling: TvSignalingServer
    private lateinit var audioReceiver: PlaybackAudioReceiver
    private var registration: NsdManager.RegistrationListener? = null
    private val serviceType = "_screenmirror._tcp."
    private val token = UUID.randomUUID().toString().replace("-", "").take(8)
    @Volatile private var streaming = false
    @Volatile private var videoPaused = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        backgroundView = ImageView(this).apply {
            setImageResource(R.drawable.cosmic_background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Космический фон DmitryTranslation"
        }
        root.addView(backgroundView, FrameLayout.LayoutParams(-1, -1))
        backgroundShade = View(this).apply { setBackgroundColor(0x33030A18) }
        root.addView(backgroundShade, FrameLayout.LayoutParams(-1, -1))

        renderer = SurfaceViewRenderer(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(renderer, FrameLayout.LayoutParams(-1, -1))

        pausedLayer = TextView(this).apply {
            text = "Передача изображения приостановлена"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6080B12.toInt())
            visibility = View.GONE
            includeFontPadding = false
        }
        root.addView(pausedLayer, FrameLayout.LayoutParams(-1, -1))

        pairingContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(16), dp(30), dp(20))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "DmitryTranslation"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(76), dp(76)))
        header.addView(TextView(this).apply {
            text = "DmitryTranslation"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        header.addView(TextView(this).apply {
            text = "Беспроводная трансляция экрана"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF0FA.toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        pairingContent.addView(header, LinearLayout.LayoutParams(-1, -2))

        pairingContent.addView(TextView(this).apply {
            text = "Отсканируйте QR-код на телефоне"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))

        val qr = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = "QR-код для подключения телефона"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        pairingContent.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER })

        status = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(8))
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = "Запуск приёмника…"
        }
        pairingContent.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(pairingContent, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        egl = EglBase.create()
        session = WebRtcReceiverSession(this, egl, renderer, { signaling.sendToPhone(it) }) { message ->
            runOnUiThread {
                status.text = message
                val showVideo = message.contains("соединение установлено", true) ||
                    message.contains("видеодорожка подключена", true) ||
                    message.contains("первый видеокадр получен", true)
                if (showVideo) showStreamingUi()
                if (message.contains("Связь потеряна", true) || message.contains("Не удалось установить соединение", true)) {
                    videoPaused = false
                    showPairingUi()
                }
            }
        }
        audioReceiver = PlaybackAudioReceiver(token).also { it.start() }

        val ip = findLocalIpv4()
        if (ip == null) {
            status.text = "Не удалось определить IP телевизора. Подключите ТВ к Wi-Fi или Ethernet."
            return
        }
        showQr(qr, "$ip|8989|$token")
        status.text = "Ожидаю телефон…\n\nОткройте DmitryTranslation на телефоне и выберите этот телевизор.\n\nКод: $token"

        signaling = TvSignalingServer(8989, token, { message ->
            try {
                val json = JSONObject(message)
                if (json.optString("kind") == "control") handlePhoneControl(json)
                else session.handleSignal(json)
            } catch (_: Exception) {
                runOnUiThread { status.text = "Ошибка обработки сообщения от телефона. Перезапустите подключение." }
            }
        }, { connected ->
            runOnUiThread {
                if (connected) status.text = "Телефон подключён!\nСогласовываем видеопоток…"
                else {
                    videoPaused = false
                    status.text = "Ожидаю телефон…\n\nОткройте DmitryTranslation на телефоне и выберите этот телевизор.\n\nКод: $token"
                    showPairingUi()
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

    private fun showQr(qr: ImageView, value: String) {
        try {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320)
            val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            for (x in 0 until 320) for (y in 0 until 320) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            qr.setImageBitmap(bitmap)
        } catch (_: Exception) { status.text = "Не удалось создать QR-код." }
    }

    private fun showStreamingUi() {
        streaming = true
        backgroundView.visibility = View.GONE
        backgroundShade.visibility = View.GONE
        pairingContent.visibility = View.GONE
        renderer.visibility = View.VISIBLE
        renderer.setBackgroundColor(Color.BLACK)
        renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        pausedLayer.visibility = if (videoPaused) View.VISIBLE else View.GONE
    }

    private fun showPairingUi() {
        streaming = false
        backgroundView.visibility = View.VISIBLE
        backgroundShade.visibility = View.VISIBLE
        pairingContent.visibility = View.VISIBLE
        renderer.visibility = View.GONE
        pausedLayer.visibility = View.GONE
    }

    private fun handlePhoneControl(json: JSONObject) {
        val action = json.optString("action")
        val enabled = json.optBoolean("enabled", true)
        runOnUiThread {
            when (action) {
                "video" -> {
                    videoPaused = !enabled
                    if (streaming) {
                        pausedLayer.text = "Передача изображения приостановлена"
                        pausedLayer.visibility = if (enabled) View.GONE else View.VISIBLE
                    }
                }
                "audio" -> Toast.makeText(this, if (enabled) "Звук включён" else "Звук выключен", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun advertise(ip: String) {
        val info = NsdServiceInfo().apply {
            serviceName = "DmitryTranslation-TV"
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