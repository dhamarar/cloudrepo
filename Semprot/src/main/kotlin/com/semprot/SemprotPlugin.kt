package com.semprot

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference

@CloudstreamPlugin
class SemprotPlugin : Plugin() {
    companion object {
        lateinit var appContext: Context
            private set

        private var activityRef: WeakReference<Activity>? = null
        val currentActivity: Activity?
            get() = activityRef?.get()
    }

    override fun load(context: Context) {
        appContext = context.applicationContext ?: context
        if (context is Activity) {
            activityRef = WeakReference(context)
        }

        registerMainAPI(Semprot())
        registerExtractorAPI(VidaraExtractor())
        registerExtractorAPI(VidaraSoExtractor())
        registerExtractorAPI(PlaymogoExtractor())

        this.openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null) {
                activityRef = WeakReference(act)
                SemprotSettingsDialog(act) {
                    MainActivity.reloadHomeEvent.invoke(true)
                }.show()
            }
        }
    }
}
