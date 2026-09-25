package com.kraptor

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

object WatchPornSettings {
    private const val PREFS_PREFIX = "WatchPorn_"
    const val KEY_CATEGORIES_GROUP_ENABLED = "${PREFS_PREFIX}categories_group_enabled"
    const val KEY_STUDIOS_GROUP_ENABLED = "${PREFS_PREFIX}studios_group_enabled"

    val defaultHighlights = listOf(
        "/top-rated/" to "Top Rated",
        "/most-popular/" to "Most Popular"
    )

    val defaultCategories = listOf(
        "/categories/18-19-years-old/" to "18 & 19 Years Old",
        "/categories/amateur/" to "Amateur",
        "/categories/anal/" to "Anal",
        "/categories/anal-creampie/" to "Anal Creampie",
        "/categories/asian/" to "Asian",
        "/categories/bbw/" to "BBW",
        "/categories/big-ass/" to "Big Ass",
        "/categories/big-tits/" to "Big Tits",
        "/categories/blonde/" to "Blonde",
        "/categories/blowjob/" to "Blowjob",
        "/categories/brunette/" to "Brunette",
        "/categories/cfnm/" to "CFNM",
        "/categories/cosplay/" to "Cosplay",
        "/categories/cougar/" to "Cougar",
        "/categories/creampie/" to "Creampie",
        "/categories/cuckold/" to "Cuckold",
        "/categories/cumshot/" to "Cumshot",
        "/categories/double-penetration/" to "Double Penetration",
        "/categories/ebony/" to "Ebony",
        "/categories/edging/" to "Edging",
        "/categories/facesitting/" to "Facesitting",
        "/categories/facial/" to "Facial",
        "/categories/feet/" to "Feet",
        "/categories/femdom/" to "Femdom",
        "/categories/fetish/" to "Fetish",
        "/categories/foot-fetish/" to "Foot Fetish",
        "/categories/footjob/" to "Footjob",
        "/categories/futanari/" to "Futanari",
        "/categories/gloryhole/" to "GloryHole",
        "/categories/group/" to "Group",
        "/categories/handjob/" to "Handjob",
        "/categories/hardcore/" to "Hardcore",
        "/categories/hotwife/" to "Hotwife",
        "/categories/interracial/" to "Interracial",
        "/categories/latina/" to "Latina",
        "/categories/lesbian/" to "Lesbian",
        "/categories/lingerie/" to "Lingerie",
        "/categories/massage/" to "Massage",
        "/categories/mature/" to "Mature",
        "/categories/milf/" to "MILF",
        "/categories/older-younger/" to "Older / Younger",
        "/categories/pantyhose/" to "Pantyhose",
        "/categories/pawg/" to "Pawg",
        "/categories/petite/" to "Petite",
        "/categories/pov/" to "POV",
        "/categories/redhead/" to "Redhead",
        "/categories/rough/" to "Rough",
        "/categories/solo/" to "Solo",
        "/categories/squirt/" to "Squirt",
        "/categories/step-fantasy/" to "Step Fantasy",
        "/categories/stepbrother/" to "Stepbrother",
        "/categories/stepdad/" to "Stepdad",
        "/categories/stepdaughter/" to "Stepdaughter",
        "/categories/stepmom/" to "Stepmom",
        "/categories/stepsister/" to "Stepsister",
        "/categories/stepson/" to "Stepson",
        "/categories/stockings/" to "Stockings",
        "/categories/strap-on/" to "Strap-on",
        "/categories/taboo/" to "Taboo",
        "/categories/teen/" to "Teen",
        "/categories/threesome/" to "Threesome",
        "/categories/toys/" to "Toys",
        "/categories/wrestling/" to "Wrestling"
    )

