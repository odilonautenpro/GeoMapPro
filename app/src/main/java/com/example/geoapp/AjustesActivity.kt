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

        val sp = getSharedPreferences("geoapp_prefs", MODE_PRIVATE)
        val currentRegion = sp.getString("region", "br") ?: "br"
        val defaultZoom = sp.getFloat("default_zoom", 16f)

        val rg = findViewById<RadioGroup>(R.id.rgRegion)
        val rbBr = findViewById<RadioButton>(R.id.rbBrasil)
        val rbPy = findViewById<RadioButton>(R.id.rbParaguai)
        val tvZoom = findViewById<TextView>(R.id.tvZoomValue)
        val sbZoom = findViewById<SeekBar>(R.id.sbZoom)
        val btnOffset = findViewById<Button>(R.id.btnOffset)
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)
        val btnSobre = findViewById<Button>(R.id.btnSobre)

        if (currentRegion == "py") rbPy?.isChecked = true else rbBr?.isChecked = true

        rg?.setOnCheckedChangeListener { _, checkedId ->
            val region = if (checkedId == R.id.rbParaguai) "py" else "br"
            sp.edit().putString("region", region).apply()
        }

        tvZoom?.text = String.format(Locale.getDefault(), "%.1f", defaultZoom)
        sbZoom?.max = 18
        sbZoom?.progress = defaultZoom.toInt()
        sbZoom?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvZoom?.text = String.format(Locale.getDefault(), "%.1f", progress.toFloat())
                sp.edit().putFloat("default_zoom", progress.toFloat()).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnOffset?.setOnClickListener {
            val dialog = OffsetDialogFragment()
            dialog.show(supportFragmentManager, "offsetDialog")
        }

        btnSobre?.setOnClickListener {
            val dialog = SobreDialogFragment()
            dialog.show(supportFragmentManager, "sobreDiaolog")
        }

            btnVoltar?.setOnClickListener {
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
