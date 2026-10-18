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
 * Why a frame produced no screen candidate.
 *
 * Diagnostic only — nothing reads this to make a decision. It exists because a bare null is
 * indistinguishable between "there is no bright region at all", "the bright region is the
 * ceiling light", and "the screen is there but smaller than the detector will accept", and
 * those three need completely different fixes. Without it, a device session at a machine that
 * does not work yields no information beyond "it does not work".
 */
enum class ScreenRejection {
    /** The grid was the wrong size, or Otsu found no split at all (a flat image). */
    NoThreshold,

    /** Nothing passed the bright threshold as a connected region. */
    NoBrightRegion,

    /**
     * A bright region was found and is **smaller than [ScreenDetector.MIN_COVERAGE]**.
     *
     * The first thing to check on any small screen. The bound was chosen for a payment
     * terminal, which fills much of the frame at reading distance; a small display filmed from
     * far enough back to also show the user's hand may never reach it.
     */
    TooSmall,

    /** Larger than [ScreenDetector.MAX_COVERAGE]: usually the lens against a lit surface. */
    TooLarge,

    /** The region's outline had too few boundary points to hull. */
    HullTooSmall,

    /** No four hull points could be chosen. */
    NoCorners,

    /** Four corners were found but enclose no area. */
    DegenerateQuad,
}

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
    /**
     * Why the last call to [detect] returned null, or null when it returned a candidate.
     *
     * Written during [detect] and read from the same thread (the pipeline's). Purely
     * diagnostic: no decision anywhere consults it.
     */
    var lastRejection: ScreenRejection? = null
        private set

    /**
     * Fraction of the grid the largest bright region covered on the last call, or -1 when the
     * call did not get as far as measuring one.
     *
     * Reported even when the region was rejected, because the rejected value is the diagnosis:
     * a coverage of 0.02 against a 0.08 floor says the screen is too small in frame, which no
     * amount of staring at a preview reveals.
     */
    var lastCoverage: Float = -1f
        private set

    /** The three parts of the last candidate's confidence, or -1 when none was scored. */
    var lastFill: Float = -1f
        private set
    var lastRectangularity: Float = -1f
        private set
    var lastSeparation: Float = -1f
        private set

    /**
     * Scores a region that was refused for being too small, **without accepting it**.
     *
     * The bound that refused it was chosen for a payment terminal, and a coffee machine
     * produced coverages of 0.055-0.079 at the distance where the app read its screen well.
     * Whether those were genuine screens or bright clutter is exactly what the confidence
     * score would say, and the early return meant it was never calculated. Recording it here
     * costs one hull on a frame that is being thrown away anyway, and is the only way to learn
     * whether a confidence gate could admit the machine without admitting the room.
     */
    private fun probeConfidence(luminance: IntArray, mask: BooleanArray, threshold: Int, component: Component) {
        if (lastCoverage < PROBE_COVERAGE) return
        val hull = convexHull(component.points) ?: return
        val corners = dominantCorners(hull) ?: return
        val quad = Quad.ordered(corners) ?: return
        if (quad.area <= 0f) return
        lastFill = (component.size.toFloat() / quad.area).coerceIn(0f, 1f)
        lastRectangularity = rectangularity(quad)
        lastSeparation = separation(luminance, mask, threshold)
        lastRejectedConfidence =
            (lastFill * 0.4f + lastRectangularity * 0.4f + lastSeparation * 0.2f).coerceIn(0f, 1f)
    }

    /**
     * Confidence a too-small candidate would have scored, or -1 when none was measured.
     * Diagnostic only: nothing reads it to make a decision.
     */
    var lastRejectedConfidence: Float = -1f
        private set

    private fun reject(reason: ScreenRejection): ScreenCandidate? {
        lastRejection = reason
        return null
    }

    fun detect(luminance: IntArray, width: Int, height: Int): ScreenCandidate? {
        lastRejection = null
        lastCoverage = -1f
        lastRejectedConfidence = -1f
        lastFill = -1f
        lastRectangularity = -1f
        lastSeparation = -1f

        if (width < MIN_GRID || height < MIN_GRID) return reject(ScreenRejection.NoThreshold)
        if (luminance.size != width * height) return reject(ScreenRejection.NoThreshold)

        val threshold = brightThreshold(luminance) ?: return reject(ScreenRejection.NoThreshold)
        val mask = BooleanArray(luminance.size) { luminance[it] >= threshold }
        val component = largestComponent(mask, width, height) ?: return reject(ScreenRejection.NoBrightRegion)

        val coverage = component.size.toFloat() / (width * height)
        lastCoverage = coverage
        if (coverage < minCoverage) {
            probeConfidence(luminance, mask, threshold, component)
            return reject(ScreenRejection.TooSmall)
        }
        if (coverage > maxCoverage) return reject(ScreenRejection.TooLarge)

        val hull = convexHull(component.points) ?: return reject(ScreenRejection.HullTooSmall)
        val corners = dominantCorners(hull) ?: return reject(ScreenRejection.NoCorners)
        val quad = Quad.ordered(corners) ?: return reject(ScreenRejection.DegenerateQuad)
        if (quad.area <= 0f) return reject(ScreenRejection.DegenerateQuad)

        // A screen is convex and roughly rectangular. Comparing the quad's own area against
        // the pixels it is supposed to contain catches L-shapes and spills into bright
        // background that a corner fit alone would happily wrap.
        val fill = (component.size.toFloat() / quad.area).coerceIn(0f, 1f)
        val rectangularity = rectangularity(quad)
        val separation = separation(luminance, mask, threshold)
        lastFill = fill
        lastRectangularity = rectangularity
        lastSeparation = separation
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

    companion object {
        private const val MIN_GRID = 16

        /**
         * Smallest bright region that may be a screen, as a fraction of frame **area**.
         *
         * **Was 0.08, lowered on measurement.** That figure was chosen for a payment terminal,
         * which fills much of the frame at reading distance, and its comment reasoned that
         * anything smaller "is too far away to read anyway". A coffee machine disproved the
         * reasoning: at the distance where the app read the screen well — 42 to 46 recognised
         * lines, a target selected and guidance running — the detector was still refusing the
         * frame, reporting coverage of **0.055, 0.058, 0.077 and 0.079** against the 0.08
         * bound, 23 times in one session. The screen was perfectly readable and the detector
         * called it too small.
         *
         * Each refusal left no geometry, so guidance was withdrawn as `ScreenNotTracked` and
         * the whole-frame scan path was used instead — slower, and the source of the runs that
         * had to be cut short by the OCR time budget.
         *
         * **Lowering it to 0.04 was tried and reverted.** At the machine it did what was
         * intended, but everywhere else it admitted weak candidates that had been refused
         * before — a ceiling light, a window, a laptop screen across a cafeteria. Measured on
         * the device, detector confidence fell from the **0.89-0.97** seen on the real machine
         * to **0.56-0.62** on a room, while `MIN_CONFIDENCE` is only 0.45, so all of it was
         * accepted. OCR was then pointed at furniture and returned 433-657 blocks per run,
         * every one discarded as noise, at 12-22 seconds a run.
         *
         * Coverage alone cannot separate those two cases: a small screen and a bright patch of
         * wall are the same size in frame. What appears to separate them is how convincingly
         * rectangular and separated the region is — the confidence score — but the early
         * return below means that score has **never been computed** for a sub-threshold
         * candidate, so there is no evidence for where a gate would sit. [probeConfidence]
         * measures exactly that, while still refusing the frame, so the next device session
         * can settle it. Do not lower this bound again until those numbers exist.
         */
        const val MIN_COVERAGE = 0.08f

        /**
         * Coverage below which a candidate is not even measured. Between this and
         * [MIN_COVERAGE] the region is scored for diagnosis only, and still refused.
         */
        const val PROBE_COVERAGE = 0.03f
        const val MAX_COVERAGE = 0.95f
        private const val MAX_HULL = 24
    }
}
