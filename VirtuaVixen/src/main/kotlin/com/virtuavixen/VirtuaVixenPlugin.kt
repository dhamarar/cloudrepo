package com.virtuavixen

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VirtuaVixenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VirtuaVixen())
    }
}
