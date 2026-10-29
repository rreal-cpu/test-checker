package com.example.testchecker

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.roundToInt

sealed class ScanResult {
    /** answers: item number -> "A".."E", "" = blank, "*" = multiple marks */
    data class Ok(val answers: Map<Int, String>, val preview: Bitmap) : ScanResult()
    data class Fail(val reason: String) : ScanResult()
}

object BubbleScanner {
    private const val S = 2.0            // pixels per PDF point in the straightened image
    private const val MIN_FILL = 0.30    // below this a bubble counts as empty
    private const val REL_FILL = 0.60    // a 2nd mark must reach this fraction of the darkest to count as "multiple"

    fun init(): Boolean = OpenCVLoader.initLocal()

    fun scan(input: Bitmap, exam: Exam): ScanResult {
        val mc = exam.items.filter { it.type == QType.MULTIPLE_CHOICE }
        if (mc.isEmpty()) return ScanResult.Fail("This exam has no multiple-choice items.")

        val rgba = Mat(); Utils.bitmapToMat(input, rgba)
        val gray0 = Mat(); Imgproc.cvtColor(rgba, gray0, Imgproc.COLOR_RGBA2GRAY)
        val gray = shrink(gray0)

        val corners = findMarkers(gray)
            ?: return ScanResult.Fail("Could not find the 4 black corner squares. Keep the whole sheet in view, flat and well lit.")

        // straighten the page so every bubble sits at its known position
        val src = MatOfPoint2f(*corners.toTypedArray())
        val dst = MatOfPoint2f(*SheetLayout.MARKER_CENTERS.map { Point(it.first * S, it.second * S) }.toTypedArray())
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(gray, warped, m, Size(SheetLayout.PAGE_W * S, SheetLayout.PAGE_H * S))

        val blur = Mat(); Imgproc.GaussianBlur(warped, blur, Size(3.0, 3.0), 0.0)
        val th = Mat()
        Imgproc.adaptiveThreshold(blur, th, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 51, 12.0)

        val r = (4.5 * S).toInt()
        val mask = Mat.zeros(2 * r + 1, 2 * r + 1, CvType.CV_8UC1)
        Imgproc.circle(mask, Point(r.toDouble(), r.toDouble()), r, Scalar(255.0), -1)
        val maskCount = Core.countNonZero(mask).toDouble()

        fun fill(x: Float, y: Float): Double {
            val cx = (x * S).roundToInt(); val cy = (y * S).roundToInt()
            val rect = Rect(cx - r, cy - r, 2 * r + 1, 2 * r + 1)
            if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > th.cols() || rect.y + rect.height > th.rows()) return 0.0
            val tmp = Mat()
            Core.bitwise_and(th.submat(rect), mask, tmp)
            return Core.countNonZero(tmp) / maskCount
        }

        val preview = Mat(); Imgproc.cvtColor(warped, preview, Imgproc.COLOR_GRAY2RGBA)
        val answers = HashMap<Int, String>()

        mc.forEachIndexed { i, item ->
            val fills = (0 until exam.choices).map { ch ->
                val (x, y) = SheetLayout.bubbleCenter(i, ch); fill(x, y)
            }
            val max = fills.max()
            if (max < MIN_FILL) {
                answers[item.number] = ""
            } else {
                val marked = fills.indices.filter { fills[it] >= maxOf(MIN_FILL, REL_FILL * max) }
                answers[item.number] = if (marked.size == 1) ('A' + marked[0]).toString() else "*"
                val colour = if (marked.size == 1) Scalar(0.0, 170.0, 0.0, 255.0) else Scalar(255.0, 140.0, 0.0, 255.0)
                marked.forEach { ch ->
                    val (x, y) = SheetLayout.bubbleCenter(i, ch)
                    Imgproc.circle(preview, Point(x * S, y * S), (SheetLayout.BUBBLE_R * S).toInt() + 3, colour, 3)
                }
            }
        }

        val small = Mat(); Imgproc.resize(preview, small, Size(SheetLayout.PAGE_W.toDouble(), SheetLayout.PAGE_H.toDouble()))
        val bmp = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(small, bmp)
        return ScanResult.Ok(answers, bmp)
    }

    private fun shrink(g: Mat): Mat {
        val big = maxOf(g.cols(), g.rows())
        if (big <= 2000) return g
        val f = 2000.0 / big
        val o = Mat(); Imgproc.resize(g, o, Size(), f, f, Imgproc.INTER_AREA)
        return o
    }

    /** Returns marker centres ordered TL, TR, BL, BR, or null if the four squares can't be found. */
    private fun findMarkers(gray: Mat): List<Point>? {
        val blur = Mat(); Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val imgArea = gray.rows().toDouble() * gray.cols()
        val cands = ArrayList<Point>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.0001 || area > imgArea * 0.01) continue
            val rr = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))
            val w = rr.size.width; val h = rr.size.height
            if (w < 1 || h < 1) continue
            val ratio = w / h
            if (ratio < 0.75 || ratio > 1.33) continue
            if (area / (w * h) < 0.88) continue      // solid square, not a ring or letter
            cands.add(rr.center)
        }
        if (cands.size < 4) return null

        val iw = gray.cols().toDouble(); val ih = gray.rows().toDouble()
        val targets = listOf(Point(0.0, 0.0), Point(iw, 0.0), Point(0.0, ih), Point(iw, ih))
        val picked = targets.map { t -> cands.minByOrNull { hypot(it.x - t.x, it.y - t.y) }!! }
        return if (picked.distinct().size == 4) picked else null
    }
}
