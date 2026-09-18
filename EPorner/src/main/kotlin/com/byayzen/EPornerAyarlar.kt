package com.byayzen

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import org.json.JSONArray

object EPornerAyarlar {
    private const val PREFS_PREFIX = "EPorner_"
    const val ALL_CATEGORIES_ORDER_KEY = "${PREFS_PREFIX}ALL_order"

    private const val COLOR_BG = "#0A0A0A"
    private const val COLOR_PRIMARY = "#E50914" // Red/EPorner accent
    private const val COLOR_FOCUS = "#00D9FF"
    private const val COLOR_DELETE = "#D32F2F"
    private const val COLOR_SAVE = "#2E7D32"
    private const val COLOR_CARD = "#1A1A1A"

    val defaultCategories = listOf(
        // Navigation / Highlights
        "/" to "Most recent",
        "/most-viewed/" to "Most viewed",
        "/top-rated/" to "Top rated",
        "/longest/" to "Longest",
        "/my/favorites/" to "My Favorites",

        // Categories from eporner.com/cats/
        "/cat/4k-porn/" to "4K Ultra HD",
        "/cat/60fps/" to "60 FPS",
        "/cat/ai/" to "AI",
        "/cat/amateur/" to "Amateur",
        "/cat/anal/" to "Anal",
        "/cat/asian/" to "Asian",
        "/cat/asmr/" to "ASMR",
        "/cat/bbw/" to "BBW",
        "/cat/bdsm/" to "BDSM",
        "/cat/big-ass/" to "Big Ass",
        "/cat/big-dick/" to "Big Dick",
        "/cat/big-tits/" to "Big Tits",
        "/cat/bisexual/" to "Bisexual",
        "/cat/blonde/" to "Blonde",
        "/cat/blowjob/" to "Blowjob",
        "/cat/bondage/" to "Bondage",
        "/cat/brunette/" to "Brunette",
        "/cat/bukkake/" to "Bukkake",
        "/cat/casting/" to "Casting",
        "/cat/compilation/" to "Compilation",
        "/cat/cosplay/" to "Cosplay",
        "/cat/creampie/" to "Creampie",
        "/cat/cuckold/" to "Cuckold",
        "/cat/cumshot/" to "Cumshot",
        "/cat/doctor/" to "Doctor",
        "/cat/double-penetration/" to "Double Penetration",
        "/cat/ebony/" to "Ebony",
        "/cat/fat/" to "Fat",
        "/cat/fetish/" to "Fetish",
        "/cat/fisting/" to "Fisting",
        "/cat/footjob/" to "Footjob",
        "/cat/for-women/" to "For Women",
        "/cat/gay/" to "Gay",
        "/cat/gloryhole/" to "Gloryhole",
        "/cat/group-sex/" to "Group Sex",
        "/cat/handjob/" to "Handjob",
        "/cat/hardcore/" to "Hardcore",
        "/cat/hd-1080p/" to "HD Porn 1080p",
        "/cat/hd-sex/" to "HD Sex",
        "/cat/hentai/" to "Hentai",
        "/cat/homemade/" to "Homemade",
        "/cat/hotel/" to "Hotel",
        "/cat/hotwife/" to "Hotwife",
        "/cat/housewives/" to "Housewives",
        "/cat/hq-porn/" to "HQ Porn",
        "/cat/indian/" to "Indian",
        "/cat/indonesia/" to "Indonesia",
        "/cat/interracial/" to "Interracial",
        "/cat/japanese/" to "Japanese",
        "/cat/latina/" to "Latina",
        "/cat/lesbians/" to "Lesbian",
        "/cat/lingerie/" to "Lingerie",
        "/cat/massage/" to "Massage",
        "/cat/masturbation/" to "Masturbation",
        "/cat/mature/" to "Mature",
        "/cat/milf/" to "MILF",
        "/cat/nurse/" to "Nurses",
        "/cat/office/" to "Office",
        "/cat/old-man/" to "Older Men",
        "/cat/orgy/" to "Orgy",
        "/cat/outdoor/" to "Outdoor",
        "/cat/pawg/" to "PAWG",
        "/cat/petite/" to "Petite",
        "/cat/pinay/" to "Pinay",
        "/cat/pornstar/" to "Pornstar",
        "/cat/pov-porn/" to "POV",
        "/cat/pregnant/" to "Pregnant",
        "/cat/public/" to "Public",
        "/cat/redhead/" to "Redhead",
        "/cat/sleep/" to "Sleep",
        "/cat/small-tits/" to "Small Tits",
        "/cat/squirt/" to "Squirt",
        "/cat/stepmom/" to "Stepmom",
        "/cat/stepsister/" to "Stepsister",
        "/cat/striptease/" to "Striptease",
        "/cat/students/" to "Students",
        "/cat/swingers/" to "Swinger",
        "/cat/teens/" to "Teen",
        "/cat/threesome/" to "Threesome",
        "/cat/toys/" to "Toys",
        "/cat/shemale/" to "Trans",
        "/cat/uncategorized/" to "Uncategorized",
        "/cat/uniform/" to "Uniform",
        "/cat/vintage/" to "Vintage",
        "/cat/vr-porn/" to "VR Porn",
        "/cat/webcam/" to "Webcam"
    )

