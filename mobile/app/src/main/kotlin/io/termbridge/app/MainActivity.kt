package io.termbridge.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import io.termbridge.core.transport.ClientName
import io.termbridge.core.ui.theme.TermBridgeTheme
import io.termbridge.feature.devices.DevicesDestination
import io.termbridge.feature.devices.devicesScreen
import io.termbridge.feature.pairing.PairingDestination
import io.termbridge.feature.pairing.pairingScreen
import io.termbridge.feature.terminal.TerminalDestination
import io.termbridge.feature.terminal.TerminalSessions
import io.termbridge.feature.terminal.TerminalSettingsDestination
import io.termbridge.feature.terminal.terminalScreen
import io.termbridge.feature.terminal.terminalSettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltAndroidApp
class TermBridgeApp : Application()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A terminal to open, from the session notification. */
    private val openRequest = MutableStateFlow<TerminalDestination?>(null)

    @Inject lateinit var sessions: TerminalSessions

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequest.value = TerminalDestination.from(intent)
        setContent {
            TermBridgeTheme { TermBridgeNavHost(openRequest, sessions.running) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = TerminalDestination.from(intent)
    }
}

@Composable
private fun TermBridgeNavHost(openRequest: MutableStateFlow<TerminalDestination?>, live: Flow<Set<String>>) {
    val nav = rememberNavController()
    val request by openRequest.collectAsState()
    LaunchedEffect(request) {
        val destination = request ?: return@LaunchedEffect
        nav.navigate(destination) {
            popUpTo(DevicesDestination)
            launchSingleTop = true
        }
        openRequest.value = null
    }
    NavHost(
        navController = nav,
        startDestination = DevicesDestination,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        devicesScreen(
            live = live,
            onOpen = { m -> nav.navigate(TerminalDestination(m.agentId, m.name)) },
            onPair = { nav.navigate(PairingDestination) },
            onSettings = { nav.navigate(TerminalSettingsDestination) },
        )
        pairingScreen(
            onClose = { nav.popBackStack() },
            onPaired = { m ->
                nav.navigate(TerminalDestination(m.agentId, m.name)) { popUpTo(DevicesDestination) }
            },
        )
        terminalScreen(onBack = { nav.popBackStack() })
        terminalSettingsScreen(onBack = { nav.popBackStack() })
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @ClientName
    fun clientName(): String = "android/${BuildConfig.VERSION_NAME}"
}
