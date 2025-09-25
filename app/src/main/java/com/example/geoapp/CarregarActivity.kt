package com.example.geoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class CarregarActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var jobsJson: JSONArray
    private lateinit var labels: MutableList<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carregar)

        listView = findViewById(R.id.listTrabalhos)

        jobsJson = loadJobs()
        labels = buildLabels(jobsJson)

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            labels
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            val job = jobsJson.getJSONObject(position)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra("nome_trabalho", job.optString("nome"))
                    putExtra("tipo_cultura", job.optString("cultura"))
                    putExtra("georreferenciamento_habilitado", job.optBoolean("georef"))
                }
            )
        }
    }

    private fun loadJobs(): JSONArray {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val s = sp.getString("jobs_json", "[]") ?: "[]"
        return JSONArray(s)
    }

    private fun buildLabels(arr: JSONArray): MutableList<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val jo: JSONObject = arr.getJSONObject(i)
            val nome = jo.optString("nome")
            val cultura = jo.optString("cultura")
            val gps = if (jo.optBoolean("georef")) "GPS ON" else "GPS OFF"
            out.add("$nome — $cultura — $gps")
        }
        return out
    }

    fun btnVoltar(view: View) = finish()
}
