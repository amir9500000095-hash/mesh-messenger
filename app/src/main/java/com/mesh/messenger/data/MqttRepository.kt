package com.mesh.messenger.data

import android.util.Log
import com.google.gson.Gson
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class MqttRepository {

    private val gson = Gson()
    private var client: Mqtt5AsyncClient? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)
    val incoming: SharedFlow<MeshEnvelope> = _incoming.asSharedFlow()

    private val _onlineUsers = MutableStateFlow<Map<String, User>>(emptyMap())
    val onlineUsers: StateFlow<Map<String, User>> = _onlineUsers.asStateFlow()

    var myId: String = UUID.randomUUID().toString().take(10)
        private set
    var myName: String = "کاربر"
        private set
    var currentRoom: String = "عمومی"
        private set

    private val topicPrefix get() = "mesh/v1/${currentRoom}"

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    fun setIdentity(name: String, room: String) {
        myName = name.ifBlank { "کاربر" }.take(20)
        currentRoom = room.ifBlank { "عمومی" }.lowercase().replace("\\s+".toRegex(), "-").take(24)
    }

    suspend fun connect(preferredHost: String? = null): Boolean {
        disconnect()
        _connectionState.value = ConnectionState.CONNECTING

        val hosts = if (preferredHost != null) {
            listOf(preferredHost to 8883) + BrokerList.ALL.filter { it.first != preferredHost }
        } else BrokerList.ALL

        for ((host, port) in hosts) {
            try {
                val c = MqttClient.builder()
                    .useMqttVersion5()
                    .identifier("mesh-$myId-${UUID.randomUUID().toString().take(4)}")
                    .serverHost(host)
                    .serverPort(port)
                    .sslWithDefaultConfig()
                    .automaticReconnectWithDefaultConfig()
                    .buildAsync()

                c.connectWith()
                    .cleanStart(true)
                    .keepAlive(30)
                    .send()
                    .get(8, TimeUnit.SECONDS)

                client = c
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "Connected to $host:$port")

                c.subscribeWith()
                    .topicFilter("$topicPrefix/#")
                    .qos(MqttQos.AT_MOST_ONCE)
                    .callback { publish -> handlePublish(publish) }
                    .send()

                publish("join", mapOf("name" to myName))
                publishPresence()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed $host: ${e.message}")
            }
        }
        _connectionState.value = ConnectionState.ERROR
        return false
    }

    fun disconnect() {
        try {
            client?.disconnect()?.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        client = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _onlineUsers.value = emptyMap()
    }

    fun publish(type: String, extra: Map<String, Any?> = emptyMap()) {
        val c = client ?: return
        val envelope = MeshEnvelope(
            t = type,
            id = myId,
            n = myName,
            chatId = currentRoom,
            ts = System.currentTimeMillis(),
            data = extra
        )
        val json = gson.toJson(envelope)
        c.publishWith()
            .topic("$topicPrefix/m")
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(json.toByteArray(StandardCharsets.UTF_8))
            .send()
    }

    fun sendChat(text: String, chatId: String = currentRoom) {
        publish("chat", mapOf("text" to text, "chatId" to chatId))
    }

    fun publishPresence(voice: Boolean = false, video: Boolean = false) {
        publish("presence", mapOf("voice" to voice, "video" to video))
    }

    fun createGroup(title: String, description: String = "") {
        val groupId = "g_${UUID.randomUUID().toString().take(8)}"
        publish("create_group", mapOf(
            "groupId" to groupId,
            "title" to title,
            "description" to description
        ))
    }

    fun createChannel(title: String, description: String = "") {
        val channelId = "c_${UUID.randomUUID().toString().take(8)}"
        publish("create_channel", mapOf(
            "channelId" to channelId,
            "title" to title,
            "description" to description
        ))
    }

    private fun handlePublish(publish: Mqtt5Publish) {
        try {
            val json = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            val envelope = gson.fromJson(json, MeshEnvelope::class.java) ?: return
            if (envelope.id == myId) return

            when (envelope.t) {
                "presence", "join" -> {
                    val name = envelope.n ?: "ناشناس"
                    val map = _onlineUsers.value.toMutableMap()
                    map[envelope.id] = User(
                        id = envelope.id,
                        name = name,
                        isOnline = true,
                        isVoice = (envelope.data?.get("voice") as? Boolean) == true,
                        isVideo = (envelope.data?.get("video") as? Boolean) == true,
                        lastSeen = System.currentTimeMillis()
                    )
                    _onlineUsers.value = map
                }
                "leave" -> {
                    val map = _onlineUsers.value.toMutableMap()
                    map.remove(envelope.id)
                    _onlineUsers.value = map
                }
            }
            _incoming.tryEmit(envelope)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
        }
    }

    companion object {
        private const val TAG = "MqttRepository"
    }
}
