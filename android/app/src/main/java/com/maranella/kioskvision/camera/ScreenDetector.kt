package com.maranella.kioskvision.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One attempt to find the kiosk screen in a frame.
 *
 * [quad] is in the coordinates of the luminance grid passed in, so the caller scales it back to
 * frame pixels. [confidence] is deliberately not a probability: it is a bounded score combining
 * how rectangular, how large and how well-separated from its surroundings the candidate is.
 * Downstream code must treat it as an ordering, not as odds.
 */
data class ScreenCandidate(
    val quad: Quad,
    val confidence: Float,
    val coverage: Float,
)

/**
 * Finds the bright rectangular region a kiosk screen forms against a darker housing.
 *
 * **Why not OpenCV.** A contour-and-approxPolyDP pipeline is the textbook answer, but it costs
 * a ~70 MB native dependency for one quadrilateral, and none of it would run in the JVM unit
 * tests this repository relies on. The approach here works on a small luminance grid and is
 * ordinary Kotlin, so it is testable without a device.
 *
 * **What it assumes, and where that breaks.** A kiosk screen is an emissive panel, so it is
 * usually the brightest large convex region in view. That assumption fails against a window,
 * a ceiling light filling the frame, or a screen photographed so dark that the housing wins.
 * Those cases must come back as a low score or no candidate at all, never as a confident wrong
 * rectangle — a wrong screen silently relocates every target on it.
 */
