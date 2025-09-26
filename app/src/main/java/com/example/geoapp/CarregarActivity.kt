package com.example.geoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class CarregarActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private var jobsJson = JSONArray()
    private var labels = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carregar)

        listView = findViewById(R.id.listTrabalhos)

        jobsJson = loadJobs()
        labels = buildLabels(jobsJson)

        listView.adapter = ArrayAdapter(
            this,
            R.layout.item_trabalho,
            R.id.textItem,
            labels
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            showJobActionsDialog(position)
        }
    }

    override fun onResume() {
        super.onResume()
        jobsJson = loadJobs()
        labels = buildLabels(jobsJson)
        (listView.adapter as ArrayAdapter<String>).apply {
            clear()
            addAll(labels)
            notifyDataSetChanged()
        }
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

        labels = buildLabels(jobsJson)
        (listView.adapter as ArrayAdapter<String>).apply {
            clear()
            addAll(labels)
            notifyDataSetChanged()
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

    private fun buildLabels(arr: JSONArray): MutableList<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val jo: JSONObject = arr.getJSONObject(i)
            val nome = jo.optString("nome")
            val cultura = jo.optString("cultura")
            val gps = if (jo.optBoolean("georef", false)) "GPS ON" else "GPS OFF"

            val label = when {
                nome.isNotBlank() && cultura.isNotBlank() -> "$nome — $cultura — $gps"
                nome.isNotBlank() -> "$nome — $gps"
                else -> "Sem nome — $gps"
            }
            out.add(label)
        }
        return out
    }

    fun btnVoltar(view: View) = finish()
}
