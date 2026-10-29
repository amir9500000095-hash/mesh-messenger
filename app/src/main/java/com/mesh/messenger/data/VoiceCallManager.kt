package com.mesh.messenger.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceCallManager(private val context: Context) {

    enum class CallState { IDLE, CALLING, IN_CALL, ENDED }

    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun startCall(peerId: String) {
        requestAudioFocus()
        _state.value = CallState.CALLING
        Log.i(TAG, "Voice call started with $peerId")
    }

    fun acceptCall(peerId: String) {
        requestAudioFocus()
        _state.value = CallState.IN_CALL
        Log.i(TAG, "Voice call accepted $peerId")
    }

    fun endCall() {
        abandonAudioFocus()
        _state.value = CallState.ENDED
        _state.value = CallState.IDLE
        Log.i(TAG, "Voice call ended")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attr)
                .setAcceptsDelayedFocusGain(true)
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    companion object {
        private const val TAG = "VoiceCallManager"
    }
}