class ScreenDetector(
    private val minCoverage: Float = MIN_COVERAGE,
    private val maxCoverage: Float = MAX_COVERAGE,
) {

    /**
     * @param luminance row-major luminance in 0..255, [width] x [height].
     */
    fun detect(luminance: IntArray, width: Int, height: Int): ScreenCandidate? {
        if (width < MIN_GRID || height < MIN_GRID) return null
        if (luminance.size != width * height) return null

        val threshold = brightThreshold(luminance) ?: return null
        val mask = BooleanArray(luminance.size) { luminance[it] >= threshold }
        val component = largestComponent(mask, width, height) ?: return null

        val coverage = component.size.toFloat() / (width * height)
        if (coverage < minCoverage || coverage > maxCoverage) return null

        val hull = convexHull(component.points) ?: return null
        val quad = Quad.ordered(dominantCorners(hull) ?: return null) ?: return null
        if (quad.area <= 0f) return null

        // A screen is convex and roughly rectangular. Comparing the quad's own area against
        // the pixels it is supposed to contain catches L-shapes and spills into bright
        // background that a corner fit alone would happily wrap.
        val fill = (component.size.toFloat() / quad.area).coerceIn(0f, 1f)
        val rectangularity = rectangularity(quad)
        val separation = separation(luminance, mask, threshold)
        val confidence = (fill * 0.4f + rectangularity * 0.4f + separation * 0.2f).coerceIn(0f, 1f)

        return ScreenCandidate(quad, confidence, coverage)
    }

    /**
     * Otsu's threshold, which finds the split between dark housing and bright panel without a
     * fixed brightness assumption — the same kiosk is a different absolute brightness indoors,
     * at night and under a skylight.
     */
    private fun brightThreshold(luminance: IntArray): Int? {
        val histogram = IntArray(256)
        for (value in luminance) histogram[value.coerceIn(0, 255)]++
        val total = luminance.size
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * histogram[i]

        var backgroundSum = 0.0
        var backgroundCount = 0
        var best = -1.0
        var bestThreshold = -1
        for (t in 0 until 256) {
            backgroundCount += histogram[t]
            if (backgroundCount == 0) continue
            val foregroundCount = total - backgroundCount
            if (foregroundCount == 0) break
            backgroundSum += t.toDouble() * histogram[t]
            val backgroundMean = backgroundSum / backgroundCount
            val foregroundMean = (sum - backgroundSum) / foregroundCount
            val between = backgroundCount.toDouble() * foregroundCount *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (between > best) {
                best = between
                bestThreshold = t
            }
        }
        return if (bestThreshold < 0) null else bestThreshold + 1
    }

    private class Component(val points: List<Point>, val size: Int)

    /** Flood fill over the bright mask, keeping the largest region. */
    private fun largestComponent(mask: BooleanArray, width: Int, height: Int): Component? {
        val visited = BooleanArray(mask.size)
        var best: Component? = null
        val stack = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            stack.addLast(start)
            visited[start] = true
            val points = ArrayList<Point>()
            var count = 0
            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % width
                val y = index / width
                count++
                // Only boundary pixels are kept: the hull is built from them, and holding
                // every interior pixel of a near-full-frame screen is wasted memory.
                if (isBoundary(mask, width, height, x, y)) points.add(Point(x.toFloat(), y.toFloat()))
                if (x > 0) push(stack, visited, mask, index - 1)
                if (x < width - 1) push(stack, visited, mask, index + 1)
                if (y > 0) push(stack, visited, mask, index - width)
                if (y < height - 1) push(stack, visited, mask, index + width)
            }
            if (best == null || count > best.size) best = Component(points, count)
        }
        return best
    }

    private fun push(stack: ArrayDeque<Int>, visited: BooleanArray, mask: BooleanArray, index: Int) {
        if (!visited[index] && mask[index]) {
            visited[index] = true
            stack.addLast(index)
        }
    }

    private fun isBoundary(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return true
        val i = y * width + x
        return !mask[i - 1] || !mask[i + 1] || !mask[i - width] || !mask[i + width]
    }

    /** Andrew's monotone chain. */
    private fun convexHull(points: List<Point>): List<Point>? {
        if (points.size < 4) return null
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 4) return null

        fun build(source: List<Point>): MutableList<Point> {
            val result = ArrayList<Point>()
            for (p in source) {
                while (result.size >= 2 && cross(result[result.size - 2], result[result.size - 1], p) <= 0) {
                    result.removeAt(result.size - 1)
                }
                result.add(p)
            }
            return result
        }

        val lower = build(sorted)
        val upper = build(sorted.reversed())
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        val hull = lower + upper
        return if (hull.size >= 4) hull else null
    }

    private fun cross(o: Point, a: Point, b: Point): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * Picks the four hull vertices that enclose the most area.
     *
     * This replaces approxPolyDP: rather than simplifying the outline by a tolerance that has
     * to be tuned per scene, it asks directly for the four points that best account for the
     * region, which is the question actually being answered.
     */
    private fun dominantCorners(hull: List<Point>): List<Point>? {
        if (hull.size < 4) return null
        if (hull.size == 4) return hull
        // The hull of a downscaled screen stays small; an exhaustive search over it is cheap
        // and avoids the local minima a greedy pick falls into on a rounded corner.
        val limited = if (hull.size > MAX_HULL) decimate(hull, MAX_HULL) else hull
        var best: List<Point>? = null
        var bestArea = 0f
        for (i in limited.indices) {
            for (j in i + 1 until limited.size) {
                for (k in j + 1 until limited.size) {
                    for (l in k + 1 until limited.size) {
                        val candidate = listOf(limited[i], limited[j], limited[k], limited[l])
                        val area = abs(Quad(candidate[0], candidate[1], candidate[2], candidate[3]).area)
                        if (area > bestArea) {
                            bestArea = area
                            best = candidate
                        }
                    }
                }
            }
        }
        return best
    }

    private fun decimate(points: List<Point>, target: Int): List<Point> {
        val step = points.size.toFloat() / target
        return (0 until target).map { points[(it * step).toInt().coerceIn(0, points.size - 1)] }
    }

    /** 1 when opposite sides are equal and corners square; falls off as the shape skews. */
    private fun rectangularity(quad: Quad): Float {
        val c = quad.corners
        val sides = FloatArray(4) { hypot(c[(it + 1) % 4].x - c[it].x, c[(it + 1) % 4].y - c[it].y) }
        if (sides.any { it < 1e-3f }) return 0f
        val oppositeRatio = min(sides[0], sides[2]) / max(sides[0], sides[2]) *
            (min(sides[1], sides[3]) / max(sides[1], sides[3]))
        // A perspective view legitimately makes opposite sides unequal, so this is a soft
        // preference, not a gate: a heavily skewed but genuine screen still scores above zero.
        return oppositeRatio.coerceIn(0f, 1f)
    }

    /** How cleanly the bright region stands out from everything else. */
    private fun separation(luminance: IntArray, mask: BooleanArray, threshold: Int): Float {
        var inside = 0.0
        var insideCount = 0
        var outside = 0.0
        var outsideCount = 0
        for (i in luminance.indices) {
            if (mask[i]) { inside += luminance[i]; insideCount++ } else { outside += luminance[i]; outsideCount++ }
        }
        if (insideCount == 0 || outsideCount == 0) return 0f
        val gap = (inside / insideCount) - (outside / outsideCount)
        return (gap / 128.0).coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val MIN_GRID = 16

        /**
         * A screen smaller than this is too far away to read anyway, and a "screen" larger
         * than the upper bound is usually the camera pressed against a lit surface.
         */
        const val MIN_COVERAGE = 0.08f
        const val MAX_COVERAGE = 0.95f
        const val MAX_HULL = 24
    }
}