    val defaultStudios = listOf(
        "/sites/alexlegend/" to "AlexLegend",
        "/sites/allherluv/" to "AllHerLuv",
        "/sites/analintroductions/" to "AnalIntroductions",
        "/sites/analvids/" to "AnalVids",
        "/sites/analized/" to "Analized",
        "/sites/bangbus/" to "BangBus",
        "/sites/blacked/" to "Blacked",
        "/sites/blackedraw/" to "BlackedRaw",
        "/sites/brattysis/" to "BrattySis",
        "/sites/brazzersexxtra/" to "BrazzersExxtra",
        "/sites/dadcrush/" to "DadCrush",
        "/sites/deeper/" to "Deeper",
        "/sites/evilangel/" to "Evilangel",
        "/sites/familystrokes/" to "FamilyStrokes",
        "/sites/familytherapy/" to "FamilyTherapy",
        "/sites/freeze/" to "Freeze",
        "/sites/hotwifexxx/" to "HotwifeXXX",
        "/sites/householdfantasy/" to "HouseholdFantasy",
        "/sites/immeganlive/" to "ImMeganLive",
        "/sites/jamieyoung/" to "JamieYoung",
        "/sites/julesjordan/" to "JulesJordan",
        "/sites/loveherfeet/" to "LoveHerFeet",
        "/sites/manyvids/" to "ManyVids",
        "/sites/meana-wolf/" to "Meana Wolf",
        "/sites/missax/" to "MissaX",
        "/sites/momcomesfirst/" to "MomComesFirst",
        "/sites/mommyblowsbest/" to "MommyBlowsBest",
        "/sites/mylifeinmiami/" to "MyLifeInMiami",
        "/sites/mypervyfamily/" to "MyPervyFamily",
        "/sites/nubiles/" to "Nubiles",
        "/sites/onlyfans/" to "OnlyFans",
        "/sites/outofthefamily/" to "OutOfTheFamily",
        "/sites/pascalssubsluts/" to "PascalsSubSluts",
        "/sites/pervmom/" to "PervMom",
        "/sites/pornworld/" to "PornWorld",
        "/sites/primalfetish/" to "PrimalFetish",
        "/sites/puretaboo/" to "PureTaboo",
        "/sites/rkprime/" to "RKPrime",
        "/sites/sexmex/" to "SexMex",
        "/sites/sislovesme/" to "SisLovesMe",
        "/sites/tabooheat/" to "TabooHeat",
        "/sites/tightandteen/" to "TightAndTeen",
        "/sites/tushy/" to "Tushy",
        "/sites/tushyraw/" to "TushyRaw",
        "/sites/vixen/" to "Vixen",
        "/sites/wcaproductions/" to "WCAProductions",
        "/sites/wifey/" to "Wifey",
        "/sites/willtilexxx/" to "WillTileXXX",
        "/sites/xvideosred/" to "XVideosRed"
    )

    fun isCategoriesGroupEnabled(): Boolean {
        return when (getKey<String>(KEY_CATEGORIES_GROUP_ENABLED)) {
            "false" -> false
            else -> true
        }
    }

    fun setCategoriesGroupEnabled(enabled: Boolean) {
        setKey(KEY_CATEGORIES_GROUP_ENABLED, enabled.toString())
    }

    fun isStudiosGroupEnabled(): Boolean {
        return when (getKey<String>(KEY_STUDIOS_GROUP_ENABLED)) {
            "false" -> false
            else -> true
        }
    }

    fun setStudiosGroupEnabled(enabled: Boolean) {
        setKey(KEY_STUDIOS_GROUP_ENABLED, enabled.toString())
    }

    fun isItemEnabled(name: String, defaultVal: Boolean = true): Boolean {
        val key = "${PREFS_PREFIX}${name}_enabled"
        return when (getKey<String>(key)) {
            "true" -> true
            "false" -> false
            else -> defaultVal
        }
    }

    fun setItemEnabled(name: String, enabled: Boolean) {
        setKey("${PREFS_PREFIX}${name}_enabled", enabled.toString())
    }

    fun getEnabledMainPageItems(mainUrl: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val cleanUrl = mainUrl.removeSuffix("/")

        // Always show highlights first
        for ((path, name) in defaultHighlights) {
            result.add("$cleanUrl$path" to name)
        }

        // Add categories if master toggle is enabled
        if (isCategoriesGroupEnabled()) {
            for ((path, name) in defaultCategories) {
                if (isItemEnabled(name, defaultVal = true)) {
                    result.add("$cleanUrl$path" to name)
                }
            }
        }

        // Add studios if master toggle is enabled
        if (isStudiosGroupEnabled()) {
            for ((path, name) in defaultStudios) {
                if (isItemEnabled(name, defaultVal = true)) {
                    result.add("$cleanUrl$path" to name)
                }
            }
        }

        return result
    }

    fun resetAllSettings() {
        setKey(KEY_CATEGORIES_GROUP_ENABLED, null)
        setKey(KEY_STUDIOS_GROUP_ENABLED, null)
        defaultCategories.forEach { (_, name) ->
            setKey("${PREFS_PREFIX}${name}_enabled", null)
        }
        defaultStudios.forEach { (_, name) ->
            setKey("${PREFS_PREFIX}${name}_enabled", null)
        }
    }
}

