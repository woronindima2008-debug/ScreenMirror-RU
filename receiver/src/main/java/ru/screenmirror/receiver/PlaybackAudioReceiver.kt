package ru.screenmirror.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAudioReceiver(private val expectedToken: String) {
    private val running = AtomicBoolean(true)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var track: AudioTrack? = null

    fun start() {
        Thread({ listen() }, "ScreenMirror-AudioReceiver").start()
    }

    private fun listen() {
        try {
            ServerSocket(PORT).use { listener ->
                server = listener
                while (running.get()) {
                    val socket = listener.accept()
                    client = socket
                    receive(socket)
                    client = null
                }
            }
        } catch (_: Exception) {
            // Audio is optional; video receiver remains active if audio socket cannot be opened.
        } finally {
            server = null
        }
    }

    private fun receive(socket: Socket) {
        socket.use { peer ->
            try {
                val input = DataInputStream(peer.getInputStream())
                require(input.readInt() == MAGIC) { "Неверный аудиопротокол" }
                val sampleRate = input.readInt()
                val channels = input.readInt()
                require(input.readUTF() == expectedToken) { "Неверный код аудиосопряжения" }
                var configured = false
                val info = MediaCodec.BufferInfo()
                while (running.get() && !peer.isClosed) {
                    when (val type = input.readInt()) {
                        -1 -> {
                            val size = input.readInt()
                            require(size in 1..4096)
                            val csd = ByteArray(size)
                            input.readFully(csd)
                            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            }
                            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                            decoder = codec
                            codec.configure(format, null, null, 0)
                            codec.start()
                            configured = true
                        }
                        1 -> {
                            if (!configured) continue
                            input.readLong() // Source presentation timestamp; TCP preserves packet order.
                            val size = input.readInt()
                            require(size in 1..65_536)
                            val bytes = ByteArray(size)
                            input.readFully(bytes)
                            val codec = decoder ?: continue
                            val index = codec.dequeueInputBuffer(10_000)
                            if (index >= 0) {
                                val buffer = codec.getInputBuffer(index)!!
                                buffer.clear()
                                if (size <= buffer.remaining()) {
                                    buffer.put(bytes)
                                    codec.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, 0)
                                } else codec.queueInputBuffer(index, 0, 0, 0, 0)
                            }
                            var outputIndex = codec.dequeueOutputBuffer(info, 0)
                            while (outputIndex >= 0) {
                                val output = codec.getOutputBuffer(outputIndex)
                                if (output != null && info.size > 0) {
                                    val outputFormat = codec.outputFormat
                                    val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    ensureTrack(rate, channelCount)
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val pcm = ByteArray(info.size)
                                    output.get(pcm)
                                    track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                                }
                                codec.releaseOutputBuffer(outputIndex, false)
                                outputIndex = codec.dequeueOutputBuffer(info, 0)
                            }
                        }
                        else -> throw IllegalArgumentException("Неизвестный тип аудиопакета: $type")
                    }
                }
            } catch (_: Exception) {
                // A disconnected or unsupported audio stream must not stop video.
            } finally {
                releaseDecoder()
            }
        }
    }

    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (track?.state == AudioTrack.STATE_INITIALIZED) return
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate).setChannelMask(mask).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
            .setBufferSizeInBytes((min * 2).coerceAtLeast(8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = audio
        audio.play()
    }

    private fun releaseDecoder() {
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
    }

    fun stop() {
        running.set(false)
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        releaseDecoder()
    }

    companion object {
        private const val PORT = 8990
        private const val MAGIC = 0x534D5241
    }
}
