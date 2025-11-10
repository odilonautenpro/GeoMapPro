package com.example.geoapp

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

class AjustesActivity : AppCompatActivity() {

    private var pendingFileToSave: File? = null

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val src = pendingFileToSave ?: return@registerForActivityResult
        pendingFileToSave = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { it.copyTo(out) }
            }
            toast("Arquivo salvo com sucesso.")
        } catch (e: Exception) {
            toast("Erro ao salvar: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)

        val btnVoltar = findViewById<Button>(R.id.btnVoltar)
        btnVoltar.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }

        renderExportList()
    }

    override fun onResume() {
        super.onResume()
        renderExportList()
    }

    private fun renderExportList() {
        val container = findViewById<LinearLayout>(R.id.exportList)
        container.removeAllViews()

        val exportsDir = File(getExternalFilesDir(null), "exports")
        val csvs = if (exportsDir.exists())
            exportsDir.listFiles { f -> f.extension.equals("csv", true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        else emptyList()

        if (csvs.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Nenhum arquivo exportado encontrado"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 14f
            }
            container.addView(tv)
            return
        }

        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        for (f in csvs) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_export_row, container, false)

            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvDate = row.findViewById<TextView>(R.id.tvDate)
            val btnMore = row.findViewById<ImageView>(R.id.iconFile)

            tvName.text = f.name
            tvDate.text = "${sdf.format(f.lastModified())}  •  ${(f.length() / 1024)} KB"

            row.setOnClickListener { showFileMenu(row, f) }
            btnMore.setOnClickListener { showFileMenu(btnMore, f) }

            container.addView(row)
        }
    }

    private fun showFileMenu(anchor: View, file: File) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Salvar em pendrive/SD")
        pm.menu.add(0, 2, 1, "Excluir arquivo")

        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> saveToExternal(file)
                2 -> deleteFileAndRefresh(file)
            }
            true
        }
        pm.show()
    }

    private fun saveToExternal(f: File) {
        pendingFileToSave = f
        createDocLauncher.launch(f.name)
    }

    private fun deleteFileAndRefresh(f: File) {
        if (f.delete()) {
            toast("Excluído: ${f.name}")
            renderExportList()
        } else {
            toast("Não foi possível excluir.")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
