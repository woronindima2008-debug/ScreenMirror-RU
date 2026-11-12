package ru.screenmirror.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    private val overlayRequest = 4103
    private val audioPermissionRequest = 4104
    private var discovery: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun emberSurface(radius: Float = 18f, fill: Int = 0xEE160A06.toInt()) = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xF52A1008.toInt(), fill, 0xF5090706.toInt())
        ).apply { cornerRadius = radius * resources.displayMetrics.density; setStroke((1.5f * resources.displayMetrics.density).toInt(), 0xFFFF6A25.toInt()) }
        fun emberButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setTextColor(0xFFFFE2BF.toInt()); textSize = 15f
            background = emberSurface(14f, 0xFF281006.toInt()); isAllCaps = false
            setPadding(pad, pad / 2, pad, pad / 2); elevation = 5f
            setOnClickListener { action() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = emberSurface(24f, 0xEE120905.toInt())
            elevation = 12f
        }
        layout.addView(TextView(this).apply {
            text = "✦ DmitryTranslation"
            textSize = 28f
            setTextColor(0xFFFFE8D5.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, ViewGroup.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply {
            text = "КОСМИЧЕСКАЯ СТАНЦИЯ  •  БЕСПРОВОДНАЯ ПЕРЕДАЧА"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFA24A.toInt())
            setPadding(0, pad, 0, pad)
        })
        address = EditText(this).apply {
            setTextColor(0xFFFFE8D5.toInt())
            setHintTextColor(0xFFD6A477.toInt())
            background = emberSurface(12f, 0xFF1B0D07.toInt())
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            hint = "IP-адрес телевизора"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(address, ViewGroup.LayoutParams(-1, -2))
        layout.addView(emberButton("Найти телевизор автоматически") { discoverTv() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        layout.addView(emberButton("Сканировать QR-код") {
                IntentIntegrator(this@MainActivity)
                    .setPrompt("Наведите камеру на QR-код DmitryTranslation на телевизоре")
                    .setBeepEnabled(false)
                    .initiateScan()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        layout.addView(emberButton("Начать трансляцию") {
                if (address.text.toString().trim().isBlank()) {
                    status.text = "Укажите IP или найдите телевизор автоматически"
                    return@emberButton
                }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        overlayRequest
                    )
                } else continueToCapture()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
        status = TextView(this).apply {
            text = "Готово к подключению"
            textSize = 14f
            setTextColor(0xFFFFB978.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        layout.addView(status)
        val root = android.widget.FrameLayout(this)
        val backdrop = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.cosmic_background)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        root.addView(backdrop, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(android.view.View(this).apply { setBackgroundColor(0x99000000.toInt()) }, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(layout, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun discoverTv() {
        try {
            discovery = getSystemService(NSD_SERVICE) as NsdManager
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    runOnUiThread { status.text = "Поиск DmitryTranslation в сети…" }
                }
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType.contains("_screenmirror._tcp")) {
                        discovery?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                runOnUiThread { status.text = "Не удалось определить адрес телевизора" }
                            }
                            @Suppress("DEPRECATION")
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress
                                if (!host.isNullOrBlank()) runOnUiThread {
                                    address.setText(host)
                                    status.text = "Телевизор найден: $host"
                                }
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    runOnUiThread { status.text = "Ошибка поиска телевизора" }
                    try { discovery?.stopServiceDiscovery(this) } catch (_: Exception) {}
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                override fun onDiscoveryStopped(type: String) {}
            }
            discoveryListener = listener
            discovery?.discoverServices("_screenmirror._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            status.text = "Автопоиск недоступен. Можно ввести IP вручную."
        }
    }

    @Deprecated("Activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            if (scan.contents?.startsWith("screenmirror://") == true) {
                val hostPort = scan.contents.removePrefix("screenmirror://")
                address.setText(hostPort.substringBefore(":"))
                status.text = "Телевизор выбран по QR-коду"
            } else if (scan.contents != null) status.text = "Это не QR-код DmitryTranslation"
            return
        }
        if (requestCode == overlayRequest) {
            if (Settings.canDrawOverlays(this)) continueToCapture()
            else status.text = "Для плавающей панели разрешите показ поверх других приложений."
        } else if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MirrorService::class.java).apply {
                putExtra("ip", address.text.toString().trim())
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            startForegroundService(service)
            status.text = "Запуск DmitryTranslation…"
        } else if (requestCode == projectionRequest) status.text = "Разрешение на трансляцию не выдано."
    }

    private fun continueToCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
        } else requestCapture()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                status.text = "Доступ к звуку не выдан: трансляция продолжится без звука."
            }
            requestCapture()
        }
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    override fun onDestroy() {
        try { discoveryListener?.let { discovery?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
