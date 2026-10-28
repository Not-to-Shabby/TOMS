package ph.toms.conductor.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One light theme, high contrast, because the phone is used in direct sun. Ratios below were computed
 * with the WCAG formula; all are at least 4.5:1 (the minimum for normal text). Status is always
 * colour plus a word or shape, never colour alone.
 */
object TomsColors {
    val Paper = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111418)        // 18.5:1 on Paper
    val InkSoft = Color(0xFF3F4650)    // 9.5:1 on Paper
    val Line = Color(0xFF8A929C)
    val Surface = Color(0xFFF1F3F5)

    val Primary = Color(0xFF0B3D91)    // 10.0:1 against white text
    val OnPrimary = Color(0xFFFFFFFF)

    val Good = Color(0xFF0A6B2E)       // 6.7:1 against white text, 5.6:1 on GoodBg
    val GoodBg = Color(0xFFD7F2E0)
    val Warn = Color(0xFF7A4A00)       // 6.3:1 on WarnBg
    val WarnBg = Color(0xFFFFE9B8)
    val Bad = Color(0xFFA11212)        // 8.0:1 against white text, 5.9:1 on BadBg
    val BadBg = Color(0xFFFAD4D4)
}

private val scheme = lightColorScheme(
    primary = TomsColors.Primary,
    onPrimary = TomsColors.OnPrimary,
    secondary = TomsColors.Ink,
    onSecondary = TomsColors.Paper,
    background = TomsColors.Paper,
    onBackground = TomsColors.Ink,
    surface = TomsColors.Paper,
    onSurface = TomsColors.Ink,
    surfaceVariant = TomsColors.Surface,
    onSurfaceVariant = TomsColors.InkSoft,
    outline = TomsColors.Line,
    error = TomsColors.Bad,
    onError = TomsColors.Paper,
)

private val type = Typography(
    // The fare: read at arm's length on a moving bus.
    displayLarge = androidx.compose.ui.text.TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Black, lineHeight = 76.sp),
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun TomsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/** Smallest thing a thumb should have to hit on a moving vehicle. Material's own minimum is 48dp. */
val TouchTarget = 64.dp
val Gap = 12.dp

/** A full-width primary action. Full width means it is as easy to hit with either thumb. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TomsColors.Primary, contentColor = TomsColors.OnPrimary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTarget),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = TomsColors.Ink) }
}

/** A solid status chip: a shape and a word as well as a colour. */
@Composable
fun StatusChip(text: String, kind: Status, modifier: Modifier = Modifier) {
    val (bg, fg, mark) = when (kind) {
        Status.Good -> Triple(TomsColors.GoodBg, TomsColors.Good, "✓")
        Status.Warn -> Triple(TomsColors.WarnBg, TomsColors.Warn, "!")
        Status.Bad -> Triple(TomsColors.BadBg, TomsColors.Bad, "✕")
        Status.Neutral -> Triple(TomsColors.Surface, TomsColors.InkSoft, "•")
    }
    Row(
        modifier = modifier.height(36.dp).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) { Text(mark, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black) }
        Text(text, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}

enum class Status { Good, Warn, Bad, Neutral }

@Composable
fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(Gap)) {
        content()
    }
}
