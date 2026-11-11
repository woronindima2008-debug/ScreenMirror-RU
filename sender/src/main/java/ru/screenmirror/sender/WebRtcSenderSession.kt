package ru.screenmirror.sender

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcSenderSession(
    context: Context,
    private val egl: EglBase,
    private val sendSignal: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    @Volatile private var videoTrack: VideoTrack? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start(permissionData: Intent, width: Int, height: Int, fps: Int) {
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        ))
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                sendSignal(JSONObject().put("kind", "ice-candidate").put(
                    "candidate", JSONObject().put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)
                ).toString())
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                onStatus(when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "DmitryTranslation: соединение установлено"
                    PeerConnection.PeerConnectionState.CONNECTING -> "Подключение к телевизору…"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "Связь потеряна"
                    PeerConnection.PeerConnectionState.FAILED -> "Не удалось установить соединение"
                    else -> "Подготовка трансляции…"
                })
            }
        }) ?: throw IllegalStateException("Не удалось создать WebRTC-соединение")

        val videoSource = factory.createVideoSource(true)
        val screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                onStatus("Системная трансляция остановлена")
                stop()
            }
        })
        val textureHelper = SurfaceTextureHelper.create("DmitryTranslation-Capture", egl.eglBaseContext)
            ?: throw IllegalStateException("Не удалось создать SurfaceTextureHelper")
        screenCapturer.initialize(textureHelper, appContext, videoSource.capturerObserver)

        // Keep the long side <= 1920 for reliable hardware H.264 encoding, preserving aspect ratio.
        val scale = minOf(1f, 1920f / maxOf(width, height).toFloat())
        val captureWidth = ((width * scale).toInt() / 2) * 2
        val captureHeight = ((height * scale).toInt() / 2) * 2
        val track = factory.createVideoTrack("dmitrytranslation-screen", videoSource)
        track.setEnabled(true)
        val videoSender = peer?.addTrack(track, listOf("dmitrytranslation-stream"))
            ?: throw IllegalStateException("Не удалось добавить видеодорожку в WebRTC")
        videoTrack = track
        videoSender.parameters?.let { parameters ->
            parameters.encodings?.forEach { encoding ->
                encoding.maxBitrateBps = 12_000_000
                encoding.maxFramerate = fps.coerceIn(15, 60)
            }
            videoSender.setParameters(parameters)
        }
        capturer = screenCapturer
        source = videoSource
        helper = textureHelper
        // Attach the track before starting capture so its first frames are not lost.
        screenCapturer.startCapture(captureWidth.coerceAtLeast(2), captureHeight.coerceAtLeast(2), fps.coerceIn(15, 60))

        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        sendSignal(JSONObject().put("kind", "offer").put("sdp", desc.description).toString())
                    }
                    override fun onSetFailure(error: String?) { onStatus("Ошибка подготовки видеопотока: $error") }
                }, desc)
            }
            override fun onCreateFailure(error: String?) { onStatus("Не удалось создать видеопоток: $error") }
        }, MediaConstraints())
    }

    fun mediaProjection(): android.media.projection.MediaProjection? = capturer?.getMediaProjection()

    @Synchronized fun handleSignal(json: JSONObject) {
        when (json.optString("kind")) {
            "answer" -> {
                val sdp = json.optString("sdp")
                if (sdp.isBlank()) return
                peer?.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        pendingCandidates.forEach { peer?.addIceCandidate(it) }
                        pendingCandidates.clear()
                    }
                    override fun onSetFailure(error: String?) { onStatus("Ошибка ответа телевизора: $error") }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice-candidate" -> {
                val c = json.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (peer?.remoteDescription != null) peer?.addIceCandidate(candidate) else pendingCandidates.add(candidate)
            }
        }
    }

    @Synchronized fun setVideoEnabled(enabled: Boolean) { videoTrack?.setEnabled(enabled) }

    @Synchronized fun stop() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { source?.dispose() } catch (_: Exception) {}
        source = null
        videoTrack = null
        try { helper?.dispose() } catch (_: Exception) {}
        helper = null
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        peer = null
        pendingCandidates.clear()
    }

    fun release() {
        stop()
        factory.dispose()
    }

    private open class SdpObserverAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}