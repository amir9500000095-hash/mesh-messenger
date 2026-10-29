package com.mesh.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mesh.messenger.call.CallManager
import com.mesh.messenger.data.MqttRepository
import com.mesh.messenger.ui.screens.ChatScreen
import com.mesh.messenger.ui.screens.HomeScreen
import com.mesh.messenger.ui.screens.LoginScreen
import com.mesh.messenger.ui.theme.MeshTheme

class MainActivity : ComponentActivity() {

    private val mqtt = MqttRepository()
    private var callManager: CallManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNeededPermissions()

        callManager = CallManager(this, mqtt, lifecycleScope)

        setContent {
            MeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeshNav(mqtt, callManager)
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val need = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    override fun onDestroy() {
        callManager?.release()
        mqtt.disconnect()
        super.onDestroy()
    }
}

@Composable
fun MeshNav(mqtt: MqttRepository, callManager: CallManager?) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "login") {
        composable("login") {
            LoginScreen(
                onJoin = { name, room ->
                    mqtt.setIdentity(name, room)
                    nav.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                mqtt = mqtt,
                onOpenChat = { chatId -> nav.navigate("chat/$chatId") },
                onLogout = {
                    mqtt.disconnect()
                    nav.navigate("login") { popUpTo(0) }
                }
            )
        }
        composable("chat/{chatId}") { backStack ->
            val chatId = backStack.arguments?.getString("chatId") ?: "عمومی"
            val peerId = mqtt.onlineUsers.value.keys.firstOrNull()
            ChatScreen(
                mqtt = mqtt,
                callManager = callManager,
                chatId = chatId,
                peerIdForCall = peerId,
                onBack = { nav.popBackStack() }
            )
        }
    }
}