    private val defaultEnabledNames = setOf(
        "Most recent", "Most viewed", "Top rated", "4K Ultra HD", "Amateur", "Anal", "Asian", "MILF", "My Favorites"
    )

    fun dpToPx(c: Context, dp: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        c.resources.displayMetrics
    ).toInt()

    fun getOrderedAndEnabledCategories(): List<Pair<String, String>> {
        val allCats = defaultCategories
        val allCategoryNames = allCats.map { it.second }
        val orderedNames = getOrderedCategories(ALL_CATEGORIES_ORDER_KEY, allCategoryNames)
        return orderedNames
            .filter { name -> isCategoryEnabled(name) }
            .mapNotNull { name -> allCats.find { it.second == name } }
    }

    fun isCategoryEnabled(categoryName: String): Boolean {
        val key = "${PREFS_PREFIX}${categoryName}_enabled"
        return when (getKey<String>(key)) {
            "true" -> true
            "false" -> false
            else -> defaultEnabledNames.contains(categoryName)
        }
    }

    fun setCategoryEnabled(categoryName: String, enabled: Boolean) {
        setKey("${PREFS_PREFIX}${categoryName}_enabled", enabled.toString())
    }

    fun getOrderedCategories(key: String, defaultList: List<String>): List<String> {
        val savedOrderJson: String? = getKey(key)
        return if (savedOrderJson != null) {
            try {
                val jsonArray = JSONArray(savedOrderJson)
                val savedList = (0 until jsonArray.length()).map { jsonArray.getString(it) }
                val defaultSet = defaultList.toSet()
                val validSavedList = savedList.filter { it in defaultSet }
                val newItems = defaultList.filter { it !in validSavedList }
                validSavedList + newItems
            } catch (e: Exception) {
                defaultList
            }
        } else {
            defaultList
        }
    }

    fun setOrderedCategories(key: String, list: List<String>) {
        val jsonArray = JSONArray().apply { list.forEach { put(it) } }
        setKey(key, jsonArray.toString())
    }

    fun resetAllSettings() {
        setKey(ALL_CATEGORIES_ORDER_KEY, null)
        defaultCategories.forEach { (_, name) ->
            setKey("${PREFS_PREFIX}${name}_enabled", null)
        }
    }

    fun showSettingsDialog(activity: AppCompatActivity, onSave: () -> Unit) {
        CategorySettingsManager(activity, onSave).show()
    }

    private class CategorySettingsManager(val context: AppCompatActivity, val onSave: () -> Unit) {
        private val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(COLOR_BG))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        private lateinit var adapter: CategoryAdapter
        private var dialog: AlertDialog? = null

