package com.semprot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private const val SEMPROT_BASE_URL = "https://www.semprot.com"
private const val SEMPROT_APEX_URL = "https://semprot.com"
private const val SEMPROT_HOME_URL = "$SEMPROT_BASE_URL/"
private const val SEMPROT_LOGIN_URL = "$SEMPROT_BASE_URL/login"
private const val SEMPROT_IGO_TEST_URL = "$SEMPROT_BASE_URL/forums/film-cewek-indonesia-igo.11/"

private var semprotActiveDialog: Dialog? = null
private val semprotWaitingList = mutableListOf<Continuation<String>>()
private var semprotIsProcessing = false

data class SessionVerifyResult(val ok: Boolean, val message: String)

private fun clearWebViewCookiesForSemprot() {
    runCatching {
        val cookieManager = CookieManager.getInstance()
        for (domainUrl in listOf(SEMPROT_BASE_URL, SEMPROT_APEX_URL, "https://.semprot.com")) {
            val cookies = cookieManager.getCookie(domainUrl) ?: continue
            for (pair in cookies.split(";")) {
                val name = pair.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieManager.setCookie(domainUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    cookieManager.setCookie(
                        domainUrl,
                        "$name=; Domain=.semprot.com; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                    )
                }
            }
        }
        cookieManager.flush()
    }
}

private fun readMergedWebViewCookies(): String {
    val cookieManager = CookieManager.getInstance()
    val merged = LinkedHashMap<String, String>()
    for (domainUrl in listOf(SEMPROT_APEX_URL, SEMPROT_BASE_URL)) {
        val raw = cookieManager.getCookie(domainUrl).orEmpty()
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val k = trimmed.substring(0, eqIdx).trim()
                val v = trimmed.substring(eqIdx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    merged[k] = v
                }
            }
        }
    }
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

fun clearSemprotCookie(context: Context = SemprotPlugin.appContext) {
    SemprotSettings.clearAuth(context)
    clearWebViewCookiesForSemprot()
}

private fun saveWebViewSession(context: Context, cookie: String, userAgent: String?) {
    if (cookie.isNotBlank()) {
        SemprotSettings.saveCookie(context, cookie)
    }
    if (!userAgent.isNullOrBlank()) {
        SemprotSettings.saveUserAgent(context, userAgent)
    }
    runCatching {
        val cookieManager = CookieManager.getInstance()
        cookie.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                cookieManager.setCookie(SEMPROT_BASE_URL, trimmed)
            }
        }
        cookieManager.flush()
    }
}

fun semprotHeaders(
    extra: Map<String, String> = emptyMap(),
    context: Context = SemprotPlugin.appContext
): Map<String, String> {
    val cookie = SemprotSettings.getCookie(context)
    val ua = SemprotSettings.getUserAgent(context)
    return buildMap {
        put("User-Agent", ua)
        put("Referer", "$SEMPROT_BASE_URL/")
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        put("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        if (cookie.isNotBlank()) {
            put("Cookie", cookie)
        }
        putAll(extra)
    }
}

private fun isCloudflareChallenge(res: NiceResponse): Boolean {
    if (res.code == 403 || res.code == 503) {
        val body = res.text
        if (
            res.headers["cf-mitigated"]?.contains("challenge", ignoreCase = true) == true ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true) ||
            body.contains("cf-browser-verification", ignoreCase = true)
        ) {
            return true
        }
    }
    return false
}

