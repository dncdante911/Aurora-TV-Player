package com.aurora.tvplayer.ui.decor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.aurora.tvplayer.data.Occasion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Where the home banner sits; [com.aurora.tvplayer.ui.home.HomeScreen] lays it out with these. */
object BannerLayout {
    const val SIDE_DP = 48f
    const val TOP_DP = 28f
    const val HEIGHT_DP = 112f
}

/**
 * The still part of the decoration: snow lying on the AURORA TV banner with icicles under it,
 * a garland, a branch over the banner's picture… Paths are built once per screen size; per frame
 * only lights, a sway and a spider move. On screens without the banner the snow lies along the top.
 */
internal class Scenery(private val occasion: Occasion, size: Size, private val dp: Float, home: Boolean) {
    private val w = size.width
    private val h = size.height
    private val random = Random(occasion.ordinal * 7919 + w.toInt())
    private val banner = if (home) Rect(BannerLayout.SIDE_DP * dp, BannerLayout.TOP_DP * dp, w - BannerLayout.SIDE_DP * dp, (BannerLayout.TOP_DP + BannerLayout.HEIGHT_DP) * dp) else null

    private val snowy = occasion == Occasion.WINTER || occasion == Occasion.NEW_YEAR
    private val snowLine = if (snowy) snowLine() else null
    private val icicles = if (snowy) icicles() else emptyList()
    private val drift = if (snowy) drift() else null
    private val garland = if (occasion == Occasion.NEW_YEAR) garland() else null
    private val branch = if (occasion == Occasion.SPRING || occasion == Occasion.EASTER) branch() else null
    private val leaves = if (occasion == Occasion.AUTUMN) leaves() else emptyList()
    private val web = if (occasion == Occasion.HALLOWEEN) web() else null
    private val rays = if (occasion == Occasion.SUMMER) rays() else null

    /** Front glows that brighten the corners a little, under the particles. */
    fun back(scope: DrawScope, t: Float) = with(scope) {
        when (occasion) {
            Occasion.WINTER, Occasion.NEW_YEAR -> {
                glow(Offset(0f, 0f), 260 * dp, Color.White.copy(alpha = 0.1f))
                glow(Offset(w, 0f), 260 * dp, Color.White.copy(alpha = 0.1f))
            }
            Occasion.SUMMER -> {
                val sun = Offset(w - 40 * dp, -40 * dp)
                glow(sun, 420 * dp, Color(0x2EFFC66B))
                rays?.let { path ->
                    rotate(t * 2.5f, sun) {
                        drawPath(path, Brush.radialGradient(0f to Color(0x1CFFE6A6), 0.5f to Color(0x08FFE6A6), 1f to Color.Transparent, center = sun, radius = 560 * dp))
                    }
                }
            }
            else -> Unit
        }
    }

