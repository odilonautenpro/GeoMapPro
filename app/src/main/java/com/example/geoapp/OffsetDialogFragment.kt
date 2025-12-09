package com.example.geoapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.DialogFragment

class OffsetDialogFragment : DialogFragment() {

    private lateinit var sp: SharedPreferences

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

        val etOffsetUmid = view.findViewById<EditText>(R.id.etOffsetUmid)
        val etOffsetTemp = view.findViewById<EditText>(R.id.etOffsetTemp)
        val etOffsetEc = view.findViewById<EditText>(R.id.etOffsetEc)
        val etOffsetPh = view.findViewById<EditText>(R.id.etOffsetPh)
        val etOffsetN = view.findViewById<EditText>(R.id.etOffsetN)
        val etOffsetP = view.findViewById<EditText>(R.id.etOffsetP)
        val etOffsetK = view.findViewById<EditText>(R.id.etOffsetK)
        val etOffsetSalinity = view.findViewById<EditText>(R.id.etOffsetSalinity)

        fun loadToEditText(et: EditText, key: String) {
            val v = sp.getFloat(key, 0f)
            if (v != 0f) {
                et.setText(v.toString())
            } else {
                et.setText("")
            }
        }

        loadToEditText(etOffsetUmid, "offset_umid")
        loadToEditText(etOffsetTemp, "offset_temp")
        loadToEditText(etOffsetEc, "offset_ec")
        loadToEditText(etOffsetPh, "offset_ph")
        loadToEditText(etOffsetN, "offset_n")
        loadToEditText(etOffsetP, "offset_p")
        loadToEditText(etOffsetK, "offset_k")
        loadToEditText(etOffsetSalinity, "offset_salinity")

        fun setupAutoSave(et: EditText, key: String) {
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val txt = s?.toString()?.trim()?.replace(',', '.')
                    val value = txt?.toFloatOrNull() ?: 0f
                    sp.edit().putFloat(key, value).apply()
                }
            })
        }

        setupAutoSave(etOffsetUmid, "offset_umid")
        setupAutoSave(etOffsetTemp, "offset_temp")
        setupAutoSave(etOffsetEc, "offset_ec")
        setupAutoSave(etOffsetPh, "offset_ph")
        setupAutoSave(etOffsetN, "offset_n")
        setupAutoSave(etOffsetP, "offset_p")
        setupAutoSave(etOffsetK, "offset_k")
        setupAutoSave(etOffsetSalinity, "offset_salinity")

        val btnFechar = view.findViewById<Button>(R.id.btnFecharOffset)
        btnFechar.setOnClickListener { dismiss() }

        return view
    }
}
