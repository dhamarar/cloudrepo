package com.fpo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FpoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Fpo())
    }
}
