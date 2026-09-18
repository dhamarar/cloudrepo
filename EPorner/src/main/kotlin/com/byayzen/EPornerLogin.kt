package com.byayzen

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

private var epornerActiveDialog: AlertDialog? = null
private val epornerWaitingList = mutableListOf<Continuation<String>>()
private var epornerIsProcessing = false

fun getEpornerCookie(): String {
    val context = EPornerPlugin.appContext
    return context.getSharedPreferences("eporner_cookies", Context.MODE_PRIVATE)
        .getString("eporner.com", "") ?: ""
}

fun clearEpornerCookie(baseUrl: String? = null) {
    val context = EPornerPlugin.appContext
    context.getSharedPreferences("eporner_cookies", Context.MODE_PRIVATE)
        .edit().remove("eporner.com").apply()

    val cookieManager = CookieManager.getInstance()
    val url = baseUrl ?: "https://www.eporner.com"
    val cookies = cookieManager.getCookie(url)
    if (cookies != null) {
        val cookiePairs = cookies.split(";")
        for (pair in cookiePairs) {
            val name = pair.split("=")[0].trim()
            cookieManager.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        cookieManager.flush()
    }
}

private fun saveEpornerCookie(cookie: String) {
    val context = EPornerPlugin.appContext
    context.getSharedPreferences("eporner_cookies", Context.MODE_PRIVATE)
        .edit().putString("eporner.com", cookie).apply()

    try {
        val cookieManager = CookieManager.getInstance()
        cookie.split(";").forEach {
            val trimmed = it.trim()
            if (trimmed.isNotEmpty()) {
                cookieManager.setCookie("https://www.eporner.com", trimmed)
            }
        }
        cookieManager.flush()
    } catch (_: Exception) {}
}

@SuppressLint("SetJavaScriptEnabled")
suspend fun epornerLogin(username: String, password: String, forceRefresh: Boolean = false): String {
    val saved = getEpornerCookie()
    if (!forceRefresh && saved.isNotEmpty() && saved.contains("PHPSESSID=")) {
        return saved
    }

    if (epornerIsProcessing) {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { epornerWaitingList.remove(cont) }
            epornerWaitingList.add(cont)
        }
    }

    epornerIsProcessing = true

    // Step 1: Attempt direct background HTTP login (fast and seamless)
    try {
        val directResult = directHttpLogin(username, password)
        if (directResult.isNotEmpty()) {
            saveEpornerCookie(directResult)
            epornerIsProcessing = false
            val waiters = epornerWaitingList.toList()
            epornerWaitingList.clear()
            waiters.forEach { runCatching { it.resume(directResult) } }
            return directResult
        }
    } catch (e: Exception) {
        Log.e("EPornerLogin", "Direct login error: ${e.message}")
    }

    // Step 2: Fallback to WebView dialog (like SimpCity) if direct login fails
    val context = EPornerPlugin.appContext
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            epornerWaitingList.remove(continuation)
            if (epornerWaitingList.isEmpty()) epornerIsProcessing = false
        }

        MainScope().launch {
            var resumed = false

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
                setBackgroundColor(Color.BLACK)
            }

            val webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setNeedInitialFocus(true)
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.BLACK)

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

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                evaluateJavascript(
                                    """
                                    (function() {
                                        var el = document.activeElement;
                                        if (!el || el.tagName === 'IFRAME') return;
                                        var opts = { bubbles: true, cancelable: true, view: window };
                                        el.dispatchEvent(new MouseEvent('mousedown', opts));
                                        el.dispatchEvent(new MouseEvent('mouseup', opts));
                                        el.dispatchEvent(new MouseEvent('click', opts));
                                    })();
                                    """.trimIndent(), null
                                )
                                return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }
            }

            container.addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )

            fun safeResumeAll(value: String) {
                if (!resumed) {
                    resumed = true
                    runCatching { continuation.resume(value) }
                }
                val copy = epornerWaitingList.toList()
                epornerWaitingList.clear()
                copy.forEach { runCatching { it.resume(value) } }
                epornerIsProcessing = false
            }

            val safeUser = username.replace("\\", "\\\\").replace("'", "\\'")
            val safePass = password.replace("\\", "\\\\").replace("'", "\\'")

            val helperJs = """
                (function() {
                    setInterval(function() {
                        var u = document.querySelector('input[name="login"]');
                        var p = document.querySelector('input[name="pass"]');
                        if (u && !u.value) {
                            u.value = '$safeUser';
                            u.dispatchEvent(new Event('input', {bubbles:true}));
                        }
                        if (p && !p.value) {
                            p.value = '$safePass';
                            p.dispatchEvent(new Event('input', {bubbles:true}));
                        }
                    }, 1000);
                })();
            """.trimIndent()

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(helperJs, null)
                }
            }

            webView.loadUrl("https://www.eporner.com/login/")

            epornerActiveDialog = AlertDialog.Builder(context)
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("Cancel") { _, _ -> safeResumeAll("") }
                .create()

            epornerActiveDialog?.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setDimAmount(1.0f)
                setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }

            epornerActiveDialog?.setOnShowListener { webView.requestFocus() }
            epornerActiveDialog?.setOnDismissListener {
                if (!resumed) safeResumeAll("")
                epornerActiveDialog = null
            }

            MainScope().launch {
                while (epornerActiveDialog?.isShowing == true) {
                    delay(2000)
                    val currentCookie = CookieManager.getInstance().getCookie("https://www.eporner.com") ?: ""
                    if (currentCookie.contains("PHPSESSID=")) {
                        saveEpornerCookie(currentCookie)
                        webView.evaluateJavascript(
                            "document.body.innerHTML='<h1 style=\"color:white;text-align:center;margin-top:20%\">Login Successful!</h1>'",
                            null
                        )
                        delay(1000)
                        epornerActiveDialog?.dismiss()
                        safeResumeAll(currentCookie)
                        return@launch
                    }
                }
            }

            epornerActiveDialog?.show()
        }
    }
}

