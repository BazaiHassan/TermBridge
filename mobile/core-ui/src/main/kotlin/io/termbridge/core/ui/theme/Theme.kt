package io.termbridge.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.termbridge.core.ui.R

/** Brand palette: near-black ink, mint signal, amber caution. */
object TbPalette {
    val Ink = Color(0xFF0B0E14)
    val Surface = Color(0xFF11151C)
    val SurfaceHigh = Color(0xFF171C25)
    val SurfaceHighest = Color(0xFF1E2430)
    val Outline = Color(0xFF2A3242)
    val OnInk = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B96A8)
    val Mint = Color(0xFF3DDC97)
    val MintDeep = Color(0xFF0E9F6E)
    val Sky = Color(0xFF59C2FF)
    val Amber = Color(0xFFFFB454)
    val Coral = Color(0xFFFF5F6D)
}

private val DarkScheme = darkColorScheme(
    primary = TbPalette.Mint,
    onPrimary = Color(0xFF04130C),
    primaryContainer = Color(0xFF123829),
    onPrimaryContainer = TbPalette.Mint,
    secondary = TbPalette.Sky,
    onSecondary = Color(0xFF00243A),
    tertiary = TbPalette.Amber,
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF33260F),
    onTertiaryContainer = TbPalette.Amber,
    error = TbPalette.Coral,
    onError = Color(0xFF2D0006),
    background = TbPalette.Ink,
    onBackground = TbPalette.OnInk,
    surface = TbPalette.Ink,
    onSurface = TbPalette.OnInk,
    surfaceVariant = TbPalette.SurfaceHigh,
    onSurfaceVariant = TbPalette.Muted,
    surfaceContainerLowest = TbPalette.Ink,
    surfaceContainerLow = TbPalette.Surface,
    surfaceContainer = TbPalette.Surface,
    surfaceContainerHigh = TbPalette.SurfaceHigh,
    surfaceContainerHighest = TbPalette.SurfaceHighest,
    outline = TbPalette.Outline,
    outlineVariant = Color(0xFF1F2633),
)

private val LightScheme = lightColorScheme(
    primary = TbPalette.MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF5E4),
    onPrimaryContainer = Color(0xFF00361F),
    secondary = Color(0xFF0B74B5),
    tertiary = Color(0xFF9A5B00),
    tertiaryContainer = Color(0xFFFFE6C2),
    onTertiaryContainer = Color(0xFF3A2300),
    error = Color(0xFFD62839),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF12161D),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF12161D),
    surfaceVariant = Color(0xFFE9EDF1),
    onSurfaceVariant = Color(0xFF5A6474),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF1F4),
    surfaceContainerHighest = Color(0xFFE6EAEE),
    outline = Color(0xFFD3D9E0),
    outlineVariant = Color(0xFFE3E7EC),
)

/** JetBrains Mono — the terminal face, reused for headings and technical labels. */
val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val Base = Typography()

private val TbTypography = Typography(
    displaySmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

private val TbShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TermBridgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = TbTypography,
        shapes = TbShapes,
        content = content,
    )
}
