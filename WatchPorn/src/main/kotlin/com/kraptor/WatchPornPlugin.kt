package com.kraptor

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WatchPornPlugin : Plugin() {
    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun load(context: Context) {
        appContext = context
        registerMainAPI(WatchPorn(context))

        this.openSettings = { ctx ->
            val dialog = WatchPornSettingsDialog(ctx as AppCompatActivity) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
            dialog.show()
        }
    }
}
