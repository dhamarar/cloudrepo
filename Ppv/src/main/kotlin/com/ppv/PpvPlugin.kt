package com.ppv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PpvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Ppv())
        registerExtractorAPI(PpvExtractor())
    }
}
