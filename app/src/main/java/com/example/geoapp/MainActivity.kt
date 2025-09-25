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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // === MAPA / LOCALIZAÇÃO ===
    private lateinit var map: MapView
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var crossOverlay: Overlay

    // === ESTADO DO TRABALHO ===
    private var nomeTrabalho: String = ""
    private var tipoCultura: String = ""
    private var georefHabilitado: Boolean = false
    private val pontosMem = mutableListOf(GeoPoint(0.0, 0.0)) // placeholder; não usado diretamente

    private val MAX_MARKERS_TO_DRAW = 500
    private val MAX_POINTS_TO_KEEP  = 5000

    private val pontosKey: String
        get() = "pontos_${nomeTrabalho.ifEmpty { "default" }}"

    // === PERMISSÕES ===
    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) setupMyLocationOverlayAndCenter()
        }

    // ===== Overlay que desenha um "X" no centro da tela =====
    private class CenterCrossOverlay(
        private val sizeDp: Float = 14f,
        private val strokeDp: Float = 2f,
        private val color: Int = Color.parseColor("#FF3D00") // laranja forte
    ) : Overlay() {

        private fun dpToPx(dp: Float, view: View) =
            dp * view.resources.displayMetrics.density

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

            // "X" central
            c.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
            c.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Extras vindos das telas "Novo/Carregar"
        nomeTrabalho     = intent.getStringExtra("nome_trabalho").orEmpty()
        tipoCultura      = intent.getStringExtra("tipo_cultura").orEmpty()
        georefHabilitado = intent.getBooleanExtra("georreferenciamento_habilitado", false)

        // Requerido pelo OSMDroid para cache
        Configuration.getInstance().userAgentValue = packageName

        // Suporta tanto @id/mapView (recomendado) quanto @id/map (se você manteve o id antigo)
        @Suppress("UNCHECKED_CAST")
        map = (findViewById<MapView?>(resources.getIdentifier("mapView", "id", packageName))
            ?: findViewById(resources.getIdentifier("map", "id", packageName))) as MapView

        // Fonte de tiles padrão do OSM (sem chave, sem GMS)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Viewport inicial (evita "mundo inteiro" antes do 1º fix)
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()
        val fallback = GeoPoint(-26.1954494, -52.6717275)
        map.controller.setZoom(defaultZoom)
        map.controller.setCenter(fallback)

        // Overlay do "X" no centro (adicionado por último para ficar por cima)
        crossOverlay = CenterCrossOverlay()
        map.overlays.add(crossOverlay)
        map.invalidate()

        // Long-press em "Listar Pontos" limpa os pontos do trabalho
        findViewById<View>(R.id.btnListarPontos).setOnLongClickListener {
            limparPontosDoTrabalho()
            true
        }

        // Configura localização (permissões + providers) e centraliza no 1º fix
        if (georefHabilitado) ensureLocationAndProvidersThen { setupMyLocationOverlayAndCenter() }

        // Desenha pontos salvos deste trabalho
        desenharPontosDoPrefs()
    }

    // ========== LOCALIZAÇÃO / CENTRALIZAÇÃO ==========
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
            // Opcional: abrir configurações
            // startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        afterOk()
    }

    private fun setupMyLocationOverlayAndCenter() {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f).toDouble()

        val provider = GpsMyLocationProvider(this).apply {
            // Usa rede além do GPS para acelerar o 1º fix
            addLocationSource(LocationManager.NETWORK_PROVIDER)
        }

        myLocation = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
            enableFollowLocation() // seguir automaticamente a posição
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

        // Fallback extra (API 30+): tentar uma leitura pontual e centralizar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { loc ->
                loc?.let {
                    val here = GeoPoint(it.latitude, it.longitude)
                    map.controller.setZoom(defaultZoom)
                    map.controller.animateTo(here)
                }
            }
        }
    }

    // ========== PERSISTÊNCIA / DESENHO ==========
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
        getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            .edit()
            .remove(pontosKey)
            .apply()
        // Mantém overlays de localização e o X; remove só os marcadores
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
            val p = GeoPoint(jo.getDouble("lat"), jo.getDouble("lng"))
            addMarker(p, "Ponto ${i + 1}")
        }
        map.invalidate()
    }

    private fun addMarker(p: GeoPoint, title: String) {
        val m = Marker(map)
        m.position = p
        m.title = title
        map.overlays.add(m)
    }

    private fun addPonto(p: GeoPoint) {
        addMarker(p, "Ponto ${(loadPontosFromPrefs().length() + 1)}")

        val arr = loadPontosFromPrefs()
        val jo = JSONObject()
            .put("lat", p.latitude)
            .put("lng", p.longitude)
            .put("ts", System.currentTimeMillis())
        arr.put(jo)
        savePontosToPrefs(arr)

        Toast.makeText(this, "Ponto salvo: ${formatGeo(p)}", Toast.LENGTH_SHORT).show()
        map.invalidate()
    }

    private fun formatGeo(p: GeoPoint): String {
        val df = DecimalFormat("0.000000")
        return "${df.format(p.latitude)}, ${df.format(p.longitude)}"
    }

    // ========== AÇÕES DE BOTÃO ==========
    fun btnNovoPonto(view: View) {
        // Sempre salva o ponto sob o "X" (centro do mapa)
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

    // ========== CICLO DE VIDA (OSMDroid precisa disso) ==========
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
}
