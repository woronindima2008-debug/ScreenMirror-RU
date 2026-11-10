package ru.screenmirror.sender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
    private val overlayRequest = 4103

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(pad,pad,pad,pad) }
        layout.addView(TextView(this).apply { text = "ScreenMirror RU"; textSize = 28f; gravity = Gravity.CENTER }, ViewGroup.LayoutParams(-1,-2))
        layout.addView(TextView(this).apply { text = "Телефон и телевизор должны быть подключены к одной сети Wi-Fi. На телевизоре откройте ScreenMirror RU."; textSize = 16f; setPadding(0,pad,0,pad) })
        address = EditText(this).apply { hint = "IP-адрес телевизора"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        layout.addView(address, ViewGroup.LayoutParams(-1,-2))
        layout.addView(Button(this).apply {
            text = "Начать трансляцию"
            setOnClickListener {
                if (address.text.toString().trim().isBlank()) { status.text = "Укажите IP-адрес телевизора"; return@setOnClickListener }
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), overlayRequest)
                } else requestCapture()
            }
        })
        status = TextView(this).apply { text = "Готово к подключению"; textSize = 14f; setPadding(0,pad,0,0) }
        layout.addView(status)
        setContentView(layout)
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == overlayRequest) {
            if (Settings.canDrawOverlays(this)) requestCapture() else status.text = "Для плавающей панели разрешите показ поверх других приложений."
        } else if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MirrorService::class.java).apply {
                putExtra("ip",address.text.toString().trim()); putExtra("resultCode",resultCode); putExtra("projectionData",data)
            }
            startForegroundService(service)
            status.text = "Запуск ScreenMirror RU…"
        } else if (requestCode == projectionRequest) status.text = "Разрешение на трансляцию не выдано."
    }
}