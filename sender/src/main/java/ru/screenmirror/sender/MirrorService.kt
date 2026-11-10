package ru.screenmirror.sender

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MirrorService : Service() {
    @Volatile private var running=false
    @Volatile private var videoEnabled=true
    @Volatile private var audioEnabled=true
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var encoder:MediaCodec?=null
    private var videoSocket:Socket?=null
    private var controlSocket:Socket?=null
    private var audioSocket:Socket?=null
    private var overlay:View?=null
    private var windowManager:WindowManager?=null
    private val main=Handler(Looper.getMainLooper())

    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        createNotification(); startForeground(7,notification())
        val ip=intent?.getStringExtra("ip")?:return START_NOT_STICKY
        val code=intent.getIntExtra("resultCode",0); val data=projectionIntent(intent)
        if(data==null){stopSelf();return START_NOT_STICKY}
        thread(name="screenmirror-video"){stream(ip,code,data)}
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun projectionIntent(i:Intent):Intent?=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra("projectionData",Intent::class.java) else i.getParcelableExtra("projectionData")
    private fun createNotification(){ if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("mirror","ScreenMirror RU",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification():Notification {
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,"mirror") else Notification.Builder(this)
        return b.setContentTitle("ScreenMirror RU").setContentText("Передача экрана активна").setSmallIcon(android.R.drawable.ic_menu_view).build()
    }
    private fun addOverlay(){
        if(Build.VERSION.SDK_INT<26 || !android.provider.Settings.canDrawOverlays(this))return
        windowManager=getSystemService(WINDOW_SERVICE) as WindowManager
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(10,8,10,8); setBackgroundColor(0xEE101827.toInt()) }
        val head=Button(this).apply { text="ScreenMirror RU  ⋮"; setTextColor(Color.WHITE); setBackgroundColor(0xFF172B49.toInt()) }
        val sound=Button(this).apply { text="♫ Звук: ВКЛ"; setTextColor(Color.WHITE); setOnClickListener { audioEnabled=!audioEnabled; text=if(audioEnabled)"♫ Звук: ВКЛ" else "♫ Звук: ВЫКЛ"; sendControl( if(audioEnabled)2 else 3) } }
        val picture=Button(this).apply { text="▣ Изображение: ВКЛ"; setTextColor(Color.WHITE); setOnClickListener { videoEnabled=!videoEnabled; text=if(videoEnabled)"▣ Изображение: ВКЛ" else "▣ Изображение: ВЫКЛ"; sendControl(if(videoEnabled)4 else 5); if(videoEnabled)requestKeyFrame() } }
        val stop=Button(this).apply { text="Завершить"; setTextColor(Color.WHITE); setOnClickListener { stopSelf() } }
        panel.addView(head); panel.addView(sound); panel.addView(picture); panel.addView(stop)
        val type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=24;y=120 }
        head.setOnTouchListener(object:View.OnTouchListener{
            var sx=0f;var sy=0f;var ox=0;var oy=0
            override fun onTouch(v:View,e:MotionEvent):Boolean { when(e.action){MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;ox=params.x;oy=params.y;return true};MotionEvent.ACTION_MOVE->{params.x=ox+(sx-e.rawX).toInt();params.y=oy+(e.rawY-sy).toInt();windowManager?.updateViewLayout(panel,params);return true}};return false }
        })
        overlay=panel
        try{windowManager?.addView(panel,params)}catch(_:Exception){overlay=null}
    }
    private fun sendControl(command:Int){ thread { try { synchronized(this){ controlSocket?.getOutputStream()?.let { DataOutputStream(it).apply { writeInt(command);flush() } } } }catch(_:Exception){} } }
    private fun requestKeyFrame(){ try{ encoder?.setParameters(android.os.Bundle().apply{putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0)}) }catch(_:Exception){} }

    private fun stream(ip:String,code:Int,data:Intent){
        try {
            videoSocket=Socket().apply{connect(InetSocketAddress(ip,8988),8000);tcpNoDelay=true;sendBufferSize=1 shl 20}
            controlSocket=Socket().apply{connect(InetSocketAddress(ip,8989),8000);tcpNoDelay=true}
            val m=android.util.DisplayMetrics(); @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            val width=(m.widthPixels.coerceAtMost(1920)/2)*2; val height=(m.heightPixels.coerceAtMost(1080)/2)*2
            val fps=if(m.refreshRate>=55f)60 else 30
            val fmt=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply{
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,if(fps==60)12_000_000 else 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,fps);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                if(Build.VERSION.SDK_INT>=30) setInteger(MediaFormat.KEY_LATENCY,1)
            }
            encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply{configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)}
            val input=encoder!!.createInputSurface();encoder!!.start()
            projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code,data)
            projection!!.registerCallback(object:MediaProjection.Callback(){override fun onStop(){running=false;try{videoSocket?.close()}catch(_:Exception){}}},Handler(Looper.getMainLooper()))
            display=projection!!.createVirtualDisplay("ScreenMirrorRU",width,height,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,input,null,null)
            running=true; main.post{addOverlay()}
            val out=DataOutputStream(videoSocket!!.getOutputStream());out.writeInt(width);out.writeInt(height);out.flush()
            val info=MediaCodec.BufferInfo();var sent=false
            while(running){
                val ix=encoder!!.dequeueOutputBuffer(info,5_000)
                when { ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{val f=encoder!!.outputFormat;val s=f.getByteBuffer("csd-0")?:ByteBuffer.allocate(0);val p=f.getByteBuffer("csd-1")?:ByteBuffer.allocate(0);val a=ByteArray(s.remaining());s.get(a);val b=ByteArray(p.remaining());p.get(b);out.writeInt(a.size);out.write(a);out.writeInt(b.size);out.write(b);out.flush();sent=true}
                    ix>=0->{val buf=encoder!!.getOutputBuffer(ix);if(buf!=null&&info.size>0&&sent&&videoEnabled){buf.position(info.offset);buf.limit(info.offset+info.size);val bytes=ByteArray(info.size);buf.get(bytes);out.writeInt(bytes.size);out.write(bytes)};encoder!!.releaseOutputBuffer(ix,false)}
                }
            }
        }catch(e:Exception){android.util.Log.e("ScreenMirrorRU","stream failed",e)}
        finally{running=false;main.post{try{overlay?.let{windowManager?.removeView(it)}}catch(_:Exception){};overlay=null};try{display?.release()}catch(_:Exception){};try{projection?.stop()}catch(_:Exception){};try{encoder?.stop();encoder?.release()}catch(_:Exception){};try{videoSocket?.close();controlSocket?.close();audioSocket?.close()}catch(_:Exception){};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    override fun onDestroy(){running=false;try{videoSocket?.close();controlSocket?.close();audioSocket?.close()}catch(_:Exception){};super.onDestroy()}
}