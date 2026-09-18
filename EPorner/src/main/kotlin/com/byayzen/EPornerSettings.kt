package com.byayzen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EPornerSettings {
    const val PREFS_NAME = "eporner_settings"
    const val KEY_USER = "username"
    const val KEY_PASS = "password"

    const val DEFAULT_USER = "hollowist"
    const val DEFAULT_PASS = "Hollowist@123"

    fun getUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USER, DEFAULT_USER).let { if (it.isNullOrBlank()) DEFAULT_USER else it }
    }

    fun getPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASS, DEFAULT_PASS).let { if (it.isNullOrBlank()) DEFAULT_PASS else it }
    }
}

class EPornerSettingsDialog(val activity: AppCompatActivity, val onSave: () -> Unit) {

    private val prefs by lazy { activity.getSharedPreferences(EPornerSettings.PREFS_NAME, Context.MODE_PRIVATE) }
    private val COLOR_BG = "#1A1C1E"
    private val COLOR_ACCENT = "#E50914" // Red/EPorner accent
    private val COLOR_CATEGORIES = "#8842f3" // Purple for Category button
    private val fontBold = Typeface.create("sans-serif", Typeface.BOLD)

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun createRoundedDrawable(color: String, radius: Float = 12f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(radius.toInt()).toFloat()
            setColor(Color.parseColor(color))
        }
    }

    private fun createEditText(hint: String, current: String): EditText {
        return EditText(activity).apply {
            setHint(hint)
            setText(current)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = createRoundedDrawable("#2A2C2E", 8f)
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            textSize = 14f
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, hasFocus ->
                (v.background as? GradientDrawable)?.setStroke(if (hasFocus) dpToPx(2) else 0, Color.WHITE)
            }
        }
    }

    fun show(mainUrl: String? = null) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_BG, 24f)
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            layoutParams = LinearLayout.LayoutParams(dpToPx(380), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(TextView(activity).apply {
            text = "EPorner Settings"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = fontBold
            setPadding(0, 0, 0, dpToPx(16))
        })

        // Category Settings Button
        val btnCategory = Button(activity).apply {
            text = "Open Category Settings"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(COLOR_CATEGORIES, 12f)
            applyTvEffect(this)
        }
        root.addView(btnCategory, LinearLayout.LayoutParams(-1, dpToPx(48)).apply { bottomMargin = dpToPx(16) })

        // Divider
        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#333333"))
        }
        root.addView(divider, LinearLayout.LayoutParams(-1, dpToPx(1)).apply { bottomMargin = dpToPx(16) })

        // Username Field
        root.addView(TextView(activity).apply { text = "User Name"; setTextColor(Color.GRAY); textSize = 11f })
        val userEdit = createEditText("User Name", EPornerSettings.getUsername(activity))
        root.addView(userEdit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(12) })

        // Password Field
        root.addView(TextView(activity).apply { text = "Password"; setTextColor(Color.GRAY); textSize = 11f })
        val passEdit = createEditText("Password", EPornerSettings.getPassword(activity)).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passEdit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(16) })

        // Action Buttons: Login / Verify
        val btnLogin = Button(activity).apply {
            text = "Log In / Verify Account"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable("#2A4B7C", 12f)
            applyTvEffect(this)
        }
        root.addView(btnLogin, LinearLayout.LayoutParams(-1, dpToPx(44)).apply { bottomMargin = dpToPx(10) })

        // Log Out Button
        val btnLogout = Button(activity).apply {
            text = "Log Out (Delete Cookies)"
            setTextColor(Color.parseColor("#FF8989"))
            background = createRoundedDrawable("#331A1A", 12f)
            applyTvEffect(this)
        }
        root.addView(btnLogout, LinearLayout.LayoutParams(-1, dpToPx(44)).apply { bottomMargin = dpToPx(10) })

        // Save Button
        val btnSave = Button(activity).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(COLOR_ACCENT, 12f)
            applyTvEffect(this)
        }
        root.addView(btnSave, LinearLayout.LayoutParams(-1, dpToPx(48)))

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCategory.setOnClickListener {
            dialog.dismiss()
            EPornerAyarlar.showSettingsDialog(activity) {
                onSave()
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }

        btnLogin.setOnClickListener {
            val user = userEdit.text.toString().trim()
            val pass = passEdit.text.toString().trim()
            prefs.edit().putString(EPornerSettings.KEY_USER, user).putString(EPornerSettings.KEY_PASS, pass).apply()
            Toast.makeText(activity, "Logging in...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                val cookie = epornerLogin(user, pass, forceRefresh = true)
                withContext(Dispatchers.Main) {
                    if (cookie.isNotEmpty() && cookie.contains("PHPSESSID=")) {
                        Toast.makeText(activity, "Logged in as $user successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Login failed. Please check credentials.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnLogout.setOnClickListener {
            clearEpornerCookie(mainUrl)
            Toast.makeText(activity, "Cookies cleared and logged out.", Toast.LENGTH_SHORT).show()
            MainActivity.reloadHomeEvent.invoke(true)
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val user = userEdit.text.toString().trim()
            val pass = passEdit.text.toString().trim()
            prefs.edit()
                .putString(EPornerSettings.KEY_USER, user)
                .putString(EPornerSettings.KEY_PASS, pass)
                .apply()
            onSave()
            Toast.makeText(activity, "Changes saved.", Toast.LENGTH_SHORT).show()
            MainActivity.reloadHomeEvent.invoke(true)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyTvEffect(view: View) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            if (v.background is GradientDrawable) {
                val gd = v.background as GradientDrawable
                if (hasFocus) {
                    gd.setStroke(dpToPx(3), Color.WHITE)
                    v.scaleX = 1.05f
                    v.scaleY = 1.05f
                } else {
                    gd.setStroke(0, Color.TRANSPARENT)
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            }
        }
    }
}