private fun isXenForoLoggedOut(html: String): Boolean {
    return html.contains("""data-logged-in="false"""", ignoreCase = true) ||
        (html.contains("""action="/login/login"""", ignoreCase = true) &&
            !html.contains("""data-logged-in="true"""", ignoreCase = true))
}

suspend fun semprotAuthedGet(
    url: String,
    extraHeaders: Map<String, String> = emptyMap(),
    allowRedirects: Boolean = true
): NiceResponse = withContext(Dispatchers.IO) {
    val context = SemprotPlugin.appContext
    val savedCookie = SemprotSettings.getCookie(context)
    if (savedCookie.isBlank()) {
        throw ErrorLoadingException("Belum login ke semprot.com. Buka Settings plugin -> Login via WebView.")
    }

    val res = app.get(
        url = url,
        headers = semprotHeaders(extraHeaders, context),
        allowRedirects = allowRedirects
    )

    if (isCloudflareChallenge(res)) {
        throw ErrorLoadingException("Sesi Cloudflare kedaluwarsa (HTTP ${res.code}). Buka Settings plugin -> Login via WebView.")
    }

    val html = res.text
    if (res.code == 403 || isXenForoLoggedOut(html)) {
        throw ErrorLoadingException("Sesi semprot.com belum login / sudah habis. Buka Settings plugin -> Login via WebView.")
    }

    res
}

suspend fun semprotAuthedPost(
    url: String,
    data: Map<String, String>,
    extraHeaders: Map<String, String> = emptyMap(),
    allowRedirects: Boolean = true
): NiceResponse = withContext(Dispatchers.IO) {
    val context = SemprotPlugin.appContext
    val savedCookie = SemprotSettings.getCookie(context)
    if (savedCookie.isBlank()) {
        throw ErrorLoadingException("Belum login ke semprot.com. Buka Settings plugin -> Login via WebView.")
    }

    val res = app.post(
        url = url,
        data = data,
        headers = semprotHeaders(
            mapOf("Origin" to SEMPROT_BASE_URL) + extraHeaders,
            context
        ),
        allowRedirects = allowRedirects
    )

    if (isCloudflareChallenge(res)) {
        throw ErrorLoadingException("Sesi Cloudflare kedaluwarsa (HTTP ${res.code}). Buka Settings plugin -> Login via WebView.")
    }

    res
}

suspend fun verifySemprotSession(context: Context = SemprotPlugin.appContext): SessionVerifyResult =
    withContext(Dispatchers.IO) {
        val cookie = SemprotSettings.getCookie(context)
        if (cookie.isBlank()) {
            return@withContext SessionVerifyResult(false, "Belum ada cookie tersimpan. Silakan Login via WebView.")
        }
        try {
            val res = app.get(SEMPROT_IGO_TEST_URL, headers = semprotHeaders(context = context))
            if (isCloudflareChallenge(res)) {
                return@withContext SessionVerifyResult(false, "Terkena Cloudflare (${res.code}). Login ulang via WebView.")
            }
            val html = res.text
            if (res.code !in 200..299) {
                return@withContext SessionVerifyResult(false, "Gagal akses sub-forum (HTTP ${res.code}).")
            }
            if (isXenForoLoggedOut(html)) {
                return@withContext SessionVerifyResult(false, "Cookie belum login (guest). Login ulang via WebView.")
            }
            val hasThreads = html.contains("structItem--thread") || html.contains("data-logged-in=\"true\"")
            if (hasThreads) {
                SessionVerifyResult(true, "Sesi aktif! Sub-forum IGO dapat diakses.")
            } else {
                SessionVerifyResult(false, "Halaman terbuka tapi daftar thread tidak ditemukan.")
            }
        } catch (e: Exception) {
            SessionVerifyResult(false, "Error verifikasi: ${e.message ?: "unknown"}")
        }
    }

@SuppressLint("SetJavaScriptEnabled")
suspend fun semprotWebViewLogin(
    context: Context,
    username: String,
    password: String,
    forceRefresh: Boolean = false
): String {
    val saved = SemprotSettings.getCookie(context)
    if (!forceRefresh && saved.isNotBlank() && saved.contains("xf_user=")) {
        return saved
    }

    val activity = (context as? Activity) ?: SemprotPlugin.currentActivity
    if (activity == null) {
        Log.e("SemprotLogin", "No Activity context available for WebView login dialog")
        return ""
    }

    if (semprotIsProcessing) {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { semprotWaitingList.remove(cont) }
            semprotWaitingList.add(cont)
        }
    }

    semprotIsProcessing = true

    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            semprotWaitingList.remove(continuation)
            if (semprotWaitingList.isEmpty()) semprotIsProcessing = false
        }

        MainScope().launch {
            var resumed = false
            var latestUa: String? = null
            var retriedRedirectReset = false
            val screenHeight = activity.resources.displayMetrics.heightPixels

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                fitsSystemWindows = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val headerWrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#16181A"))
                setPadding(16, 12, 16, 10)
            }

            val infoText = TextView(activity).apply {
                text = "Memuat semprot.com..."
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 2
            }
            headerWrapper.addView(infoText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })

            val buttonRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            fun makeBarBtn(label: String, bgHex: String): Button = Button(activity).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(bgHex))
                setPadding(14, 6, 14, 6)
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
            }

            val btnHome = makeBarBtn("Home", "#2B3A4A")
            val btnLoginPage = makeBarBtn("Login", "#3B4A6B")
            val btnReset = makeBarBtn("Reset", "#5A4214")
            val btnCapture = makeBarBtn("Simpan", "#2D6A4F")
            val btnCancel = makeBarBtn("Tutup", "#6B2020")

            buttonRow.addView(btnHome, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
            buttonRow.addView(btnLoginPage, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
            buttonRow.addView(btnReset, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6 })
            buttonRow.addView(btnCapture, LinearLayout.LayoutParams(0, -2, 1.1f).apply { rightMargin = 6 })
            buttonRow.addView(btnCancel, LinearLayout.LayoutParams(0, -2, 1f))
            headerWrapper.addView(buttonRow, LinearLayout.LayoutParams(-1, -2))

            root.addView(headerWrapper, LinearLayout.LayoutParams(-1, -2))

            val webView = WebView(activity).apply {
                // Hilangkan tanda "; wv)" & "Version/4.0 " agar Cloudflare/WAF memperlakukannya seperti Chrome Mobile
                val cleanUa = settings.userAgentString
                    .replace("; wv)", ")")
                    .replace("Version/4.0 ", "")
                    .trim()
                settings.userAgentString = cleanUa
                latestUa = cleanUa

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setNeedInitialFocus(true)
                isFocusable = true
                isFocusableInTouchMode = true
                minimumHeight = (screenHeight * 0.75f).toInt()
                setBackgroundColor(Color.WHITE)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        transport.webView = view
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                // Khusus D-Pad Android TV (jangan cegat KEYCODE_ENTER saat mengetik di mobile)
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                        evaluateJavascript(
                            """
                            (function() {
                                var el = document.activeElement;
                                if (!el || el.tagName === 'IFRAME' || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') return;
                                var opts = { bubbles: true, cancelable: true, view: window };
                                el.dispatchEvent(new MouseEvent('mousedown', opts));
                                el.dispatchEvent(new MouseEvent('mouseup', opts));
                                el.dispatchEvent(new MouseEvent('click', opts));
                            })();
                            """.trimIndent(),
                            null
                        )
                        return@setOnKeyListener true
                    }
                    false
                }
            }

            root.addView(
                webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )

            fun cleanupWebView() {
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }

            fun safeResumeAll(value: String) {
                if (!resumed) {
                    resumed = true
                    runCatching { continuation.resume(value) }
                }
                val copy = semprotWaitingList.toList()
                semprotWaitingList.clear()
                copy.forEach { runCatching { it.resume(value) } }
                semprotIsProcessing = false
            }

            val safeUser = username.replace("\\", "\\\\").replace("'", "\\'")
            val safePass = password.replace("\\", "\\\\").replace("'", "\\'")

            val helperJs = """
                (function() {
                    var u = document.querySelector('input[name="login"]');
                    var p = document.querySelector('input[name="password"]');
                    var r = document.querySelector('input[name="remember"]');
                    if (u && '$safeUser' && !u.value) {
                        u.value = '$safeUser';
                        u.dispatchEvent(new Event('input', {bubbles: true}));
                    }
                    if (p && '$safePass' && !p.value) {
                        p.value = '$safePass';
                        p.dispatchEvent(new Event('input', {bubbles: true}));
                    }
                    if (r && !r.checked) {
                        r.checked = true;
                    }
                })();
            """.trimIndent()

            var currentPageUrl = SEMPROT_HOME_URL

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrBlank()) currentPageUrl = url
                    infoText.text = "Memuat: ${url?.removePrefix("https://")?.take(50) ?: "..."}"
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) currentPageUrl = url
                    latestUa = view?.settings?.userAgentString ?: latestUa
                    infoText.text = "URL: ${currentPageUrl.removePrefix("https://").take(45)} | Login lalu Simpan"
                    view?.evaluateJavascript(helperJs, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString().orEmpty()
                        val code = error?.errorCode ?: 0
                        if (!retriedRedirectReset && (code == ERROR_REDIRECT_LOOP || desc.contains("TOO_MANY_REDIRECTS", true))) {
                            retriedRedirectReset = true
                            infoText.text = "Redirect loop terdeteksi, mereset cookie & membuka Home..."
                            clearWebViewCookiesForSemprot()
                            view?.post { view.loadUrl(SEMPROT_HOME_URL) }
                        } else {
                            infoText.text = "Error $code: $desc (coba tombol Reset / cek VPN & DNS)"
                        }
                    }
                }
            }

            // Mulai dari halaman utama (seperti membuka di Chrome) agar sesi Cloudflare & xf_session terbentuk dulu
            webView.loadUrl(SEMPROT_HOME_URL)

            val dialog = Dialog(activity, android.R.style.Theme_NoTitleBar).apply {
                setContentView(root)
                setCancelable(true)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.BLACK))
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    clearFlags(
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    )
                    @Suppress("DEPRECATION")
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
            semprotActiveDialog = dialog

            btnHome.setOnClickListener {
                webView.loadUrl(SEMPROT_HOME_URL)
            }

            btnLoginPage.setOnClickListener {
                webView.loadUrl(SEMPROT_LOGIN_URL)
            }

            btnReset.setOnClickListener {
                retriedRedirectReset = false
                clearWebViewCookiesForSemprot()
                webView.clearCache(true)
                infoText.text = "Cookie WebView direset, memuat ulang Home..."
                webView.loadUrl(SEMPROT_HOME_URL)
            }

            btnCapture.setOnClickListener {
                val cookie = readMergedWebViewCookies()
                saveWebViewSession(activity, cookie, latestUa)
                dialog.dismiss()
                cleanupWebView()
                safeResumeAll(cookie)
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
                cleanupWebView()
                safeResumeAll("")
            }

            dialog.setOnShowListener {
                webView.requestFocus()
                webView.requestFocusFromTouch()
            }
            dialog.setOnDismissListener {
                if (!resumed) {
                    cleanupWebView()
                    safeResumeAll("")
                }
                semprotActiveDialog = null
            }

            MainScope().launch {
                while (dialog.isShowing) {
                    delay(1500)
                    val currentCookie = readMergedWebViewCookies()
                    val onLoginPage = currentPageUrl.contains("/login", ignoreCase = true)
                    if (currentCookie.contains("xf_user=") && !onLoginPage) {
                        saveWebViewSession(activity, currentCookie, latestUa)
                        webView.evaluateJavascript(
                            "document.body.innerHTML='<h2 style=\"color:#1b4332;text-align:center;margin-top:25%\">Login Berhasil! Menyimpan sesi...</h2>'",
                            null
                        )
                        delay(800)
                        dialog.dismiss()
                        cleanupWebView()
                        safeResumeAll(currentCookie)
                        return@launch
                    }
                }
            }

            dialog.show()
        }
    }
}