class WatchPornSettingsDialog(
    private val activity: AppCompatActivity,
    private val onSave: () -> Unit
) {
    private val COLOR_BG = "#121212"
    private val COLOR_CARD = "#1E1E1E"
    private val COLOR_ACCENT = "#FF3366" // Hot Pink accent
    private val COLOR_FOCUS = "#00D9FF"
    private val COLOR_DELETE = "#D32F2F"
    private val COLOR_SAVE = "#2E7D32"

    private enum class Tab {
        CATEGORIES,
        STUDIOS
    }

    private var currentTab = Tab.CATEGORIES
    private var tempCategoriesGroup = WatchPornSettings.isCategoriesGroupEnabled()
    private var tempStudiosGroup = WatchPornSettings.isStudiosGroupEnabled()

    private val tempItemStates = mutableMapOf<String, Boolean>().apply {
        WatchPornSettings.defaultCategories.forEach { (_, name) ->
            put(name, WatchPornSettings.isItemEnabled(name, defaultVal = true))
        }
        WatchPornSettings.defaultStudios.forEach { (_, name) ->
            put(name, WatchPornSettings.isItemEnabled(name, defaultVal = true))
        }
    }

    private lateinit var adapter: SettingsItemAdapter
    private lateinit var btnTabCategories: Button
    private lateinit var btnTabStudios: Button
    private lateinit var cbCategoriesMaster: CheckBox
    private lateinit var cbStudiosMaster: CheckBox
    private var dialog: AlertDialog? = null

    private fun dpToPx(dp: Int): Int =
        (dp * activity.resources.displayMetrics.density).toInt()

    private fun createRoundedDrawable(color: String, radiusDp: Float = 12f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(radiusDp.toInt()).toFloat()
            setColor(Color.parseColor(color))
        }
    }

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_BG, 20f)
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header Title
        val title = TextView(activity).apply {
            text = "WatchPorn Settings"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12))
        }
        root.addView(title)

        // Master Toggles Box
        val masterBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(COLOR_CARD, 12f)
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12) }
        }

        val tvMasterTitle = TextView(activity).apply {
            text = "Master Tampilan Halaman Utama:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(4))
        }
        masterBox.addView(tvMasterTitle)

        val masterChecksLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        cbCategoriesMaster = CheckBox(activity).apply {
            text = "Kategori (Categories)"
            setTextColor(Color.WHITE)
            isChecked = tempCategoriesGroup
            buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, isChecked ->
                tempCategoriesGroup = isChecked
            }
        }

        cbStudiosMaster = CheckBox(activity).apply {
            text = "Studio (Sites)"
            setTextColor(Color.WHITE)
            isChecked = tempStudiosGroup
            buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, isChecked ->
                tempStudiosGroup = isChecked
            }
        }

        masterChecksLayout.addView(cbCategoriesMaster)
        masterChecksLayout.addView(cbStudiosMaster)
        masterBox.addView(masterChecksLayout)
        root.addView(masterBox)

        // Tab Selector Row
        val tabLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(42)
            ).apply { bottomMargin = dpToPx(8) }
        }

        btnTabCategories = Button(activity).apply {
            text = "Categories (${WatchPornSettings.defaultCategories.size})"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener {
                switchTab(Tab.CATEGORIES)
            }
        }

        btnTabStudios = Button(activity).apply {
            text = "Studios (${WatchPornSettings.defaultStudios.size})"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener {
                switchTab(Tab.STUDIOS)
            }
        }

        tabLayout.addView(btnTabCategories)
        tabLayout.addView(btnTabStudios)
        root.addView(tabLayout)

        // Bulk Action Bar (Select All / Deselect All)
        val actionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(36)
            ).apply { bottomMargin = dpToPx(8) }
        }

        val btnSelectAll = Button(activity).apply {
            text = "Pilih Semua"
            setTextColor(Color.WHITE)
            textSize = 11f
            background = createRoundedDrawable("#2A2A2A", 8f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener {
                setAllInCurrentTab(true)
            }
        }

        val btnDeselectAll = Button(activity).apply {
            text = "Batal Semua"
            setTextColor(Color.WHITE)
            textSize = 11f
            background = createRoundedDrawable("#2A2A2A", 8f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener {
                setAllInCurrentTab(false)
            }
        }

        actionBar.addView(btnSelectAll)
        actionBar.addView(btnDeselectAll)
        root.addView(actionBar)

        // RecyclerView List
        adapter = SettingsItemAdapter { name, checked ->
            tempItemStates[name] = checked
        }

        val rv = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@WatchPornSettingsDialog.adapter
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(rv)

        // Footer Actions
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(12), 0, 0)
            gravity = Gravity.CENTER
        }

        val btnReset = Button(activity).apply {
            text = "RESET DEFAULT"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            background = createButtonDrawable(Color.parseColor(COLOR_DELETE))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(46), 1f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                WatchPornSettings.resetAllSettings()
                tempCategoriesGroup = true
                tempStudiosGroup = true
                cbCategoriesMaster.isChecked = true
                cbStudiosMaster.isChecked = true
                WatchPornSettings.defaultCategories.forEach { (_, name) ->
                    tempItemStates[name] = true
                }
                WatchPornSettings.defaultStudios.forEach { (_, name) ->
                    tempItemStates[name] = true
                }
                refreshList()
            }
        }

        val btnSave = Button(activity).apply {
            text = "SIMPAN & TERAPKAN"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
            background = createButtonDrawable(Color.parseColor(COLOR_SAVE))
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(46), 1.3f).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener {
                saveAll()
                onSave()
                dialog?.dismiss()
            }
        }

        footer.addView(btnReset)
        footer.addView(btnSave)
        root.addView(footer)

        switchTab(Tab.CATEGORIES)

        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(true)
            .create()

        dialog?.show()

        val window = dialog?.window
        val dm = activity.resources.displayMetrics
        window?.setLayout(
            (dm.widthPixels * 0.92).toInt(),
            (dm.heightPixels * 0.90).toInt()
        )
        window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.parseColor(COLOR_BG))
            cornerRadius = dpToPx(20).toFloat()
            setStroke(dpToPx(2), Color.parseColor(COLOR_ACCENT))
        })
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        if (tab == Tab.CATEGORIES) {
            btnTabCategories.background = createRoundedDrawable(COLOR_ACCENT, 8f)
            btnTabCategories.setTextColor(Color.WHITE)
            btnTabStudios.background = createRoundedDrawable("#2A2A2A", 8f)
            btnTabStudios.setTextColor(Color.parseColor("#AAAAAA"))
        } else {
            btnTabStudios.background = createRoundedDrawable(COLOR_ACCENT, 8f)
            btnTabStudios.setTextColor(Color.WHITE)
            btnTabCategories.background = createRoundedDrawable("#2A2A2A", 8f)
            btnTabCategories.setTextColor(Color.parseColor("#AAAAAA"))
        }
        refreshList()
    }

    private fun setAllInCurrentTab(enabled: Boolean) {
        val list = if (currentTab == Tab.CATEGORIES) {
            WatchPornSettings.defaultCategories
        } else {
            WatchPornSettings.defaultStudios
        }
        list.forEach { (_, name) ->
            tempItemStates[name] = enabled
        }
        refreshList()
    }

    private fun refreshList() {
        val list = if (currentTab == Tab.CATEGORIES) {
            WatchPornSettings.defaultCategories
        } else {
            WatchPornSettings.defaultStudios
        }
        val items = list.map { (_, name) ->
            SettingsItem(
                name = name,
                isEnabled = tempItemStates[name] ?: true
            )
        }
        adapter.submitList(items)
    }

    private fun saveAll() {
        WatchPornSettings.setCategoriesGroupEnabled(tempCategoriesGroup)
        WatchPornSettings.setStudiosGroupEnabled(tempStudiosGroup)
        tempItemStates.forEach { (name, enabled) ->
            WatchPornSettings.setItemEnabled(name, enabled)
        }
    }

    private fun createButtonDrawable(color: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.parseColor(COLOR_FOCUS))
            cornerRadius = dpToPx(10).toFloat()
            setStroke(dpToPx(3), Color.WHITE)
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(10).toFloat()
        })
    }

    data class SettingsItem(
        val name: String,
        var isEnabled: Boolean
    )

    private inner class SettingsItemAdapter(
        private val onCheckChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<SettingsItemAdapter.ViewHolder>() {

        private val items = mutableListOf<SettingsItem>()

        fun submitList(newItems: List<SettingsItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(48)
                ).apply {
                    bottomMargin = dpToPx(6)
                }
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(Color.parseColor(COLOR_CARD))
                        cornerRadius = dpToPx(8).toFloat()
                        setStroke(dpToPx(2), Color.parseColor(COLOR_FOCUS))
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(Color.parseColor(COLOR_CARD))
                        cornerRadius = dpToPx(8).toFloat()
                    })
                }
            }

            val checkBox = CheckBox(parent.context).apply {
                buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
                isFocusable = false
                isClickable = false
            }

            val textView = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dpToPx(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            itemLayout.addView(checkBox)
            itemLayout.addView(textView)

            return ViewHolder(itemLayout, checkBox, textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.textView.text = item.name
            holder.checkBox.isChecked = item.isEnabled

            holder.itemView.setOnClickListener {
                item.isEnabled = !item.isEnabled
                holder.checkBox.isChecked = item.isEnabled
                onCheckChanged(item.name, item.isEnabled)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(
            view: View,
            val checkBox: CheckBox,
            val textView: TextView
        ) : RecyclerView.ViewHolder(view)
    }
}
