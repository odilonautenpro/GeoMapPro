package com.example.geoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject

class NovoActivity : AppCompatActivity() {
    private lateinit var edtNome: EditText
    private lateinit var edtCultura: EditText
    private lateinit var swGeo: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novo)

        edtNome = findViewById(R.id.edtNomeTrabalho)
        edtCultura = findViewById(R.id.edtTipoCultura)
        swGeo = findViewById(R.id.swGeoref)
    }

    private fun saveJob(nome: String, cultura: String, georef: Boolean) {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val arr = JSONArray(sp.getString("jobs_json", "[]") ?: "[]")
        var idx = -1
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            if (jo.optString("nome").equals(nome, ignoreCase = true))
                idx = i; break
        }
        val novo = JSONObject()
            .put("nome", nome)
            .put("cultura", cultura)
            .put("georef", georef)
            .put("ts", System.currentTimeMillis())
        if (idx >= 0)
            arr.put(idx, novo)
        else
            arr.put(novo)
        sp.edit().putString("jobs_json", arr.toString()).apply()
    }

    fun btnContinuar(view: View) {
        val nome = edtNome.text?.toString()?.trim().orEmpty()
        val cultura = edtCultura.text?.toString()?.trim().orEmpty()
        val georef = swGeo.isChecked
        if (nome.isBlank()) {
            Toast.makeText(this, "Informe o nome do trabalho", Toast.LENGTH_SHORT).show()
            return
        }
        saveJob(nome, cultura, georef)
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra("nome_trabalho", nome)
            putExtra("tipo_cultura", cultura)
            putExtra("georreferenciamento_habilitado", georef)
        }
        startActivity(i)
        finish()
    }
}