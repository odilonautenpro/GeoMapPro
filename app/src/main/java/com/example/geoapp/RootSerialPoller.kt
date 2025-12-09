package com.example.geoapp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

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

        val sp = GeoApp.instance.getSharedPreferences(
            "geoapp_prefs",
            android.content.Context.MODE_PRIVATE
        )

        val offUmid = sp.getFloat("offset_umid", 0f).toDouble()
        val offTemp = sp.getFloat("offset_temp", 0f).toDouble()
        val offEc = sp.getFloat("offset_ec", 0f).toDouble()
        val offPh = sp.getFloat("offset_ph", 0f).toDouble()
        val offN = sp.getFloat("offset_n", 0f).toDouble()
        val offP = sp.getFloat("offset_p", 0f).toDouble()
        val offK = sp.getFloat("offset_k", 0f).toDouble()
        val offSal = sp.getFloat("offset_salinity", 0f).toDouble()

        val umidVal = r0 / 10.0 + offUmid
        val tempVal = r1 / 10.0 + offTemp
        val ecVal = r2.toDouble() + offEc
        val phVal = r3 / 10.0 + offPh
        val nVal = r4.toDouble() + offN
        val pVal = r5.toDouble() + offP
        val kVal = r6.toDouble() + offK
        val salVal = r7.toDouble() + offSal

        val umidStr = String.format(Locale.US, "%.1f%%", umidVal)
        val tempStr = String.format(Locale.US, "%.1f°C", tempVal)
        val ecStr = ecVal.toInt().toString()
        val phStr = String.format(Locale.US, "%.1f", phVal)

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