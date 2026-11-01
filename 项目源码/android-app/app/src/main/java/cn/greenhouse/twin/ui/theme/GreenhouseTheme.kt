package cn.greenhouse.twin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 用户已认可的视觉基线 V1，后续页面统一复用 r02 令牌。
object GreenhouseColors {
    val Background = Color(0xFF031812)
    val Surface = Color(0xFF071D17)
    val Overview = Color(0xFF0A241D)
    val Raised = Color(0xFF123429)
    val Navigation = Color(0xFF02110D)
    val Outline = Color(0xFF1A3B2E)
    val Ambient = Color(0xFF24775A)
    val ModelGlow = Color(0xFF57D6B3)
    val Primary = Color(0xFF57D99A)
    val Text = Color(0xFFF1F7F3)
    val Secondary = Color(0xFF9FB8AB)
    val Muted = Color(0xFF78978A)
    val Running = Primary
    val Waiting = Color(0xFFD8BE6B)
    val Warning = Color(0xFFF4B45F)
    val Critical = Color(0xFFF17B72)
    val Offline = Color(0xFF8CA497)
    val Advice = Color(0xFFBCA2F5)
    val OnPrimary = Color(0xFF092719)
    val Scrim = Color.Black
}

object GreenhouseDimensions {
    val PageMargin = 20.dp
    val Gap = 8.dp
    val CardRadius = 20.dp
    val ButtonRadius = 14.dp
    val EnvironmentIcon = 52.dp
    val DeviceIcon = 48.dp
    val SettingsIcon = 33.dp
    val ModelStage = 170.dp
    val NavigationIcon = 54.dp
}

val GreenhouseTypography = Typography(
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
)

@Composable
fun GreenhouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = GreenhouseColors.Primary,
            onPrimary = GreenhouseColors.OnPrimary,
            background = GreenhouseColors.Background,
            onBackground = GreenhouseColors.Text,
            surface = GreenhouseColors.Surface,
            onSurface = GreenhouseColors.Text,
            surfaceVariant = GreenhouseColors.Raised,
            onSurfaceVariant = GreenhouseColors.Secondary,
            outline = GreenhouseColors.Outline,
            error = GreenhouseColors.Critical,
            scrim = GreenhouseColors.Scrim,
        ),
        typography = GreenhouseTypography,
        shapes = Shapes(medium = RoundedCornerShape(GreenhouseDimensions.ButtonRadius),
            large = RoundedCornerShape(GreenhouseDimensions.CardRadius)),
        content = content,
    )
}
