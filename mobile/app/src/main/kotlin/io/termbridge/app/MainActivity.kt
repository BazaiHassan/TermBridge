package io.termbridge.app

import android.app.Application
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
import io.termbridge.feature.terminal.terminalScreen

@HiltAndroidApp
class TermBridgeApp : Application()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TermBridgeTheme { TermBridgeNavHost() }
        }
    }
}

@Composable
private fun TermBridgeNavHost() {
    val nav = rememberNavController()
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
            onOpen = { m -> nav.navigate(TerminalDestination(m.agentId, m.name)) },
            onPair = { nav.navigate(PairingDestination) },
        )
        pairingScreen(
            onClose = { nav.popBackStack() },
            onPaired = { m ->
                nav.navigate(TerminalDestination(m.agentId, m.name)) { popUpTo(DevicesDestination) }
            },
        )
        terminalScreen(onBack = { nav.popBackStack() })
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @ClientName
    fun clientName(): String = "android/${BuildConfig.VERSION_NAME}"
}
