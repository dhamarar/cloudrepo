package com.avtub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AvtubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Avtub())
        registerExtractorAPI(MorenciusExtractor())
        registerExtractorAPI(YStreamExtractor())
    }
}
