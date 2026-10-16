package com.maranella.kioskvision.camera

/** A point in whatever coordinate system the surrounding code is working in. */
data class Point(val x: Float, val y: Float)

/**
 * A convex quadrilateral, corners in clockwise order starting from the top-left **as seen in
 * the image**. "Top-left" is the corner nearest the image origin after ordering, not a claim
 * about the physical screen's orientation.
 */
data class Quad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {
    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Shoelace area. Positive for the clockwise order above in a y-down image. */
    val area: Float
        get() {
            val c = corners
            var sum = 0.0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
            return (sum / 2.0).toFloat()
        }

    /**
     * Scales by a different factor on each axis.
     *
     * Needed because the detection grid is square while the camera frame is not: the frame is
     * squashed into it, so mapping a quadrilateral back out has to undo each axis separately.
     * Using one factor for both would stretch every corner and quietly move every target.
     */
    fun scaled(scaleX: Float, scaleY: Float): Quad = Quad(
        Point(topLeft.x * scaleX, topLeft.y * scaleY),
        Point(topRight.x * scaleX, topRight.y * scaleY),
        Point(bottomRight.x * scaleX, bottomRight.y * scaleY),
        Point(bottomLeft.x * scaleX, bottomLeft.y * scaleY),
    )

    fun scaled(factor: Float): Quad = Quad(
        Point(topLeft.x * factor, topLeft.y * factor),
        Point(topRight.x * factor, topRight.y * factor),
        Point(bottomRight.x * factor, bottomRight.y * factor),
        Point(bottomLeft.x * factor, bottomLeft.y * factor),
    )

    companion object {
        /**
         * Orders four unordered corners into the clockwise convention above.
         *
         * Sorting by angle around the centroid is used rather than the common
         * "sum/difference of coordinates" trick, which mislabels corners once the
         * quadrilateral is rotated more than about 45 degrees — exactly the case a handheld
         * phone produces.
         */
        fun ordered(points: List<Point>): Quad? {
            if (points.size != 4) return null
            val cx = points.sumOf { it.x.toDouble() } / 4.0
            val cy = points.sumOf { it.y.toDouble() } / 4.0
            val byAngle = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            // atan2 is measured from the +x axis and increases downward in a y-down image, so
            // this list runs clockwise. Rotate it so it starts at the corner closest to the
            // origin, which is the one we call top-left.
            val startIndex = byAngle.indices.minByOrNull {
                val p = byAngle[it]
                p.x.toDouble() * p.x + p.y.toDouble() * p.y
            } ?: return null
            val c = List(4) { byAngle[(startIndex + it) % 4] }
            return Quad(c[0], c[1], c[2], c[3])
        }
    }
}

/**
 * A 3x3 projective transform, stored row-major.
 *
 * This is what makes a kiosk screen photographed at an angle usable: it maps the detected
 * screen quadrilateral onto a canonical rectangle, so OCR boxes and the fingertip can be
 * compared in the screen's own coordinates rather than the camera's. An affine transform
 * cannot do this — perspective is exactly the part it cannot represent.
 */
class Homography(private val m: DoubleArray) {

    init {
        require(m.size == 9) { "A homography has 9 elements, got ${m.size}" }
    }

    /** Applies the transform. Returns null when the point maps to infinity. */
    fun map(point: Point): Point? {
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val w = m[6] * x + m[7] * y + m[8]
        if (kotlin.math.abs(w) < EPSILON) return null
        return Point(
            ((m[0] * x + m[1] * y + m[2]) / w).toFloat(),
            ((m[3] * x + m[4] * y + m[5]) / w).toFloat(),
        )
    }

    /** The inverse transform, or null when this one is singular. */
    fun inverse(): Homography? {
        val a = m
        val c0 = a[4] * a[8] - a[5] * a[7]
        val c1 = a[5] * a[6] - a[3] * a[8]
        val c2 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c0 + a[1] * c1 + a[2] * c2
        if (kotlin.math.abs(det) < EPSILON) return null
        val inv = doubleArrayOf(
            c0, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c1, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c2, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        for (i in inv.indices) inv[i] /= det
        return Homography(inv)
    }

    /** Row-major values, for tests and for `android.graphics.Matrix.setValues`. */
    fun values(): FloatArray = FloatArray(9) { m[it].toFloat() }

    companion object {
        private const val EPSILON = 1e-10

        /**
         * Solves for the homography mapping four source points to four destination points.
         *
         * Returns null when the correspondence is degenerate — three points collinear, or a
         * quadrilateral collapsed by an extreme viewing angle. A null here is a legitimate
         * "the screen cannot be rectified from this frame", not an error to be papered over.
         */
        fun fromCorrespondences(source: List<Point>, destination: List<Point>): Homography? {
            if (source.size != 4 || destination.size != 4) return null
            // Eight equations, eight unknowns, with h22 fixed at 1.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val sx = source[i].x.toDouble()
                val sy = source[i].y.toDouble()
                val dx = destination[i].x.toDouble()
                val dy = destination[i].y.toDouble()
                a[i * 2] = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dx * sy, dx)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0, -dy * sx, -dy * sy, dy)
            }
            val h = solve(a) ?: return null
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /**
         * Maps [quad] onto the unit square, which is the canonical screen space used
         * throughout the app: (0,0) top-left of the kiosk screen, (1,1) bottom-right.
         */
        fun quadToUnitSquare(quad: Quad): Homography? = fromCorrespondences(
            quad.corners,
            listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f)),
        )

        /** Gaussian elimination with partial pivoting on an 8x9 augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) {
                    if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                }
                if (kotlin.math.abs(a[pivot][col]) < EPSILON) return null
                val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
                val diagonal = a[col][col]
                for (k in col..n) a[col][k] /= diagonal
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = a[row][col]
                    if (factor == 0.0) continue
                    for (k in col..n) a[row][k] -= factor * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] }
        }
    }
}
