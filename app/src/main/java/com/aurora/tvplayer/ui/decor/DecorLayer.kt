package com.aurora.tvplayer.ui.decor

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aurora.tvplayer.data.Occasion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The decoration in effect; cards read it to put snow or a leaf on themselves. */
val LocalOccasion = staticCompositionLocalOf<Occasion?> { null }

/** Soft glow colours of the aurora backdrop for each season and holiday. */
fun auroraGlows(occasion: Occasion): Pair<Color, Color> = when (occasion) {
    Occasion.WINTER -> Color(0xFF9FD8FF) to Color(0xFF7B8CFF)
    Occasion.NEW_YEAR -> Color(0xFFFFC86B) to Color(0xFFFF5470)
    Occasion.SPRING -> Color(0xFFFF9EC2) to Color(0xFF7CE0A0)
    Occasion.SUMMER -> Color(0xFFFFC66B) to Color(0xFF2BE6EF)
    Occasion.AUTUMN -> Color(0xFFFF8A3D) to Color(0xFFC0392B)
    Occasion.VALENTINE -> Color(0xFFFF5C7A) to Color(0xFFB45CFF)
    Occasion.HALLOWEEN -> Color(0xFFFF7A1A) to Color(0xFF8E5BD6)
    Occasion.EASTER -> Color(0xFFFFE08A) to Color(0xFFC9A8FF)
    Occasion.INDEPENDENCE -> Color(0xFF3D8BFF) to Color(0xFFFFD500)
}

/**
 * Everything that moves, shared by the layer behind the menus (bokeh, stars) and the one in
 * front (snow, leaves, salute). One frame loop drives both; it stops in the background.
 */
class DecorState internal constructor(val occasion: Occasion) {
    internal var clock by mutableFloatStateOf(0f)
    internal var size by mutableStateOf(IntSize.Zero)
    internal val front = frontFlocks(occasion)
    internal val back = backFlocks(occasion)
    internal val fireworks = if (occasion == Occasion.NEW_YEAR || occasion == Occasion.INDEPENDENCE) Fireworks(occasion) else null
    internal val shootingStars = if (occasion == Occasion.WINTER || occasion == Occasion.NEW_YEAR || occasion == Occasion.SUMMER || occasion == Occasion.HALLOWEEN) ShootingStar() else null
}

@Composable
fun rememberDecor(occasion: Occasion, home: Boolean): DecorState {
    val state = remember(occasion) { DecorState(occasion) }
    val dp = LocalDensity.current.density
    val onHome by rememberUpdatedState(home)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, state.size) {
        val size = state.size
        if (size == IntSize.Zero) return@LaunchedEffect
        val area = Size(size.width.toFloat(), size.height.toFloat())
        (state.front + state.back).forEach { it.scatter(area, dp) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val t = state.clock + dt
                // Gusts of wind now and then push the snow and leaves sideways together.
                val wind = sin(t * 0.11f) * sin(t * 0.29f + 1f) * 42f * dp
                state.front.forEach { it.step(dt, t, area, dp, wind) }
                state.back.forEach { it.step(dt, t, area, dp, wind * 0.2f) }
                state.fireworks?.step(t, area, dp, enabled = onHome)
                state.shootingStars?.step(t, area, dp)
                state.clock = t
            }
        }
    }
    return state
}

/** Behind the menus: drifting bokeh lights, twinkling stars and an occasional shooting star. */
@Composable
fun DecorBackdrop(state: DecorState, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val dp = density
            onDrawBehind {
                val t = state.clock
                state.back.forEach { it.draw(this) }
                state.shootingStars?.draw(this, t, dp)
            }
        },
    )
}

/** In front of the menus, under dialogs. Takes no focus and no input. */
@Composable
fun DecorLayer(state: DecorState, home: Boolean, modifier: Modifier = Modifier) {
    val dp = LocalDensity.current.density
    Spacer(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.size = it }
            .graphicsLayer()
            .drawWithCache {
                val scenery = Scenery(state.occasion, size, dp, home)
                onDrawBehind {
                    val t = state.clock
                    scenery.back(this, t)
                    state.front.forEach { it.draw(this) }
                    state.fireworks?.draw(this, t, dp)
                    scenery.front(this, t)
                }
            },
    )
}

