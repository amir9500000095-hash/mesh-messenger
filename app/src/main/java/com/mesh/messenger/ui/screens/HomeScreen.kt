package com.mesh.messenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mesh.messenger.data.Chat
import com.mesh.messenger.data.ChatType
import com.mesh.messenger.data.MqttRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mqtt: MqttRepository,
    onOpenChat: (String) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val connection by mqtt.connectionState.collectAsState()
    val users by mqtt.onlineUsers.collectAsState()
    var connecting by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createType by remember { mutableStateOf(ChatType.GROUP) }
    var createTitle by remember { mutableStateOf("") }

    val chats = remember {
        mutableStateListOf(
            Chat(id = mqtt.currentRoom, title = "اتاق: ${mqtt.currentRoom}", type = ChatType.PRIVATE)
        )
    }

    LaunchedEffect(Unit) {
        connecting = true
        mqtt.connect()
        connecting = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("پیام‌رسان آزاد", fontWeight = FontWeight.Bold)
                        Text(
                            when (connection) {
                                MqttRepository.ConnectionState.CONNECTED -> "متصل • ${users.size + 1} نفر"
                                MqttRepository.ConnectionState.CONNECTING -> "در حال اتصال..."
                                else -> "قطع"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "خروج")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "ساخت گروه/کانال")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (connecting || connection == MqttRepository.ConnectionState.CONNECTING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (users.isNotEmpty()) {
                Text(
                    "آنلاین‌ها",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                    items(users.values.toList()) { user ->
                        ListItem(
                            headlineContent = { Text(user.name) },
                            supportingContent = {
                                Text(
                                    if (user.isVoice) "🎙️ در حال صحبت"
                                    else if (user.isVideo) "📹 تصویری"
                                    else "آنلاین"
                                )
                            },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(40.dp).clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(user.name.take(1), color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        )
                    }
                }
                HorizontalDivider()
            }

            LazyColumn(Modifier.weight(1f)) {
                items(chats) { chat ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(chat.id) },
                        headlineContent = { Text(chat.title, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(
                                when (chat.type) {
                                    ChatType.GROUP -> "گروه"
                                    ChatType.CHANNEL -> "کانال"
                                    ChatType.BOT -> "ربات"
                                    else -> "چت"
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                when (chat.type) {
                                    ChatType.GROUP -> Icons.Default.Group
                                    ChatType.CHANNEL -> Icons.Default.Campaign
                                    ChatType.BOT -> Icons.Default.SmartToy
                                    else -> Icons.Default.Chat
                                },
                                contentDescription = null
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("ساخت جدید") },
            text = {
                Column {
                    Row {
                        FilterChip(
                            selected = createType == ChatType.GROUP,
                            onClick = { createType = ChatType.GROUP },
                            label = { Text("گروه") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = createType == ChatType.CHANNEL,
                            onClick = { createType = ChatType.CHANNEL },
                            label = { Text("کانال") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = createType == ChatType.BOT,
                            onClick = { createType = ChatType.BOT },
                            label = { Text("ربات") }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = createTitle,
                        onValueChange = { createTitle = it },
                        label = { Text("عنوان") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (createTitle.isNotBlank()) {
                        when (createType) {
                            ChatType.GROUP -> {
                                mqtt.createGroup(createTitle)
                                chats.add(0, Chat(id = "g_${createTitle.hashCode()}", title = createTitle, type = ChatType.GROUP))
                            }
                            ChatType.CHANNEL -> {
                                mqtt.createChannel(createTitle)
                                chats.add(0, Chat(id = "c_${createTitle.hashCode()}", title = createTitle, type = ChatType.CHANNEL))
                            }
                            ChatType.BOT -> {
                                chats.add(0, Chat(id = "bot_${createTitle.hashCode()}", title = "ربات $createTitle", type = ChatType.BOT))
                            }
                            else -> {}
                        }
                        createTitle = ""
                        showCreate = false
                    }
                }) { Text("بساز") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("لغو") }
            }
        )
    }
}
