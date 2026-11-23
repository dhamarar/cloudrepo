package com.semprot

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class Semprot : MainAPI() {
    override var mainUrl = "https://www.semprot.com"
    override var name = "Semprot"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        private const val TAG = "Semprot"
        private const val IGO_NODE_ID = "11"
        private const val IGO_FORUM_PATH = "/forums/film-cewek-indonesia-igo.11/"
        private const val LIVE_THREAD_ID = "1502373"
        private const val LIVE_THREAD_PATH =
            "/threads/konten-live-show-disini-dilarang-request-ask-bertanya-part-3-bukan-tempat-reques-dan-bertanya-spam-oot-dilarang.1502373/"

        private const val IGO_CONCURRENCY = 4
        private val VIDARA_REGEX = Regex(
            """https?://(?:www\.)?(?:vidara\.(?:to|so)|bnvcq8s\.com)/[^\s"'<>()\[\]]+""",
            RegexOption.IGNORE_CASE
        )
        private val PLAYMOGO_REGEX = Regex(
            """https?://(?:www\.)?playmogo\.com/[^\s"'<>()\[\]]+""",
            RegexOption.IGNORE_CASE
        )
        private val POST_ID_REGEX = Regex("""(?:#post-|/posts/)(\d+)""")
    }

    private data class IgoThreadRef(
        val url: String,
        val title: String,
        val fallbackPoster: String?,
        val cacheKey: String
    )

    private data class StreamCardData(
        val title: String,
        val cardUrl: String,
        val postUrl: String,
        val videoUrl: String,
        val poster: String?,
        val author: String,
        val plot: String
    )

    private val igoThreadCache = ConcurrentHashMap<String, List<StreamCardData>>()

    @Volatile
    private var liveLastPage: Int? = null

    @Volatile
    private var lastSearchQuery: String? = null

    @Volatile
    private var lastIgoSearchUrl: String? = null

    @Volatile
    private var lastLiveSearchUrl: String? = null

    override val mainPage = mainPageOf(
        "$mainUrl$IGO_FORUM_PATH" to "IGO (Newest)",
        "$mainUrl$LIVE_THREAD_PATH" to "Live"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = semprotHeaders()
        return if (request.data.contains(LIVE_THREAD_PATH)) {
            getLiveMainPage(page, request, headers)
        } else {
            getIgoMainPage(page, request, headers)
        }
    }

    private suspend fun getIgoMainPage(
        page: Int,
        request: MainPageRequest,
        posterHeaders: Map<String, String>
    ): HomePageResponse {
        val pagePart = if (page <= 1) "" else "page-$page"
        val url = "$mainUrl$IGO_FORUM_PATH$pagePart?order=post_date&direction=desc"
        val doc = semprotAuthedGet(url).document

        val rows = doc.select("div.structItemContainer-group--threads div.structItem--thread")
            .ifEmpty { doc.select("div.structItem--thread:not(.is-sticky)") }

        val threadRefs = rows.mapNotNull { row ->
            val titleEl = row.selectFirst("div.structItem-title a[data-tp-primary]")
                ?: row.select("div.structItem-title a").lastOrNull {
                    !it.hasClass("labelLink") && it.attr("href").contains("/threads/")
                } ?: return@mapNotNull null

            val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = fixUrlNull(titleEl.attr("href")) ?: return@mapNotNull null
            val cleanHref = href.substringBefore("/unread").substringBefore("?").let {
                if (it.endsWith("/")) it else "$it/"
            }

            val imgEl = row.selectFirst("div.structItem-iconContainer img")
            val avatarPoster = fixUrlNull(
                imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
            )
            val latestTime = row.selectFirst("time.structItem-latestDate")?.attr("data-time")
                ?: row.selectFirst("div.structItem-cell--meta dd")?.text()?.trim()
                ?: ""

            IgoThreadRef(
                url = cleanHref,
                title = title,
                fallbackPoster = avatarPoster,
                cacheKey = "$cleanHref|$latestTime"
            )
        }.distinctBy { it.url }

        val semaphore = Semaphore(IGO_CONCURRENCY)
        val perThreadCards: List<List<StreamCardData>> = coroutineScope {
            threadRefs.map { ref ->
                async {
                    igoThreadCache[ref.cacheKey]?.let { return@async it }
                    semaphore.withPermit {
                        try {
                            val cards = scanIgoThreadCards(ref)
                            if (cards.isNotEmpty()) {
                                igoThreadCache[ref.cacheKey] = cards
                            }
                            cards
                        } catch (e: ErrorLoadingException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Skip IGO thread ${ref.url}: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }

        val items = perThreadCards.flatten().distinctBy { it.cardUrl }.map { card ->
            newMovieSearchResponse(card.title, card.cardUrl, TvType.NSFW) {
                this.posterUrl = card.poster
                this.posterHeaders = posterHeaders
            }
        }

        val hasNext = doc.selectFirst("a.pageNav-jump--next") != null
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private suspend fun scanIgoThreadCards(ref: IgoThreadRef): List<StreamCardData> {
        val firstDoc = semprotAuthedGet(ref.url).document
        val result = extractCardsFromDocument(
            doc = firstDoc,
            baseThreadUrl = ref.url,
            pageNum = 1,
            threadTitle = ref.title,
            fallbackPoster = ref.fallbackPoster
        ).toMutableList()

        // Jika halaman 1 tidak punya link (misal video ada di halaman terakhir), cek halaman terakhir
        if (result.isEmpty()) {
            val totalPages = parseTotalPages(firstDoc)
            if (totalPages > 1) {
                val lastDoc = semprotAuthedGet("${ref.url}page-$totalPages").document
                result.addAll(
                    extractCardsFromDocument(
                        doc = lastDoc,
                        baseThreadUrl = ref.url,
                        pageNum = totalPages,
                        threadTitle = ref.title,
                        fallbackPoster = ref.fallbackPoster
                    )
                )
            }
        }

        return result
    }

    private fun extractCardsFromDocument(
        doc: Document,
        baseThreadUrl: String,
        pageNum: Int,
        threadTitle: String,
        fallbackPoster: String?
    ): List<StreamCardData> {
        val cards = mutableListOf<StreamCardData>()
        val posts = doc.select("article.message")
        val firstPostId = posts.firstOrNull()?.let { extractPostId(it) }
        for (post in posts) {
            val links = extractVideoLinks(post)
            if (links.isEmpty()) continue

            val postId = extractPostId(post) ?: cards.size.toString()
            val author = post.attr("data-author").takeIf { it.isNotBlank() }
                ?: post.selectFirst("h4.message-name a, .message-name")?.text()?.trim()
                ?: "Anon"
            val postUrl = "${baseThreadUrl}page-$pageNum#post-$postId"
            val postPoster = extractPostImage(post) ?: fallbackPoster
            val postSnippet = extractCleanPostText(post)
            val baseLabel = extractPostTitle(post, threadTitle).let {
                if (it.equals(threadTitle, ignoreCase = true) || postId == firstPostId) {
                    threadTitle
                } else {
                    "$threadTitle - $it"
                }
            }

            links.forEachIndexed { idx, videoUrl ->
                val hostTag = hostLabel(videoUrl)
                val cardTitle = if (links.size > 1) {
                    "$baseLabel • #${idx + 1} [$hostTag]"
                } else {
                    "$baseLabel [$hostTag]"
                }
                val cardUrl = buildStreamCardUrl(
                    postUrl = postUrl,
                    index = idx,
                    title = cardTitle,
                    videoUrl = videoUrl,
                    poster = postPoster,
                    author = author,
                    plot = postSnippet
                )
                cards.add(
                    StreamCardData(
                        title = cardTitle,
                        cardUrl = cardUrl,
                        postUrl = postUrl,
                        videoUrl = videoUrl,
                        poster = postPoster,
                        author = author,
                        plot = postSnippet
                    )
                )
            }
        }
        return cards
    }

    private suspend fun getLiveMainPage(
        page: Int,
        request: MainPageRequest,
        posterHeaders: Map<String, String>
    ): HomePageResponse {
        val lastPage: Int = if (page <= 1 || liveLastPage == null) {
            val firstDoc = semprotAuthedGet("$mainUrl$LIVE_THREAD_PATH").document
            val parsedLast = parseTotalPages(firstDoc)
            liveLastPage = parsedLast
            if (page <= 1 && parsedLast == 1) {
                val items = parseLiveCardsFromPage(firstDoc, 1, posterHeaders)
                return newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = false
                )
            }
            parsedLast
        } else {
            liveLastPage ?: 1
        }

        var targetPage = lastPage - (page - 1)
        if (targetPage < 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }
        var currentPageDoc = semprotAuthedGet("$mainUrl${LIVE_THREAD_PATH}page-$targetPage").document
        var items = parseLiveCardsFromPage(currentPageDoc, targetPage, posterHeaders)

        // Jika halaman terakhir baru saja dibuat dan belum punya post berlink video, mundur hingga 2 halaman pada page 1
        if (page <= 1 && items.isEmpty() && targetPage > 1) {
            var step = 0
            while (items.isEmpty() && targetPage > 1 && step < 2) {
                targetPage--
                step++
                liveLastPage = targetPage
                currentPageDoc = semprotAuthedGet("$mainUrl${LIVE_THREAD_PATH}page-$targetPage").document
                items = parseLiveCardsFromPage(currentPageDoc, targetPage, posterHeaders)
            }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = targetPage > 1
        )
    }

    private fun parseLiveCardsFromPage(
        doc: Document,
        pageNum: Int,
        posterHeaders: Map<String, String>
    ): List<SearchResponse> {
        val posts = doc.select("article.message").reversed()
        val results = mutableListOf<SearchResponse>()

        for (post in posts) {
            val links = extractVideoLinks(post)
            if (links.isEmpty()) continue

            val postId = extractPostId(post) ?: continue
            val author = post.attr("data-author").takeIf { it.isNotBlank() }
                ?: post.selectFirst("h4.message-name a, .message-name")?.text()?.trim()
                ?: "Anon"
            val baseTitle = extractPostTitle(post, "Live #$postId ($author)")
            val poster = extractPostImage(post)
            val postUrl = "$mainUrl${LIVE_THREAD_PATH}page-$pageNum#post-$postId"
            val snippet = extractCleanPostText(post)

            links.forEachIndexed { idx, videoUrl ->
                val hostTag = hostLabel(videoUrl)
                val cardTitle = if (links.size > 1) {
                    "$baseTitle • #${idx + 1} [$hostTag]"
                } else {
                    "$baseTitle [$hostTag]"
                }
                val cardUrl = buildStreamCardUrl(
                    postUrl = postUrl,
                    index = idx,
                    title = cardTitle,
                    videoUrl = videoUrl,
                    poster = poster,
                    author = author,
                    plot = snippet
                )
                results.add(
                    newMovieSearchResponse(cardTitle, cardUrl, TvType.NSFW) {
                        this.posterUrl = poster
                        this.posterHeaders = posterHeaders
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return newSearchResponseList(emptyList(), false)

        val headers = semprotHeaders()
        if (page <= 1 || lastSearchQuery != cleanQuery) {
            lastSearchQuery = cleanQuery
            lastIgoSearchUrl = null
            lastLiveSearchUrl = null
        }

        val tokenDoc = semprotAuthedGet("$mainUrl$IGO_FORUM_PATH").document
        val xfToken = extractXfToken(tokenDoc)

        val igoResults = runCatching {
            searchScoped(
                query = cleanQuery,
                page = page,
                xfToken = xfToken,
                extraParams = mapOf("c[nodes][]" to IGO_NODE_ID),
                cachedResultUrl = lastIgoSearchUrl,
                onResultUrl = { lastIgoSearchUrl = it },
                posterHeaders = headers,
                preferPostAnchor = false
            )
        }.getOrDefault(SearchBatch(emptyList(), false))

        val liveResults = runCatching {
            searchScoped(
                query = cleanQuery,
                page = page,
                xfToken = xfToken,
                extraParams = mapOf(
                    "c[thread]" to LIVE_THREAD_ID,
                    "search_type" to "post"
                ),
                cachedResultUrl = lastLiveSearchUrl,
                onResultUrl = { lastLiveSearchUrl = it },
                posterHeaders = headers,
                preferPostAnchor = true
            )
        }.getOrDefault(SearchBatch(emptyList(), false))

        val combined = (igoResults.items + liveResults.items).distinctBy { it.url }
        val hasNext = igoResults.hasNext || liveResults.hasNext
        return newSearchResponseList(combined, hasNext)
    }

    private data class SearchBatch(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun searchScoped(
        query: String,
        page: Int,
        xfToken: String?,
        extraParams: Map<String, String>,
        cachedResultUrl: String?,
        onResultUrl: (String) -> Unit,
        posterHeaders: Map<String, String>,
        preferPostAnchor: Boolean
    ): SearchBatch {
        val res = if (page > 1 && !cachedResultUrl.isNullOrBlank()) {
            val pagedUrl = appendPageQuery(cachedResultUrl, page)
            semprotAuthedGet(pagedUrl)
        } else if (!xfToken.isNullOrBlank()) {
            val form = buildMap {
                put("_xfToken", xfToken)
                put("keywords", query)
                put("order", "date")
                putAll(extraParams)
            }
            semprotAuthedPost("$mainUrl/search/search", data = form)
        } else {
            val params = (mapOf("keywords" to query, "o" to "date") + extraParams)
                .entries.joinToString("&") { (k, v) ->
                    "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }
            semprotAuthedGet("$mainUrl/search/search?$params")
        }

        val finalUrl = res.url
        if (finalUrl.contains("/search/") && !finalUrl.endsWith("/search/search")) {
            onResultUrl(finalUrl.substringBefore("?"))
        }

        val doc = res.document
        val rows = doc.select("li.block-row")
        val items = rows.mapNotNull { row ->
            val titleEl = row.selectFirst("h3.contentRow-title a") ?: return@mapNotNull null
            val rawHref = fixUrlNull(titleEl.attr("href")) ?: return@mapNotNull null
            val snippet = row.selectFirst("div.contentRow-snippet")?.text()?.trim().orEmpty()
            val rawTitle = titleEl.text().trim()
            val title = if (preferPostAnchor && snippet.isNotBlank()) {
                snippet.take(80)
            } else {
                rawTitle
            }

            val imgEl = row.selectFirst("span.contentRow-figure img, img")
            val poster = fixUrlNull(
                imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
            )

            newMovieSearchResponse(title, rawHref, TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders
            }
        }

        val hasNext = doc.selectFirst("a.pageNav-jump--next") != null
        return SearchBatch(items, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val posterHeaders = semprotHeaders()

        // 1. Jika kartu berasal dari feed stream langsung (IGO / Live), muat instan tanpa re-fetch forum
        decodeStreamCardUrl(url)?.let { card ->
            val payload = encodePayload(card.postUrl, listOf(card.videoUrl))
            return newMovieLoadResponse(card.title, url, TvType.NSFW, payload) {
                this.posterUrl = card.poster
                this.posterHeaders = posterHeaders
                this.plot = buildString {
                    if (card.author.isNotBlank()) append("Author: ${card.author}\n")
                    append("Host: ${hostLabel(card.videoUrl)}")
                    if (card.plot.isNotBlank()) {
                        append("\n\n")
                        append(card.plot.take(600))
                    }
                }
            }
        }

        // 2. Fallback untuk hasil pencarian yang menunjuk ke post spesifik (#post-ID)
        val postIdMatch = POST_ID_REGEX.find(url)?.groupValues?.get(1)
        if (postIdMatch != null) {
            val doc = semprotAuthedGet(url).document
            val post = doc.selectFirst("article.message[data-content=post-$postIdMatch], article#js-post-$postIdMatch")
                ?: doc.selectFirst("article.message")
                ?: return null

            val links = extractVideoLinks(post)
            val author = post.attr("data-author").takeIf { it.isNotBlank() }
                ?: post.selectFirst("h4.message-name a, .message-name")?.text()?.trim()
                ?: "Anon"
            val threadTitle = doc.selectFirst("h1.p-title-value")?.text()?.trim()
            val title = extractPostTitle(post, threadTitle ?: "Post #$postIdMatch")
            val poster = extractPostImage(post)
            val bodyText = extractCleanPostText(post)
            val payload = encodePayload(url, links)

            return newMovieLoadResponse(title, url, TvType.NSFW, payload) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders
                this.plot = buildString {
                    append("Author: $author | Post #$postIdMatch")
                    if (bodyText.isNotBlank()) {
                        append("\n\n")
                        append(bodyText.take(600))
                    }
                }
            }
        }

        // 3. Fallback untuk hasil pencarian thread penuh -> tetap MovieLoadResponse langsung
        val firstDoc = semprotAuthedGet(url).document
        val threadTitle = firstDoc.selectFirst("h1.p-title-value")?.text()?.trim() ?: "Semprot Thread"
        val tags = firstDoc.select("a.tagItem").map { it.text().trim() }.filter { it.isNotBlank() }
        val totalPages = parseTotalPages(firstDoc)
        val scanLimit = SemprotSettings.getIgoScanPages(SemprotPlugin.appContext)

        val pagesToScan = buildList {
            add(1)
            if (totalPages > 1 && scanLimit > 1) {
                val tailCount = scanLimit - 1
                val startTail = maxOf(2, totalPages - tailCount + 1)
                for (p in startTail..totalPages) {
                    if (p !in this) add(p)
                }
            }
        }

        val cleanBaseThreadUrl = url.substringBefore("#").substringBefore("?").let {
            it.replace(Regex("""/page-\d+/?$"""), "/").let { u -> if (u.endsWith("/")) u else "$u/" }
        }

        val allLinks = LinkedHashSet<String>()
        var mainPoster: String? = null
        var firstSnippet: String? = null

        for (p in pagesToScan) {
            val pageDoc = if (p == 1) firstDoc else semprotAuthedGet("${cleanBaseThreadUrl}page-$p").document
            pageDoc.select("article.message").forEach { post ->
                val links = extractVideoLinks(post)
                if (links.isNotEmpty()) {
                    allLinks.addAll(links)
                    if (mainPoster == null) mainPoster = extractPostImage(post)
                    if (firstSnippet.isNullOrBlank()) {
                        firstSnippet = extractCleanPostText(post)
                    }
                }
            }
        }

        if (mainPoster == null) {
            mainPoster = firstDoc.selectFirst("article.message")?.let { extractPostImage(it) }
        }
        val plotText = firstSnippet?.takeIf { it.isNotBlank() }?.take(600)
            ?: firstDoc.selectFirst("article.message")?.let { extractCleanPostText(it).take(600) }

        val payload = encodePayload(url, allLinks.toList())
        return newMovieLoadResponse(threadTitle, url, TvType.NSFW, payload) {
            this.posterUrl = mainPoster
            this.posterHeaders = posterHeaders
            this.tags = tags
            this.plot = plotText
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (postUrl, preParsedLinks) = decodePayload(data)
        val links = if (preParsedLinks.isNotEmpty()) {
            preParsedLinks
        } else if (postUrl.startsWith("http")) {
            val doc = semprotAuthedGet(postUrl).document
            val postId = POST_ID_REGEX.find(postUrl)?.groupValues?.get(1)
            val targetEl: Element = if (postId != null) {
                doc.selectFirst("article.message[data-content=post-$postId], article#js-post-$postId") ?: doc
            } else {
                doc
            }
            extractVideoLinks(targetEl)
        } else {
            emptyList()
        }

        if (links.isEmpty()) {
            Log.w(TAG, "No vidara/playmogo links found for $data")
            return false
        }

        val vidaraTotal = links.count { isVidaraUrl(it) }
        val playmogoTotal = links.count { isPlaymogoUrl(it) }
        var vidaraIdx = 0
        var playmogoIdx = 0
        var anyFound = false

        val vidaraExtractor = VidaraExtractor()
        val playmogoExtractor = PlaymogoExtractor()

        for (videoUrl in links) {
            var linkFound = false
            val trackedCallback: (ExtractorLink) -> Unit = { extLink ->
                linkFound = true
                anyFound = true
                callback(extLink)
            }

            when {
                isVidaraUrl(videoUrl) -> {
                    vidaraIdx++
                    val label = if (vidaraTotal > 1) "Vidara #$vidaraIdx" else "Vidara"
                    if (!vidaraExtractor.extractWithSource(videoUrl, "$mainUrl/", label, subtitleCallback, trackedCallback)) {
                        loadExtractor(videoUrl, referer = "$mainUrl/", subtitleCallback, trackedCallback)
                    }
                }
                isPlaymogoUrl(videoUrl) -> {
                    playmogoIdx++
                    val label = if (playmogoTotal > 1) "Playmogo #$playmogoIdx" else "Playmogo"
                    if (!playmogoExtractor.extractWithSource(videoUrl, "$mainUrl/", label, subtitleCallback, trackedCallback)) {
                        loadExtractor(videoUrl, referer = "$mainUrl/", subtitleCallback, trackedCallback)
                    }
                }
                else -> {
                    loadExtractor(videoUrl, referer = "$mainUrl/", subtitleCallback, trackedCallback)
                }
            }

            if (!linkFound) {
                Log.w(TAG, "Failed extracting link: $videoUrl")
            }
        }

        return anyFound
    }

    private fun hostLabel(url: String): String = when {
        isVidaraUrl(url) -> "Vidara"
        isPlaymogoUrl(url) -> "Playmogo"
        else -> "Stream"
    }

    private fun extractVideoLinks(root: Element): List<String> {
        val scope = (root.selectFirst("div.bbWrapper") ?: root).clone()
        // Kecualikan kutipan balasan (blockquote) agar link tidak double / terambil dari quote
        scope.select("blockquote, .bbCodeBlock--quote, script, style").remove()
        val found = LinkedHashSet<String>()

        fun addCandidate(raw: String?) {
            if (raw.isNullOrBlank()) return
            // Pisahkan jika ada beberapa URL menempel tanpa spasi/baris baru
            val parts = raw.replace("&amp;", "&").split(Regex("""(?=https?://)"""))
            for (part in parts) {
                val cleaned = part
                    .trim()
                    .trimEnd('.', ',', ';', ')', ']', '"', '\'')
                if (cleaned.isBlank()) continue
                val normalized = if (cleaned.startsWith("http://", ignoreCase = true)) {
                    "https://" + cleaned.substring(7)
                } else {
                    cleaned
                }
                if (isVidaraUrl(normalized) || isPlaymogoUrl(normalized)) {
                    found.add(normalized)
                }
            }
        }

        scope.select("a[href]").forEach { addCandidate(it.attr("href")) }
        scope.select("iframe[src], iframe[data-src]").forEach {
            addCandidate(it.attr("src"))
            addCandidate(it.attr("data-src"))
        }
        scope.select("[data-url]").forEach { addCandidate(it.attr("data-url")) }

        // Pindai teks mentah (ganti <br> dengan newline), [SPOILER], [CODE], [PLAIN]
        val plainWithBreaks = Jsoup.parse(scope.html().replace(Regex("(?i)<br\\s*/?>"), "\n")).text()
        val rawCombined = "$plainWithBreaks\n${scope.html()}"
        VIDARA_REGEX.findAll(rawCombined).forEach { addCandidate(it.value) }
        PLAYMOGO_REGEX.findAll(rawCombined).forEach { addCandidate(it.value) }

        return found.toList()
    }

    private fun isVidaraUrl(url: String): Boolean =
        url.contains("vidara.to", ignoreCase = true) ||
            url.contains("vidara.so", ignoreCase = true) ||
            url.contains("bnvcq8s.com", ignoreCase = true)

    private fun isPlaymogoUrl(url: String): Boolean =
        url.contains("playmogo.com", ignoreCase = true)

    private fun extractPostId(post: Element): String? {
        val dataContent = post.attr("data-content")
        if (dataContent.startsWith("post-")) return dataContent.removePrefix("post-")
        val idAttr = post.id()
        if (idAttr.startsWith("js-post-")) return idAttr.removePrefix("js-post-")
        val attrLink = post.selectFirst("a[href*=\"#post-\"], a[href*=\"/post-\"]")?.attr("href")
        return attrLink?.let { Regex("""post-(\d+)""").find(it)?.groupValues?.get(1) }
    }

    private fun extractPostImage(post: Element): String? {
        val scope = (post.selectFirst("div.message-content, div.bbWrapper") ?: post).clone()
        scope.select("blockquote, .bbCodeBlock--quote").remove()
        val imgs = scope.select("img")
        for (img in imgs) {
            if (img.hasClass("smilie") || img.hasClass("avatar") || img.closest(".avatar") != null) continue
            val candidate = img.attr("data-src").ifBlank { img.attr("data-url") }.ifBlank { img.attr("src") }
            if (candidate.isBlank() || candidate.startsWith("data:")) continue
            if (candidate.contains("/styles/default/", ignoreCase = true) || candidate.contains("smilies", ignoreCase = true)) {
                continue
            }
            return fixUrlNull(candidate)
        }
        return null
    }

    private fun extractCleanPostText(post: Element): String {
        val wrapper = (post.selectFirst("div.bbWrapper") ?: return "").clone()
        wrapper.select("blockquote, .bbCodeBlock--quote, script, style").remove()
        return Jsoup.parse(wrapper.html().replace(Regex("(?i)<br\\s*/?>"), "\n")).text().trim()
    }

    private fun extractPostTitle(post: Element, fallback: String): String {
        val wrapper = (post.selectFirst("div.bbWrapper") ?: return fallback).clone()
        wrapper.select("blockquote, .bbCodeBlock--quote, script, style").remove()
        val lines = Jsoup.parse(wrapper.html().replace(Regex("(?i)<br\\s*/?>"), "\n")).text()
            .lines()
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    !it.startsWith("http://", ignoreCase = true) &&
                    !it.startsWith("https://", ignoreCase = true) &&
                    !it.equals("Spoiler", ignoreCase = true)
            }
        val firstLine = lines.firstOrNull()?.take(90)
        return firstLine?.takeIf { it.length >= 3 } ?: fallback
    }

    private fun parseTotalPages(doc: Document): Int {
        val pageNums = doc.select(".pageNav-main li.pageNav-page a")
            .mapNotNull { it.text().trim().toIntOrNull() }
        return pageNums.maxOrNull()?.coerceAtLeast(1) ?: 1
    }

    private fun extractXfToken(doc: Document): String? {
        return doc.selectFirst("input[name=_xfToken]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("html")?.attr("data-csrf")?.takeIf { it.isNotBlank() }
    }

    private fun appendPageQuery(baseUrl: String, page: Int): String {
        val clean = baseUrl.substringBefore("?")
        return if (clean.contains("?")) "$clean&page=$page" else "$clean?page=$page"
    }

    private fun buildStreamCardUrl(
        postUrl: String,
        index: Int,
        title: String,
        videoUrl: String,
        poster: String?,
        author: String,
        plot: String
    ): String {
        val json = JSONObject().apply {
            put("t", title)
            put("p", postUrl)
            put("v", videoUrl)
            put("i", poster.orEmpty())
            put("a", author)
            put("d", plot.take(600))
        }.toString()
        val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cleanBase = postUrl.substringBefore("#")
        val sep = if (cleanBase.contains("?")) "&" else "?"
        val postId = POST_ID_REGEX.find(postUrl)?.groupValues?.get(1) ?: "0"
        return "$cleanBase${sep}p=$postId&idx=$index#cs=$encoded"
    }

    private fun decodeStreamCardUrl(url: String): StreamCardData? {
        val marker = "#cs="
        val idx = url.indexOf(marker)
        if (idx == -1) return null
        val b64 = url.substring(idx + marker.length).trim()
        if (b64.isEmpty()) return null
        return runCatching {
            val raw = String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val json = JSONObject(raw)
            val videoUrl = json.optString("v")
            if (videoUrl.isBlank()) return@runCatching null
            StreamCardData(
                title = json.optString("t").ifBlank { "Semprot Stream" },
                cardUrl = url,
                postUrl = json.optString("p"),
                videoUrl = videoUrl,
                poster = json.optString("i").takeIf { it.isNotBlank() },
                author = json.optString("a"),
                plot = json.optString("d")
            )
        }.getOrNull()
    }

    private fun encodePayload(postUrl: String, links: List<String>): String {
        return JSONObject().apply {
            put("postUrl", postUrl)
            put("links", JSONArray(links))
        }.toString()
    }

    private fun decodePayload(data: String): Pair<String, List<String>> {
        val trimmed = data.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val json = JSONObject(trimmed)
                val postUrl = json.optString("postUrl")
                val arr = json.optJSONArray("links")
                val list = buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i).trim()
                            if (item.isNotBlank()) add(item)
                        }
                    }
                }
                return postUrl to list
            }
        }
        return trimmed to emptyList()
    }
}
