package com.mesh.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.mesh.messenger.data.MqttRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.Executors

class CallManager(
    private val context: Context,
    private val mqtt: MqttRepository,
    private val scope: CoroutineScope
) {
    enum class CallState { IDLE, OUTGOING, INCOMING, CONNECTED, ENDED }

    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _remoteUserId = MutableStateFlow<String?>(null)
    val remoteUserId: StateFlow<String?> = _remoteUserId.asStateFlow()

    private val _isVideo = MutableStateFlow(false)
    val isVideo: StateFlow<Boolean> = _isVideo.asStateFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    init {
        initFactory()
        scope.launch {
            mqtt.incoming.collect { env ->
                if (env.t == "call_signal" && env.id != mqtt.myId) {
                    handleSignal(env)
                }
            }
        }
    }

    private fun initFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        eglBase = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startVoiceCall(peerId: String) {
        if (_state.value != CallState.IDLE) return
        _isVideo.value = false
        _remoteUserId.value = peerId
        _state.value = CallState.OUTGOING
        requestAudioFocus()
        createPeerConnection()
        createLocalAudio()
        createOffer(peerId)
        mqtt.publish("call_signal", mapOf("action" to "invite", "to" to peerId, "video" to false))
    }

    fun startVideoCall(peerId: String) {
        if (_state.value != CallState.IDLE) return
        _isVideo.value = true
        _remoteUserId.value = peerId
        _state.value = CallState.OUTGOING
        requestAudioFocus()
        createPeerConnection()
        createLocalAudio()
        createLocalVideo()
        createOffer(peerId)
        mqtt.publish("call_signal", mapOf("action" to "invite", "to" to peerId, "video" to true))
    }

    fun acceptCall() {
        if (_state.value != CallState.INCOMING) return
        _state.value = CallState.CONNECTED
        requestAudioFocus()
    }

    fun rejectCall() {
        val peer = _remoteUserId.value
        if (peer != null) mqtt.publish("call_signal", mapOf("action" to "reject", "to" to peer))
        endCall()
    }

    fun endCall() {
        val peer = _remoteUserId.value
        if (peer != null && _state.value != CallState.IDLE) {
            mqtt.publish("call_signal", mapOf("action" to "hangup", "to" to peer))
        }
        cleanup()
        _state.value = CallState.ENDED
        _state.value = CallState.IDLE
        _remoteUserId.value = null
        _isVideo.value = false
        abandonAudioFocus()
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE state: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    _state.value = CallState.CONNECTED
                }
                if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) {
                    endCall()
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val peer = _remoteUserId.value ?: return
                mqtt.publish("call_signal", mapOf(
                    "action" to "ice", "to" to peer,
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp
                ))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.i(TAG, "Remote track added: " + (receiver?.track()?.kind() ?: "?"))
                val track = receiver?.track()
                if (track is AudioTrack) {
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    Log.i(TAG, "Remote audio track enabled")
                }
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    Log.i(TAG, "Remote video track enabled")
                }
            }
        })
    }

    private fun createLocalAudio() {
        val constraints = MediaConstraints()
        val source = peerConnectionFactory?.createAudioSource(constraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("AUDIO_${UUID.randomUUID()}", source)
        localAudioTrack?.setEnabled(true)
        localAudioTrack?.let { peerConnection?.addTrack(it) }
    }

    private fun createLocalVideo() {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return

        videoCapturer = enumerator.createCapturer(deviceName, null)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        val videoSource = peerConnectionFactory?.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 24)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("VIDEO_${UUID.randomUUID()}", videoSource)
        localVideoTrack?.setEnabled(true)
        localVideoTrack?.let { peerConnection?.addTrack(it) }
    }

    private fun createOffer(peerId: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", _isVideo.value.toString()))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        mqtt.publish("call_signal", mapOf(
                            "action" to "offer", "to" to peerId,
                            "sdp" to sdp.description, "type" to sdp.type.canonicalForm()
                        ))
                    }
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "setLocal failed: $p0") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun handleSignal(env: com.mesh.messenger.data.MeshEnvelope) {
        val data = env.data ?: return
        val to = data["to"] as? String
        if (to != null && to != mqtt.myId) return

        when (data["action"] as? String ?: return) {
            "invite" -> {
                if (_state.value != CallState.IDLE) return
                _remoteUserId.value = env.id
                _isVideo.value = (data["video"] as? Boolean) == true
                _state.value = CallState.INCOMING
                createPeerConnection()
                createLocalAudio()
                if (_isVideo.value) createLocalVideo()
            }
            "offer" -> {
                val sdp = data["sdp"] as? String ?: return
                val type = data["type"] as? String ?: "offer"
                val session = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answer: SessionDescription?) {
                                answer ?: return
                                peerConnection?.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        mqtt.publish("call_signal", mapOf(
                                            "action" to "answer", "to" to env.id,
                                            "sdp" to answer.description, "type" to answer.type.canonicalForm()
                                        ))
                                        _state.value = CallState.CONNECTED
                                    }
                                    override fun onSetFailure(p0: String?) {}
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, answer)
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetSuccess() {}
                            override fun onSetFailure(p0: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, session)
            }
            "answer" -> {
                val sdp = data["sdp"] as? String ?: return
                val type = data["type"] as? String ?: "answer"
                val session = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { _state.value = CallState.CONNECTED }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, session)
            }
            "ice" -> {
                val candidate = data["candidate"] as? String ?: return
                val sdpMid = data["sdpMid"] as? String
                val sdpMLineIndex = (data["sdpMLineIndex"] as? Double)?.toInt() ?: (data["sdpMLineIndex"] as? Int) ?: 0
                peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
            "reject", "hangup" -> endCall()
        }
    }

    private fun cleanup() {
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            localAudioTrack?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack = null
            localVideoTrack = null
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            Log.e(TAG, "cleanup error", e)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attr).build()
            audioManager.requestAudioFocus(focusRequest!!)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
    }

    fun release() {
        endCall()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
