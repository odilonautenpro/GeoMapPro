package com.example.geoapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
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
import android.graphics.BitmapFactory
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val sensorExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var reading = false
    private lateinit var map: MapView
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var crossOverlay: Overlay
    private var nomeTrabalho: String = ""
    private var tipoCultura: String = ""
    private var georefHabilitado: Boolean = false
    private val MAX_MARKERS_TO_DRAW = 500
    private val MAX_POINTS_TO_KEEP  = 5000
    private val pontosKey: String get() = "pontos_${nomeTrabalho.ifEmpty { "default" }}"
    private val FIXED_FALLBACK = GeoPoint(-26.1955215, -52.6710228)
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

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                centerOnStartupLocation()
                setupMyLocationOverlayAndCenter()
            }
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

        Configuration.getInstance().userAgentValue = packageName

        @Suppress("UNCHECKED_CAST")
        map = (findViewById<MapView?>(resources.getIdentifier("mapView", "id", packageName))
            ?: findViewById(resources.getIdentifier("map", "id", packageName))) as MapView

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()
        map.controller.setZoom(defaultZoom)
        map.setMultiTouchControls(true)

        setupOfflineMapOrWarn()

        crossOverlay = CenterCrossOverlay()
        map.overlays.add(crossOverlay)

        if (georefHabilitado) {
            ensureLocationAndProvidersThen {
                centerOnStartupLocation()
                setupMyLocationOverlayAndCenter()
            }
        } else {
            map.controller.setCenter(FIXED_FALLBACK)
        }

        findViewById<View>(R.id.btnListarPontos).setOnLongClickListener {
            limparPontosDoTrabalho()
            true
        }

        desenharPontosDoPrefs()
    }

    private fun setupOfflineMapOrWarn() {
        AndroidGraphicFactory.createInstance(application)
        MapsForgeTileSource.createInstance(application)

        val mapsDir = File(getExternalFilesDir("maps"), "")
        val mapFile = File(mapsDir, "brazil.map")

        if (!mapFile.exists()) {
            Toast.makeText(this, "brazil.map não encontrado em ${mapsDir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }

        try {
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
            Toast.makeText(this, "Falha no mapa offline: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureLocationAndProvidersThen(afterOk: () -> Unit) {
        val hasFine  = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse= ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!(hasFine || hasCoarse)) {
            requestLocationPerms.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsOn && !netOn) {
            Toast.makeText(this, "Ative GPS ou Localização por Rede", Toast.LENGTH_LONG).show()
        }
        afterOk()
    }

    private fun centerOnStartupLocation() {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager

        val lastGps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
        val lastNet = try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: SecurityException) { null }
        val best = listOfNotNull(lastGps, lastNet).maxByOrNull { it.time }
        if (best != null) {
            val p = GeoPoint(best.latitude, best.longitude)
            map.controller.setZoom(defaultZoom)
            map.controller.setCenter(p)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { loc ->
                    loc?.let {
                        val here = GeoPoint(it.latitude, it.longitude)
                        map.controller.setZoom(defaultZoom)
                        map.controller.setCenter(here)
                    }
                }
            } catch (_: SecurityException) { }
        }
    }

    private fun setupMyLocationOverlayAndCenter() {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()

        val provider = GpsMyLocationProvider(this).apply {
            addLocationSource(LocationManager.NETWORK_PROVIDER)
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            try {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { loc ->
                    loc?.let {
                        val here = GeoPoint(it.latitude, it.longitude)
                        map.controller.setZoom(defaultZoom)
                        map.controller.animateTo(here)
                    }
                }
            } catch (_: SecurityException) { }
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

    fun btnVoltar(view: View) = finish()
}