private suspend fun directHttpLogin(username: String, password: String): String = withContext(Dispatchers.IO) {
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://www.eporner.com/"
    )

    // 1. Fetch homepage to get initial cookies & csrfToken
    val homeResp = app.get("https://www.eporner.com/", headers = headers)
    val homeHtml = homeResp.text

    val csrfToken = Regex("EP\\.user\\.csrfToken\\s*=\\s*'([^']+)'").find(homeHtml)?.groupValues?.get(1)
        ?: return@withContext ""

    val cookieList = mutableListOf<String>()
    homeResp.headers.forEach { (k, v) ->
        if (k.equals("set-cookie", ignoreCase = true)) {
            val c = v.substringBefore(";")
            if (c.isNotEmpty() && !cookieList.contains(c)) cookieList.add(c)
        }
    }
    val initialCookieHeader = cookieList.joinToString("; ")

    // 2. Post credentials to /xhr/login/
    val postHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://www.eporner.com/",
        "X-Requested-With" to "XMLHttpRequest",
        "X-CSRF-TOKEN" to csrfToken,
        "Cookie" to initialCookieHeader
    )

    val postData = mapOf(
        "xhr" to "1",
        "act" to "login",
        "login" to username,
        "pass" to password,
        "ref" to "/",
        "csrf_token" to csrfToken
    )

    val loginResp = app.post("https://www.eporner.com/xhr/login/", data = postData, headers = postHeaders)
    val loginJson = JSONObject(loginResp.text)

    if (loginJson.optInt("status", 0) == 1) {
        val finalCookies = cookieList.toMutableList()
        loginResp.headers.forEach { (k, v) ->
            if (k.equals("set-cookie", ignoreCase = true)) {
                val c = v.substringBefore(";")
                if (c.isNotEmpty() && !finalCookies.contains(c)) finalCookies.add(c)
            }
        }
        val finalCookieStr = finalCookies.joinToString("; ")
        return@withContext finalCookieStr
    }

    return@withContext ""
}
