package com.byayzen

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import android.util.Log

open class MyDaddyExtractor : ExtractorApi() {
    override val name = "MyDaddy"
    override val mainUrl = "https://mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = "https://diepornos.com/")
        val html = res.text
        Log.d(name, html.take(500))

        val sourceregex = Regex("""<source[^>]+>""")
        val srcRegex = Regex("""src=\\?["'](.*?)\\?["']""")
        val titleRegex = Regex("""title=\\?["'](.*?)\\?["']""")

        val matches = sourceregex.findAll(html).toList()
        Log.d(name, "${matches.size}")

        val processed = mutableSetOf<String>()
        matches.forEach { match ->
            val sourceTag = match.value
            val srcMatch = srcRegex.find(sourceTag)
            val titleMatch = titleRegex.find(sourceTag)

            val rawurl = srcMatch?.groupValues?.get(1)?.replace("\\", "") ?: ""
            if (rawurl.isEmpty()) return@forEach

            if (!processed.add(rawurl)) return@forEach

            val finalurl = if (rawurl.startsWith("//")) "https:$rawurl" else rawurl
            val title = titleMatch?.groupValues?.get(1) ?: this.name

            Log.d(name, finalurl)

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = title,
                    url = finalurl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
        }
    }
}