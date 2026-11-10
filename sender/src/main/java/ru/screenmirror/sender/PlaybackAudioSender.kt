package ru.screenmirror.sender

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAudioSender(
    private val context: Context,
    private val projection: MediaProjection,
    private val host: String,
    private val token: String
) {
    private val running = AtomicBoolean(false)
    @Volatile private var record: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var socket: Socket? = null
    private var worker: Thread? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !running.compareAndSet(false, true)) return
        worker = Thread({ runCapture() }, "ScreenMirror-AudioCapture").also { it.start() }
    }

    private fun runCapture() {
        var output: DataOutputStream? = null
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(16_384))
                .setAudioPlaybackCaptureConfig(captureConfig).build()
            record = audioRecord
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = codec
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val client = Socket()
            socket = client
            client.connect(InetSocketAddress(host, PORT), 5000)
            client.tcpNoDelay = true
            output = DataOutputStream(client.getOutputStream())
            output.writeInt(MAGIC)
            output.writeInt(SAMPLE_RATE)
            output.writeInt(CHANNELS)
            output.writeUTF(token)
            output.flush()

            audioRecord.startRecording()
            val pcm = ByteArray(8_192)
            var totalBytes = 0L
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val count = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)!!
                        input.clear()
                        val accepted = count.coerceAtMost(input.remaining())
                        input.put(pcm, 0, accepted)
                        val pts = totalBytes * 1_000_000L / (SAMPLE_RATE * CHANNELS * 2L)
                        codec.queueInputBuffer(inputIndex, 0, accepted, pts, 0)
                        totalBytes += accepted
                    }
                }
                var outIndex = codec.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        synchronized(output) {
                            output.writeInt(1)
                            output.writeLong(info.presentationTimeUs)
                            output.writeInt(bytes.size)
                            output.write(bytes)
                            output.flush()
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val csd = codec.outputFormat.getByteBuffer("csd-0") ?: ByteBuffer.allocate(0)
                    val bytes = ByteArray(csd.remaining())
                    csd.get(bytes)
                    synchronized(output) {
                        output.writeInt(-1)
                        output.writeInt(bytes.size)
                        output.write(bytes)
                        output.flush()
                    }
                }
            }
        } catch (_: SecurityException) {
            // Захват звука запрещён системой или не разрешён пользователем; видео продолжает работать.
        } catch (_: Exception) {
            // Аудиоканал необязателен: его сбой не останавливает видеотрансляцию.
        } finally {
            running.set(false)
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            record = null
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            encoder = null
            try { output?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    fun stop() {
        running.set(false)
        try { record?.stop() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { worker?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val PORT = 8990
        private const val MAGIC = 0x534D5241
    }
}
