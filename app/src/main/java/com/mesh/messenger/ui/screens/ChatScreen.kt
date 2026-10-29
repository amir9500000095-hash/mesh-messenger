package com.mesh.messenger.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mesh.messenger.call.CallManager
import com.mesh.messenger.data.Message
import com.mesh.messenger.data.MessageType
import com.mesh.messenger.data.MqttRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    mqtt: MqttRepository,
    callManager: CallManager?,
    chatId: String,
    peerIdForCall: String? = null,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Message>() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale("fa")) }
    val callState by (callManager?.state ?: MutableStateFlow(CallManager.CallState.IDLE)).collectAsState()

    LaunchedEffect(Unit) {
        mqtt.incoming.collectLatest { env ->
            when (env.t) {
                "chat" -> {
                    val text = env.data?.get("text") as? String ?: return@collectLatest
                    messages.add(
                        Message(
                            chatId = chatId,
                            senderId = env.id,
                            senderName = env.n ?: "ناشناس",
                            text = text,
                            timestamp = env.ts,
                            isMine = false
                        )
                    )
                    scope.launch { listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0)) }
                }
                "join" -> {
                    messages.add(
                        Message(
                            chatId = chatId,
                            senderId = "system",
                            senderName = "",
                            text = "${env.n ?: "کسی"} وارد شد",
                            type = MessageType.SYSTEM
                        )
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chatId) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (peerIdForCall != null && callManager != null) {
                        IconButton(onClick = {
                            if (callState == CallManager.CallState.IDLE) {
                                callManager.startVoiceCall(peerIdForCall)
                            } else {
                                callManager.endCall()
                            }
                        }) {
                            Icon(
                                if (callState == CallManager.CallState.IDLE) Icons.Default.Call
                                else Icons.Default.CallEnd,
                                contentDescription = "تماس صوتی"
                            )
                        }
                        IconButton(onClick = {
                            if (callState == CallManager.CallState.IDLE) {
                                callManager.startVideoCall(peerIdForCall)
                            } else {
                                callManager.endCall()
                            }
                        }) {
                            Icon(Icons.Default.Videocam, contentDescription = "تماس تصویری")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (callState != CallManager.CallState.IDLE) {
                    Surface(
                        color = when (callState) {
                            CallManager.CallState.CONNECTED -> MaterialTheme.colorScheme.secondary
                            CallManager.CallState.INCOMING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                when (callState) {
                                    CallManager.CallState.OUTGOING -> "در حال تماس..."
                                    CallManager.CallState.INCOMING -> "تماس ورودی"
                                    CallManager.CallState.CONNECTED -> "تماس برقرار است"
                                    else -> ""
                                },
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Row {
                                if (callState == CallManager.CallState.INCOMING) {
                                    TextButton(onClick = { callManager?.acceptCall() }) {
                                        Text("پذیرش", color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                                TextButton(onClick = { callManager?.endCall() }) {
                                    Text("قطع", color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("پیام بنویس...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                mqtt.sendChat(text, chatId)
                                messages.add(
                                    Message(
                                        chatId = chatId,
                                        senderId = mqtt.myId,
                                        senderName = mqtt.myName,
                                        text = text,
                                        isMine = true
                                    )
                                )
                                input = ""
                                scope.launch {
                                    listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
                                }
                            }
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { msg ->
                when (msg.type) {
                    MessageType.SYSTEM -> {
                        Text(
                            msg.text ?: "",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    else -> {
                        val isMe = msg.isMine
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isMe) Alignment.Start else Alignment.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (isMe) 4.dp else 16.dp,
                                    bottomEnd = if (isMe) 16.dp else 4.dp
                                ),
                                color = if (isMe) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.widthIn(max = 300.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    if (!isMe) {
                                        Text(
                                            msg.senderName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        msg.text ?: "",
                                        color = if (isMe) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        timeFormat.format(Date(msg.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = (if (isMe) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.6f),
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
