package com.example.geoapp

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import java.util.concurrent.Executors
import kotlin.math.abs

class NmeaSerialLocationProvider(
    private val device: String = "/dev/ttyUSB0",
    private val baud: Int = 115200,
    private val minFixQuality: Int = 1
) : IMyLocationProvider {

    @Volatile private var running = false
    @Volatile private var proc: Process? = null
    @Volatile private var lastLoc: Location? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var consumer: IMyLocationConsumer? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        if (running) return true
        running = true
        exec.execute { runLoop() }
        return true
    }

    override fun stopLocationProvider() {
        running = false
        try { proc?.destroy(); proc?.waitFor() } catch (_: Throwable) {}
        proc = null
    }

    override fun getLastKnownLocation(): Location? = lastLoc

    override fun destroy() {
        stopLocationProvider()
        try { exec.shutdownNow() } catch (_: Throwable) {}
    }

    private fun runLoop() {
        while (running) {
            try {
                val cmd = """
                stty -F $device $baud raw -echo -ixon -ixoff -parenb cs8 -cstopb -crtscts;
                exec cat $device
            """.trimIndent()

                proc = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

                val inBuf = java.io.BufferedInputStream(proc!!.inputStream, 4096)
                val sb = StringBuilder(1024)
                val buf = ByteArray(2048)

                while (running) {
                    val n = inBuf.read(buf)
                    if (n == -1) break

                    for (i in 0 until n) {
                        val b = buf[i].toInt() and 0xFF
                        when (b) {
                            13 -> {
                                val raw = sb.toString()
                                sb.setLength(0)

                                val m = Regex(".*(\\$..(RMC|GGA),[^*]*\\*[0-9A-Fa-f]{2}).*").find(raw)
                                val sentence = m?.groupValues?.get(1)
                                if (sentence != null && nmeaChecksumOk(sentence)) {
                                    if (isType(sentence, "RMC")) {
                                        parseRMC(sentence)?.let { push(it) }
                                    } else if (isType(sentence, "GGA")) {
                                        parseGGA(sentence)?.let { (loc, q) ->
                                            if (q >= minFixQuality) push(loc)
                                        }
                                    }
                                }
                            }
                            10 -> { /* ignora LF */ }
                            else -> {
                                if (b == 9 || (b in 0x20..0x7E)) {
                                    sb.append(b.toChar())
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NMEA", "Erro NMEA: ${t.message}")
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
            } finally {
                try { proc?.destroy(); proc?.waitFor() } catch (_: Throwable) {}
                proc = null
            }
        }
    }

    private fun push(loc: Location) {
        lastLoc = loc
        consumer?.onLocationChanged(loc, this)
    }

    private fun isType(line: String, type3: String): Boolean =
        line.length > 6 && line[0] == '$' && line.regionMatches(3, type3, 0, type3.length)

    private fun nmeaChecksumOk(line: String): Boolean {
        val star = line.indexOf('*')
        if (star < 0 || star + 3 > line.length) return false
        val body = line.substring(1, star)
        val hex  = line.substring(star + 1, star + 3)
        var chk = 0
        for (c in body) chk = chk xor c.code
        return try { chk == hex.toInt(16) } catch (_: NumberFormatException) { false }
    }

    private fun parseLatLon(latStr: String, latHem: String, lonStr: String, lonHem: String)
            : Pair<Double, Double>? {
        fun dmsToDec(raw: String, isLat: Boolean): Double {
            val degLen = if (isLat) 2 else 3
            if (raw.length < degLen) return Double.NaN
            val deg = raw.substring(0, degLen).toIntOrNull() ?: return Double.NaN
            val min = raw.substring(degLen).toDoubleOrNull() ?: return Double.NaN
            return deg + (min / 60.0)
        }
        var lat = dmsToDec(latStr, true)
        var lon = dmsToDec(lonStr, false)
        if (lat.isNaN() || lon.isNaN()) return null
        if (latHem.equals("S", true)) lat = -abs(lat)
        if (lonHem.equals("W", true)) lon = -abs(lon)
        return lat to lon
    }

    private fun parseRMC(s: String): Location? {
        val f = s.split(',')
        if (f.size < 12) return null
        if (f[2] != "A") return null
        val pair = parseLatLon(f[3], f[4], f[5], f[6]) ?: return null

        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude  = pair.first
            longitude = pair.second
            f.getOrNull(7)?.toDoubleOrNull()?.let { speed = (it * 0.514444).toFloat() }
            f.getOrNull(8)?.toFloatOrNull()?.let { bearing = it }
            accuracy = 3.0f
            time = System.currentTimeMillis()
            if (android.os.Build.VERSION.SDK_INT >= 17)
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun parseGGA(s: String): Pair<Location, Int>? {
        val f = s.split(',')
        if (f.size < 15) return null
        val fixQ = f[6].toIntOrNull() ?: 0
        val pair = parseLatLon(f[2], f[3], f[4], f[5]) ?: return null

        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude  = pair.first
            longitude = pair.second
            f.getOrNull(8)?.toFloatOrNull()?.let { accuracy = it }
            f.getOrNull(9)?.toDoubleOrNull()?.let { altitude = it }
            time = System.currentTimeMillis()
            if (android.os.Build.VERSION.SDK_INT >= 17)
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        return loc to fixQ
    }
}