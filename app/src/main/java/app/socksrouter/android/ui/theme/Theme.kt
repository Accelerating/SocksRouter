package app.socksrouter.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ForestGreen = Color(0xFF245C3A)
private val DeepGreen = Color(0xFF17462B)
private val Ivory = Color(0xFFFCFBF6)
private val SageSurface = Color(0xFFF1F5EC)
private val SageContainer = Color(0xFFE4EDE1)
private val WarmOutline = Color(0xFFD7DED2)
private val WarmText = Color(0xFF17221A)
private val MutedText = Color(0xFF68736B)

private val SocksRouterColors = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = SageContainer,
    onPrimaryContainer = DeepGreen,
    secondary = Color(0xFF54735D),
    onSecondary = Color.White,
    secondaryContainer = SageSurface,
    onSecondaryContainer = DeepGreen,
    background = Ivory,
    onBackground = WarmText,
    surface = Ivory,
    onSurface = WarmText,
    surfaceBright = Color(0xFFFFFEFA),
    surfaceDim = Color(0xFFE4E8E0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8F2),
    surfaceContainer = Color(0xFFF2F4ED),
    surfaceContainerHigh = Color(0xFFECEFE8),
    surfaceContainerHighest = Color(0xFFE5EAE2),
    surfaceVariant = SageSurface,
    onSurfaceVariant = MutedText,
    outline = WarmOutline,
)

@Composable
fun SocksRouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SocksRouterColors,
        typography = Typography(),
        content = content,
    )
}
