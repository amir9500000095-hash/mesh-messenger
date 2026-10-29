package com.mesh.messenger.data

import java.util.UUID

enum class ChatType { PRIVATE, GROUP, CHANNEL, BOT }

data class User(
    val id: String,
    val name: String,
    val isOnline: Boolean = false,
    val isVoice: Boolean = false,
    val isVideo: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

data class Chat(
    val id: String,
    val title: String,
    val type: ChatType,
    val members: List<String> = emptyList(),
    val lastMessage: String? = null,
    val lastMessageTime: Long = 0L,
    val unread: Int = 0,
    val isMuted: Boolean = false,
    val description: String? = null,
    val ownerId: String? = null
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val text: String? = null,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val replyTo: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val isMine: Boolean = false
)

enum class MessageType { TEXT, IMAGE, FILE, VOICE, SYSTEM, BOT }

data class MeshEnvelope(
    val t: String,
    val id: String,
    val n: String? = null,
    val chatId: String? = null,
    val text: String? = null,
    val ts: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>? = null
)

object BrokerList {
    val ALL = listOf(
        "broker.emqx.io" to 8883,
        "broker-cn.emqx.io" to 8883,
        "broker.hivemq.com" to 8883,
        "test.mosquitto.org" to 8883
    )
}
