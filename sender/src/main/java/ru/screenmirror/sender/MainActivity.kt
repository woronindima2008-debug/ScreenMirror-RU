package ru.screenmirror.sender

import android.app.Activity
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var nsdManager: NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private val projectionRequest = 4102
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
            text = "Телефон и телевизор должны быть подключены к одной сети Wi-Fi."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        address = EditText(this).apply {
            hint = "IP телевизора (или найдите автоматически)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(address, ViewGroup.LayoutParams(-1, -2))
        layout.addView(Button(this).apply {
            text = "Найти телевизор"
            setOnClickListener { discoverReceiver() }
        })
        layout.addView(Button(this).apply {
            text = "Начать трансляцию"
            setOnClickListener {
                val ip = address.text.toString().trim()
                if (ip.isBlank()) { status.text = "Сначала найдите телевизор или введите его IP-адрес"; return@setOnClickListener }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
            }
        })
        status = TextView(this).apply {
            text = "На телевизоре откройте ScreenMirror RU — ТВ."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(status)
        setContentView(layout)
    }

    private fun discoverReceiver() {
        stopDiscovery()
        status.text = "Поиск телевизора в локальной сети…"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.trimEnd('.') != serviceType.trimEnd('.')) return
                if (resolving) return
                resolving = true
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val host = serviceInfo.host?.hostAddress
                        if (!host.isNullOrBlank()) {
                            runOnUiThread {
                                address.setText(host)
                                status.text = "Телевизор найден: $host"
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
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discoveryListener = null
            status.text = "Ошибка поиска: ${e.localizedMessage ?: "неизвестная ошибка"}"
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    @Deprecated("Activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MirrorService::class.java).apply {
                putExtra("ip", address.text.toString().trim())
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            startForegroundService(service)
            status.text = "Разрешение получено. Подключение к телевизору…"
        } else if (requestCode == projectionRequest) {
            status.text = "Трансляция не запущена: разрешение не выдано."
        }
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }
}
