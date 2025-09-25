package com.example.geoapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var nomeTrabalho: String = ""
    private var tipoCultura: String = ""
    private var georefHabilitado: Boolean = false
    private val pontosMem = mutableListOf<LatLng>()
    private val MAX_MARKERS_TO_DRAW = 500
    private val MAX_POINTS_TO_KEEP  = 5000

    private val pontosKey: String
        get() = "pontos_${nomeTrabalho.ifEmpty { "default" }}"

    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) enableMyLocationAndCenter()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        nomeTrabalho = intent.getStringExtra("nome_trabalho").orEmpty()
        tipoCultura = intent.getStringExtra("tipo_cultura").orEmpty()
        georefHabilitado = intent.getBooleanExtra("georreferenciamento_habilitado", false)

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        // Long-press em "Listar Pontos" limpa tudo do trabalho atual
        findViewById<View>(R.id.btnListarPontos).setOnLongClickListener {
            limparPontosDoTrabalho()
            true
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        mMap.mapType = sp.getInt("map_type", GoogleMap.MAP_TYPE_SATELLITE)
        mMap.uiSettings.isZoomControlsEnabled = true

        if (georefHabilitado) {
            enableMyLocationAndCenter()
        } else {
            val fallback = LatLng(-26.1954494, -52.6717275)
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fallback, 12f))
        }

        desenharPontosDoPrefs()
    }

    private fun enableMyLocationAndCenter() {
        if (!georefHabilitado) return

        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!(hasFine || hasCoarse)) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        mMap.isMyLocationEnabled = true
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val defaultZoom = sp.getFloat("default_zoom", 16f)
        val cts = CancellationTokenSource()
        fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                val here: LatLng = if (loc != null) {
                    LatLng(loc.latitude, loc.longitude)
                } else {
                    LatLng(-26.1954494, -52.6717275)
                }
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(here, defaultZoom))
            }
            .addOnFailureListener {
                val fallback = LatLng(-26.1954494, -52.6717275)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fallback, defaultZoom))
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
        getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            .edit()
            .remove(pontosKey)
            .apply()
        pontosMem.clear()
        if (::mMap.isInitialized) mMap.clear()
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
            Toast.makeText(
                this,
                "Mostrando os últimos $MAX_MARKERS_TO_DRAW de $n pontos",
                Toast.LENGTH_LONG
            ).show()
        }

        for (i in start until n) {
            val jo = arr.getJSONObject(i)
            val p = LatLng(jo.getDouble("lat"), jo.getDouble("lng"))
            pontosMem.add(p)
            mMap.addMarker(MarkerOptions().position(p).title("Ponto ${i + 1}"))
        }
    }

    private fun addPonto(latLng: LatLng) {
        pontosMem.add(latLng)
        mMap.addMarker(MarkerOptions().position(latLng).title("Ponto ${pontosMem.size}"))

        val arr = loadPontosFromPrefs()
        val jo = JSONObject()
            .put("lat", latLng.latitude)
            .put("lng", latLng.longitude)
            .put("ts", System.currentTimeMillis())
        arr.put(jo)
        savePontosToPrefs(arr)

        Toast.makeText(this, "Ponto ${pontosMem.size} salvo", Toast.LENGTH_SHORT).show()
    }

    private fun formatLatLng(p: LatLng): String {
        val df = DecimalFormat("0.000000")
        return "${df.format(p.latitude)}, ${df.format(p.longitude)}"
    }

    fun btnNovoPonto(view: View) {
        if (georefHabilitado) {
            val hasFine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                val cts = CancellationTokenSource()
                fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc ->
                        val p = if (loc != null)
                            LatLng(loc.latitude, loc.longitude)
                        else
                            mMap.cameraPosition.target
                        addPonto(p)
                    }
                    .addOnFailureListener {
                        addPonto(mMap.cameraPosition.target)
                    }
                return
            }
        }
        addPonto(mMap.cameraPosition.target)
    }

    fun btnListarPontos(view: View) {
        val arr = loadPontosFromPrefs()
        if (arr.length() == 0) {
            Toast.makeText(this, "Nenhum ponto salvo", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = Array(arr.length()) { i ->
            val jo = arr.getJSONObject(i)
            val p = LatLng(jo.getDouble("lat"), jo.getDouble("lng"))
            val whenStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(Date(jo.optLong("ts", 0L)))
            "Ponto ${i + 1} — ${formatLatLng(p)} — $whenStr"
        }

        AlertDialog.Builder(this)
            .setTitle("Pontos de $nomeTrabalho")
            .setItems(labels) { _, which ->
                val jo = arr.getJSONObject(which)
                val p = LatLng(jo.getDouble("lat"), jo.getDouble("lng"))
                val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
                val defaultZoom = sp.getFloat("default_zoom", 16f)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(p, defaultZoom))
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

        val dir = File(getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = if (nomeTrabalho.isBlank()) "trabalho" else nomeTrabalho.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(dir, "pontos_${safeName}_$ts.csv")

        try {
            FileWriter(file, false).use { fw ->
                fw.appendLine("nome_trabalho,tipo_cultura,lat,lng,timestamp")
                for (i in 0 until arr.length()) {
                    val jo = arr.getJSONObject(i)
                    val lat = jo.getDouble("lat")
                    val lng = jo.getDouble("lng")
                    val t = jo.optLong("ts", 0L)
                    fw.appendLine("\"$nomeTrabalho\",\"$tipoCultura\",$lat,$lng,$t")
                }
            }
            Toast.makeText(this, "Exportado: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}