package com.example.geoapp

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class GeoApp : Application() {
    companion object {
        lateinit var instance: GeoApp
            private set
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                RootShell.execAsync("sh -c 'echo 4 > /sys/class/EMDEBUG/custom_output_gpio'")
            }
            override fun onStop(owner: LifecycleOwner) {
                RootShell.execAsync("sh -c 'echo 5 > /sys/class/EMDEBUG/custom_output_gpio'")
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            RootShell.execAsync("sh -c 'echo 5 > /sys/class/EMDEBUG/custom_output_gpio'")
        }
    }
}

object RootShell {
    private val execPool = Executors.newSingleThreadExecutor()
    fun execAsync(cmd: String) {
        execPool.execute {
            runCatching {
                val pb = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

                BufferedReader(InputStreamReader(pb.inputStream)).use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        Log.d("RootShell", line!!)
                    }
                }
                val code = pb.waitFor()
                Log.d("RootShell", "Command exit=$code : $cmd")
            }.onFailure {
                Log.e("RootShell", "Erro ao executar: $cmd", it)
            }
        }
    }
}
