package com.vikuy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VikuyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Vikuy())
    }
}
