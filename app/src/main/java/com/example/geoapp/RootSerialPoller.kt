package com.example.geoapp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import android.content.SharedPreferences

class RootSerialPoller(
    private val device: String = "/dev/ttyS7",
    private val periodSeconds: Double = 2.0,
    private val baud: Int = 9600,
    private val onReading: (SensorReading) -> Unit,
    private val onError: (String) -> Unit = { Log.e(TAG, it) }
) {
    @Volatile private var running = false
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var proc: Process? = null

    fun start() {
        if (running) return
        running = true
        exec.execute { runLoopWithAutoRestart() }
    }

    fun stop() {
        running = false
        proc?.let { p ->
            try {
                p.destroy() // isso sinaliza o fim; o loop detecta e sai sem logar erro
                if (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(250, TimeUnit.MILLISECONDS)
                }
            } catch (_: Throwable) { /* sem barulho no shutdown */ }
        }
    }

    private fun runLoopWithAutoRestart() {
        var attempt = 0
        val maxBackoffMs = 5_000L
        while (running) {
            val ok = runOnce()
            if (!running) break
            if (!ok) {
                val delay = min(300L * (1 shl attempt), maxBackoffMs)
                try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                attempt = min(attempt + 1, 4)
            } else {
                attempt = 0
            }
        }
    }

    private fun runOnce(): Boolean {
        val cmd = buildShellScript()
        return try {
            proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val br = BufferedReader(InputStreamReader(proc!!.inputStream))
            var normalExit = true

            while (running) {
                val line = br.readLine() ?: break // EOF do processo
                val ln = line.trim()
                if (ln.isEmpty()) continue
                val pretty = ln.lowercase(Locale.US)
                Log.d(TAG, "HEX: ${pretty}")
                parseHexToReadingOrNull(pretty)?.let(onReading)
            }

            if (running) normalExit = false

            try { proc?.waitFor(50, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            normalExit
        } catch (t: Throwable) {
            if (running) onError("Poller falhou: ${t.message ?: t::class.java.simpleName}")
            false
        } finally {
            try {
                proc?.destroy()
                proc?.waitFor(50, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {}
            proc = null
        }
    }

    private fun buildShellScript(): String {
        val devPath = device
        val sleepStr = "%.2f".format(Locale.US, periodSeconds)
        val baudStr = baud.toString()
        return """
      #!/system/bin/sh
      set +e
      /system/bin/stty ${baudStr} cs8 -cstopb -parenb -ixon -ixoff -crtscts -echo raw min 0 time 2 < "${devPath}"
      /system/bin/toybox sleep 0.05
      while true; do
        /system/bin/printf '\x01\x03\x00\x00\x00\x08\x44\x0C' > "${devPath}" || true
        /system/bin/toybox dd if="${devPath}" bs=1 count=21 status=none 2>/dev/null | /system/bin/od -An -tx1 -v -w1024
        /system/bin/toybox sleep ${sleepStr}
      done
    """.trimIndent()
    }

    private fun applyCalibration(
        raw: Double,
        sp: SharedPreferences,
        keyPrefix: String
    ): Double {
        val a = sp.getFloat("${keyPrefix}_a", 1f).toDouble()
        val b = sp.getFloat("${keyPrefix}_b", 0f).toDouble()
        return a * raw + b
    }

    private fun parseHexToReadingOrNull(hexLine: String): SensorReading? {
        val parts = hexLine.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (parts.size < 21) return null
        val bytes = ByteArray(21)
        try {
            for (i in 0 until 21) {
                bytes[i] = parts[i].toInt(16).toByte()
            }
        } catch (_: Throwable) {
            return null
        }
        if (bytes[0] != 0x01.toByte() || bytes[1] != 0x03.toByte() || bytes[2] != 0x10.toByte()) {
            return null
        }
        val crcCalc = modbusCrc16(bytes, 0, 19)
        val crcLo = (crcCalc and 0xFF).toByte()
        val crcHi = ((crcCalc ushr 8) and 0xFF).toByte()
        if (bytes[19] != crcLo || bytes[20] != crcHi) {
            return null
        }

        fun u16(idx: Int): Int {
            val base = 3 + idx * 2
            return ((bytes[base].toInt() and 0xFF) shl 8) or (bytes[base + 1].toInt() and 0xFF)
        }
        val r0 = u16(0) // umidade *10
        val r1 = u16(1) // temperatura *10
        val r2 = u16(2) // EC (inteiro)
        val r3 = u16(3) // pH *10
        val r4 = u16(4) // N
        val r5 = u16(5) // P
        val r6 = u16(6) // K
        val r7 = u16(7) // salinity

        val sp = GeoApp.instance.getSharedPreferences("geoapp_prefs", android.content.Context.MODE_PRIVATE)
        val soilFactor = sp.getFloat("soil_factor", 1.5f).toDouble()

        // valores crus
        val umidRaw = r0 / 10.0
        val tempRaw = r1 / 10.0
        val ecRaw   = r2.toDouble()
        val phRaw   = r3 / 10.0
        val nRaw    = r4.toDouble() * soilFactor
        val pRaw    = r5.toDouble() * soilFactor
        val kRaw    = r6.toDouble() * soilFactor
        val salRaw  = r7.toDouble()

        // aplica calibração Y = A·X + B
        val umidVal = applyCalibration(umidRaw, sp, "cal_umid")
        val tempVal = applyCalibration(tempRaw, sp, "cal_temp")
        val ecVal   = applyCalibration(ecRaw,   sp, "cal_ec")
        val phVal   = applyCalibration(phRaw,   sp, "cal_ph")
        val nVal    = applyCalibration(nRaw,    sp, "cal_n")
        val pVal    = applyCalibration(pRaw,    sp, "cal_p")
        val kVal    = applyCalibration(kRaw,    sp, "cal_k")
        val salVal  = applyCalibration(salRaw,  sp, "cal_sal")

        val umidStr = String.format(Locale.US, "%.1f%%", umidVal)
        val tempStr = String.format(Locale.US, "%.1f°C", tempVal)
        val ecStr   = ecVal.toInt().toString()
        val phStr   = String.format(Locale.US, "%.1f", phVal)

        return SensorReading(
            umid = umidStr,
            temp = tempStr,
            ec = ecStr,
            ph = phStr,
            n = nVal.toInt().toString(),
            p = pVal.toInt().toString(),
            k = kVal.toInt().toString(),
            salinity = salVal.toInt().toString()
        )
    }

    private fun modbusCrc16(buf: ByteArray, off: Int = 0, len: Int = buf.size): Int {
        var crc = 0xFFFF
        for (i in off until off + len) {
            crc = crc xor (buf[i].toInt() and 0xFF)
            for (b in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    (crc ushr 1)
                }
            }
        }
        return crc and 0xFFFF
    }

    companion object {
        private const val TAG = "GeoApp"
    }
}