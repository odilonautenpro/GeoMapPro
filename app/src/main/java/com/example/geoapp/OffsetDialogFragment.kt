package com.example.geoapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import android.text.TextWatcher

class OffsetDialogFragment : DialogFragment() {

    private lateinit var sp: SharedPreferences
    private var tipoSolo: Float = 1.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_offset_fullscreen, container, false)

        sp = requireContext().getSharedPreferences("geoapp_prefs", Context.MODE_PRIVATE)

        val rgTipoSolo = view.findViewById<android.widget.RadioGroup>(R.id.rgTipoSolo)
        val etSoloValor = view.findViewById<EditText>(R.id.etSoloValor)
        val btnSoloMenos = view.findViewById<Button>(R.id.btnSoloMenos)
        val btnSoloMais = view.findViewById<Button>(R.id.btnSoloMais)

        var updatingSoilUI = false

        fun parseSoilText(): Float? {
            val txt = etSoloValor.text?.toString()
                ?.trim()
                ?.replace(',', '.')
            return txt?.toFloatOrNull()
        }

        fun setSoilValue(v: Float, updateEditText: Boolean) {
            tipoSolo = v
            sp.edit().putFloat("soil_factor", tipoSolo).apply()

            if (updateEditText) {
                updatingSoilUI = true
                try {
                    etSoloValor.setText(v.toString())
                    etSoloValor.setSelection(etSoloValor.text?.length ?: 0)
                } finally {
                    updatingSoilUI = false
                }
            }
        }

        val savedFactor = sp.getFloat("soil_factor", 1.5f)
        tipoSolo = savedFactor
        etSoloValor.setText(savedFactor.toString())

        when (savedFactor) {
            1.3f -> rgTipoSolo.check(R.id.rbFofo)
            1.5f -> rgTipoSolo.check(R.id.rbMedio)
            1.8f -> rgTipoSolo.check(R.id.rbCompactado)
        }

        rgTipoSolo.setOnCheckedChangeListener { _, checkedId ->
            if (updatingSoilUI) return@setOnCheckedChangeListener

            val v = when (checkedId) {
                R.id.rbFofo -> 1.3f
                R.id.rbMedio -> 1.5f
                R.id.rbCompactado -> 1.8f
                else -> tipoSolo
            }
            setSoilValue(v, updateEditText = true)
        }

        etSoloValor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingSoilUI) return
                val v = parseSoilText() ?: return
                setSoilValue(v, updateEditText = false)
            }
        })

        val step = 0.1f
        val minV = 0.9f
        val maxV = 2.0f

        fun clamp(v: Float): Float = v.coerceIn(minV, maxV)

        fun currentSoil(): Float = parseSoilText() ?: tipoSolo

        btnSoloMenos.setOnClickListener {
            val next = clamp(currentSoil() - step)
            setSoilValue(next, updateEditText = true)
        }

        btnSoloMais.setOnClickListener {
            val next = clamp(currentSoil() + step)
            setSoilValue(next, updateEditText = true)
        }

        val etUmidX1 = view.findViewById<EditText>(R.id.etUmidX1)
        val etUmidY1 = view.findViewById<EditText>(R.id.etUmidY1)
        val etUmidX2 = view.findViewById<EditText>(R.id.etUmidX2)
        val etUmidY2 = view.findViewById<EditText>(R.id.etUmidY2)

        val etTempX1 = view.findViewById<EditText>(R.id.etTempX1)
        val etTempY1 = view.findViewById<EditText>(R.id.etTempY1)
        val etTempX2 = view.findViewById<EditText>(R.id.etTempX2)
        val etTempY2 = view.findViewById<EditText>(R.id.etTempY2)

        val etEcX1 = view.findViewById<EditText>(R.id.etEcX1)
        val etEcY1 = view.findViewById<EditText>(R.id.etEcY1)
        val etEcX2 = view.findViewById<EditText>(R.id.etEcX2)
        val etEcY2 = view.findViewById<EditText>(R.id.etEcY2)

        val etPhX1 = view.findViewById<EditText>(R.id.etPhX1)
        val etPhY1 = view.findViewById<EditText>(R.id.etPhY1)
        val etPhX2 = view.findViewById<EditText>(R.id.etPhX2)
        val etPhY2 = view.findViewById<EditText>(R.id.etPhY2)

        val etNX1 = view.findViewById<EditText>(R.id.etNX1)
        val etNY1 = view.findViewById<EditText>(R.id.etNY1)
        val etNX2 = view.findViewById<EditText>(R.id.etNX2)
        val etNY2 = view.findViewById<EditText>(R.id.etNY2)

        val etPX1 = view.findViewById<EditText>(R.id.etPX1)
        val etPY1 = view.findViewById<EditText>(R.id.etPY1)
        val etPX2 = view.findViewById<EditText>(R.id.etPX2)
        val etPY2 = view.findViewById<EditText>(R.id.etPY2)

        val etKX1 = view.findViewById<EditText>(R.id.etKX1)
        val etKY1 = view.findViewById<EditText>(R.id.etKY1)
        val etKX2 = view.findViewById<EditText>(R.id.etKX2)
        val etKY2 = view.findViewById<EditText>(R.id.etKY2)

        val etSalX1 = view.findViewById<EditText>(R.id.etSalX1)
        val etSalY1 = view.findViewById<EditText>(R.id.etSalY1)
        val etSalX2 = view.findViewById<EditText>(R.id.etSalX2)
        val etSalY2 = view.findViewById<EditText>(R.id.etSalY2)

        val btnFechar = view.findViewById<Button>(R.id.btnFecharOffset)
        btnFechar.setOnClickListener {
            val editor = sp.edit()

            fun txt2d(et: EditText?): Double? =
                et?.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

            fun salvarCalibracao(
                etX1: EditText?, etY1: EditText?,
                etX2: EditText?, etY2: EditText?,
                keyPrefix: String
            ) {
                val x1 = txt2d(etX1)
                val y1 = txt2d(etY1)
                val x2 = txt2d(etX2)
                val y2 = txt2d(etY2)

                if (x1 != null && y1 != null && x2 != null && y2 != null && x2 != x1) {
                    val a = (y2 - y1) / (x2 - x1)   // A = ΔY / ΔX
                    val b = y1 - a * x1            // B = Y1 - A·X1
                    editor.putFloat("${keyPrefix}_a", a.toFloat())
                    editor.putFloat("${keyPrefix}_b", b.toFloat())
                }
            }

            salvarCalibracao(etUmidX1, etUmidY1, etUmidX2, etUmidY2, "cal_umid")
            salvarCalibracao(etTempX1, etTempY1, etTempX2, etTempY2, "cal_temp")
            salvarCalibracao(etEcX1,   etEcY1,   etEcX2,   etEcY2,   "cal_ec")
            salvarCalibracao(etPhX1,   etPhY1,   etPhX2,   etPhY2,   "cal_ph")
            salvarCalibracao(etNX1,    etNY1,    etNX2,    etNY2,    "cal_n")
            salvarCalibracao(etPX1,    etPY1,    etPX2,    etPY2,    "cal_p")
            salvarCalibracao(etKX1,    etKY1,    etKX2,    etKY2,    "cal_k")
            salvarCalibracao(etSalX1,  etSalY1,  etSalX2,  etSalY2,  "cal_sal")

            editor.apply()
            dismiss()
        }

        return view
    }
}
