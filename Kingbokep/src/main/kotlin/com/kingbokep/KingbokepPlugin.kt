package com.kingbokep

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KingbokepPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kingbokep())
    }
}
