package com.cinejoy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinejoyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cinejoy())
    }
}
