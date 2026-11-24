package jp.audioscope.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Galaxy S26 Dynamic AMOLED 2X 向けラグジュアリー・ダークカラーパレット
val ObsidianBackground = Color(0xFF090B10)
val ObsidianSurface = Color(0xFF11151F)
val ObsidianSurfaceElevated = Color(0xFF181E2C)
val ObsidianSurfaceHigh = Color(0xFF222A3D)

val ChampagneGold = Color(0xFFE8C574)
val ChampagneGoldDim = Color(0xFF3A301A)
val LosslessCyan = Color(0xFF4FD8EB)
val LosslessCyanDim = Color(0xFF10333B)
val CobaltAccent = Color(0xFFB4C5FF)
val EmeraldOk = Color(0xFF67DCA8)
val WarningAmber = Color(0xFFFFB86C)
val ErrorCoral = Color(0xFFFF897D)
val TextPrimary = Color(0xFFF1F4FA)
val TextSecondary = Color(0xFF9DA7BA)
val OutlineSubtle = Color(0xFF2B344A)

private val AudioScopeDarkColorScheme = darkColorScheme(
    primary = ChampagneGold,
    onPrimary = Color(0xFF221A04),
    primaryContainer = ChampagneGoldDim,
    onPrimaryContainer = Color(0xFFFFE29E),
    secondary = LosslessCyan,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = LosslessCyanDim,
    onSecondaryContainer = Color(0xFFA6EEFF),
    tertiary = CobaltAccent,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSubtle,
    error = ErrorCoral
)

private val AudioScopeTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun AudioScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AudioScopeDarkColorScheme,
        typography = AudioScopeTypography,
        content = content
    )
}
