package com.example.geoapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

    }

    fun btnNovo(view: View) {
        val intent = Intent(this, NovoActivity::class.java)
        startActivity(intent)
    }

    fun btnCarregar(view: View) {
        val intent = Intent(this, CarregarActivity::class.java)
        startActivity(intent)
    }

    fun btnAjustes(view: View) {
        val intent = Intent(this, AjustesActivity::class.java)
        startActivity(intent)
    }
}