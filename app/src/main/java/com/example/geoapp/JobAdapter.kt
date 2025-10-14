package com.example.geoapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import org.osmdroid.util.BoundingBox

class JobAdapter(
    private val context: Context,
    private val jobs: JSONArray
) : BaseAdapter() {

    private val cache: LruCache<String, Bitmap> by lazy {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMem / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    override fun getCount(): Int = jobs.length()
    override fun getItem(position: Int): Any = jobs.getJSONObject(position)
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_galeria, parent, false)

        val job = getItem(position) as JSONObject
        val txt = view.findViewById<TextView>(R.id.textItem)
        val img = view.findViewById<ImageView>(R.id.imgPreview)

        val nome    = job.optString("nome")
        val cultura = job.optString("cultura")
        val gps     = if (job.optBoolean("georef", false)) "GPS ON" else "GPS OFF"
        txt.text    = when {
            nome.isNotBlank() && cultura.isNotBlank() -> "$nome — $cultura — $gps"
            nome.isNotBlank() -> "$nome — $gps"
            else -> "Sem nome — $gps"
        }

        val safe = if (nome.isBlank()) "trabalho" else nome.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val thumb = File(context.getExternalFilesDir("previews"), "${safe}_thumb.jpg")

        img.setImageResource(android.R.drawable.ic_menu_mapmode)

        if (thumb.exists()) {
            val key = thumb.absolutePath
            val cached = cache.get(key)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                Executors.newSingleThreadExecutor().execute {
                    val bmp = decodeSampled(thumb, 400, 300)
                    if (bmp != null) {
                        cache.put(key, bmp)
                        img.post { img.setImageBitmap(bmp) }
                    }
                }
            }
        } else {
            img.setImageResource(android.R.drawable.ic_menu_mapmode)
        }

        return view
    }

    private fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)

        opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = Bitmap.Config.RGB_565 // usa metade da RAM de ARGB_8888
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = opts.outHeight
        val width  = opts.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfH = height / 2
            var halfW = width / 2
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                halfH /= 2
                halfW /= 2
            }
        }
        return inSampleSize
    }
}