package com.example.geoapp

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AjustesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val currentRegion = sp.getString("region", "br") ?: "br"
        val defaultZoom = sp.getFloat("default_zoom", 16f)

        val rg = findViewById<RadioGroup>(R.id.rgRegion)
        val rbBr = findViewById<RadioButton>(R.id.rbBrasil)
        val rbPy = findViewById<RadioButton>(R.id.rbParaguai)
        val tvZoom = findViewById<TextView>(R.id.tvZoomValue)
        val sbZoom = findViewById<SeekBar>(R.id.sbZoom)

        if (currentRegion == "py") rbPy.isChecked = true else rbBr.isChecked = true

        tvZoom.text = String.format("%.1f", defaultZoom)
        sbZoom.max = 25
        sbZoom.progress = defaultZoom.toInt()
        sbZoom.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvZoom.text = String.format("%.1f", progress.toFloat())
                sp.edit().putFloat("default_zoom", progress.toFloat()).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rg.setOnCheckedChangeListener { _, checkedId ->
            val region = if (checkedId == R.id.rbParaguai) "py" else "br"
            sp.edit().putString("region", region).apply()
        }

        findViewById<android.view.View>(R.id.btnVoltar)?.setOnClickListener { finish() }
    }
}
