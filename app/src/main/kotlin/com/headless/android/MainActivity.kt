package com.headless.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Kotlin UI over the existing engine, with a native C++ helper. */
class MainActivity : Activity() {

    private var core: Core? = null
    private val pages = arrayOfNulls<View>(3)
    private val nav = arrayOfNulls<View>(3)
    private val indicators = arrayOfNulls<View>(3)
    private val icons = arrayOfNulls<ImageView>(3)
    private val labels = arrayOfNulls<TextView>(3)
    private var current = DISPLAYS

    private lateinit var tiles: LinearLayout
    private lateinit var shellText: TextView
    private lateinit var endpointText: TextView
    private lateinit var countText: TextView
    private lateinit var appAdapter: AppAdapter
    private lateinit var appsReload: Runnable
    private var signature = ""

    private val ticker = Handler(Looper.getMainLooper())
    private val step = object : Runnable {
        override fun run() {
            try { refresh() } catch (_: Throwable) {}
            ticker.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            core = Core.init(this)
            setContentView(R.layout.activity_main)
            bind()
        } catch (e: Throwable) {
            setContentView(R.layout.activity_main)
            AlertDialog.Builder(this).setTitle("Could not start")
                .setMessage(Util.message(e)).setPositiveButton("Close", null).show()
        }
    }

