package ru.screenmirror.sender

import android.Manifest\nimport android.content.pm.PackageManager\nimport android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private val projectionRequest = 4102
    private val overlayRequest = 4103\n    private val audioPermissionRequest = 4104
    private var discovery: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad) }
        layout.addView(TextView(this).apply { text = "ScreenMirror RU"; textSize = 28f; gravity = Gravity.CENTER }, ViewGroup.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply { text = "Телефон и телевизор должны быть подключены к одной сети Wi-Fi."; textSize = 16f; setPadding(0, pad, 0, pad) })
        address = EditText(this).apply { hint = "IP-адрес телевизора"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        layout.addView(address, ViewGroup.LayoutParams(-1, -2))
        layout.addView(Button(this).apply { text = "Найти телевизор автоматически"; setOnClickListener { discoverTv() } })
        layout.addView(Button(this).apply { text = "Сканировать QR-код"; setOnClickListener { IntentIntegrator(this@MainActivity).setPrompt("Наведите камеру на QR-код ScreenMirror RU на телевизоре").setBeepEnabled(false).initiateScan() } })
        layout.addView(Button(this).apply {
            text = "Начать трансляцию"
            setOnClickListener {
                if (address.text.toString().trim().isBlank()) { status.text = "Укажите IP или найдите телевизор автоматически"; return@setOnClickListener }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), overlayRequest)
                } else requestCapture()
            }
        })
        status = TextView(this).apply { text = "Готово к подключению"; textSize = 14f; setPadding(0, pad, 0, 0) }
        layout.addView(status)
        setContentView(layout)
    }

    private fun discoverTv() {
        try {
            discovery = getSystemService(NSD_SERVICE) as NsdManager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) { status.text = "Поиск ScreenMirror RU в сети…" }
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_screenmirror._tcp")) {
                        discovery?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { status.text = "Не удалось определить адрес телевизора" }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                @Suppress("DEPRECATION")\n                                val host = info.host?.hostAddress
                                if (!host.isNullOrBlank()) runOnUiThread { address.setText(host); status.text = "Телевизор найден: $host" }
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) { status.text = "Ошибка поиска телевизора"; discovery?.stopServiceDiscovery(this) }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                override fun onDiscoveryStopped(type: String) {}
            }
            discoveryListener = listener
            discovery?.discoverServices("_screenmirror._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) { status.text = "Автопоиск недоступен. Можно ввести IP вручную." }
    }

    @Deprecated("Activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            if (scan.contents?.startsWith("screenmirror://") == true) {
                val hostPort = scan.contents.removePrefix("screenmirror://")
                address.setText(hostPort.substringBefore(":"))
                status.text = "Телевизор подключён по QR-коду"
            } else if (scan.contents != null) status.text = "Это не QR-код ScreenMirror RU"
            return
        }
        if (requestCode == overlayRequest) {
            if (Settings.canDrawOverlays(this)) continueToCapture() else status.text = "Для плавающей панели разрешите показ поверх других приложений."
        } else if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MirrorService::class.java).apply {
                putExtra("ip", address.text.toString().trim())
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            startForegroundService(service)
            status.text = "Запуск ScreenMirror RU…"
        } else if (requestCode == projectionRequest) status.text = "Разрешение на трансляцию не выдано."
    }

    private fun continueToCapture() {\n        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {\n            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)\n        } else requestCapture()\n    }\n\n    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {\n        super.onRequestPermissionsResult(requestCode, permissions, grantResults)\n        if (requestCode == audioPermissionRequest) {\n            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()\n            else { status.text = "Доступ к микрофону не выдан: трансляция будет без звука."; requestCapture() }\n        }\n    }\n\n    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    override fun onDestroy() {
        try { discoveryListener?.let { discovery?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
