package com.example.geoapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import android.widget.VideoView

class HomeActivity : AppCompatActivity() {
    private var currentPosition = 0
    private lateinit var videoView: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        videoView = findViewById(R.id.globo_terrestre)
        val uri = Uri.parse("android.resource://${packageName}/${R.raw.globo_terrestre}")
        videoView.setVideoURI(uri)
        videoView.setMediaController(null)
        videoView.setOnCompletionListener {
            videoView.start()
        }
        videoView.start()
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