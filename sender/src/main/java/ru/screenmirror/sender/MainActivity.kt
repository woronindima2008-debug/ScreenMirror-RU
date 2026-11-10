package ru.screenmirror.sender

import android.app.Activity
import android.content.Intent
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
    private val projectionRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "Введите IP-адрес Android TV. Телефон и телевизор должны быть в одной сети Wi-Fi."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        address = EditText(this).apply {
            hint = "Например: 192.168.1.100"
            setSingleLine(true)
            setText("192.168.1.")
        }
        layout.addView(address, ViewGroup.LayoutParams(-1, -2))
        val start = Button(this).apply {
            text = "Начать трансляцию"
            setOnClickListener {
                val ip = address.text.toString().trim()
                if (ip.isBlank()) { status.text = "Укажите IP-адрес телевизора"; return@setOnClickListener }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
            }
        }
        layout.addView(start)
        status = TextView(this).apply {
            text = "На телевизоре откройте ScreenMirror RU — ТВ."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        layout.addView(status)
        setContentView(layout)
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
}