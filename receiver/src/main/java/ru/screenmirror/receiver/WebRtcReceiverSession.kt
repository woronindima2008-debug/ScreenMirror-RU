package ru.screenmirror.receiver

import android.content.Context
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import org.webrtc.VideoFrame

class WebRtcReceiverSession(
    context: Context,
    private val egl: EglBase,
    private val renderer: SurfaceViewRenderer,
    private val sendSignal: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var remoteVideo: VideoTrack? = null
    private var remoteSink: VideoSink? = null
    @Volatile private var firstFrameReceived = false
    private val pendingCandidates = mutableListOf<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        renderer.init(egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(false)
        renderer.setMirror(false)
    }

    @Synchronized fun handleSignal(json: JSONObject) {
        when (json.optString("kind")) {
            "offer" -> acceptOffer(json.optString("sdp"))
            "ice-candidate" -> {
                val c = json.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (peer?.remoteDescription != null) peer?.addIceCandidate(candidate) else pendingCandidates.add(candidate)
            }
            "stop" -> closePeer()
        }
    }

    private fun acceptOffer(sdp: String) {
        if (sdp.isBlank()) { onStatus("Получено пустое SDP-предложение"); return }
        val earlyCandidates = pendingCandidates.toList()
        closePeer(clearCandidates = true)
        pendingCandidates.addAll(earlyCandidates)
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
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideo?.let { old -> remoteSink?.let { old.removeSink(it) } }
                    remoteVideo = track
                    firstFrameReceived = false
                    track.setEnabled(true)
                    val sink = object : VideoSink {
                        override fun onFrame(frame: VideoFrame) {
                            renderer.onFrame(frame)
                            if (!firstFrameReceived) {
                                firstFrameReceived = true
                                onStatus("Первый видеокадр получен — идёт трансляция")
                            }
                        }
                    }
                    remoteSink = sink
                    track.addSink(sink)
                    onStatus("Видеодорожка подключена — запускаю отображение…")
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                onStatus(when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "Соединение установлено — включаю видеовывод…"
                    PeerConnection.PeerConnectionState.CONNECTING -> "Устанавливаем видеосоединение…"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "Связь потеряна. Ожидание повторного подключения…"
                    PeerConnection.PeerConnectionState.FAILED -> "Не удалось установить соединение"
                    else -> "Ожидание трансляции…"
                })
            }
        })
        val pc = peer ?: return
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                pendingCandidates.forEach { pc.addIceCandidate(it) }
                pendingCandidates.clear()
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        pc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                sendSignal(JSONObject().put("kind", "answer").put("sdp", desc.description).toString())
                            }
                            override fun onSetFailure(error: String?) { onStatus("Ошибка локального SDP: $error") }
                        }, desc)
                    }
                    override fun onCreateFailure(error: String?) { onStatus("Не удалось создать ответ: $error") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { onStatus("Не удалось принять предложение: $error") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    @Synchronized private fun closePeer(clearCandidates: Boolean = true) {
        remoteVideo?.let { track -> remoteSink?.let { track.removeSink(it) } }
        remoteVideo = null
        remoteSink = null
        firstFrameReceived = false
        peer?.close()
        peer?.dispose()
        peer = null
        if (clearCandidates) pendingCandidates.clear()
    }

    fun release() {
        closePeer()
        renderer.release()
        factory.dispose()
    }

    private open class SdpObserverAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}