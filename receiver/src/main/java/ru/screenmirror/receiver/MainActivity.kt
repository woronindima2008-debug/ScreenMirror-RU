package ru.screenmirror.receiver

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var pausedLayer: TextView
    private val alive = AtomicBoolean(true)
    @Volatile private var renderSurface: Surface? = null
    @Volatile private var videoPaused = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var controlServer: ServerSocket? = null
    @Volatile private var decoder: MediaCodec? = null\n    @Volatile private var audioServer: ServerSocket? = null\n    @Volatile private var audioEnabled = true
    private val surfaceLock = java.lang.Object()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF080B12.toInt()) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1,-1))
        pausedLayer = TextView(this).apply {
            text = "Передача приостановлена"
            textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF080B12.toInt()); visibility = View.GONE
        }
        root.addView(pausedLayer, FrameLayout.LayoutParams(-1,-1))
        status = TextView(this).apply {
            text = "ScreenMirror RU\nОжидание подключения телефона…"; textSize = 17f
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(16,12,16,12)
            setBackgroundColor(0xAA080B12.toInt())
        }
        root.addView(status, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        Thread({ listenVideo() },"screenmirror-video-listener").start()
        Thread({ listenControl() },"screenmirror-control-listener").start()\n        Thread({ listenAudio() },"screenmirror-audio-listener").start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { renderSurface=holder.surface; synchronized(surfaceLock){surfaceLock.notifyAll()} }
    override fun surfaceChanged(holder: SurfaceHolder, format:Int, width:Int, height:Int) { renderSurface=holder.surface }
    override fun surfaceDestroyed(holder: SurfaceHolder) { renderSurface=null }

    private fun awaitSurface(): Surface? {
        synchronized(surfaceLock) {
            var remaining=20_000L
            while(alive.get() && (renderSurface==null || renderSurface?.isValid!=true) && remaining>0) {
                val before=System.currentTimeMillis(); surfaceLock.wait(remaining)
                remaining-=(System.currentTimeMillis()-before).coerceAtLeast(1)
            }
            return renderSurface?.takeIf{it.isValid}
        }
    }

    private fun listenControl() {
        try {
            controlServer=ServerSocket(8989)
            while(alive.get()) {
                val peer=controlServer!!.accept()
                Thread({
                    peer.use { s ->
                        val input=DataInputStream(s.getInputStream())
                        while(alive.get()) {
                            when(input.readInt()) {
                                2 -> runOnUiThread { status.text="Звук включён" }
                                3 -> runOnUiThread { status.text="Звук выключен" }
                                4 -> { videoPaused=false; runOnUiThread { pausedLayer.visibility=View.GONE; status.text="ScreenMirror RU\nИзображение включено" } }
                                5 -> { videoPaused=true; runOnUiThread { pausedLayer.visibility=View.VISIBLE; status.visibility=View.GONE } }
                            }
                        }
                    }
                },"screenmirror-control-client").start()
            }
        } catch(e:Exception) { if(alive.get()) android.util.Log.e("ScreenMirrorRU","control listener",e) }
    }

    private fun listenVideo() {
        try {
            server=ServerSocket(8988)
            while(alive.get()) {
                val client=server!!.accept()
                runOnUiThread { status.visibility=View.VISIBLE; status.text="Телефон подключён. Подготовка изображения…" }
                handle(client)
            }
        } catch(e:Exception) { if(alive.get()) runOnUiThread { status.text="Не удалось запустить приём. Перезапустите ScreenMirror RU." } }
        finally { try{server?.close()}catch(_:Exception){} }
    }

    private fun handle(peer:Socket) {
        peer.use { socket ->
            var codec:MediaCodec?=null
            try {
                socket.tcpNoDelay=true
                val input=DataInputStream(socket.getInputStream())
                val width=input.readInt(); val height=input.readInt()
                require(width in 2..3840 && height in 2..2160)
                val sps=readPacket(input); val pps=readPacket(input)
                val surface=awaitSurface()?:throw IllegalStateException("Поверхность ТВ не готова")
                val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
                    setByteBuffer("csd-0",ByteBuffer.wrap(sps)); setByteBuffer("csd-1",ByteBuffer.wrap(pps))
                }
                codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder=codec; codec.configure(format,surface,null,0); codec.start()
                runOnUiThread { status.visibility=View.VISIBLE; status.text="ScreenMirror RU\nИдёт трансляция" }
                val info=MediaCodec.BufferInfo()
                while(alive.get() && !socket.isClosed) {
                    val packet=readPacket(input)
                    if(videoPaused) continue
                    val ix=codec.dequeueInputBuffer(10_000)
                    if(ix>=0) {
                        val buffer=codec.getInputBuffer(ix)?:throw IllegalStateException("Видеодекодер недоступен")
                        buffer.clear(); require(packet.size<=buffer.remaining()); buffer.put(packet)
                        codec.queueInputBuffer(ix,0,packet.size,System.nanoTime()/1000,0)
                    }
                    var out=codec.dequeueOutputBuffer(info,0)
                    while(out>=0) { codec.releaseOutputBuffer(out,true); out=codec.dequeueOutputBuffer(info,0) }
                }
            } catch(e:Exception) {
                if(alive.get()) runOnUiThread { status.visibility=View.VISIBLE; status.text="Соединение завершено. Ожидание телефона…" }
            } finally {
                try{codec?.stop()}catch(_:Exception){}; try{codec?.release()}catch(_:Exception){}
                if(decoder===codec)decoder=null
            }
        }
    }


    private fun listenAudio() {
        try {
            audioServer=ServerSocket(8990)
            while(alive.get()) {
                val peer=audioServer!!.accept()
                Thread({ decodeAudio(peer) },"screenmirror-audio-client").start()
            }
        } catch(e:Exception) { if(alive.get()) android.util.Log.e("ScreenMirrorRU","audio listener",e) }
    }

    private fun decodeAudio(peer:Socket) {
        var codec:MediaCodec?=null
        var track:AudioTrack?=null
        try {
            peer.use { socket ->
                socket.tcpNoDelay=true
                val input=DataInputStream(socket.getInputStream())
                val sampleRate=input.readInt(); val channels=input.readInt()
                require(sampleRate in 8000..96000 && channels in 1..2)
                val channelOut=if(channels==1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val min=AudioTrack.getMinBufferSize(sampleRate,channelOut,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(channelOut).build())
                    .setBufferSizeInBytes(min*2).setTransferMode(AudioTrack.MODE_STREAM).build()
                val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate,channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                codec=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format,null,null,0);codec.start();track.play()
                val info=MediaCodec.BufferInfo()
                while(alive.get() && !socket.isClosed) {
                    val size=input.readInt(); require(size in 1..65536)
                    val packet=ByteArray(size);input.readFully(packet)
                    val ix=codec.dequeueInputBuffer(10000)
                    if(ix>=0) {
                        val buf=codec.getInputBuffer(ix)!!;buf.clear();buf.put(packet)
                        codec.queueInputBuffer(ix,0,packet.size,System.nanoTime()/1000,0)
                    }
                    var out=codec.dequeueOutputBuffer(info,0)
                    while(out>=0) {
                        val pcm=codec.getOutputBuffer(out)
                        if(pcm!=null && info.size>0 && audioEnabled) {
                            val bytes=ByteArray(info.size);pcm.position(info.offset);pcm.limit(info.offset+info.size);pcm.get(bytes)
                            track.write(bytes,0,bytes.size)
                        }
                        codec.releaseOutputBuffer(out,false);out=codec.dequeueOutputBuffer(info,0)
                    }
                }
            }
        } catch(e:Exception) { if(alive.get()) android.util.Log.w("ScreenMirrorRU","Audio stream ended: ${e.message}") }
        finally { try{track?.pause();track?.flush();track?.release()}catch(_:Exception){};try{codec?.stop();codec?.release()}catch(_:Exception){} }
    }
\n    private fun readPacket(input:DataInputStream):ByteArray {
        val size=input.readInt(); require(size in 1..8_000_000) { "Некорректный размер пакета" }
        return ByteArray(size).also{input.readFully(it)}
    }

    override fun onDestroy() {
        alive.set(false); synchronized(surfaceLock){surfaceLock.notifyAll()}
        try{server?.close();controlServer?.close();audioServer?.close()}catch(_:Exception){}
        try{decoder?.stop();decoder?.release()}catch(_:Exception){}
        decoder=null; super.onDestroy()
    }
}