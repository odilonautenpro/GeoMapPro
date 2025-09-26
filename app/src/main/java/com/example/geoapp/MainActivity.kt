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

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var crossOverlay: Overlay
    private var nomeTrabalho: String = ""
    private var tipoCultura: String = ""
    private var georefHabilitado: Boolean = false
    private val MAX_MARKERS_TO_DRAW = 500
    private val MAX_POINTS_TO_KEEP  = 5000
    private val pontosKey: String
        get() = "pontos_${nomeTrabalho.ifEmpty { "default" }}"
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

        // Long-press em "Listar Pontos" limpa os pontos do trabalho
        findViewById<View>(R.id.btnListarPontos).setOnLongClickListener {
            limparPontosDoTrabalho()
            true
        }

        // Desenha pontos salvos
        desenharPontosDoPrefs()
    }

    private fun setupOfflineMapOrWarn() {
        // Necessário pelo Mapsforge
        AndroidGraphicFactory.createInstance(application)
        MapsForgeTileSource.createInstance(application)

        // Espera-se o arquivo em: Android/data/<pkg>/files/maps/brazil.map
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
        map.overlays.removeAll { it is Marker } // preserva alvo e overlay de localização
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
                    .put(PontoKeys.PH, "-")
                    .put(PontoKeys.N, "-")
                    .put(PontoKeys.K, "-")
                    .put(PontoKeys.P, "-")
                )
            }
            true
        }
        map.overlays.add(m)
    }

    private fun showPontoInfoDialog(jo: JSONObject) {
        val view = layoutInflater.inflate(R.layout.dialog_ponto_info, null)

        fun setText(id: Int, v: Any?) {
            view.findViewById<android.widget.TextView>(id).text = (v ?: "-").toString()
        }

        setText(R.id.tvId,  jo.opt(PontoKeys.ID))
        setText(R.id.tvLat, jo.optDouble(PontoKeys.LAT, Double.NaN).takeIf { it==it })
        setText(R.id.tvLng, jo.optDouble(PontoKeys.LNG, Double.NaN).takeIf { it==it })
        setText(R.id.tvUmid, jo.opt(PontoKeys.UMID))
        setText(R.id.tvTemp, jo.opt(PontoKeys.TEMP))
        setText(R.id.tvPh,   jo.opt(PontoKeys.PH))
        setText(R.id.tvN,    jo.opt(PontoKeys.N))
        setText(R.id.tvK,    jo.opt(PontoKeys.K))
        setText(R.id.tvP,    jo.opt(PontoKeys.P))

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun addPonto(p: GeoPoint) {
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
        val center = map.mapCenter
        val p = GeoPoint(center.latitude, center.longitude)
        addPonto(p)
    }

    fun btnListarPontos(view: View) {
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
        val dir = File(getExternalFilesDir(null), "exports").apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = if (nomeTrabalho.isBlank()) "trabalho" else nomeTrabalho.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(dir, "pontos_${safeName}_$ts.csv")
        try {
            FileWriter(file, false).use { fw ->
                fw.appendLine("nome_trabalho,tipo_cultura,lat,lng,timestamp")
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    fw.appendLine("\"$nomeTrabalho\",\"$tipoCultura\",${jo.getDouble("lat")},${jo.getDouble("lng")},${jo.optLong("ts",0)}")
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
        if (::myLocation.isInitialized) myLocation.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    fun btnVoltar(view: View) = finish()
}