internal enum class Motion { FALL, RISE, FLY, STILL }

/**
 * One kind of particle. Each has a depth: far ones are smaller, slower and fainter, which gives
 * the snow its feeling of space. Drawn from small pre-rendered images, one quad per particle.
 */
internal class Flock(
    val sprites: List<ImageBitmap>,
    val count: Int,
    val motion: Motion,
    val sizeDp: ClosedFloatingPointRange<Float>,
    val speedDp: ClosedFloatingPointRange<Float>,
    val alpha: ClosedFloatingPointRange<Float>,
    val swayDp: Float,
    val spin: Float = 0f,
    val flip: Boolean = false,
    val twinkle: Boolean = false,
    val flap: Boolean = false,
    /** Fraction of the screen height used by [Motion.STILL] particles (stars stay in the sky). */
    val band: Float = 1f,
) {
    private val random = Random(sprites.size * 31 + count + motion.ordinal)
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val base = FloatArray(count)
    private val depth = FloatArray(count)
    private val phase = FloatArray(count)
    private val rate = FloatArray(count)
    private val angle = FloatArray(count)
    private val direction = FloatArray(count)
    private val sprite = IntArray(count)
    private val sx = FloatArray(count)
    private val sy = FloatArray(count)
    private val shade = FloatArray(count)
    private val sizePx = FloatArray(count)

    fun scatter(area: Size, dp: Float) {
        for (i in 0 until count) {
            spawn(i, area, dp)
            when (motion) {
                Motion.FLY -> x[i] = random.nextFloat() * area.width
                Motion.STILL -> Unit
                else -> y[i] = random.nextFloat() * area.height
            }
        }
    }

    private fun spawn(i: Int, area: Size, dp: Float) {
        val z = random.nextFloat()
        depth[i] = z
        sizePx[i] = lerp(sizeDp, z) * dp
        phase[i] = random.nextFloat() * 2 * PI.toFloat()
        rate[i] = 0.4f + random.nextFloat() * 0.9f
        angle[i] = random.nextFloat() * 360f
        sprite[i] = random.nextInt(sprites.size)
        direction[i] = if (random.nextBoolean()) 1f else -1f
        when (motion) {
            Motion.FALL -> { base[i] = random.nextFloat() * area.width; y[i] = -sizePx[i] }
            Motion.RISE -> { base[i] = random.nextFloat() * area.width; y[i] = area.height + sizePx[i] }
            Motion.FLY -> {
                base[i] = area.height * (0.08f + random.nextFloat() * 0.5f)
                x[i] = if (direction[i] > 0) -sizePx[i] else area.width + sizePx[i]
            }
            Motion.STILL -> { x[i] = random.nextFloat() * area.width; y[i] = random.nextFloat() * area.height * band }
        }
    }

    fun step(dt: Float, t: Float, area: Size, dp: Float, wind: Float) {
        for (i in 0 until count) {
            val z = depth[i]
            val speed = lerp(speedDp, z) * dp
            val sway = sin(t * rate[i] + phase[i]) * swayDp * dp * (0.5f + z * 0.5f)
            when (motion) {
                Motion.FALL -> {
                    y[i] += speed * dt
                    base[i] += wind * dt * (0.4f + 0.6f * z)
                    if (base[i] < -40 * dp) base[i] += area.width + 80 * dp
                    if (base[i] > area.width + 40 * dp) base[i] -= area.width + 80 * dp
                    x[i] = base[i] + sway
                    if (y[i] > area.height + sizePx[i]) spawn(i, area, dp)
                }
                Motion.RISE -> {
                    y[i] -= speed * dt
                    base[i] += wind * dt * 0.5f
                    x[i] = base[i] + sway
                    if (y[i] < -sizePx[i]) spawn(i, area, dp)
                }
                Motion.FLY -> {
                    x[i] += speed * dt * direction[i]
                    y[i] = base[i] + sway
                    if (x[i] < -2 * sizePx[i] || x[i] > area.width + 2 * sizePx[i]) spawn(i, area, dp)
                }
                Motion.STILL -> Unit
            }
            angle[i] += spin * dt * (if (direction[i] > 0) 1f else -1f) * (0.6f + z * 0.4f)
            sx[i] = if (motion == Motion.FLY) direction[i] else if (flip) cos(t * rate[i] * 2.2f + phase[i]) else 1f
            sy[i] = if (flap) 0.45f + 0.55f * abs(sin(t * 7f * rate[i] + phase[i])) else 1f
            val a = lerp(alpha, z)
            shade[i] = if (twinkle) a * (0.3f + 0.7f * abs(sin(t * rate[i] * 1.7f + phase[i]))) else a
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        for (i in 0 until count) {
            val image = sprites[sprite[i]]
            val scale = sizePx[i] / image.width
            val px = x[i]
            val py = y[i]
            val rotation = if (motion == Motion.FLY || motion == Motion.STILL) 0f else angle[i]
            withTransform({
                translate(px, py)
                rotate(rotation, Offset.Zero)
                scale(scale * sx[i], scale * sy[i], Offset.Zero)
            }) {
                drawImage(image, Offset(-image.width / 2f, -image.height / 2f), alpha = shade[i])
            }
        }
    }

    private fun lerp(range: ClosedFloatingPointRange<Float>, z: Float) = range.start + (range.endInclusive - range.start) * z
}

private fun frontFlocks(occasion: Occasion): List<Flock> = when (occasion) {
    Occasion.WINTER, Occasion.NEW_YEAR -> listOf(
        Flock(listOf(Sprites.softFlake()), 56, Motion.FALL, 3f..9f, 16f..46f, 0.35f..0.95f, swayDp = 16f),
        Flock(listOf(Sprites.crystal()), 14, Motion.FALL, 12f..22f, 20f..40f, 0.55f..0.95f, swayDp = 22f, spin = 26f),
    )
    Occasion.SPRING -> listOf(
        Flock(
            listOf(
                Sprites.petal(Color(0xFFFFE6EF), Color(0xFFFF9EC2)),
                Sprites.petal(Color(0xFFFFF5F8), Color(0xFFFFC1D6)),
                Sprites.petal(Color(0xFFFFD6E5), Color(0xFFE87AA3)),
            ),
            30, Motion.FALL, 13f..22f, 22f..48f, 0.55f..0.95f, swayDp = 30f, spin = 70f, flip = true,
        ),
    )
    Occasion.SUMMER -> listOf(
        Flock(listOf(Sprites.glow(Color(0xFFFFE08A)), Sprites.glow(Color(0xFFFFF3C4)), Sprites.glow(Color(0xFFD8FF8A))),
            30, Motion.RISE, 10f..22f, 5f..15f, 0.45f..1f, swayDp = 34f, twinkle = true),
    )
    Occasion.AUTUMN -> listOf(
        Flock(
            listOf(
                Sprites.maple(Color(0xFFF08A3A), Color(0xFFB4421E)),
                Sprites.maple(Color(0xFFE0533A), Color(0xFF8E2219)),
                Sprites.maple(Color(0xFFF7C24A), Color(0xFFC77F16)),
                Sprites.maple(Color(0xFFC9662A), Color(0xFF7A3414)),
            ),
            22, Motion.FALL, 22f..38f, 28f..60f, 0.6f..0.95f, swayDp = 44f, spin = 85f, flip = true,
        ),
    )
    Occasion.VALENTINE -> listOf(
        Flock(listOf(Sprites.heart(Color(0xFFFF6B88), Color(0xFFC72C4F)), Sprites.heart(Color(0xFFFFA3B5), Color(0xFFE0506E))),
            22, Motion.FALL, 12f..24f, 16f..36f, 0.5f..0.95f, swayDp = 26f, spin = 18f),
    )
    Occasion.HALLOWEEN -> listOf(
        Flock(listOf(Sprites.glow(Color(0xFFFF9A3C)), Sprites.glow(Color(0xFFFFC46B))), 20, Motion.RISE, 5f..10f, 10f..26f, 0.4f..0.95f, swayDp = 20f, twinkle = true),
        Flock(listOf(Sprites.bat()), 6, Motion.FLY, 28f..46f, 50f..95f, 0.75f..1f, swayDp = 22f, flap = true),
    )
    Occasion.EASTER -> listOf(
        Flock(
            listOf(
                Sprites.petal(Color(0xFFFFF6DC), Color(0xFFF2D49B)),
                Sprites.petal(Color(0xFFF1E6FF), Color(0xFFB79AE6)),
                Sprites.petal(Color(0xFFFFE6EF), Color(0xFFFF9EC2)),
            ),
            26, Motion.FALL, 13f..21f, 20f..42f, 0.55f..0.95f, swayDp = 28f, spin = 60f, flip = true,
        ),
    )
    Occasion.INDEPENDENCE -> listOf(
        Flock(listOf(Sprites.confetti(Color(0xFF3D8BFF)), Sprites.confetti(Color(0xFFFFD500))),
            40, Motion.FALL, 13f..21f, 40f..80f, 0.6f..0.95f, swayDp = 18f, spin = 150f, flip = true),
    )
}

/** Big, very soft lights drifting up behind the menus, plus stars on winter and night holidays. */
private fun backFlocks(occasion: Occasion): List<Flock> {
    val (first, second) = auroraGlows(occasion)
    val bokeh = Flock(listOf(Sprites.glow(first), Sprites.glow(second), Sprites.glow(Color.White)), 12, Motion.RISE, 40f..120f, 4f..11f, 0.08f..0.22f, swayDp = 36f)
    val stars = Flock(listOf(Sprites.glow(Color.White), Sprites.glow(Color(0xFFCFE6FF))), 46, Motion.STILL, 3f..8f, 0f..0f, 0.35f..0.95f, swayDp = 0f, twinkle = true, band = 0.7f)
    return when (occasion) {
        Occasion.WINTER, Occasion.NEW_YEAR, Occasion.HALLOWEEN, Occasion.SUMMER -> listOf(stars, bokeh)
        else -> listOf(bokeh)
    }
}

/** A streak across the upper sky every 8–16 seconds. */
internal class ShootingStar {
    private val random = Random(7)
    private var start = Offset.Zero
    private var velocity = Offset.Zero
    private var startedAt = -1f
    private var nextAt = 5f

    fun step(t: Float, area: Size, dp: Float) {
        if (startedAt >= 0 && t - startedAt > LIFE) startedAt = -1f
        if (startedAt >= 0 || t < nextAt) return
        start = Offset(area.width * (0.2f + random.nextFloat() * 0.6f), area.height * (0.04f + random.nextFloat() * 0.2f))
        val angle = (150f + random.nextFloat() * 30f) * PI.toFloat() / 180
        velocity = Offset(cos(angle), sin(angle)) * ((520 + random.nextFloat() * 240) * dp)
        startedAt = t
        nextAt = t + 8f + random.nextFloat() * 8f
    }

    fun draw(scope: DrawScope, t: Float, dp: Float) = with(scope) {
        if (startedAt < 0) return@with
        val age = t - startedAt
        val head = start + velocity * age
        val tail = head - velocity * 0.18f
        val fade = sin(PI.toFloat() * (age / LIFE)).coerceAtLeast(0f)
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * fade)), start = tail, end = head), tail, head, strokeWidth = 2.2f * dp, cap = StrokeCap.Round)
        drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = fade), 1f to Color.Transparent, center = head, radius = 7 * dp), 7 * dp, head)
    }

    private companion object {
        const val LIFE = 0.9f
    }
}

