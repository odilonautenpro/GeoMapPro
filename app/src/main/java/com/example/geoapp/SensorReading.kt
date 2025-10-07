package com.example.geoapp

import org.json.JSONObject
import java.util.Locale

data class SensorReading(
    val umid: String,  // "12.3%"
    val temp: String,  // "24.5°C"
    val ec:  String,  // "523"
    val ph:   String,  // "6.8"
    val n:    String,
    val p:    String,
    val k:    String,
    val salinity: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("umid", umid)
        .put("temp", temp)
        .put("ec",  ec)
        .put("ph",   ph)
        .put("n",    n)
        .put("p",    p)
        .put("k",    k)
        .put("salinity",    salinity)

    companion object {
        fun fromHexLine(line: String): SensorReading? {
            val toks = line.trim()
                .split(Regex("\\s+"))
                .filter { it.matches(Regex("[0-9a-fA-F]{2}")) }
            if (toks.size < 21) return null

            val b = toks.map { it.toInt(16) }
            if (b[0] != 0x01 || b[1] != 0x03 || b[2] != 0x10) return null

            val data = b.subList(3, 3 + 16)
            fun reg(i: Int) = (data[2*i] shl 8) or data[2*i + 1]

            val r0 = reg(0) // umidade *0.1 %
            val r1 = reg(1) // temp *0.1 °C
            val r2 = reg(2) // EC µS/cm
            val r3 = reg(3) // pH *0.1
            val r4 = reg(4) // N
            val r5 = reg(5) // P
            val r6 = reg(6) // K
            val r7 = reg(7) // salinity

            val umidStr = String.format(Locale.US, "%.1f%%", r0 / 10.0)
            val tempStr = String.format(Locale.US, "%.1f°C", r1 / 10.0)
            val ecStr  = String.format(Locale.US, "%d",   r2)
            val phStr   = String.format(Locale.US, "%.1f", r3 / 10.0)


            return SensorReading(
                umid = umidStr,
                temp = tempStr,
                ec  = ecStr,
                ph   = phStr,
                n    = r4.toString(),
                p    = r5.toString(),
                k    = r6.toString(),
                salinity = r7.toString()
            )
        }
    }
}
