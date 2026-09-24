package com.pemersatufun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PemersatufunPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pemersatufun())
    }
}