/** Salute on the home screen: a rocket climbs, bursts, the sparks glitter as they fade. */
internal class Fireworks(occasion: Occasion) {
    private val sparks = 56
    private val palette = if (occasion == Occasion.INDEPENDENCE) {
        listOf(Color(0xFF3D8BFF), Color(0xFFFFD500), Color(0xFF8CC0FF), Color(0xFFFFE780))
    } else {
        listOf(Color(0xFFFFD27A), Color(0xFF2BE6EF), Color(0xFFFF7AA8), Color(0xFFFFFFFF), Color(0xFFB28CFF), Color(0xFF7CE05E))
    }
    private val random = Random(occasion.ordinal)
    private val angle = FloatArray(sparks)
    private val speed = FloatArray(sparks)
    private var launch = Offset.Zero
    private var origin = Offset.Zero
    private var color = palette.first()
    private var accent = palette.first()
    private var startedAt = -1f
    private var nextAt = 2.5f

    fun step(t: Float, area: Size, dp: Float, enabled: Boolean) {
        if (startedAt >= 0 && t - startedAt > RISE + LIFE) startedAt = -1f
        if (!enabled || startedAt >= 0 || t < nextAt) return
        // Over the banner's picture and title, clear of the clock in its top-right corner.
        origin = Offset(area.width * (0.14f + random.nextFloat() * 0.62f), area.height * (0.07f + random.nextFloat() * 0.2f))
        launch = Offset(origin.x + (random.nextFloat() - 0.5f) * 80 * dp, area.height * 0.62f)
        color = palette[random.nextInt(palette.size)]
        accent = palette[random.nextInt(palette.size)]
        for (i in 0 until sparks) {
            angle[i] = i * 2 * PI.toFloat() / sparks + random.nextFloat() * 0.1f
            speed[i] = (130 + random.nextFloat() * 90) * dp * (if (i % 2 == 0) 1f else 0.7f)
        }
        startedAt = t
        nextAt = t + RISE + LIFE + 2.5f + random.nextFloat() * 4f
    }

