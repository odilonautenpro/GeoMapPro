package com.example.geoapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject

class NovoActivity : AppCompatActivity() {
    private var currentPosition = 0
    private lateinit var videoView: VideoView
    private lateinit var edtNome: EditText
    private lateinit var edtCultura: EditText
    private lateinit var swGeo: SwitchMaterial
    private var editMode = false
    private var editIndex = -1
    private var oldNameForMigration: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novo)

        edtNome = findViewById(R.id.edtNomeTrabalho)
        edtCultura = findViewById(R.id.edtTipoCultura)
        swGeo = findViewById(R.id.swGeoref)

        videoView = findViewById(R.id.globo_terrestre)
        val uri = Uri.parse("android.resource://${packageName}/${R.raw.globo_terrestre}")
        videoView.setVideoURI(uri)
        videoView.setMediaController(null)
        videoView.setOnCompletionListener {
            videoView.start()
        }
        videoView.start()

        editMode = intent.getBooleanExtra("edit_mode", false)
        editIndex = intent.getIntExtra("edit_index", -1)

        if (editMode) {
            oldNameForMigration = intent.getStringExtra("nome_trabalho") ?: ""
            edtNome.setText(oldNameForMigration)
            edtCultura.setText(intent.getStringExtra("tipo_cultura") ?: "")
            swGeo.isChecked = intent.getBooleanExtra("georreferenciamento_habilitado", false)
            findViewById<Button?>(R.id.btnContinuar)?.text = "Abrir"
        }
    }

    private fun saveJob(nome: String, cultura: String, georef: Boolean) {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val arr = JSONArray(sp.getString("jobs_json", "[]") ?: "[]")

        var idx = -1
        for (i in 0 until arr.length()) {
            val jo = arr.getJSONObject(i)
            if (jo.optString("nome").equals(nome, ignoreCase = true)) {
                idx = i
                break
            }
        }

        val region = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            .getString("region", "br") ?: "br"

        val novo = JSONObject()
            .put("nome", nome)
            .put("cultura", cultura)
            .put("georef", georef)
            .put("region", region)
            .put("ts", System.currentTimeMillis())


        if (idx >= 0) {
            arr.put(idx, novo)
        } else {
            arr.put(novo)
        }

        sp.edit().putString("jobs_json", arr.toString()).apply()
    }

    private fun openMain(nome: String, cultura: String, georef: Boolean) {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra("nome_trabalho", nome)
            putExtra("tipo_cultura", cultura)
            putExtra("georreferenciamento_habilitado", georef)
        }
        startActivity(i)
        finish()
    }

    fun btnContinuar(view: View) {
        val nome = edtNome.text?.toString()?.trim().orEmpty()
        val cultura = edtCultura.text?.toString()?.trim().orEmpty()
        val georef = swGeo.isChecked

        if (nome.isBlank()) {
            Toast.makeText(this, "Informe o nome do trabalho", Toast.LENGTH_SHORT).show()
            return
        }

        if (editMode && editIndex >= 0) {
            updateJob(editIndex, oldNameForMigration, nome, cultura, georef)
            Toast.makeText(this, "Alterações salvas", Toast.LENGTH_SHORT).show()
            openMain(nome, cultura, georef)
        } else {
            saveJob(nome, cultura, georef)
            openMain(nome, cultura, georef)
        }
    }

    fun btnSalvar(view: View) {
        val nome = edtNome.text.toString().trim()
        val cultura = edtCultura.text.toString().trim()
        val georef = swGeo.isChecked

        if (nome.isBlank()) {
            Toast.makeText(this, "Informe o nome do trabalho", Toast.LENGTH_SHORT).show()
            return
        }

        if (editMode && editIndex >= 0) {
            updateJob(editIndex, oldNameForMigration, nome, cultura, georef)
            Toast.makeText(this, "Trabalho atualizado", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            saveJob(nome, cultura, georef)
            openMain(nome, cultura, georef)
        }
    }

    private fun updateJob(index: Int, oldName: String, newName: String, cultura: String, georef: Boolean) {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val arr = JSONArray(sp.getString("jobs_json", "[]") ?: "[]")
        val region = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
            .getString("region", "br") ?: "br"

        if (index in 0 until arr.length()) {
            if (!oldName.equals(newName, ignoreCase = true) && jobIndexByName(newName) != -1) {
                Toast.makeText(this, "Já existe um trabalho com esse nome", Toast.LENGTH_SHORT).show()
                return
            }
            val jo = arr.getJSONObject(index)
            jo.put("nome", newName)
            jo.put("cultura", cultura)
            jo.put("georef", georef)
            jo.put("region", region)
            jo.put("ts", System.currentTimeMillis())

            sp.edit().putString("jobs_json", arr.toString()).apply()

            migratePointsKeyIfNeeded(oldName, newName)
        }
    }

    private fun migratePointsKeyIfNeeded(oldName: String?, newName: String?) {
        val from = (oldName ?: "").ifEmpty { "default" }
        val to = (newName ?: "").ifEmpty { "default" }
        if (from.equals(to, ignoreCase = true)) return

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val fromKey = "pontos_$from"
        val toKey = "pontos_$to"

        val oldJson = sp.getString(fromKey, null) ?: return
        sp.edit()
            .putString(toKey, oldJson)
            .remove(fromKey)
            .apply()
    }

    private fun jobIndexByName(name: String): Int {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val arr = JSONArray(sp.getString("jobs_json", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("nome").equals(name, ignoreCase = true)) return i
        }
        return -1
    }

    override fun onPause() {
        super.onPause()
        currentPosition = videoView.currentPosition
        videoView.pause()
    }

    override fun onResume() {
        super.onResume()
        videoView.seekTo(currentPosition)
        videoView.start()
    }

    fun btnVoltar(view: View) = finish()
}
