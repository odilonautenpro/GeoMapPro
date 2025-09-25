package com.example.geoapp

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.GoogleMap
import kotlin.math.roundToInt

class AjustesActivity : AppCompatActivity() {

    private lateinit var rgMapType: RadioGroup
    private lateinit var seekZoom: SeekBar
    private lateinit var tvZoomVal: TextView

    private val sp by lazy { getSharedPreferences("geoapp_prefs", MODE_PRIVATE) }
    private val zoomMin = 5
    private val zoomMax = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)

        rgMapType = findViewById(R.id.rgMapType)
        seekZoom  = findViewById(R.id.seekZoom)
        tvZoomVal = findViewById(R.id.tvZoomVal)

        // Carrega atuais
        val savedType = sp.getInt("map_type", GoogleMap.MAP_TYPE_SATELLITE)
        when (savedType) {
            GoogleMap.MAP_TYPE_NORMAL   -> rgMapType.check(R.id.rbNormal)
            GoogleMap.MAP_TYPE_SATELLITE-> rgMapType.check(R.id.rbSatelite)
            GoogleMap.MAP_TYPE_TERRAIN  -> rgMapType.check(R.id.rbTerreno)
            GoogleMap.MAP_TYPE_HYBRID   -> rgMapType.check(R.id.rbHibrido)
            else                        -> rgMapType.check(R.id.rbSatelite)
        }

        val savedZoom = sp.getFloat("default_zoom", 16f).coerceIn(zoomMin.toFloat(), zoomMax.toFloat())
        seekZoom.max = (zoomMax - zoomMin) // progress 0..15
        seekZoom.progress = (savedZoom - zoomMin).roundToInt()
        tvZoomVal.text = "Zoom: ${savedZoom.roundToInt()}"

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val z = zoomMin + progress
                tvZoomVal.text = "Zoom: $z"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    fun btnSalvar(view: View) {
        val mapType = when (rgMapType.checkedRadioButtonId) {
            R.id.rbNormal   -> GoogleMap.MAP_TYPE_NORMAL
            R.id.rbSatelite -> GoogleMap.MAP_TYPE_SATELLITE
            R.id.rbTerreno  -> GoogleMap.MAP_TYPE_TERRAIN
            R.id.rbHibrido  -> GoogleMap.MAP_TYPE_HYBRID
            else            -> GoogleMap.MAP_TYPE_SATELLITE
        }
        val zoom = (zoomMin + seekZoom.progress).toFloat()

        sp.edit()
            .putInt("map_type", mapType)
            .putFloat("default_zoom", zoom)
            .apply()

        finish()
    }

    fun btnVoltar(view: View) = finish()
}