    fun draw(scope: DrawScope, t: Float, dp: Float) = with(scope) {
        if (startedAt < 0) return@with
        val age = t - startedAt
        if (age < RISE) {
            val u = age / RISE
            val eased = 1 - (1 - u) * (1 - u)
            val head = launch + (origin - launch) * eased
            val tail = launch + (origin - launch) * (eased - 0.12f).coerceAtLeast(0f)
            drawLine(Brush.linearGradient(listOf(Color.Transparent, color.copy(alpha = 0.9f)), start = tail, end = head), tail, head, strokeWidth = 2f * dp, cap = StrokeCap.Round)
            drawCircle(Color.White, 2.2f * dp, head)
            return@with
        }
        val burst = age - RISE
        val fade = (1f - burst / LIFE).coerceIn(0f, 1f)
        if (burst < 0.3f) {
            val flash = 1f - burst / 0.3f
            drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.6f * flash), 0.3f to color.copy(alpha = 0.35f * flash), 1f to Color.Transparent, center = origin, radius = 90 * dp), 90 * dp, origin)
        }
        for (i in 0 until sparks) {
            val head = position(i, burst, dp)
            val tail = position(i, (burst - 0.14f).coerceAtLeast(0f), dp)
            val c = if (i % 3 == 0) accent else color
            // In the last part of their life the sparks glitter.
            val glitter = if (fade < 0.45f) 0.4f + 0.6f * abs(sin(t * 28f + i * 1.7f)) else 1f
            drawLine(c.copy(alpha = 0.55f * fade), tail, head, strokeWidth = 1.8f * dp, cap = StrokeCap.Round)
            drawCircle(c.copy(alpha = fade * glitter), 2.1f * dp, head)
        }
    }

    private fun position(i: Int, age: Float, dp: Float): Offset {
        // Sparks slow down in the air and fall a little.
        val travel = speed[i] * (1 - exp(-age * 2.2f)) / 2.2f
        return origin + Offset(cos(angle[i]) * travel, sin(angle[i]) * travel + 30 * dp * age * age)
    }

    private companion object {
        const val RISE = 0.7f
        const val LIFE = 2.2f
    }
}
