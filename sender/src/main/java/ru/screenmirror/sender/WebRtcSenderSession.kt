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
        val config = PeerConnection.RTCConfiguration(emptyList())
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
                    PeerConnection.PeerConnectionState.CONNECTED -> "Трансляция запущена"
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
        val textureHelper = SurfaceTextureHelper.create("ScreenMirror-Capture", egl.eglBaseContext)
            ?: throw IllegalStateException("Не удалось создать SurfaceTextureHelper")
        screenCapturer.initialize(textureHelper, appContext, videoSource.capturerObserver)
        screenCapturer.startCapture(width, height, fps)
        val track = factory.createVideoTrack("screen-video", videoSource)
        track.setEnabled(true)
        peer?.addTrack(track, listOf("screen-stream"))

        capturer = screenCapturer
        source = videoSource
        helper = textureHelper

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

    @Synchronized fun stop() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null
        try { source?.dispose() } catch (_: Exception) {}
        source = null
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