        fun show() {
            dialog = AlertDialog.Builder(context)
                .setView(createRootView())
                .setCancelable(false)
                .create()
            dialog?.show()

            val window = dialog?.window
            val displayMetrics = context.resources.displayMetrics
            window?.setLayout(
                (displayMetrics.widthPixels * 0.92).toInt(),
                (displayMetrics.heightPixels * 0.88).toInt()
            )
            window?.setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_BG))
                cornerRadius = 32f
                setStroke(3, Color.parseColor(COLOR_PRIMARY))
            })
        }

        private fun createRootView(): View {
            val title = TextView(context).apply {
                text = "EPORNER CATEGORIES"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(context, 16), 0, dpToPx(context, 16))
            }
            mainLayout.addView(title)

            adapter = CategoryAdapter(context) { name, enabled ->
                setCategoryEnabled(name, enabled)
            }
            val rv = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = this@CategorySettingsManager.adapter
                setPadding(dpToPx(context, 8), 0, dpToPx(context, 8), 0)
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            mainLayout.addView(rv)

            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(context, 16), dpToPx(context, 12), dpToPx(context, 16), dpToPx(context, 16))
                gravity = Gravity.CENTER
            }

            val btnReset = createActionButton("RESET ALL", COLOR_DELETE) {
                resetAllSettings()
                refreshList()
            }
            val btnSave = createActionButton("SAVE & EXIT", COLOR_SAVE) {
                onSave()
                dialog?.dismiss()
            }

            footer.addView(btnReset)
            footer.addView(btnSave)
            mainLayout.addView(footer)

            refreshList()
            return mainLayout
        }

        private fun createActionButton(txt: String, color: String, onClick: (View) -> Unit) = Button(context).apply {
            text = txt
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            background = createButtonDrawable(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, 50), 1f).apply {
                marginStart = dpToPx(context, 8)
                marginEnd = dpToPx(context, 8)
            }
            setOnClickListener { onClick(it) }
        }

        private fun refreshList() {
            val names = defaultCategories.map { it.second }
            val ordered = getOrderedCategories(ALL_CATEGORIES_ORDER_KEY, names)
            adapter.setList(ordered)
        }

        private fun createButtonDrawable(color: Int) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_FOCUS))
                cornerRadius = 16f
                setStroke(4, Color.WHITE)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(color)
                cornerRadius = 16f
            })
        }

        private inner class CategoryAdapter(
            val ctx: Context,
            val onCheckedChange: (String, Boolean) -> Unit
        ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {
            private val items = mutableListOf<String>()
            private val CHECKBOX_ID = View.generateViewId()
            private val UP_ID = View.generateViewId()
            private val DOWN_ID = View.generateViewId()

            fun setList(newList: List<String>) {
                items.clear()
                items.addAll(newList)
                notifyDataSetChanged()
            }

            inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
                val cb = v.findViewById<CheckBox>(CHECKBOX_ID)
                val up = v.findViewById<Button>(UP_ID)
                val down = v.findViewById<Button>(DOWN_ID)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ViewHolder(createItemLayout(parent.context))

            override fun getItemCount() = items.size

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val name = items[position]

                holder.cb.setOnCheckedChangeListener(null)
                holder.cb.text = name
                holder.cb.isChecked = isCategoryEnabled(name)

                holder.cb.setOnCheckedChangeListener { _, isChecked ->
                    setCategoryEnabled(name, isChecked)
                }

                holder.up.setOnClickListener {
                    move(holder.adapterPosition, holder.adapterPosition - 1)
                }
                holder.down.setOnClickListener {
                    move(holder.adapterPosition, holder.adapterPosition + 1)
                }
            }

            private fun move(from: Int, to: Int) {
                if (from < 0 || to !in items.indices) return
                val item = items.removeAt(from)
                items.add(to, item)
                notifyItemMoved(from, to)
                setOrderedCategories(ALL_CATEGORIES_ORDER_KEY, items)
            }

            private fun createItemLayout(c: Context) = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dpToPx(c, 12)
                setPadding(p, p, p, p)
                val margin = dpToPx(c, 6)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(Color.parseColor(COLOR_CARD))
                        cornerRadius = 12f
                        setStroke(2, Color.parseColor(COLOR_FOCUS))
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(Color.parseColor(COLOR_CARD))
                        cornerRadius = 12f
                    })
                }

                addView(CheckBox(c).apply {
                    id = CHECKBOX_ID
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_PRIMARY))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    isFocusable = true
                    setOnFocusChangeListener { _, hasFocus ->
                        setTextColor(if (hasFocus) Color.parseColor(COLOR_FOCUS) else Color.WHITE)
                    }
                })

                val bSize = dpToPx(c, 42)
                addView(createNavButton(c, "▲", UP_ID).apply {
                    layoutParams = LinearLayout.LayoutParams(bSize, bSize).apply { marginEnd = 10 }
                })
                addView(createNavButton(c, "▼", DOWN_ID).apply {
                    layoutParams = LinearLayout.LayoutParams(bSize, bSize)
                })
            }

            private fun createNavButton(c: Context, symbol: String, btnId: Int) = Button(c).apply {
                id = btnId
                text = symbol
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                background = createButtonDrawable(Color.parseColor("#333333"))
            }
        }
    }
}
