package com.example.geoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.GridView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class CarregarActivity : AppCompatActivity() {
    private var jobsJson = JSONArray()
    private lateinit var gridView: GridView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carregar)

        gridView = findViewById(R.id.gridTrabalhos)

        jobsJson = loadJobs()

        gridView.adapter = JobAdapter(this, jobsJson)

        gridView.setOnItemClickListener { _, _, position, _ ->
            showJobActionsDialog(position)
        }
    }

    override fun onResume() {
        super.onResume()
        jobsJson = loadJobs()
        gridView.adapter = JobAdapter(this, jobsJson)
    }

    private fun showJobActionsDialog(index: Int) {
        if (index !in 0 until jobsJson.length()) return
        val jo = jobsJson.getJSONObject(index)
        val nome = jo.optString("nome")
        val cultura = jo.optString("cultura")
        val georef = jo.optBoolean("georef", false)

        val options = arrayOf("Entrar no trabalho", "Editar informações", "Excluir trabalho")
        AlertDialog.Builder(this)
            .setTitle("Trabalho: $nome")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> enterJob(nome, cultura, georef)
                    1 -> editJob(index, jo)
                    2 -> confirmDelete(index, nome)
                }
            }
            .show()
    }

    private fun enterJob(nome: String, cultura: String, georef: Boolean) {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra("nome_trabalho", nome)
            putExtra("tipo_cultura", cultura)
            putExtra("georreferenciamento_habilitado", georef)
        }
        startActivity(i)
    }

    private fun editJob(index: Int, jo: JSONObject) {
        val i = Intent(this, NovoActivity::class.java).apply {
            putExtra("edit_mode", true)
            putExtra("edit_index", index)
            putExtra("nome_trabalho", jo.optString("nome"))
            putExtra("tipo_cultura", jo.optString("cultura"))
            putExtra("georreferenciamento_habilitado", jo.optBoolean("georef", false))
        }
        startActivity(i)
    }

    private fun confirmDelete(index: Int, nome: String) {
        AlertDialog.Builder(this)
            .setTitle("Excluir")
            .setMessage("Tem certeza que deseja excluir o trabalho \"$nome\"?")
            .setPositiveButton("Excluir") { _, _ ->
                deleteJobAndPoints(index, nome)
                Toast.makeText(this, "Trabalho \"$nome\" excluído", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteJobAndPoints(index: Int, nome: String) {
        val newArr = JSONArray()
        for (i in 0 until jobsJson.length()) {
            if (i != index) newArr.put(jobsJson.getJSONObject(i))
        }
        jobsJson = newArr
        saveJobs(jobsJson)

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        sp.edit().remove("pontos_${nome.ifEmpty { "default" }}").apply()

        val safe = if (nome.isBlank()) "trabalho" else nome.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = getExternalFilesDir("previews")
        if (dir != null) {
            val full  = java.io.File(dir, "${safe}_full.jpg")
            val thumb = java.io.File(dir, "${safe}_thumb.jpg")
            try { if (full.exists()) full.delete() } catch (_: Throwable) {}
            try { if (thumb.exists()) thumb.delete() } catch (_: Throwable) {}
        }

        gridView.adapter = JobAdapter(this, jobsJson)

        if (jobsJson.length() == 0) {
            Toast.makeText(this, "Nenhum trabalho cadastrado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveJobs(arr: JSONArray) {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        sp.edit().putString("jobs_json", arr.toString()).apply()
    }

    private fun loadJobs(): JSONArray {
        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val s = sp.getString("jobs_json", "[]") ?: "[]"
        return try { JSONArray(s) } catch (_: Exception) { JSONArray() }
    }

    fun btnVoltar(view: View) = finish()
}
