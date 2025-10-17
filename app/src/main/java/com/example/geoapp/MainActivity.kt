package com.example.geoapp

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import java.io.File
import java.io.FileWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.Button
import android.graphics.Bitmap
import java.io.FileOutputStream
import kotlin.math.roundToInt
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver

class MainActivity : AppCompatActivity() {
    private val sensorExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var reading = false
    private lateinit var map: MapView
    private val areaPts = mutableListOf<GeoPoint>()
    private var areaPolygon: Polygon? = null
    private val areaMarkers = mutableListOf<Marker>()
    private var areaFinalizada: Boolean = false
    private val COLOR_VERTEX       = 0xFF757575.toInt() // marcadores cinza 600
    private val COLOR_AREA_OUTLINE = 0xFF616161.toInt() // contorno cinza 700
    private val COLOR_AREA_FILL    = 0x80666666.toInt() // preenchimento cinza 50%
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var crossOverlay: Overlay
    private var nomeTrabalho: String = ""
    private var tipoCultura: String = ""
    private var georefHabilitado: Boolean = false
    private val MAX_MARKERS_TO_DRAW = 500
    private val MAX_POINTS_TO_KEEP  = 5000
    private val pontosKey: String get() = "pontos_${nomeTrabalho.ifEmpty { "default" }}"
    private fun fallbackCenter(): GeoPoint {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        return when (sp.getString("region", "br")) {
            "py" -> GeoPoint(-25.2749506, -57.602302) // Paraguay
            else -> GeoPoint(-26.1955215, -52.6710228) // Brazil
        }
    }
    private object PontoKeys {
        const val ID   = "id"
        const val LAT  = "lat"
        const val LNG  = "lng"
        const val TS   = "ts"
        const val UMID = "umid"
        const val TEMP = "temp"
        const val PH   = "ph"
        const val N    = "n"
        const val K    = "k"
        const val P    = "p"
        const val EC   = "ec"
        const val SALINITY = "salinity"
        const val SAVED = "sensorSaved"
    }
    private val activePollers = java.util.Collections.synchronizedSet(mutableSetOf<RootSerialPoller>())
    private var globalPoller: RootSerialPoller? = null
    @Volatile private var lastReading: SensorReading? = null
    private var mapLocked = false
    private var activeHeatmap: Overlay? = null
    private var allowedWhileLockedId: Int = R.id.btnMapaPh

    private fun startGlobalPoller(
        device: String = "/dev/ttyS7",
        periodSeconds: Double = 2.0,
        baud: Int = 9600
    ) {
        if (globalPoller != null) return
        globalPoller = RootSerialPoller(
            device = device,
            periodSeconds = periodSeconds,
            baud = baud,
            onReading = { r ->
                lastReading = r
            },
            onError = { msg -> android.util.Log.e("GeoApp", msg) }
        ).also { it.start() }
    }

    private fun stopGlobalPoller() {
        try { globalPoller?.stop() } catch (_: Throwable) {}
        globalPoller = null
    }

    private fun stopAllSerials() {
        synchronized(activePollers) {
            activePollers.forEach { p ->
                try { p.stop() } catch (_: Throwable) {}
            }
            activePollers.clear()
        }
    }

    private fun newPoller(
        device: String = "/dev/ttyS7",
        periodSeconds: Double = 2.0,
        baud: Int = 9600,
        onReading: (SensorReading) -> Unit,
        onError: (String) -> Unit
    ): RootSerialPoller {
        stopAllSerials()
        val p = RootSerialPoller(
            device = device,
            periodSeconds = periodSeconds,
            baud = baud,
            onReading = onReading,
            onError = onError
        )
        activePollers += p
        return p
    }

    private class CenterCrossOverlay(
        private val sizeDp: Float = 14f,
        private val strokeDp: Float = 2f,
        private val color: Int = Color.parseColor("#FF3D00")
    ) : Overlay() {
        private fun dpToPx(dp: Float, view: View) = dp * view.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.color = this@CenterCrossOverlay.color
        }
        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            val cx = osmv.width / 2f
            val cy = osmv.height / 2f
            val r  = dpToPx(sizeDp, osmv)
            paint.strokeWidth = dpToPx(strokeDp, osmv)
            c.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
            c.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        startGlobalPoller(device = "/dev/ttyS7", periodSeconds = 2.0, baud = 9600)

        nomeTrabalho     = intent.getStringExtra("nome_trabalho").orEmpty()
        tipoCultura      = intent.getStringExtra("tipo_cultura").orEmpty()
        georefHabilitado = intent.getBooleanExtra("georreferenciamento_habilitado", false)

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val appRegion = sp.getString("region", "br") ?: "br"

        val jobRegion = try {
            val jobs = org.json.JSONArray(sp.getString("jobs_json", "[]") ?: "[]")
            var found: String? = null
            for (i in 0 until jobs.length()) {
                val jo = jobs.getJSONObject(i)
                if (jo.optString("nome").equals(nomeTrabalho, ignoreCase = true)) {
                    found = jo.optString("region", "br")
                    break
                }
            }
            found ?: "br"
        } catch (_: Throwable) { "br" }

