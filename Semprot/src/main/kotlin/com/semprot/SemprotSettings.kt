package com.semprot

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SemprotSettings {
    const val PREFS_NAME = "semprot_settings"
    const val KEY_USER = "username"
    const val KEY_PASS = "password"
    const val KEY_COOKIES = "cookies"
    const val KEY_USER_AGENT = "user_agent"
    const val KEY_IGO_SCAN_PAGES = "igo_scan_pages"

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_IGO_SCAN_PAGES = 3

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUsername(context: Context = SemprotPlugin.appContext): String =
        prefs(context).getString(KEY_USER, "")?.trim().orEmpty()

    fun getPassword(context: Context = SemprotPlugin.appContext): String =
        prefs(context).getString(KEY_PASS, "")?.trim().orEmpty()

    fun saveCredentials(context: Context = SemprotPlugin.appContext, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASS, password)
            .apply()
    }

    fun getCookie(context: Context = SemprotPlugin.appContext): String =
        prefs(context).getString(KEY_COOKIES, "")?.trim().orEmpty()

    fun saveCookie(context: Context = SemprotPlugin.appContext, cookie: String) {
        prefs(context).edit()
            .putString(KEY_COOKIES, cookie.trim())
            .apply()
    }

    fun getUserAgent(context: Context = SemprotPlugin.appContext): String =
        prefs(context).getString(KEY_USER_AGENT, DEFAULT_USER_AGENT)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

    fun saveUserAgent(context: Context = SemprotPlugin.appContext, ua: String) {
        if (ua.isNotBlank()) {
            prefs(context).edit()
                .putString(KEY_USER_AGENT, ua.trim())
                .apply()
        }
    }

    fun getIgoScanPages(context: Context = SemprotPlugin.appContext): Int =
        prefs(context).getInt(KEY_IGO_SCAN_PAGES, DEFAULT_IGO_SCAN_PAGES).coerceIn(1, 10)

    fun saveIgoScanPages(context: Context = SemprotPlugin.appContext, pages: Int) {
        prefs(context).edit()
            .putInt(KEY_IGO_SCAN_PAGES, pages.coerceIn(1, 10))
            .apply()
    }

    fun clearAuth(context: Context = SemprotPlugin.appContext) {
        prefs(context).edit()
            .remove(KEY_COOKIES)
            .apply()
    }

    fun hasLoggedInCookie(context: Context = SemprotPlugin.appContext): Boolean {
        val cookie = getCookie(context)
        return cookie.contains("xf_user=") || cookie.contains("xf_session=")
    }

    fun getSessionStatusLabel(context: Context = SemprotPlugin.appContext): String {
        val cookie = getCookie(context)
        return when {
            cookie.contains("xf_user=") -> "Sesi: Terautentikasi (xf_user aktif)"
            cookie.contains("xf_session=") && cookie.contains("cf_clearance=") ->
                "Sesi: Cookie sesi & Cloudflare tersimpan"
            cookie.contains("xf_session=") -> "Sesi: Cookie sesi tersimpan (verifikasi disarankan)"
            cookie.isNotBlank() -> "Sesi: Cookie parsial tersimpan"
            else -> "Sesi: Belum Login"
        }
    }
}

