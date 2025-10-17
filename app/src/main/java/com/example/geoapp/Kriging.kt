package com.example.geoapp

import kotlin.math.*
import org.osmdroid.util.GeoPoint

data class Variogram(val range: Double, val sill: Double, val nugget: Double) {
    fun gamma(h: Double): Double = nugget + sill * (1.0 - exp(-h / range))
}

//Pequeno solver linear (Gauss) para matrizes densas
private fun solve(A: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val a = Array(n) { i -> A[i].clone() }
    val x = b.clone()
    for (p in 0 until n) {
        var max = p
        for (i in p+1 until n) if (abs(a[i][p]) > abs(a[max][p])) max = i
        val tmp = a[p]; a[p] = a[max]; a[max] = tmp
        val t = x[p]; x[p] = x[max]; x[max] = t
        val piv = a[p][p]
        if (abs(piv) < 1e-12) continue
        for (j in p until n) a[p][j] /= piv
        x[p] /= piv
        for (i in 0 until n) if (i != p) {
            val f = a[i][p]
            if (f == 0.0) continue
            for (j in p until n) a[i][j] -= f * a[p][j]
            x[i] -= f * x[p]
        }
    }
    return x
}

//Projeção equiretangular local para (x,y) em metros (ok para talhões)
fun projectXY(points: List<GeoPoint>): Pair<List<Pair<Double, Double>>, Double> {
    val lat0 = points.map { it.latitude }.average()
    val R = 6371000.0
    val lat0Rad = Math.toRadians(lat0)
    val xy = points.map {
        val x = R * Math.toRadians(it.longitude) * cos(lat0Rad)
        val y = R * Math.toRadians(it.latitude)
        x to y
    }
    return xy to lat0Rad
}

//Ordinary Kriging – retorna função que interpola z em (x,y) metros
fun buildKriging(
    geoPts: List<GeoPoint>,
    values: List<Double>,
    variogram: Variogram
): (Double, Double) -> Double {
    require(geoPts.size == values.size && geoPts.isNotEmpty())
    val (xy, _) = projectXY(geoPts)
    val n = xy.size
    val K = Array(n + 1) { DoubleArray(n + 1) { 0.0 } }
    for (i in 0 until n) {
        for (j in 0 until n) {
            val dx = xy[i].first - xy[j].first
            val dy = xy[i].second - xy[j].second
            val h = hypot(dx, dy)
            K[i][j] = variogram.gamma(h)
        }
        K[i][n] = 1.0
        K[n][i] = 1.0
    }
    val z = values.toDoubleArray()

    return fun(x: Double, y: Double): Double {
        val rhs = DoubleArray(n + 1)
        for (i in 0 until n) {
            val dx = x - xy[i].first
            val dy = y - xy[i].second
            val h = hypot(dx, dy)
            rhs[i] = variogram.gamma(h)
        }
        rhs[n] = 1.0
        val w = solve(K, rhs)
        var est = 0.0
        for (i in 0 until n) est += w[i] * z[i]
        return est
    }
}

//Ray-casting point in polygon (em coordenadas projetadas)
fun pointInPolygonXY(p: Pair<Double,Double>, poly: List<Pair<Double,Double>>): Boolean {
    var c = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val (xi, yi) = poly[i]
        val (xj, yj) = poly[j]
        if (((yi > p.second) != (yj > p.second)) &&
            (p.first < (xj - xi) * (p.second - yi) / (yj - yi + 1e-12) + xi)) {
            c = !c
        }
        j = i
    }
    return c
}

//Mapa de cores (azul/ciano/verde/amarelo/vermelho)
fun colorRampPH(v: Double, vmin: Double, vmax: Double, alpha: Int = 120): Int {
    val t = ((v - vmin) / (vmax - vmin + 1e-9)).coerceIn(0.0, 1.0)
    val r = when {
        t < 0.25 -> 0.0
        t < 0.50 -> 0.0 + (t - 0.25) / 0.25 * 0.0
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

//Converte lista GeoPoint -> XY metros com mesma base do buildKriging
fun projectPolygon(geoPoly: List<GeoPoint>, ref: List<GeoPoint>): List<Pair<Double,Double>> {
    val (xyRef, _) = projectXY(ref)
    val lat0 = ref.map { it.latitude }.average()
    val R = 6371000.0
    val lat0Rad = Math.toRadians(lat0)
    return geoPoly.map {
        val x = R * Math.toRadians(it.longitude) * cos(lat0Rad)
        val y = R * Math.toRadians(it.latitude)
        x to y
    }
}
