package com.aurora.tvplayer.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * The Aurora TV look users already know from 2.x: midnight navy, cyan focus fill, mint accents.
 * Kept on purpose so the 3.0 update feels like the same app, only calmer and sharper.
 */
object Aurora {
    val navy = Color(0xFF071522)
    val navyDeep = Color(0xFF050E18)
    val panel = Color(0xFF10263B)
    val panelHigh = Color(0xFF173452)
    val line = Color(0xFF24486A)
    val cyan = Color(0xFF2BE6EF)
    val mint = Color(0xFF80E3D4)
    val violet = Color(0xFF7B6CFF)
    val white = Color(0xFFF1F7FC)
    val muted = Color(0xFFA9C0D1)
    val faint = Color(0xFF6F8BA2)
    val good = Color(0xFF5BE39B)
    val warn = Color(0xFFF2C14E)
    val bad = Color(0xFFFF7A7A)

    val backdrop = Brush.verticalGradient(listOf(Color(0xFF06111D), Color(0xFF0A1B2C), Color(0xFF0F2740)))
    val scrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC050E18), Color(0xF2050E18)))
    val scrimTop = Brush.verticalGradient(listOf(Color(0xE6050E18), Color.Transparent))
    val cardGradient = Brush.linearGradient(listOf(Color(0xFF0F3A57), Color(0xFF122C46)))

    /** Thumbnail placeholders keep one hue per title. */
    private val thumbs = listOf(
        listOf(Color(0xFF1B5E7A), Color(0xFF0F2A44)),
        listOf(Color(0xFF3A4E9A), Color(0xFF14203F)),
        listOf(Color(0xFF16736C), Color(0xFF0E2D3A)),
        listOf(Color(0xFF5A3F8C), Color(0xFF1B1A3C)),
        listOf(Color(0xFF2C6A92), Color(0xFF102538)),
    )

    fun thumb(seed: String): Brush = Brush.linearGradient(thumbs[(seed.hashCode() and Int.MAX_VALUE) % thumbs.size])
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
)

@Composable
fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Aurora.cyan,
            onPrimary = Aurora.navy,
            background = Aurora.navy,
            onBackground = Aurora.white,
            surface = Aurora.panel,
            onSurface = Aurora.white,
            surfaceVariant = Aurora.panelHigh,
            onSurfaceVariant = Aurora.muted,
            error = Aurora.bad,
        ),
        typography = typography,
        content = content,
    )
}

/**
 * Navy backdrop with two soft aurora glows. When [animated], the glows drift slowly; the values
 * are read only while drawing, so the screen content is never recomposed by the animation.
 */
@Composable
fun AuroraBackground(animated: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val driftState = if (animated) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "drift",
        )
    } else null
    val drift: () -> Float = { driftState?.value ?: 0.35f }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Aurora.backdrop)
                val t = drift()
                val r = size.maxDimension * 0.55f
                val mint = Offset(size.width * (0.15f + 0.25f * t), size.height * (0.05f + 0.1f * t))
                drawCircle(Brush.radialGradient(listOf(Aurora.mint.copy(alpha = 0.16f), Color.Transparent), mint, r), r, mint)
                val violet = Offset(size.width * (0.95f - 0.2f * t), size.height * (0.85f - 0.15f * t))
                drawCircle(Brush.radialGradient(listOf(Aurora.violet.copy(alpha = 0.13f), Color.Transparent), violet, r), r, violet)
            },
        content = content,
    )
}
