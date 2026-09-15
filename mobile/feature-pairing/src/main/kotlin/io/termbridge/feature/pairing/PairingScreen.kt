package io.termbridge.feature.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import io.termbridge.core.crypto.PairedMachine
import io.termbridge.core.ui.theme.TbPalette
import io.termbridge.core.ui.theme.TermBridgeTheme
import kotlinx.serialization.Serializable

@Serializable
data object PairingDestination

fun NavGraphBuilder.pairingScreen(onClose: () -> Unit, onPaired: (PairedMachine) -> Unit) {
    composable<PairingDestination> {
        val vm: PairingViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(state) { (state as? PairingState.Paired)?.let { onPaired(it.machine) } }
        TermBridgeTheme(darkTheme = true) { PairingScreen(state, vm, onClose) }
    }
}

@Composable
private fun PairingScreen(state: PairingState, vm: PairingViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize().background(TbPalette.Ink)) {
        if (granted && state is PairingState.Scanning) CameraScanner(onScanned = vm::onScanned)
        ScanOverlay(active = state is PairingState.Scanning && granted)

        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            Text("Pair a computer", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "pairing") { s ->
                when (s) {
                    is PairingState.Scanning -> ScanHint(
                        hint = if (!granted) "Camera access is needed to scan the code." else s.hint,
                        cameraMissing = !granted,
                        onAllowCamera = { request.launch(Manifest.permission.CAMERA) },
                        onPaste = { clipboard.getText()?.text?.let(vm::onScanned) },
                    )
                    is PairingState.Confirm -> ConfirmCard(s, onNameChange = vm::setDeviceName, onPair = vm::pair, onRescan = vm::scanAgain)
                    is PairingState.Pairing -> StatusCard(busy = true, title = "Pairing with ${s.qr.name}…", detail = "Setting up end-to-end encryption")
                    is PairingState.Failed -> StatusCard(busy = false, title = "Couldn't pair", detail = s.message, action = "Scan again", onAction = vm::scanAgain)
                    is PairingState.Paired -> StatusCard(busy = false, title = "Paired with ${s.machine.name}", detail = "Opening a shell…")
                }
            }
        }
    }
}

/** CameraX preview + ML Kit QR analysis on the view's lifecycle. */
@Composable
private fun CameraScanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(lifecycle) {
        val executor = ContextCompat.getMainExecutor(context)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                result.getValue(scanner)?.firstNotNullOfOrNull { it.rawValue }?.let(onScanned)
            },
        )
        controller.bindToLifecycle(lifecycle)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { ctx -> PreviewView(ctx).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Dim scrim with a rounded viewfinder, mint corner brackets and a sweeping scan line. */
@Composable
private fun ScanOverlay(active: Boolean) {
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "sweep",
    )
    val mint = TbPalette.Mint
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f
        val topLeft = Offset((size.width - side) / 2, size.height * 0.40f - side / 2)
        val radius = CornerRadius(28.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.62f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
        val arm = side * 0.16f
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val r = radius.x
        for ((cx, cy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            val x = topLeft.x + cx * side
            val y = topLeft.y + cy * side
            val dx = if (cx == 0f) 1 else -1
            val dy = if (cy == 0f) 1 else -1
            drawLine(mint, Offset(x + dx * r, y), Offset(x + dx * (r + arm), y), stroke.width, StrokeCap.Round)
            drawLine(mint, Offset(x, y + dy * r), Offset(x, y + dy * (r + arm)), stroke.width, StrokeCap.Round)
        }
        if (active) {
            val y = topLeft.y + side * (0.08f + 0.84f * sweep)
            drawLine(mint.copy(alpha = 0.85f), Offset(topLeft.x + side * 0.1f, y), Offset(topLeft.x + side * 0.9f, y), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun ScanHint(hint: String?, cameraMissing: Boolean, onAllowCamera: () -> Unit, onPaste: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Scan the code on your computer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Open TermBridge Desktop, or run  termbridge pair  in a terminal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cameraMissing) Button(onClick = onAllowCamera) { Text("Allow camera") }
                TextButton(onClick = onPaste) { Text("Paste pairing code") }
            }
        }
    }
}

@Composable
private fun ConfirmCard(s: PairingState.Confirm, onNameChange: (String) -> Unit, onPair: () -> Unit, onRescan: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.qr.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(s.qr.name, style = MaterialTheme.typography.titleLarge)
                    Text(s.qr.lan.joinToString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Fingerprint  ${s.fingerprint}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "It should match the fingerprint shown on your computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = s.deviceName,
                onValueChange = onNameChange,
                label = { Text("This phone's name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f).height(50.dp)) { Text("Scan again") }
                Button(onClick = onPair, modifier = Modifier.weight(1f).height(50.dp)) { Text("Pair") }
            }
        }
    }
}

@Composable
private fun StatusCard(busy: Boolean, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(action) }
            }
        }
    }
}