        if (!jobRegion.equals(appRegion, ignoreCase = true)) {
            sp.edit().putString("region", jobRegion).apply()
            android.widget.Toast.makeText(
                this,
                "Alternado para ${if (jobRegion == "py") "Paraguai" else "Brasil"} para abrir o trabalho “$nomeTrabalho”.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        Configuration.getInstance().userAgentValue = packageName

        @Suppress("UNCHECKED_CAST")
        map = (findViewById<MapView?>(resources.getIdentifier("mapView", "id", packageName))
            ?: findViewById(resources.getIdentifier("map", "id", packageName))) as MapView

        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()
        map.controller.setZoom(defaultZoom)
        map.setMultiTouchControls(true)

        val closerOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint?): Boolean {
                InfoWindow.closeAllInfoWindowsOn(map)
                return false
            }
            override fun longPressHelper(p: org.osmdroid.util.GeoPoint?): Boolean = false
        })
        map.overlays.add(0, closerOverlay)

        setupOfflineMapOrWarn()
        if (!georefHabilitado) {
            crossOverlay = CenterCrossOverlay()
            map.overlays.add(crossOverlay)
        }
        else {
            crossOverlay = CenterCrossOverlay()
            map.overlays.add(crossOverlay)
        }
        if (georefHabilitado) {
            setupNmeaLocationOverlay()
        } else {
            map.controller.setCenter(fallbackCenter())
        }

        findViewById<View>(R.id.btnListarPontos).setOnLongClickListener {
            limparPontosDoTrabalho()
            true
        }

        carregarAreaDoPrefs()
        atualizarAreaOverlay()

        findViewById<View>(R.id.btnAddPonto).setOnClickListener {
            adicionarPontoNoCentro()
        }
        findViewById<View>(R.id.btnAddPonto).setOnLongClickListener {
            limparArea()
            true
        }
        findViewById<View>(R.id.btnGerarArea).setOnClickListener {
            finalizarArea()
        }
        findViewById<View>(R.id.btnGerarArea).setOnLongClickListener {
            desfazerArea()
            true
        }
        findViewById<View>(R.id.btnMapaPh).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaPh, "pH", "ph")
        }
        findViewById<View>(R.id.btnMapaTemp).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaTemp, "Temperatura (°C)", "temp")
        }
        findViewById<View>(R.id.btnMapaUmid).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaUmid, "Umidade (%)", "umid")
        }
        findViewById<View>(R.id.btnMapaN).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaN, "Nitrogênio (N)", "n")
        }
        findViewById<View>(R.id.btnMapaP).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaP, "Fósforo (P)", "p")
        }
        findViewById<View>(R.id.btnMapaK).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaK, "Potássio (K)", "k")
        }
        findViewById<View>(R.id.btnMapaSal).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaSal, "Salinidade", "salinity")
        }
        findViewById<View>(R.id.btnMapaCond).setOnClickListener {
            gerarMapaAtributo(R.id.btnMapaCond, "Condutividade", "ec")
        }

        desenharPontosDoPrefs()
    }

    private fun setupNmeaLocationOverlay() {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()

        val provider = NmeaSerialLocationProvider(
            device = "/dev/ttyUSB0",
            baud = 115200,
            minFixQuality = 1
        )

        myLocation = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
            enableFollowLocation()
            runOnFirstFix {
                runOnUiThread {
                    getMyLocation()?.let {
                        map.controller.setZoom(defaultZoom)
                        map.controller.animateTo(it)
                    }
                }
            }
        }
        map.overlays.add(myLocation)
        map.invalidate()
    }

    private fun setupOfflineMapOrWarn() {
        AndroidGraphicFactory.createInstance(application)
        MapsForgeTileSource.createInstance(application)

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val region = sp.getString("region", "br") ?: "br"
        val mapName = if (region == "py") "paraguay.map" else "brazil.map"

        val mapsDir = File(getExternalFilesDir("maps"), "")
        val mapFile = File(mapsDir, mapName)

        if (mapFile.exists() && mapFile.length() < 1_000_000L) {
            try { mapFile.delete() } catch (_: Throwable) {}
        }

        if (!mapFile.exists()) {
            Thread {
                try {
                    val assetPath = "maps/$mapName"
                    assets.open(assetPath).use { input ->
                        mapsDir.mkdirs()
                        FileOutputStream(mapFile).use { output ->
                            val buf = ByteArray(8 * 1024 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                            }
                            output.flush()
                        }
                    }
                    if (mapFile.length() < 1_000_000L) throw IllegalStateException("tamanho inválido (${mapFile.length()})")

                    runOnUiThread {
                        tryUseOfflineMap(mapFile)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Mapa offline ausente nos assets ou cópia falhou (${e.message}). " +
                                    "Coloque o arquivo em /Android/data/${packageName}/files/maps/",
                            Toast.LENGTH_LONG
                        ).show()
                        map.setUseDataConnection(true)
                    }
                }
            }.start()
            return
        }

        tryUseOfflineMap(mapFile)
    }

    private fun tryUseOfflineMap(mapFile: File) {
        try {
            if (!mapFile.exists() || mapFile.length() < 1_000_000L) {
                throw IllegalArgumentException("invalid size (${mapFile.length()})")
            }

            val tileSource = MapsForgeTileSource.createFromFiles(arrayOf(mapFile))
            val forgeProvider = MapsForgeTileProvider(
                SimpleRegisterReceiver(this),
                tileSource,
                null
            )
            map.setTileProvider(forgeProvider)
            map.setUseDataConnection(false)
            Toast.makeText(this, "Usando mapa offline: ${mapFile.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try { if (mapFile.exists()) mapFile.delete() } catch (_: Throwable) {}
            Toast.makeText(this, "Falha no mapa offline: ${e.message}. Usando Mapnik online.", Toast.LENGTH_LONG).show()
            map.setUseDataConnection(true)
        }
    }

    private fun loadPontosFromPrefs(): JSONArray {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val s = sp.getString(pontosKey, "[]") ?: "[]"
        return JSONArray(s)
    }

    private fun savePontosToPrefs(arr: JSONArray) {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        sp.edit().putString(pontosKey, arr.toString()).apply()
    }

    private fun limparPontosDoTrabalho() {
        getSharedPreferences("geoapp_prefs", MODE_PRIVATE).edit().remove(pontosKey).apply()
        map.overlays.removeAll { it is Marker }
        map.invalidate()
        Toast.makeText(this, "Pontos apagados", Toast.LENGTH_SHORT).show()
    }

    private fun desenharPontosDoPrefs() {
        var arr = loadPontosFromPrefs()
        if (arr.length() > MAX_POINTS_TO_KEEP) {
            val trimmed = JSONArray()
            for (i in (arr.length() - MAX_POINTS_TO_KEEP) until arr.length()) {
                trimmed.put(arr.getJSONObject(i))
            }
            savePontosToPrefs(trimmed)
            arr = trimmed
        }

        val n = arr.length()
        val start = if (n > MAX_MARKERS_TO_DRAW) n - MAX_MARKERS_TO_DRAW else 0
        if (start > 0) {
            Toast.makeText(this, "Mostrando os últimos $MAX_MARKERS_TO_DRAW de $n pontos", Toast.LENGTH_LONG).show()
        }

        for (i in start until n) {
            val jo = arr.getJSONObject(i)
            val p = GeoPoint(jo.getDouble(PontoKeys.LAT), jo.getDouble(PontoKeys.LNG))
            val title = "Ponto ${jo.optInt(PontoKeys.ID, i + 1)}"
            addMarker(p, title, jo)
        }
        map.invalidate()
    }

    private fun addMarker(p: GeoPoint, title: String, data: JSONObject? = null) {
        val m = Marker(map)
        m.position = p
        m.title = title
        m.relatedObject = data
        m.setOnMarkerClickListener { marker, _ ->
            val jo = marker.relatedObject as? JSONObject
            if (jo != null) {
                showPontoInfoDialog(jo)
            } else {
                val lat = marker.position.latitude
                val lng = marker.position.longitude
                showPontoInfoDialog(JSONObject()
                    .put(PontoKeys.ID, "-")
                    .put(PontoKeys.LAT, lat)
                    .put(PontoKeys.LNG, lng)
                    .put(PontoKeys.UMID, "-")
                    .put(PontoKeys.TEMP, "-")
                    .put(PontoKeys.EC,  "-")
                    .put(PontoKeys.PH,   "-")
                    .put(PontoKeys.N,    "-")
                    .put(PontoKeys.K,    "-")
                    .put(PontoKeys.P,    "-")
                    .put(PontoKeys.SALINITY, "-")
                    .put(PontoKeys.SAVED, false)
                )
            }
            true
        }
        map.overlays.add(m)
    }

    private fun atualizarPontoComLeituraPorId(idPonto: Int, leitura: JSONObject): Boolean {
        val arr = loadPontosFromPrefs()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt(PontoKeys.ID, -1) == idPonto) {
                o.put(PontoKeys.UMID, leitura.optString("umid", "-"))
                o.put(PontoKeys.TEMP, leitura.optString("temp", "-"))
                o.put(PontoKeys.EC,   leitura.optString("ec",   "-"))
                o.put(PontoKeys.PH,   leitura.optString("ph",   "-"))
                o.put(PontoKeys.N,    leitura.optString("n",    "-"))
                o.put(PontoKeys.K,    leitura.optString("k",    "-"))
                o.put(PontoKeys.P,    leitura.optString("p",    "-"))
                o.put(PontoKeys.SALINITY, leitura.optString("salinity", "-"))
                o.put(PontoKeys.SAVED, true)
                arr.put(i, o)
                savePontosToPrefs(arr)
                return true
            }
        }
        return false
    }

    private fun apagarLeituraPorId(idPonto: Int): Boolean {
        val arr = loadPontosFromPrefs()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt(PontoKeys.ID, -1) == idPonto) {
                o.put(PontoKeys.UMID, "-")
                o.put(PontoKeys.TEMP, "-")
                o.put(PontoKeys.EC,   "-")
                o.put(PontoKeys.PH,   "-")
                o.put(PontoKeys.N,    "-")
                o.put(PontoKeys.K,    "-")
                o.put(PontoKeys.P,    "-")
                o.put(PontoKeys.SALINITY, "-")
                o.put(PontoKeys.SAVED, false)
                arr.put(i, o)
                savePontosToPrefs(arr)
                return true
            }
        }
        return false
    }

    private fun showPontoInfoDialog(jo: JSONObject) {
        stopAllSerials()
        val view = layoutInflater.inflate(R.layout.dialog_ponto_info, null)
        val tvLeitura = view.findViewById<android.widget.TextView>(R.id.tvLeitura)
        var leituraIdx = 0
        tvLeitura.text = "-"

        fun setText(id: Int, v: Any?) {
            view.findViewById<android.widget.TextView>(id).text = (v ?: "-").toString()
        }

        val df = DecimalFormat("0.000000")

        setText(R.id.tvId,  jo.opt(PontoKeys.ID))
        setText(R.id.tvLat, jo.optDouble(PontoKeys.LAT, Double.NaN).takeIf { it==it }?.let { df.format(it) })
        setText(R.id.tvLng, jo.optDouble(PontoKeys.LNG, Double.NaN).takeIf { it==it }?.let { df.format(it) })
        setText(R.id.tvUmid, jo.opt(PontoKeys.UMID))
        setText(R.id.tvTemp, jo.opt(PontoKeys.TEMP))
        setText(R.id.tvEc,   jo.opt(PontoKeys.EC))
        setText(R.id.tvPh,   jo.opt(PontoKeys.PH))
        setText(R.id.tvN,    jo.opt(PontoKeys.N))
        setText(R.id.tvK,    jo.opt(PontoKeys.K))
        setText(R.id.tvP,    jo.opt(PontoKeys.P))
        setText(R.id.salinity, jo.opt(PontoKeys.SALINITY))

        val btnGravar = view.findViewById<Button>(R.id.btnGravar)
        val btnApagar = view.findViewById<Button>(R.id.btnApagar)
        btnApagar.isEnabled = jo.optBoolean(PontoKeys.SAVED, false)

        data class Acum(
            var n:Int = 0,
            var umid:Double = 0.0,
            var temp:Double = 0.0,
            var ec:Double  = 0.0,
            var ph:Double   = 0.0,
            var nN:Double   = 0.0,
            var pP:Double   = 0.0,
            var kK:Double   = 0.0,
            var salinity:Double = 0.0
        )
        var acum = Acum()

        fun numFromStr(s: String): Double {
            val t = s.replace("%","")
                .replace("°C","")
                .replace(",",".")
                .trim()
            return t.toDoubleOrNull() ?: Double.NaN
        }

        fun acumular(r: SensorReading) {
            val u = numFromStr(r.umid)
            val t = numFromStr(r.temp)
            val ec = numFromStr(r.ec)
            val pH= numFromStr(r.ph)
            val n = numFromStr(r.n)
            val p = numFromStr(r.p)
            val k = numFromStr(r.k)
            val s = numFromStr(r.salinity)

            if (!u.isNaN()) acum.umid += u
            if (!t.isNaN()) acum.temp += t
            if (!ec.isNaN()) acum.ec  += ec
            if (!pH.isNaN())acum.ph   += pH
            if (!n.isNaN()) acum.nN   += n
            if (!p.isNaN()) acum.pP   += p
            if (!k.isNaN()) acum.kK   += k
            if (!s.isNaN()) acum.salinity += s
            acum.n++
        }

        fun mediaComoReading(ac: Acum): SensorReading? {
            if (ac.n <= 0) return null
            val nn = ac.n.toDouble()
            return SensorReading(
                umid = String.format(Locale.US, "%.1f%%", ac.umid/nn),
                temp = String.format(Locale.US, "%.1f°C", ac.temp/nn),
                ec   = ((ac.ec/nn).toLong()).toString(),
                ph   = String.format(Locale.US, "%.1f", ac.ph/nn),
                n    = ((ac.nN/nn).toLong()).toString(),
                p    = ((ac.pP/nn).toLong()).toString(),
                k    = ((ac.kK/nn).toLong()).toString(),
                salinity = ((ac.salinity/nn).toLong()).toString()
            )
        }

        val jaSalvo = jo.optBoolean(PontoKeys.SAVED, false)
        btnGravar.isEnabled = !jaSalvo
        if (jaSalvo) btnGravar.text = "Já gravado"
        btnApagar.isEnabled = jaSalvo

        var uiTimer: java.util.Timer? = null

        fun aplicarLeituraNaUI(r: SensorReading) {
            setText(R.id.tvUmid, r.umid)
            setText(R.id.tvTemp, r.temp)
            setText(R.id.tvEc,   r.ec)
            setText(R.id.tvPh,   r.ph)
            setText(R.id.tvN,    r.n)
            setText(R.id.tvK,    r.k)
            setText(R.id.tvP,    r.p)
            setText(R.id.salinity, r.salinity)
            leituraIdx += 1
            tvLeitura.text = "$leituraIdx"
            acumular(r)
        }

        fun startUiTimer(periodMs: Long = 2000L) {
            uiTimer?.cancel()
            uiTimer = java.util.Timer().apply {
                scheduleAtFixedRate(object : java.util.TimerTask() {
                    override fun run() {
                        val r = this@MainActivity.lastReading ?: return
                        runOnUiThread { aplicarLeituraNaUI(r) }
                    }
                }, 0L, periodMs)
            }
        }

        if (!jaSalvo) startUiTimer()

        val dlg = AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener {
                try { uiTimer?.cancel() } catch (_: Throwable) {}
                uiTimer = null
            }
            .create()

        dlg.setOnShowListener {
            if (!jaSalvo) {
                acum = Acum()
                leituraIdx = 0
                tvLeitura.text = "-"
            } else {
                tvLeitura.text = "-"
            }
        }

        btnGravar.setOnClickListener {
            stopAllSerials()
            if (jo.optBoolean(PontoKeys.SAVED, false)) return@setOnClickListener

            val id = jo.optInt(PontoKeys.ID, -1)
            val r = mediaComoReading(acum)

            if (id <= 0 || r == null) {
                Toast.makeText(this, "Sem leituras suficientes para gravar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ok = atualizarPontoComLeituraPorId(id, r.toJson())
            if (ok) {
                jo.put(PontoKeys.UMID, r.umid)
                jo.put(PontoKeys.TEMP, r.temp)
                jo.put(PontoKeys.EC,   r.ec)
                jo.put(PontoKeys.PH,   r.ph)
                jo.put(PontoKeys.N,    r.n)
                jo.put(PontoKeys.K,    r.k)
                jo.put(PontoKeys.P,    r.p)
                jo.put(PontoKeys.SALINITY, r.salinity)
                jo.put(PontoKeys.SAVED, true)

                setText(R.id.tvUmid, r.umid)
                setText(R.id.tvTemp, r.temp)
                setText(R.id.tvEc,   r.ec)
                setText(R.id.tvPh,   r.ph)
                setText(R.id.tvN,    r.n)
                setText(R.id.tvK,    r.k)
                setText(R.id.tvP,    r.p)
                setText(R.id.salinity, r.salinity)

                tvLeitura.text = "Total: $leituraIdx"

                btnGravar.isEnabled = false
                btnGravar.text = "Já gravado"
                btnApagar.isEnabled = true

                uiTimer?.cancel()
                uiTimer = null

                Toast.makeText(this, "Média das leituras gravada no ponto #$id", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Não foi possível localizar o ponto", Toast.LENGTH_SHORT).show()
            }
        }

        btnApagar.setOnClickListener {
            stopAllSerials()
            val id = jo.optInt(PontoKeys.ID, -1)
            if (id <= 0) {
                Toast.makeText(this, "Ponto inválido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Apagar gravação do ponto #$id?")
                .setMessage("Isso limpará os valores de sensor salvos para este ponto.")
                .setPositiveButton("Apagar") { _, _ ->
                    val ok = apagarLeituraPorId(id)
                    if (!ok) {
                        Toast.makeText(this, "Não foi possível apagar a gravação", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    var acum2 = Acum()

                    leituraIdx = 0
                    tvLeitura.text = "-"

                    jo.put(PontoKeys.UMID, "-")
                    jo.put(PontoKeys.TEMP, "-")
                    jo.put(PontoKeys.EC,   "-")
                    jo.put(PontoKeys.PH,   "-")
                    jo.put(PontoKeys.N,    "-")
                    jo.put(PontoKeys.K,    "-")
                    jo.put(PontoKeys.P,    "-")
                    jo.put(PontoKeys.SALINITY, "-")
                    jo.put(PontoKeys.SAVED, false)

                    setText(R.id.tvUmid, "-")
                    setText(R.id.tvTemp, "-")
                    setText(R.id.tvEc,   "-")
                    setText(R.id.tvPh,   "-")
                    setText(R.id.tvN,    "-")
                    setText(R.id.tvK,    "-")
                    setText(R.id.tvP,    "-")
                    setText(R.id.salinity, "-")

                    btnGravar.isEnabled = true
                    btnGravar.text = "Gravar"
                    btnApagar.isEnabled = false

                    try { uiTimer?.cancel() } catch (_: Throwable) {}
                    uiTimer = null
                    acum2 = Acum()
                    leituraIdx = 0
                    tvLeitura.text = "-"
                    startUiTimer()
                    Toast.makeText(this, "Gravação apagada no ponto #$id", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        dlg.show()
    }

    private fun addPonto(p: GeoPoint) {
        stopAllSerials()
        val arr = loadPontosFromPrefs()
        val id = nextPontoId(arr)

        val jo = JSONObject()
            .put(PontoKeys.ID,  id)
            .put(PontoKeys.LAT, p.latitude)
            .put(PontoKeys.LNG, p.longitude)
            .put(PontoKeys.TS,  System.currentTimeMillis())
            .put(PontoKeys.UMID, "-")
            .put(PontoKeys.TEMP, "-")
            .put(PontoKeys.PH,   "-")
            .put(PontoKeys.N,    "-")
            .put(PontoKeys.K,    "-")
            .put(PontoKeys.P,    "-")
            .put(PontoKeys.EC, "-")
            .put(PontoKeys.SALINITY, "-")
            .put(PontoKeys.SAVED, false)

        arr.put(jo)
        savePontosToPrefs(arr)

        addMarker(p, "Ponto $id", jo)

        Toast.makeText(this, "Ponto salvo: ${formatGeo(p)}", Toast.LENGTH_SHORT).show()
        map.invalidate()
    }

    private fun formatGeo(p: GeoPoint): String {
        val df = DecimalFormat("0.000000")
        return "${df.format(p.latitude)}, ${df.format(p.longitude)}"
    }

    private fun nextPontoId(arr: JSONArray): Int {
        return arr.length() + 1
    }

    fun btnNovoPonto(view: View) {
        stopAllSerials()
        if (georefHabilitado) {
            val p = try {
                if (::myLocation.isInitialized) myLocation.getMyLocation() else null
            } catch (_: Throwable) { null }

            if (p == null) {
                Toast.makeText(this, "Sem posição da antena ainda. Aguarde fix GPS.", Toast.LENGTH_SHORT).show()
                return
            }
            addPonto(p)
            return
        }
        val center = map.mapCenter
        val p = GeoPoint(center.latitude, center.longitude)
        addPonto(p)
    }

    fun btnListarPontos(view: View) {
        stopAllSerials()
        val arr = loadPontosFromPrefs()
        if (arr.length() == 0) {
            Toast.makeText(this, "Nenhum ponto salvo", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = Array(arr.length()) { i ->
            val jo = arr.getJSONObject(i)
            val p = GeoPoint(jo.getDouble("lat"), jo.getDouble("lng"))
            val whenStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(Date(jo.optLong("ts", 0L)))
            "Ponto ${i + 1} — ${formatGeo(p)} — $whenStr"
        }
        AlertDialog.Builder(this)
            .setTitle("Pontos de $nomeTrabalho")
            .setItems(labels) { _, which ->
                stopAllSerials()
                val jo = arr.getJSONObject(which)
                val p = GeoPoint(jo.getDouble("lat"), jo.getDouble("lng"))
                val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
                val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()
                map.controller.setZoom(defaultZoom)
                map.controller.animateTo(p)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    fun btnExportar(view: View) {
        val arr = loadPontosFromPrefs()
        if (arr.length() == 0) {
            Toast.makeText(this, "Nada para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        fun csv(v: Any?): String {
            val s = (v ?: "").toString()
            return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else s
        }

        val dir = File(getExternalFilesDir(null), "exports").apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = if (nomeTrabalho.isBlank()) "trabalho" else nomeTrabalho.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(dir, "pontos_${safeName}_$ts.csv")

        try {
            FileWriter(file, false).use { fw ->
                fw.appendLine("nome_trabalho,tipo_cultura,lat,lng,timestamp,umid,temp,ec,ph,n,p,k,salinity,sensorSaved")
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    val linha = listOf(
                        nomeTrabalho,
                        tipoCultura,
                        jo.optDouble("lat", Double.NaN).toString(),
                        jo.optDouble("lng", Double.NaN).toString(),
                        jo.optLong("ts", 0L).toString(),
                        jo.optString("umid", "-"),
                        jo.optString("temp", "-"),
                        jo.optString("ec", "-"),
                        jo.optString("ph", "-"),
                        jo.optString("n", "-"),
                        jo.optString("p", "-"),
                        jo.optString("k", "-"),
                        jo.optString("salinity", "-"),
                        jo.optBoolean("sensorSaved", false).toString()
                    ).joinToString(",") { csv(it) }
                    fw.appendLine(linha)
                }
            }
            Toast.makeText(this, "Exportado: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (::myLocation.isInitialized) myLocation.enableMyLocation()
    }

    override fun onPause() {
        saveMapPreviews()
        if (::myLocation.isInitialized) myLocation.disableMyLocation()
        stopAllSerials()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopGlobalPoller()
        super.onDestroy()
    }

    private fun saveMapPreviews() {
        try {
            if (!::map.isInitialized || map.width <= 0 || map.height <= 0) return

            val bmp = Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            map.draw(canvas)

            val dir = getExternalFilesDir("previews")!!
            dir.mkdirs()

            val safe = if (nomeTrabalho.isBlank()) "trabalho" else nomeTrabalho.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val fullFile  = File(dir, "${safe}_full.jpg")
            val thumbFile = File(dir, "${safe}_thumb.jpg")

            FileOutputStream(fullFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, out) }

            val targetW = 512
            val scale   = targetW.toFloat() / bmp.width.toFloat()
            val targetH = (bmp.height * scale).roundToInt()
            val thumb   = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            FileOutputStream(thumbFile).use { out -> thumb.compress(Bitmap.CompressFormat.JPEG, 80, out) }

            bmp.recycle()
            if (thumb !== bmp) thumb.recycle()
        } catch (e: Exception) {
            android.util.Log.e("GeoApp", "Falha salvando previews: ${e.message}")
        }
    }

    private fun adicionarPontoNoCentro() {
        val center = map.mapCenter as? GeoPoint ?: return
        if (areaFinalizada) {
            areaFinalizada = false
            android.widget.Toast.makeText(this, "Área desfeita. Continue adicionando pontos e depois gere novamente.", android.widget.Toast.LENGTH_SHORT).show()
        }
        areaPts.add(GeoPoint(center.latitude, center.longitude))
        salvarAreaNoPrefs()
        atualizarAreaOverlay()
    }

    private fun finalizarArea() {
        if (areaPts.size < 3) {
            android.widget.Toast.makeText(this, "Adicione pelo menos 3 pontos para gerar a área.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        areaFinalizada = true
        salvarAreaNoPrefs()
        atualizarAreaOverlay()
        android.widget.Toast.makeText(this, "Área gerada.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun desfazerArea() {
        if (!areaFinalizada) return
        areaFinalizada = false
        salvarAreaNoPrefs()
        atualizarAreaOverlay()
        android.widget.Toast.makeText(this, "Área desfeita. Você pode editar os pontos.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun limparArea() {
        areaMarkers.forEach { map.overlays.remove(it) }
        areaMarkers.clear()
        areaPolygon?.let { map.overlays.remove(it) }
        areaPolygon = null
        areaPts.clear()
        areaFinalizada = false
        salvarAreaNoPrefs()
        map.invalidate()
    }

    private fun atualizarAreaOverlay() {
        areaMarkers.forEach { map.overlays.remove(it) }
        areaMarkers.clear()

        val vertexIcon = makeDotDrawable(COLOR_VERTEX)

        for ((idx, gp) in areaPts.withIndex()) {
            val m = Marker(map).apply {
                position = gp
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Vértice ${idx + 1}"
                icon = vertexIcon
            }
            areaMarkers.add(m)
            map.overlays.add(m)
        }

        areaPolygon?.let { map.overlays.remove(it) }
        areaPolygon = null

        if (areaFinalizada && areaPts.size >= 3) {
            val polygon = Polygon(map).apply {
                points = areaPts + areaPts.first()
                outlinePaint.strokeWidth = 4f
                outlinePaint.isAntiAlias = true
                outlinePaint.color = COLOR_AREA_OUTLINE
                fillPaint.style = android.graphics.Paint.Style.FILL
                fillPaint.color = COLOR_AREA_FILL
            }

            polygon.setOnClickListener { _, _, _ ->
                if (polygon.isInfoWindowOpen) {
                    polygon.closeInfoWindow()
                    return@setOnClickListener true
                }
                val ha  = computeAreaHectares(areaPts)
                val per = computePerimeterMeters(areaPts)
                val haStr = String.format(java.util.Locale.US, "%.2f", ha)
                val perStr = if (per >= 1000) {
                    String.format(java.util.Locale.US, "%.2f km", per / 1000.0)
                } else {
                    String.format(java.util.Locale.US, "%.0f m", per)
                }
                polygon.title = "Área: ${haStr} ha"
                polygon.snippet = "Perímetro: ${perStr}"
                polygon.showInfoWindow()
                true
            }

            areaPolygon = polygon
            map.overlays.add(polygon)
        }

        map.invalidate()
    }

    private fun computeAreaHectares(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0
        var lat0 = 0.0; var lon0 = 0.0
        for (p in points) { lat0 += p.latitude; lon0 += p.longitude }
        lat0 /= points.size; lon0 /= points.size

        val R = 6371000.0
        val lat0Rad = Math.toRadians(lat0)
        fun toXY(p: GeoPoint): Pair<Double, Double> {
            val x = R * Math.toRadians(p.longitude - lon0) * Math.cos(lat0Rad)
            val y = R * Math.toRadians(p.latitude - lat0)
            return x to y
        }

        var sum = 0.0
        for (i in points.indices) {
            val (x1, y1) = toXY(points[i])
            val (x2, y2) = toXY(points[(i + 1) % points.size])
            sum += (x1 * y2 - x2 * y1)
        }
        val areaM2 = kotlin.math.abs(sum) * 0.5
        return areaM2 / 10_000.0
    }

    private fun computePerimeterMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var d = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            d += a.distanceToAsDouble(b)
        }
        return d
    }

    private fun salvarAreaNoPrefs() {
        try {
            val arr = org.json.JSONArray()
            for (p in areaPts) {
                arr.put(org.json.JSONObject().put("lat", p.latitude).put("lon", p.longitude))
            }
            val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            sp.edit()
                .putString("area_${nomeTrabalho}", arr.toString())
                .putBoolean("area_finalizada_${nomeTrabalho}", areaFinalizada)
                .apply()
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun carregarAreaDoPrefs() {
        areaPts.clear()
        areaFinalizada = false
        try {
            val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            val s = sp.getString("area_${nomeTrabalho}", null)
            if (s != null) {
                val arr = org.json.JSONArray(s)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    areaPts.add(GeoPoint(o.getDouble("lat"), o.getDouble("lon")))
                }
            }
            areaFinalizada = sp.getBoolean("area_finalizada_${nomeTrabalho}", false)
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun makeDotDrawable(@androidx.annotation.ColorInt color: Int): android.graphics.drawable.BitmapDrawable {
        val size = dpToPx(16f)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.style = android.graphics.Paint.Style.FILL
        p.color = color
        c.drawCircle(size / 2f, size / 2f, size / 2.4f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = dpToPx(1.5f).toFloat()
        p.color = 0xFF1A1A1A.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2.4f, p)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun sensorAtributoDentroDaArea(chave: String): List<Pair<GeoPoint, Double>> {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val s = sp.getString(pontosKey, "[]") ?: "[]"
        val arr = org.json.JSONArray(s)
        if (areaPts.size < 3) return emptyList()

        val poly = areaPts.toList()
        val pts = mutableListOf<Pair<GeoPoint, Double>>()

        fun parseDouble(str: String?): Double? {
            if (str.isNullOrBlank()) return null
            val v = str.replace(",", ".").lowercase()
                .replace("ph", "")
                .replace("°c", "")
                .replace("%", "")
                .replace("mg/kg", "")
                .replace("ms/cm", "")
                .replace("[^0-9.\\-+]".toRegex(), "")
                .trim()
            return v.toDoubleOrNull()
        }

        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            if (!jo.optBoolean("sensorSaved", false)) continue
            val lat = jo.optDouble("lat", Double.NaN)
            val lng = jo.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue

            val valueStr = jo.optString(chave, null) ?: continue
            val value = parseDouble(valueStr) ?: continue

            val gp = GeoPoint(lat, lng)
            val inside = pointInPolygonXY(
                projectPolygon(listOf(gp), poly)[0],
                projectPolygon(poly, listOf(gp))
            )
            if (inside) pts += gp to value
        }
        return pts
    }

    private fun gerarMapaAtributo(
        botaoId: Int,
        tituloLegenda: String,
        chaveJson: String,
        paleta: (Double, Double, Double, Int) -> Int = SensorHeatmapOverlay::defaultRamp
    ) {
        if (activeHeatmap != null && allowedWhileLockedId == botaoId) {
            removerMapaAtributo()
            return
        }

        val amostras = sensorAtributoDentroDaArea(chaveJson)
        if (amostras.size < 3) {
            android.widget.Toast.makeText(this, "É preciso pelo menos 3 pontos com $tituloLegenda dentro da área.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        activeHeatmap?.let { map.overlays.remove(it) }
        activeHeatmap = null

        allowedWhileLockedId = botaoId

        Thread {
            try {
                val ov = SensorHeatmapOverlay(
                    polygon = areaPts.toList(),
                    samples = amostras,
                    variogram = Variogram(range = 35.0, sill = 1.0, nugget = 0.05),
                    gridSizePx = 420,
                    legendTitle = tituloLegenda,
                    colorRamp = paleta
                )
                runOnUiThread {
                    activeHeatmap = ov
                    map.overlays.add(ov)
                    lockMapInteractions()
                    map.invalidate()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Erro ao gerar mapa: ${t.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun removerMapaAtributo() {
        activeHeatmap?.let { map.overlays.remove(it) }
        activeHeatmap = null
        unlockMapInteractions()
        map.invalidate()
    }

    private fun lockMapInteractions() {
        if (mapLocked) return
        mapLocked = true

        map.setMultiTouchControls(false)
        map.setOnTouchListener { _, _ -> true }
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        fun disableTree(v: android.view.View) {
            if (v.id == allowedWhileLockedId) return
            v.isEnabled = false
            v.isClickable = false
            v.isFocusable = false
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) disableTree(v.getChildAt(i))
        }
        disableTree(root)

        val mapButtons = intArrayOf(
            R.id.btnMapaPh, R.id.btnMapaTemp, R.id.btnMapaUmid,
            R.id.btnMapaN,  R.id.btnMapaP,   R.id.btnMapaK,
            R.id.btnMapaSal, R.id.btnMapaCond
        )
        for (id in mapButtons) {
            val b = findViewById<android.view.View>(id) ?: continue
            if (id == allowedWhileLockedId) {
                b.alpha = 1f
                b.isEnabled = true
                b.isClickable = true
            } else {
                b.alpha = 0.35f
                b.isEnabled = false
                b.isClickable = false
            }
        }

        val center = map.mapCenter as org.osmdroid.util.GeoPoint
        val eps = 1e-6
        val bb = org.osmdroid.util.BoundingBox(center.latitude + eps, center.longitude + eps, center.latitude - eps, center.longitude - eps)
        map.setScrollableAreaLimitDouble(bb)
        val z = map.zoomLevelDouble
        map.setMinZoomLevel(z)
        map.setMaxZoomLevel(z)
    }

    private fun unlockMapInteractions() {
        if (!mapLocked) return
        mapLocked = false

        map.setOnTouchListener(null)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)

        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        fun enableTree(v: android.view.View) {
            v.isEnabled = true
            v.isClickable = true
            v.isFocusable = true
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) enableTree(v.getChildAt(i))
        }
        enableTree(root)

        val mapButtons = intArrayOf(
            R.id.btnMapaPh, R.id.btnMapaTemp, R.id.btnMapaUmid,
            R.id.btnMapaN,  R.id.btnMapaP,   R.id.btnMapaK,
            R.id.btnMapaSal, R.id.btnMapaCond
        )
        for (id in mapButtons) {
            val b = findViewById<android.view.View>(id) ?: continue
            b.alpha = 1f
            b.isEnabled = true
            b.isClickable = true
        }

        val world = org.osmdroid.util.BoundingBox(85.0511, 180.0, -85.0511, -180.0)
        map.setScrollableAreaLimitDouble(world)
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(22.0)
    }

    fun btnVoltar(view: View) = finish()
}