class SemprotSettingsDialog(
    private val activity: AppCompatActivity,
    private val onSave: () -> Unit
) {
    private val colorBg = "#1A1C1E"
    private val colorAccent = "#E50914"
    private val colorWebView = "#2A4B7C"
    private val colorVerify = "#2D6A4F"
    private val fontBold = Typeface.create("sans-serif", Typeface.BOLD)

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun createRoundedDrawable(color: String, radius: Float = 12f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(radius.toInt()).toFloat()
            setColor(Color.parseColor(color))
        }
    }

    private fun createEditText(hint: String, current: String, isPassword: Boolean = false, isNumeric: Boolean = false): EditText {
        return EditText(activity).apply {
            setHint(hint)
            setText(current)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = createRoundedDrawable("#2A2C2E", 8f)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            textSize = 14f
            isFocusable = true
            isFocusableInTouchMode = true
            if (isPassword) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else if (isNumeric) {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            setOnFocusChangeListener { v, hasFocus ->
                (v.background as? GradientDrawable)?.setStroke(if (hasFocus) dpToPx(2) else 0, Color.WHITE)
            }
        }
    }

    private fun applyTvEffect(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            if (v.background is GradientDrawable) {
                val gd = v.background as GradientDrawable
                if (hasFocus) {
                    gd.setStroke(dpToPx(3), Color.WHITE)
                    v.scaleX = 1.03f
                    v.scaleY = 1.03f
                } else {
                    gd.setStroke(0, Color.TRANSPARENT)
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
        }
    }

    fun show() {
        val scroll = ScrollView(activity).apply {
            background = createRoundedDrawable(colorBg, 20f)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            layoutParams = ViewGroup.LayoutParams(dpToPx(380), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(root)

        root.addView(TextView(activity).apply {
            text = "Semprot Settings"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = fontBold
            setPadding(0, 0, 0, dpToPx(6))
        })

        val statusView = TextView(activity).apply {
            text = SemprotSettings.getSessionStatusLabel(activity)
            setTextColor(
                if (SemprotSettings.hasLoggedInCookie(activity)) Color.parseColor("#80ED99")
                else Color.parseColor("#FFB703")
            )
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(14))
        }
        root.addView(statusView)

        root.addView(TextView(activity).apply {
            text = "Username / Email (opsional, untuk auto-fill WebView)"
            setTextColor(Color.GRAY)
            textSize = 11f
        })
        val userEdit = createEditText("Username / Email", SemprotSettings.getUsername(activity))
        root.addView(userEdit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(10) })

        root.addView(TextView(activity).apply {
            text = "Password (opsional, untuk auto-fill WebView)"
            setTextColor(Color.GRAY)
            textSize = 11f
        })
        val passEdit = createEditText("Password", SemprotSettings.getPassword(activity), isPassword = true)
        root.addView(passEdit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(10) })

        root.addView(TextView(activity).apply {
            text = "Maks. halaman dipindai per thread IGO (1-10)"
            setTextColor(Color.GRAY)
            textSize = 11f
        })
        val scanPagesEdit = createEditText(
            "3",
            SemprotSettings.getIgoScanPages(activity).toString(),
            isNumeric = true
        )
        root.addView(scanPagesEdit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(16) })

        val btnLogin = Button(activity).apply {
            text = "Login via WebView"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(colorWebView, 12f)
            applyTvEffect(this)
        }
        root.addView(btnLogin, LinearLayout.LayoutParams(-1, dpToPx(44)).apply { bottomMargin = dpToPx(10) })

        val btnVerify = Button(activity).apply {
            text = "Verifikasi Sesi"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(colorVerify, 12f)
            applyTvEffect(this)
        }
        root.addView(btnVerify, LinearLayout.LayoutParams(-1, dpToPx(44)).apply { bottomMargin = dpToPx(10) })

        val btnLogout = Button(activity).apply {
            text = "Hapus Cookie (Log Out)"
            setTextColor(Color.parseColor("#FF8989"))
            background = createRoundedDrawable("#331A1A", 12f)
            applyTvEffect(this)
        }
        root.addView(btnLogout, LinearLayout.LayoutParams(-1, dpToPx(44)).apply { bottomMargin = dpToPx(10) })

        val btnSave = Button(activity).apply {
            text = "Simpan"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(colorAccent, 12f)
            applyTvEffect(this)
        }
        root.addView(btnSave, LinearLayout.LayoutParams(-1, dpToPx(46)))

        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        fun persistFields() {
            val user = userEdit.text.toString().trim()
            val pass = passEdit.text.toString()
            val scanPages = scanPagesEdit.text.toString().trim().toIntOrNull() ?: SemprotSettings.DEFAULT_IGO_SCAN_PAGES
            SemprotSettings.saveCredentials(activity, user, pass)
            SemprotSettings.saveIgoScanPages(activity, scanPages)
        }

        btnLogin.setOnClickListener {
            persistFields()
            val user = userEdit.text.toString().trim()
            val pass = passEdit.text.toString()
            CoroutineScope(Dispatchers.Main).launch {
                val cookie = semprotWebViewLogin(activity, user, pass, forceRefresh = true)
                statusView.text = SemprotSettings.getSessionStatusLabel(activity)
                statusView.setTextColor(
                    if (SemprotSettings.hasLoggedInCookie(activity)) Color.parseColor("#80ED99")
                    else Color.parseColor("#FFB703")
                )
                if (cookie.isNotBlank()) {
                    Toast.makeText(activity, "Cookie semprot.com berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    MainActivity.reloadHomeEvent.invoke(true)
                } else {
                    Toast.makeText(activity, "Login dibatalkan atau cookie belum tersimpan.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnVerify.setOnClickListener {
            Toast.makeText(activity, "Memeriksa sesi ke sub-forum IGO...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                val result = verifySemprotSession(activity)
                withContext(Dispatchers.Main) {
                    statusView.text = result.message
                    statusView.setTextColor(
                        if (result.ok) Color.parseColor("#80ED99")
                        else Color.parseColor("#FF8989")
                    )
                    Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnLogout.setOnClickListener {
            clearSemprotCookie(activity)
            statusView.text = SemprotSettings.getSessionStatusLabel(activity)
            statusView.setTextColor(Color.parseColor("#FFB703"))
            Toast.makeText(activity, "Cookie semprot.com dihapus.", Toast.LENGTH_SHORT).show()
            MainActivity.reloadHomeEvent.invoke(true)
        }

        btnSave.setOnClickListener {
            persistFields()
            onSave()
            Toast.makeText(activity, "Pengaturan disimpan.", Toast.LENGTH_SHORT).show()
            MainActivity.reloadHomeEvent.invoke(true)
            dialog.dismiss()
        }

        dialog.show()
    }
}
