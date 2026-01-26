package com.example.geoapp

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class SobreDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_sobre, null, false)
        val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setPositiveButton("Fechar") { d, _ -> d.dismiss() }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }
}