    private fun bind() {
        pages[DISPLAYS] = findViewById(R.id.page_displays)
        pages[APPS] = findViewById(R.id.page_apps)
        pages[SETTINGS] = findViewById(R.id.page_settings)
        nav[DISPLAYS] = findViewById(R.id.nav_displays)
        nav[APPS] = findViewById(R.id.nav_apps)
        nav[SETTINGS] = findViewById(R.id.nav_settings)
        indicators[DISPLAYS] = findViewById(R.id.indicator_displays)
        indicators[APPS] = findViewById(R.id.indicator_apps)
        indicators[SETTINGS] = findViewById(R.id.indicator_settings)
        icons[DISPLAYS] = findViewById(R.id.icon_displays)
        icons[APPS] = findViewById(R.id.icon_apps)
        icons[SETTINGS] = findViewById(R.id.icon_settings)
        labels[DISPLAYS] = findViewById(R.id.label_displays)
        labels[APPS] = findViewById(R.id.label_apps)
        labels[SETTINGS] = findViewById(R.id.label_settings)
        for (i in 0..2) nav[i]!!.setOnClickListener { switchTo(i) }
        for (i in 1..2) pages[i]!!.visibility = View.GONE

        tiles = findViewById(R.id.tiles)
        shellText = findViewById(R.id.shell_text)
        endpointText = findViewById(R.id.endpoint_text)
        countText = findViewById(R.id.count_text)

        shellText.text = "Shizuku: ${Shizuku.get(this).state()}"
        findViewById<View>(R.id.btn_grant).setOnClickListener {
            Shizuku.get(this).requestPermission()
        }
        findViewById<View>(R.id.btn_open_shizuku).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) startActivity(intent)
        }
        findViewById<View>(R.id.btn_create).setOnClickListener { core?.createDisplay() }
        findViewById<View>(R.id.btn_add_app).setOnClickListener { switchTo(APPS) }
        endpointText.setOnClickListener { showToken() }

        val search = findViewById<EditText>(R.id.app_search)
        val list = findViewById<ListView>(R.id.app_list)
        appAdapter = AppAdapter(core?.apps().orEmpty())
        list.adapter = appAdapter
        appsReload = Runnable { appAdapter.replace(core?.apps().orEmpty()) }
        core?.addListener(appsReload)
        list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
            val entry = appAdapter.item(position)
            core?.launch(entry.pkg)
            switchTo(DISPLAYS)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                appAdapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val portField = findViewById<EditText>(R.id.port_field)
        portField.setText(Prefs.port(this).toString())
        val tokenText = findViewById<TextView>(R.id.token_text)
        tokenText.text = Prefs.token(this) + "\n" + nativeEngineInfo()
        findViewById<View>(R.id.btn_save).setOnClickListener {
            try { Prefs.port(this, portField.text.toString().toInt()) } catch (_: Throwable) {}
            core?.let { Mcp.start(it, Prefs.port(this), Prefs.token(this)) }
            toast("Endpoint restarted")
        }
        findViewById<View>(R.id.btn_copy).setOnClickListener { copy(Prefs.token(this)) }
        findViewById<View>(R.id.btn_regen).setOnClickListener {
            Prefs.newToken(this)
            tokenText.text = Prefs.token(this) + "\n" + nativeEngineInfo()
            core?.let { Mcp.start(it, Prefs.port(this), Prefs.token(this)) }
        }
        findViewById<View>(R.id.btn_columns_1).setOnClickListener {
            Prefs.columns(this, 1); toast("Columns: 1")
        }
        findViewById<View>(R.id.btn_columns_2).setOnClickListener {
            Prefs.columns(this, 2); toast("Columns: 2")
        }
        switchTo(DISPLAYS)
    }

    override fun onResume() {
        super.onResume()
        try {
            Shizuku.get(this).refresh()
            ticker.removeCallbacks(step)
            ticker.postDelayed(step, 400)
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        try { ticker.removeCallbacks(step) } catch (_: Throwable) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { core?.removeListener(appsReload) } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun switchTo(index: Int) {
        if (index == current) return
        val outgoing = pages[current] ?: return
        val incoming = pages[index] ?: return
        val out = AlphaAnimation(1f, 0f)
        out.duration = 150
        out.interpolator = DecelerateInterpolator()
        out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                outgoing.visibility = View.GONE
                incoming.visibility = View.VISIBLE
                val inside = AlphaAnimation(0f, 1f)
                inside.duration = 150
                inside.interpolator = DecelerateInterpolator()
                incoming.startAnimation(inside)
            }
        })
        outgoing.startAnimation(out)
        current = index
        for (i in 0..2) {
            val active = i == index
            indicators[i]!!.visibility = if (active) View.VISIBLE else View.INVISIBLE
            icons[i]!!.setColorFilter(
                resources.getColor(if (active) R.color.on_secondary_container
                else R.color.on_surface_variant, null))
            labels[i]!!.setTextColor(
                resources.getColor(if (active) R.color.on_surface
                else R.color.on_surface_variant, null))
        }
    }

    private fun controlButton(label: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setBackgroundResource(R.drawable.bg_button_tonal)
            setTextColor(resources.getColor(R.color.on_surface, null))
            layoutParams = LinearLayout.LayoutParams(Util.dp(this@MainActivity, 76f),
                Util.dp(this@MainActivity, 44f))
            setOnClickListener { click() }
        }
    }

    private fun sendKey(slot: Core.Slot, code: Int) {
        try {
            core?.input("key", org.json.JSONObject()
                .put("slot", slot.slot).put("code", code))
        } catch (_: Throwable) {}
    }

    private fun invalidateTiles(view: View) {
        if (view is DisplayTile) {
            view.postInvalidate()
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) invalidateTiles(view.getChildAt(i))
        }
    }

    private fun refresh() {
        val engine = core ?: return
        val state = Shizuku.get(this).state()
        shellText.text = "Shizuku: $state"
        shellText.setTextColor(resources.getColor(if (Shizuku.READY == state)
            R.color.primary else R.color.error, null))
        val endpoint = Mcp.endpoint()
        endpointText.text = "Agent: ${endpoint ?: "offline"}/mcp"

        val slots = engine.slots()
        countText.text = "${slots.size} / 9"
        val next = slots.joinToString("|") { "${it.slot}:${it.pkg}" }
        if (next != signature) {
            signature = next
            tiles.removeAllViews()
            var row: LinearLayout? = null
            slots.forEachIndexed { index, slot ->
                if (index % 2 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    tiles.addView(row)
                }
                val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tile = DisplayTile(this, slot)
                tile.setOnClickListener { signature = ""; refresh() }
                tile.layoutParams = LinearLayout.LayoutParams(0,
                    Util.dp(this, 180f), 1f)
                card.addView(tile)

                val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                controls.addView(controlButton("Back") {
                    sendKey(slot, android.view.KeyEvent.KEYCODE_BACK)
                })
                controls.addView(controlButton("Home") {
                    sendKey(slot, android.view.KeyEvent.KEYCODE_HOME)
                })
                controls.addView(controlButton("Close") {
                    core?.close(slot.slot)
                    signature = ""
                    refresh()
                })
                card.addView(controls)

                val params = LinearLayout.LayoutParams(0,
                    Util.dp(this, 180f), 1f)
                params.setMargins(0, 0, Util.dp(this, 8f), Util.dp(this, 8f))
                card.layoutParams = params
                row!!.addView(card)
            }
        }
        invalidateTiles(tiles)
    }

    private fun showToken() {
        val endpoint = Mcp.endpoint()
        AlertDialog.Builder(this).setTitle("Agent endpoint")
            .setMessage("Endpoint: ${endpoint ?: "offline"}/mcp\nToken: ${Prefs.token(this)}")
            .setPositiveButton("Copy") { _, _ -> copy(Prefs.token(this)) }
            .setNegativeButton("Close", null).show()
    }

    private fun copy(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("HeadlessAndroid", value))
        toast("Copied")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class AppAdapter(entries: List<Core.AppInfo>) : BaseAdapter() {
        private val all = ArrayList(entries)
        private var shown = ArrayList(entries)

        fun replace(entries: List<Core.AppInfo>) {
            all.clear()
            all.addAll(entries)
            shown = ArrayList(entries)
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val needle = query.trim().lowercase(Locale.US)
            shown = ArrayList(all.filter {
                needle.isEmpty() || it.label.lowercase(Locale.US).contains(needle)
                        || it.pkg.lowercase(Locale.US).contains(needle)
            })
            notifyDataSetChanged()
        }

        fun item(position: Int): Core.AppInfo = shown[position]
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(Util.dp(context, 16f), Util.dp(context, 10f),
                    Util.dp(context, 16f), Util.dp(context, 10f))
                val icon = ImageView(context).apply {
                    id = 1
                    layoutParams = LinearLayout.LayoutParams(
                        Util.dp(context, 40f), Util.dp(context, 40f))
                }
                addView(icon)
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val label = TextView(context).apply {
                    id = 2
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.on_surface, null))
                }
                val pkg = TextView(context).apply {
                    id = 3
                    textSize = 12f
                    setTextColor(resources.getColor(R.color.on_surface_variant, null))
                }
                texts.addView(label)
                texts.addView(pkg)
                addView(texts)
            }
            val entry = shown[position]
            view.findViewById<TextView>(2).text = entry.label
            view.findViewById<TextView>(3).text = entry.pkg
            val icon = view.findViewById<ImageView>(1)
            try { icon.setImageDrawable(packageManager.getApplicationIcon(entry.pkg)) }
            catch (_: Throwable) {}
            return view
        }
    }

    private inner class DisplayTile(context: Context, private val slot: Core.Slot) : View(context) {
        private val label = Paint(Paint.ANTI_ALIAS_FLAG)
        private val meta = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dst = Rect()

        init {
            label.textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            meta.textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
            setBackgroundResource(R.drawable.bg_card)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val pad = Util.dp(context, 12f)
            val footer = Util.dp(context, 44f)
            val frame: Bitmap? = if (slot.session == null) null else slot.session!!.capture()
            val availW = w - pad * 2
            val availH = h - footer - pad
            if (frame != null && !frame.isRecycled) {
                val scale = Math.min(availW.toFloat() / frame.width,
                    availH.toFloat() / frame.height)
                val dw = Math.round(frame.width * scale)
                val dh = Math.round(frame.height * scale)
                val left = (w - dw) / 2
                val top = pad + (availH - dh) / 2
                dst.set(left, top, left + dw, top + dh)
                canvas.drawBitmap(frame, null, dst, null)
            }
            label.color = resources.getColor(R.color.on_surface, null)
            canvas.drawText(slot.label, pad.toFloat(), (h - Util.dp(context, 24f)).toFloat(), label)
            meta.color = resources.getColor(R.color.on_surface_variant, null)
            canvas.drawText("D ${slot.displayId}  ·  ${slot.state}", pad.toFloat(),
                (h - Util.dp(context, 8f)).toFloat(), meta)
        }
    }

    companion object {
        private const val DISPLAYS = 0
        private const val APPS = 1
        private const val SETTINGS = 2

        init {
            System.loadLibrary("headless")
        }
    }

    /** Implemented by the NDK C++ library. */
    private external fun nativeEngineInfo(): String
}
