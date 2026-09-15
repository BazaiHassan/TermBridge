package io.termbridge.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The `termbridge_` wordmark with a blinking mint cursor. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1060
                1f at 0
                1f at 529
                0f at 530
                0f at 1059
            },
        ),
        label = "cursorAlpha",
    )
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append("termbridge")
            withStyle(SpanStyle(color = primary.copy(alpha = blink))) { append("_") }
        },
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier,
    )
}

/** A status dot; [pulsing] adds a soft halo for "live" states. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = false, size: Dp = 8.dp) {
    Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val t = rememberInfiniteTransition(label = "pulse")
            val scale by t.animateFloat(
                1f, 2f,
                infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart, StartOffset(0)),
                label = "haloScale",
            )
            val alpha by t.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "haloAlpha")
            Box(Modifier.size(size).scale(scale).alpha(alpha).background(color, CircleShape))
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** A compact rounded label, e.g. connection state or latency. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Always-visible notice for the unencrypted phase-1 build (docs/adr/0004 #9). */
@Composable
fun InsecureBuildBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                "Unencrypted dev build — use only on a trusted Wi-Fi. Pairing and end-to-end encryption arrive in phase 4.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