    /** In front of the particles: snow lands behind the drift, the garland hangs over everything. */
    fun front(scope: DrawScope, t: Float) = with(scope) {
        snowLine?.let {
            drawPath(it, Color(0x66000000), style = Stroke(2.5f * dp))
            drawPath(it, Brush.verticalGradient(listOf(Color.White, Color(0xFFD4E4F3)), startY = (banner?.top ?: 0f) - 16 * dp, endY = (banner?.top ?: 0f) + 14 * dp))
        }
        icicles.forEach { (path, top) ->
            drawPath(path, Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0x55BFD9F0)), startY = top, endY = top + 26 * dp))
        }
        drift?.let {
            drawPath(it, Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xFFC9D9EA)), startY = h - 14 * dp, endY = h))
            drawPath(it, Color.White, style = Stroke(1.2f * dp))
        }
        garland?.let { drawGarland(it, t) }
        branch?.let { drawBranch(it, t) }
        leaves.forEach { drawLeaf(it) }
        web?.let { drawWeb(it, t) }
        if (occasion == Occasion.INDEPENDENCE) {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF3D8BFF), Color(0xFFFFD500))), size = Size(w, 4 * dp), alpha = 0.9f)
        }
    }

    private fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
        drawCircle(Brush.radialGradient(0f to color, 1f to Color.Transparent, center = center, radius = radius), radius, center)
    }

    // Winter: a thick layer of snow on the banner (or the top of the page), icicles, a drift.

    private fun snowLine() = Path().apply {
        val left = banner?.left ?: 0f
        val right = banner?.right ?: w
        val base = banner?.top ?: 0f
        // Snow sits on the edge: it rises above the banner and hangs a little over its face.
        moveTo(left - 4 * dp, base + 2 * dp)
        var x = left - 4 * dp
        while (x < right + 4 * dp) {
            val next = (x + (26 + random.nextFloat() * 36) * dp).coerceAtMost(right + 4 * dp)
            quadraticTo((x + next) / 2, base - (12 + random.nextFloat() * 10) * dp, next, base - (3 + random.nextFloat() * 4) * dp)
            x = next
        }
        lineTo(right + 4 * dp, base + 6 * dp)
        x = right + 4 * dp
        while (x > left - 4 * dp) {
            val next = (x - (18 + random.nextFloat() * 26) * dp).coerceAtLeast(left - 4 * dp)
            quadraticTo((x + next) / 2, base + (8 + random.nextFloat() * 8) * dp, next, base + (4 + random.nextFloat() * 2) * dp)
            x = next
        }
        close()
    }

    /** Hang from the banner's bottom edge (right part, away from the section title) or from the top. */
    private fun icicles(): List<Pair<Path, Float>> {
        val top = banner?.bottom ?: (6 * dp)
        val from = banner?.let { it.left + it.width * 0.38f } ?: (40 * dp)
        val to = banner?.let { it.right - 24 * dp } ?: (w - 20 * dp)
        val list = ArrayList<Pair<Path, Float>>()
        var x = from + random.nextFloat() * 40 * dp
        while (x < to) {
            val half = (2.2f + random.nextFloat() * 2f) * dp
            val length = (8 + random.nextFloat() * 14) * dp
            list += Path().apply {
                moveTo(x - half, top - 1 * dp)
                quadraticTo(x - half * 0.3f, top + length * 0.6f, x, top + length)
                quadraticTo(x + half * 0.3f, top + length * 0.6f, x + half, top - 1 * dp)
                close()
            } to top
            x += (36 + random.nextFloat() * 70) * dp
        }
        return list
    }

    private fun drift() = Path().apply {
        moveTo(0f, h)
        lineTo(0f, h - 5 * dp)
        var x = 0f
        while (x < w) {
            val next = (x + (60 + random.nextFloat() * 90) * dp).coerceAtMost(w)
            quadraticTo((x + next) / 2, h - (9 + random.nextFloat() * 7) * dp, next, h - (3 + random.nextFloat() * 3) * dp)
            x = next
        }
        lineTo(w, h)
        close()
    }

    // New Year: a garland of lights along the top edge of the screen.

    private class Bulb(val at: Offset, val color: Color, val phase: Float, val rate: Float)
    private class Garland(val wire: Path, val bulbs: List<Bulb>)

    private fun garland(): Garland {
        val colors = listOf(Color(0xFFFF4D5E), Color(0xFFFFC94D), Color(0xFF2BE6EF), Color(0xFFFF7AD9), Color(0xFF7CE05E), Color(0xFF6BA8FF))
        val wire = Path()
        val bulbs = ArrayList<Bulb>()
        val spans = 7
        val sag = 12 * dp
        for (i in 0 until spans) {
            val a = Offset(w * i / spans, -2 * dp)
            val b = Offset(w * (i + 1) / spans, -2 * dp)
            val control = Offset((a.x + b.x) / 2, a.y + sag * 2)
            wire.moveTo(a.x, a.y)
            wire.quadraticTo(control.x, control.y, b.x, b.y)
            val perSpan = 5
            for (k in 1..perSpan) {
                val u = k / (perSpan + 1f)
                val p = a * ((1 - u) * (1 - u)) + control * (2 * (1 - u) * u) + b * (u * u)
                bulbs += Bulb(p, colors[(i * perSpan + k) % colors.size], random.nextFloat() * 6.3f, 1.2f + random.nextFloat() * 1.8f)
            }
        }
        return Garland(wire, bulbs)
    }

    private fun DrawScope.drawGarland(garland: Garland, t: Float) {
        drawPath(garland.wire, Color(0xFF5C7A66), style = Stroke(1.6f * dp))
        // A chase runs along the garland now and then, on top of each bulb's own twinkle.
        val chase = (t * 0.35f) % 1f
        garland.bulbs.forEachIndexed { index, bulb ->
            val wave = (1f - abs(index / garland.bulbs.size.toFloat() - chase) * 6f).coerceIn(0f, 1f)
            val light = (0.5f + 0.4f * sin(t * bulb.rate + bulb.phase) + 0.4f * wave).coerceIn(0f, 1f)
            val center = bulb.at + Offset(0f, 7 * dp)
            drawCircle(Brush.radialGradient(0f to bulb.color.copy(alpha = 0.65f * light), 1f to Color.Transparent, center = center, radius = 20 * dp), 20 * dp, center)
            drawRect(Color(0xFF8C9A92), bulb.at + Offset(-2 * dp, 0f), Size(4 * dp, 3 * dp))
            drawOval(bulb.color.copy(alpha = 0.45f + 0.55f * light), bulb.at + Offset(-3.5f * dp, 2.5f * dp), Size(7 * dp, 10 * dp))
            drawOval(Color.White.copy(alpha = 0.55f * light), bulb.at + Offset(-1.8f * dp, 4 * dp), Size(2 * dp, 3.5f * dp))
        }
    }

    // Spring (blossom) and Easter (pussy willow): a branch over the left, picture part of the banner.

    private class Branch(val anchor: Offset, val wood: Path, val twigs: Path, val buds: List<Offset>)

    private fun branch(): Branch {
        // Mirrored from the top-left corner: x grows to the right.
        val ox = -6 * dp
        val oy = (banner?.top ?: 40 * dp) + 6 * dp
        fun p(x: Float, y: Float) = Offset(ox + x * dp, oy + y * dp)
        val wood = Path().apply {
            val s = p(0f, 0f); moveTo(s.x, s.y)
            val c1 = p(50f, 10f); val c2 = p(110f, 44f); val e = p(190f, 36f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
        }
        val twigs = Path().apply {
            fun twig(from: Offset, via: Offset, to: Offset) { moveTo(from.x, from.y); quadraticTo(via.x, via.y, to.x, to.y) }
            twig(p(70f, 22f), p(80f, 50f), p(96f, 74f))
            twig(p(130f, 40f), p(138f, 22f), p(152f, 8f))
            twig(p(30f, 8f), p(36f, 32f), p(30f, 56f))
            twig(p(160f, 38f), p(172f, 56f), p(184f, 70f))
        }
        val buds = listOf(
            p(190f, 36f), p(152f, 8f), p(96f, 74f), p(30f, 56f), p(184f, 70f),
            p(112f, 44f), p(58f, 16f), p(82f, 54f), p(140f, 26f), p(170f, 50f),
        )
        return Branch(p(0f, 0f), wood, twigs, buds)
    }

    private fun DrawScope.drawBranch(branch: Branch, t: Float) {
        rotate(sin(t * 0.6f) * 1.6f, branch.anchor) {
            drawPath(branch.wood, Color(0xFF6B4A3A), style = Stroke(4 * dp, cap = StrokeCap.Round))
            drawPath(branch.twigs, Color(0xFF6B4A3A), style = Stroke(2 * dp, cap = StrokeCap.Round))
            branch.buds.forEachIndexed { index, at ->
                if (occasion == Occasion.EASTER) catkin(at, index) else blossom(at, if (index % 3 == 2) 0.7f else 1.1f)
            }
        }
    }

    private fun DrawScope.blossom(at: Offset, scale: Float) {
        val r = 4.6f * dp * scale
        drawCircle(Brush.radialGradient(0f to Color(0x55FFB7CF), 1f to Color.Transparent, center = at, radius = r * 4), r * 4, at)
        repeat(5) { k ->
            val a = k * 2 * PI.toFloat() / 5 - PI.toFloat() / 2
            val c = at + Offset(cos(a), sin(a)) * (r * 1.05f)
            drawCircle(Brush.radialGradient(0f to Color(0xFFFFE3EC), 1f to Color(0xFFFFA9C6), center = c, radius = r), r, c)
        }
        drawCircle(Color(0xFFFFD86B), r * 0.55f, at)
    }

    private fun DrawScope.catkin(at: Offset, index: Int) {
        val tilt = if (index % 2 == 0) -18f else 14f
        rotate(tilt, at) {
            drawOval(Color(0xFF7A5A44), at + Offset(-1.2f * dp, -1 * dp), Size(2.4f * dp, 4 * dp))
            drawOval(Brush.radialGradient(0f to Color.White, 1f to Color(0xFFCFC8BC), center = at + Offset(0f, 6 * dp), radius = 6 * dp),
                at + Offset(-3.5f * dp, 1.5f * dp), Size(7 * dp, 11 * dp))
        }
    }

    // Autumn: leaves that settled on top of the banner and in the bottom corners.

    private class Leaf(val at: Offset, val side: Float, val angle: Float, val color: Color, val dark: Color)

    private fun leaves(): List<Leaf> {
        val colors = listOf(Color(0xFFE8742A) to Color(0xFFB4421E), Color(0xFFD2452F) to Color(0xFF8E2219), Color(0xFFF2B53A) to Color(0xFFC77F16))
        fun leaf(at: Offset, side: Float): Leaf {
            val (color, dark) = colors[random.nextInt(colors.size)]
            return Leaf(at, side, random.nextFloat() * 360f, color, dark)
        }
        val corners = List(10) { i ->
            val left = i < 5
            val x = if (left) random.nextFloat() * 120 * dp else w - random.nextFloat() * 120 * dp
            val spread = abs(if (left) x else w - x) / (120 * dp)
            leaf(Offset(x, h + (2 - random.nextFloat() * 10 * (1 - spread)) * dp), (18 + random.nextFloat() * 10) * dp)
        }
        val onBanner = banner?.let { b -> List(6) { leaf(Offset(b.left + random.nextFloat() * b.width, b.top - 2 * dp), (16 + random.nextFloat() * 8) * dp) } }.orEmpty()
        return corners + onBanner
    }

    private fun DrawScope.drawLeaf(leaf: Leaf) {
        rotate(leaf.angle, leaf.at) {
            drawUnit(Shapes.maple, leaf.at - Offset(leaf.side / 2, leaf.side / 2), leaf.side,
                Brush.linearGradient(listOf(leaf.color, leaf.dark), start = Offset(0.2f, 0f), end = Offset(0.8f, 1f)), alpha = 0.95f)
        }
    }

    // Halloween: a cobweb in the top-left corner with a spider going up and down.

    private fun web() = Path().apply {
        val spokes = 7
        val radius = 160 * dp
        val angles = List(spokes) { it * (PI.toFloat() / 2) / (spokes - 1) }
        angles.forEach { a -> moveTo(0f, 0f); lineTo(cos(a) * radius, sin(a) * radius) }
        listOf(30f, 58f, 88f, 118f, 148f).forEach { ring ->
            val r = ring * dp
            for (i in 0 until spokes - 1) {
                val a = angles[i]
                val b = angles[i + 1]
                val mid = (a + b) / 2
                if (i == 0) moveTo(cos(a) * r, sin(a) * r)
                quadraticTo(cos(mid) * r * 0.86f, sin(mid) * r * 0.86f, cos(b) * r, sin(b) * r)
            }
        }
    }

    private fun DrawScope.drawWeb(web: Path, t: Float) {
        drawPath(web, Color.White.copy(alpha = 0.32f), style = Stroke(1 * dp))
        val anchor = Offset(74 * dp, 60 * dp)
        val body = anchor + Offset(0f, (54 + sin(t * 0.8f) * 20) * dp)
        drawLine(Color.White.copy(alpha = 0.4f), anchor, body, strokeWidth = 0.8f * dp)
        for (side in listOf(-1f, 1f)) {
            for (k in 0 until 4) {
                val wiggle = sin(t * 6f + k) * 0.8f * dp
                val knee = body + Offset(side * 7 * dp, (-4.5f + k * 3.2f) * dp + wiggle)
                drawLine(Color(0xFFB9A6D6), body, knee, strokeWidth = 1.1f * dp, cap = StrokeCap.Round)
                drawLine(Color(0xFFB9A6D6), knee, knee + Offset(side * 3.5f * dp, (2 + k) * dp), strokeWidth = 1.1f * dp, cap = StrokeCap.Round)
            }
        }
        drawCircle(Color(0xFF3A2C4A), 5.4f * dp, body)
        drawCircle(Color(0xFF3A2C4A), 3.4f * dp, body + Offset(0f, -6 * dp))
        drawCircle(Color(0xFFFFA43A), 1 * dp, body + Offset(-1.4f * dp, -6.5f * dp))
        drawCircle(Color(0xFFFFA43A), 1 * dp, body + Offset(1.4f * dp, -6.5f * dp))
    }

    // Summer: soft sun rays turning very slowly from the top-right corner.

    private fun rays() = Path().apply {
        val sun = Offset(w - 40 * dp, -40 * dp)
        val length = 660 * dp
        repeat(10) { k ->
            val a = (k * 20f + 68f) * PI.toFloat() / 180
            val spread = 4f * PI.toFloat() / 180
            moveTo(sun.x, sun.y)
            lineTo(sun.x + cos(a - spread) * length, sun.y + sin(a - spread) * length)
            lineTo(sun.x + cos(a + spread) * length, sun.y + sin(a + spread) * length)
            close()
        }
    }
}
