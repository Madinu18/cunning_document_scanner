package biz.cunning.cunning_document_scanner.fallback.utils

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Finds the largest 4-corner document quadrilateral in a camera frame using
 * OpenCV (grayscale → blur → Canny → contours → polygon approximation).
 *
 * Returns the quad as 8 normalized values [x0,y0, x1,y1, x2,y2, x3,y3] ordered
 * top-left, top-right, bottom-right, bottom-left, in the *upright* preview
 * coordinate space (0..1), or null when no convincing document is found.
 *
 * All work is done on the luminance (Y) plane at a reduced resolution so it can
 * run on every preview frame in real time.
 */
object DocumentDetector {

    /** Detection working resolution (longest side, px). Smaller = faster. */
    private const val WORK_SIZE = 400.0

    /** A candidate must cover at least this fraction of the frame area. */
    private const val MIN_AREA_FRACTION = 0.18

    fun detect(image: ImageProxy): FloatArray? {
        val gray = imageToGray(image) ?: return null
        val upright = rotateUpright(gray, image.imageInfo.rotationDegrees)
        if (upright !== gray) gray.release()

        val w = upright.cols()
        val h = upright.rows()
        if (w == 0 || h == 0) {
            upright.release()
            return null
        }

        val scale = WORK_SIZE / max(w, h)
        val small = Mat()
        Imgproc.resize(upright, small, Size(w * scale, h * scale))
        upright.release()

        val edges = Mat()
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(small, edges, 50.0, 150.0)
        Imgproc.dilate(
            edges, edges,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        )

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(),
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )

        val frameArea = small.width().toDouble() * small.height().toDouble()
        val minArea = frameArea * MIN_AREA_FRACTION

        var best: Array<Point>? = null
        var bestArea = 0.0
        for (contour in contours) {
            val c2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            if (pts.size == 4) {
                val area = Math.abs(Imgproc.contourArea(approx))
                if (area > minArea && area > bestArea &&
                    Imgproc.isContourConvex(MatOfPoint(*pts))
                ) {
                    bestArea = area
                    best = pts
                }
            }
            c2f.release()
            approx.release()
            contour.release()
        }

        val sw = small.width().toDouble()
        val sh = small.height().toDouble()
        small.release()
        edges.release()

        val quad = best ?: return null
        val ordered = orderCorners(quad)
        return FloatArray(8).also {
            for (i in 0 until 4) {
                it[i * 2] = (ordered[i].x / sw).toFloat().coerceIn(0f, 1f)
                it[i * 2 + 1] = (ordered[i].y / sh).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    /** Builds a CV_8UC1 grayscale Mat from the image's Y (luminance) plane. */
    private fun imageToGray(image: ImageProxy): Mat? {
        if (image.planes.isEmpty()) return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null

        val data = ByteArray(w * h)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(data, row * w, w)
        }
        val mat = Mat(h, w, CvType.CV_8UC1)
        mat.put(0, 0, data)
        return mat
    }

    /** Rotates [src] so the result is upright, given the frame's rotation. */
    private fun rotateUpright(src: Mat, rotationDegrees: Int): Mat {
        val dst = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return dst
    }

    /** Orders 4 points as top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val tr = pts.maxByOrNull { it.x - it.y }!!
        val bl = pts.minByOrNull { it.x - it.y }!!
        return arrayOf(tl, tr, br, bl)
    }
}
