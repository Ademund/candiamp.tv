package tv.candiamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Bandcamp-ish teal on near-black. */
object Colors {
    val bg = Color(0xFF0B0B0C)
    val bg2 = Color(0xFF1B1B1F)
    val bg3 = Color(0xFF2E2E34)
    val text = Color(0xFFF1F1F1)
    val muted = Color(0xFF9A9AA2)
    val accent = Color(0xFF4AA8B8)
    val accentDark = Color(0xFF08191C)
}

object Type {
    val hero = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Colors.text)
    val pageTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Colors.text)
    val section = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Colors.text)
    val title = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = Colors.text)
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = Colors.text)
    val sub = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = Colors.muted)
    val badge = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
    val code = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Colors.accent, letterSpacing = 8.sp)
}

@Composable
fun CandiampTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Colors.accent,
        onPrimary = Colors.accentDark,
        background = Colors.bg,
        onBackground = Colors.text,
        surface = Colors.bg,
        onSurface = Colors.text,
        surfaceVariant = Colors.bg2,
        onSurfaceVariant = Colors.muted,
        inverseSurface = Colors.text,
        inverseOnSurface = Colors.bg,
        secondaryContainer = Colors.bg2,
        onSecondaryContainer = Colors.text,
        border = Colors.text,
    ),
    content = content,
)
