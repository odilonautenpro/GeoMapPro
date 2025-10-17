package com.example.geoapp

import android.content.res.Resources
import android.graphics.*
import android.graphics.Typeface
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

typealias ColorRamp = (Double, Double, Double, Int) -> Int

class SensorHeatmapOverlay(
    private val polygon: List<GeoPoint>,
    private val samples: List<Pair<GeoPoint, Double>>,
    private val variogram: Variogram = Variogram(range = 30.0, sill = 1.0, nugget = 0.05),
    private val gridSizePx: Int = 500,
    private val legendTitle: String = "",
    private val colorRamp: ColorRamp = ::defaultRamp
) : Overlay() {

    @Volatile private var bmp: Bitmap? = null
    @Volatile private var bboxHash: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var lastVmin = 0.0
    @Volatile private var lastVmax = 1.0
    private var legendBmp: Bitmap? = null
    private val legendBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 0, 0, 0)
    }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * Resources.getSystem().displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        if (samples.isEmpty() || polygon.size < 3) return

        val prj: Projection = osmv.projection
        val bb = prj.intrinsicScreenRect
        val w = bb.width()
        val h = bb.height()

        val key = (w shl 16) xor (h shl 1) xor osmv.zoomLevelDouble.hashCode()
        if (bmp == null || key != bboxHash) {
            bmp = render(prj, w, h)
            bboxHash = key
        }

        bmp?.let { c.drawBitmap(it, bb.left.toFloat(), bb.top.toFloat(), paint) }

        legendBmp?.let { lb ->
            val pad = dp(12f)
            val boxPad = dp(8f)
            val ticks = listOf(lastVmin, (lastVmin + lastVmax) / 2.0, lastVmax)
            val tickLabels = ticks.map { String.format(java.util.Locale.US, "%.1f", it) }

            val gradW = lb.width.toFloat()
            val gradH = lb.height.toFloat()
            val titleH = legendText.fontMetrics.let { it.descent - it.ascent }
            val labelH = legendText.fontMetrics.let { it.descent - it.ascent }
            val boxW = gradW + boxPad * 2
            val boxH = titleH + dp(6f) + gradH + dp(10f) + labelH + boxPad * 2

            val left = prj.intrinsicScreenRect.left.toFloat() + pad
            val top = prj.intrinsicScreenRect.top.toFloat() + pad
            val right = left + boxW
            val bottom = top + boxH

            val rr = dp(10f)
            c.drawRoundRect(left, top, right, bottom, rr, rr, legendBg)

            val titleX = left + boxPad
            val titleY = top + boxPad - legendText.fontMetrics.ascent
            c.drawText(legendTitle, titleX, titleY, legendText)

            val gradX = left + boxPad
            val gradY = titleY + dp(6f)
            c.drawBitmap(lb, gradX, gradY, null)

            val tickY = gradY + gradH + dp(10f)
            val tickXs = floatArrayOf(gradX, gradX + gradW / 2f, gradX + gradW)
            for (i in 0 until 3) {
                val label = tickLabels[i]
                val tw = legendText.measureText(label)
                val tx = when (i) {
                    0 -> tickXs[i]
                    1 -> tickXs[i] - tw / 2f
                    else -> tickXs[i] - tw
                }
                c.drawText(label, tx, tickY - legendText.fontMetrics.ascent, legendText)
            }
        }
    }

    private fun render(prj: Projection, w: Int, h: Int): Bitmap? {
        val S = max(120, min(gridSizePx, max(w, h)))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val geoPts = samples.map { it.first }
        val vals   = samples.map { it.second }
        val krig = buildKriging(geoPts, vals, variogram)

        val polyXY = projectPolygon(polygon, geoPts)

        val pxStepX = w.toDouble() / S
        val pxStepY = h.toDouble() / S
        val xs = DoubleArray(S) { i -> (i + 0.5) * pxStepX }
        val ys = DoubleArray(S) { j -> (j + 0.5) * pxStepY }

        lastVmin = vals.minOrNull() ?: 0.0
        lastVmax = vals.maxOrNull() ?: 1.0
        if (legendBmp == null) {
            rebuildLegendBitmap()
        }
        else {
            // legendBmp = null; rebuildLegendBitmap()
        }

        val cellPaint = Paint()
        for (j in 0 until S) {
            for (i in 0 until S) {
                val px = xs[i]
                val py = ys[j]
                val gp = prj.fromPixels(px.toInt(), py.toInt()) as GeoPoint
                val xy = projectPolygon(listOf(gp), geoPts)[0]
                if (!pointInPolygonXY(xy, polyXY)) continue

                val v = krig(xy.first, xy.second)
                cellPaint.color = colorRamp(v, lastVmin, lastVmax, 130)

                canvas.drawRect(
                    (px - pxStepX / 2).toFloat(),
                    (py - pxStepY / 2).toFloat(),
                    (px + pxStepX / 2).toFloat(),
                    (py + pxStepY / 2).toFloat(),
                    cellPaint
                )
            }
        }
        return bitmap
    }

    private fun rebuildLegendBitmap() {
        val width = 220
        val height = 16
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val t = x.toDouble() / (width - 1).toDouble()
            val v = lastVmin + t * (lastVmax - lastVmin)
            val col = colorRamp(v, lastVmin, lastVmax, 255)
            for (y in 0 until height) bmp.setPixel(x, y, col)
        }
        legendBmp = bmp
    }

    private fun dp(dp: Float): Float =
        dp * Resources.getSystem().displayMetrics.density

    companion object {
        fun defaultRamp(v: Double, vmin: Double, vmax: Double, alpha: Int): Int {
            val t = ((v - vmin) / (vmax - vmin + 1e-9)).coerceIn(0.0, 1.0)
            val r = when {
                t < 0.25 -> 0.0
                t < 0.50 -> 0.0
                t < 0.75 -> (t - 0.50) / 0.25
                else     -> 1.0
            }
            val g = when {
                t < 0.25 -> t / 0.25
                t < 0.50 -> 1.0
                t < 0.75 -> 1.0 - (t - 0.50) / 0.25
                else     -> 0.0
            }
            val b = when {
                t < 0.25 -> 1.0
                t < 0.50 -> 1.0 - (t - 0.25) / 0.25
                else     -> 0.0
            }
            val R = (r*255).toInt().coerceIn(0,255)
            val G = (g*255).toInt().coerceIn(0,255)
            val B = (b*255).toInt().coerceIn(0,255)
            return (alpha shl 24) or (R shl 16) or (G shl 8) or B
        }
    }
}