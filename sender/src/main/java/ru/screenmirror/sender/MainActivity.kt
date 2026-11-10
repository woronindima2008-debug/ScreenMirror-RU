package ru.screenmirror.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Switch
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var tokenInput: EditText
    private lateinit var status: TextView
    private lateinit var quality: Spinner
    private lateinit var audioSwitch: Switch
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private var selectedToken = ""
    private val projectionRequest = 4102
    private val audioPermissionRequest = 4103
    private val serviceType = "_screenmirror._tcp."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "ScreenMirror RU"
            textSize = 28f
            gravity = Gravity.CENTER
        }, ViewGroup.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply {
            text = "Зеркалирование экрана на Android TV. Оба устройства должны быть в одной сети."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        address = EditText(this).apply { hint = "IP-адрес телевизора"; setSingleLine(true) }
        layout.addView(address, ViewGroup.LayoutParams(-1, -2))
        tokenInput = EditText(this).apply { hint = "Код сопряжения (из QR-кода)"; setSingleLine(true) }
        layout.addView(tokenInput, ViewGroup.LayoutParams(-1, -2))
        layout.addView(Button(this).apply {
            text = "Найти телевизор автоматически"
            setOnClickListener { discoverReceiver() }
        })
        layout.addView(Button(this).apply {
            text = "Сканировать QR-код телевизора"
            setOnClickListener {
                IntentIntegrator(this@MainActivity).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("Наведите камеру на QR-код ScreenMirror RU на телевизоре")
                    .setBeepEnabled(false).setOrientationLocked(false).initiateScan()
            }
        })
        layout.addView(TextView(this).apply { text = "Качество трансляции"; setPadding(0, pad, 0, 4) })
        quality = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Сбалансированное — до 720p / 30 кадров", "Высокое — до 1080p / 30 кадров", "Плавное — до 1080p / 60 кадров"))
        }
        layout.addView(quality, ViewGroup.LayoutParams(-1, -2))
        audioSwitch = Switch(this).apply {
            text = "Передавать звук приложений (Android 10+)"
            isChecked = true
            if (android.os.Build.VERSION.SDK_INT < 29) isEnabled = false
        }
        layout.addView(audioSwitch, ViewGroup.LayoutParams(-1, -2))
        layout.addView(Button(this).apply {
            text = "Начать трансляцию"
            setOnClickListener { requestAudioOrStart() }
        })
        layout.addView(Button(this).apply {
            text = "Остановить трансляцию"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MirrorService::class.java))
                status.text = "Трансляция остановлена."
            }
        })
        status = TextView(this).apply {
            text = "Откройте ScreenMirror RU — ТВ на телевизоре, затем найдите его или отсканируйте QR-код."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(status)
        setContentView(layout)
    }

    private fun discoverReceiver() {
        stopDiscovery()
        resolving = false
        status.text = "Поиск телевизора в локальной сети…"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_screenmirror._tcp", true) || resolving) return
                resolving = true
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving = false }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val host = serviceInfo.host?.hostAddress
                        val token = serviceInfo.attributes["token"]?.toString(Charsets.UTF_8)
                        if (!host.isNullOrBlank() && !token.isNullOrBlank()) {
                            runOnUiThread {
                                address.setText(host)
                                tokenInput.setText(token)
                                selectedToken = token
                                status.text = "Телевизор найден: $host. Можно запускать трансляцию."
                                stopDiscovery()
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
                runOnUiThread { status.text = "Не удалось начать поиск телевизора (код $errorCode)." }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }
        discoveryListener = listener
        try { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) {
            discoveryListener = null
            status.text = "Ошибка поиска: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }

    private fun requestAudioOrStart() {
        if (audioSwitch.isChecked && android.os.Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
        } else startProjection()
    }

    private fun startProjection() {
        val ip = address.text.toString().trim()
        val token = tokenInput.text.toString().trim().ifBlank { selectedToken }
        if (ip.isBlank() || token.isBlank()) {
            status.text = "Сначала найдите телевизор автоматически или отсканируйте его QR-код."
            return
        }
        selectedToken = token
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    private fun startSenderService(resultCode: Int, data: Intent) {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val maxDimension = if (quality.selectedItemPosition == 0) 1280 else 1920
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
        val fps = if (quality.selectedItemPosition == 2) 60 else 30
        val service = Intent(this, MirrorService::class.java).apply {
            putExtra("ip", address.text.toString().trim())
            putExtra("token", selectedToken)
            putExtra("resultCode", resultCode)
            putExtra("projectionData", data)
            putExtra("width", width.coerceAtLeast(2))
            putExtra("height", height.coerceAtLeast(2))
            putExtra("fps", fps)
            putExtra("audioEnabled", audioSwitch.isChecked &&
                android.os.Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        status.text = "Запрашиваем подключение к телевизору…"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                audioSwitch.isChecked = false
                status.text = "Разрешение на звук не выдано. Продолжим трансляцию без звука."
            }
            startProjection()
        }
    }

    @Deprecated("Activity result API retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            if (scan.contents != null) {
                val parts = scan.contents.split("|")
                if (parts.size == 3 && parts[1] == "8989" && parts[2].isNotBlank()) {
                    address.setText(parts[0])
                    tokenInput.setText(parts[2])
                    selectedToken = parts[2]
                    status.text = "QR-код распознан. Телевизор: ${parts[0]}"
                } else status.text = "Это не QR-код ScreenMirror RU."
            } else status.text = "Сканирование QR-кода отменено."
            return
        }
        if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            startSenderService(resultCode, data)
        } else if (requestCode == projectionRequest) status.text = "Трансляция не запущена: разрешение не выдано."
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
