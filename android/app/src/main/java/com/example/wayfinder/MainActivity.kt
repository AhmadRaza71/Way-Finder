package com.example.wayfinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val ACC = 0xFFE7B95A.toInt()
    private val PANEL = 0xF2161719.toInt()
    private lateinit var mv: MapView
    private var gm: GoogleMap? = null
    private lateinit var q: EditText
    private lateinit var sugg: LinearLayout
    private lateinit var suggScroll: ScrollView
    private lateinit var top: LinearLayout
    private lateinit var banner: TextView
    private lateinit var card: LinearLayout
    private lateinit var cTitle: TextView
    private lateinit var cSub: TextView
    private lateinit var cAlts: LinearLayout
    private lateinit var cBtns: LinearLayout
    private var picked: Place? = null
    private var pin: Marker? = null
    private var lines = listOf<Polyline>()
    private var drawn: List<Rte>? = null
    private var centered = false
    private var lastStatus = ""
    private val h = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private val refresh: () -> Unit = { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int = 18) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD) }
    private fun btn(t: String, kind: Int = 0, fn: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setTextColor(if (kind == 1) 0xFF1B1306.toInt() else Color.WHITE)
        background = bg(when (kind) { 1 -> ACC; 2 -> 0xFFE5675D.toInt(); else -> 0xFF23252C.toInt() }, 14)
        setPadding(dp(14), 0, dp(14), 0); minHeight = 0; minimumHeight = dp(44)
        setOnClickListener { fn() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        NavEngine.init(this)
        mv = MapView(this); mv.onCreate(s)
        q = EditText(this).apply {
            hint = "Search places or addresses"; setHintTextColor(0xFF8D8F99.toInt()); setTextColor(Color.WHITE); setSingleLine()
            background = bg(0xFF101115.toInt(), 14); setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun afterTextChanged(e: Editable?) = onQuery(e?.toString()?.trim() ?: "")
            })
        }
        sugg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        suggScroll = ScrollView(this).apply { addView(sugg); visibility = View.GONE }
        val chips = LinearLayout(this)
        fun chip(t: String, fn: () -> Unit) = chips.addView(btn(t) { fn() })
        chip("◎ Me") { NavEngine.me?.let { gm?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 16f)) } }
        chip("★ Saved") { rows(NavEngine.saved("fav"), "Tap ☆ on a place to save it here.") }
        chip("Recent") { rows(NavEngine.saved("rec"), "Places you navigate to appear here.") }
        chip("🚦 Traffic") { gm?.let { it.isTrafficEnabled = !it.isTrafficEnabled } }
        listOf("⛽ Fuel" to "gas_station", "🍴 Food" to "restaurant", "🅿 Parking" to "parking", "🏧 ATM" to "atm", "🏥 Hospital" to "hospital").forEach { (l, t) ->
            chip(l) {
                val c = NavEngine.me ?: gm?.cameraPosition?.target?.let { Pt(it.latitude, it.longitude) }
                if (c == null) toast("Waiting for your location…") else NavEngine.nearby(t, c) { rows(it, "Nothing found within 5 km.") }
            }
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(PANEL); setPadding(dp(12), dp(12), dp(12), dp(8))
            addView(q); addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }); addView(suggScroll)
        }
        banner = tv(20f, Color.WHITE, true).apply { background = bg(PANEL); setPadding(dp(18), dp(14), dp(18), dp(14)); visibility = View.GONE }
        cTitle = tv(20f, Color.WHITE, true); cSub = tv(14f, 0xFF8D8F99.toInt())
        cAlts = LinearLayout(this); cBtns = LinearLayout(this)
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg(PANEL); setPadding(dp(16), dp(14), dp(16), dp(12)); visibility = View.GONE
            addView(cTitle); addView(cSub); addView(cAlts); addView(cBtns)
        }
        setContentView(FrameLayout(this).apply {
            addView(mv)
            addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(10), dp(36), dp(10), 0) })
            addView(banner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(10), dp(36), dp(10), 0) })
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(16)) })
        })
        if (BuildConfig.MAPS_API_KEY.isEmpty()) toast("No Google Maps API key was set when this app was built. See the setup instructions.")
        mv.getMapAsync { g ->
            gm = g
            g.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.dark_map))
            g.uiSettings.apply { isZoomControlsEnabled = false; isMyLocationButtonEnabled = false; isMapToolbarEnabled = false }
            g.setOnPoiClickListener { poi -> NavEngine.details(poi.placeId) { it?.let(::pick) } }
            g.setOnMapClickListener { l -> if (!NavEngine.navigating) pick(Place("Dropped pin", "%.5f, %.5f".format(l.latitude, l.longitude), Pt(l.latitude, l.longitude))) }
            g.setOnPolylineClickListener { p -> (p.tag as? Int)?.let { NavEngine.sel = it; NavEngine.changed() } }
            enableMyLocation(); render()
        }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) gm?.isMyLocationEnabled = true
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { NavEngine.startGps(); enableMyLocation() }

    // ---------- search & lists ----------
    private fun onQuery(t: String) {
        pending?.let { h.removeCallbacks(it) }
        if (t.length < 2) { suggScroll.visibility = View.GONE; return }
        pending = Runnable {
            NavEngine.suggest(t) { l ->
                showRows(l.map { s -> Triple(s.main, s.sub) { NavEngine.details(s.id) { p -> p?.let(::pick) } } }, "No places found. Try a different name.")
            }
        }.also { h.postDelayed(it, 300) }
    }
    private fun rows(l: List<Place>, empty: String) = showRows(l.map { p -> Triple(p.name, p.addr) { pick(p) } }, empty)
    private fun showRows(l: List<Triple<String, String, () -> Unit>>, empty: String) {
        sugg.removeAllViews()
        if (l.isEmpty()) sugg.addView(tv(14f, 0xFF8D8F99.toInt()).apply { text = empty; setPadding(dp(6), dp(10), dp(6), dp(10)) })
        l.forEach { (a, b, fn) ->
            sugg.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)); setOnClickListener { fn() }
                addView(tv(15f, Color.WHITE, true).apply { text = a; setSingleLine() })
                addView(tv(13f, 0xFF8D8F99.toInt()).apply { text = b; setSingleLine() })
            })
        }
        suggScroll.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, if (l.size > 4) dp(260) else WRAP_CONTENT)
        suggScroll.visibility = View.VISIBLE
    }
    private fun pick(p: Place) {
        picked = p; suggScroll.visibility = View.GONE; q.setText(""); q.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(q.windowToken, 0)
        pin?.remove(); val pos = LatLng(p.pt.lat, p.pt.lng)
        pin = gm?.addMarker(MarkerOptions().position(pos).title(p.name))
        if (NavEngine.routes.isEmpty()) gm?.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 15f))
        render()
    }

    // ---------- rendering ----------
    private fun mins(s: Double) = max(1, (s / 60).roundToInt()).let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }
    private fun clock(s: Double) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (s * 1000).toLong()))

    private fun render() {
        val e = NavEngine; val g = gm
        if (e.status.isNotEmpty() && e.status != lastStatus && e.status != "Finding route…") toast(e.status)
        lastStatus = e.status
        if (g != null) {
            e.me?.let { m ->
                val ll = LatLng(m.lat, m.lng)
                if (!centered) { centered = true; g.moveCamera(CameraUpdateFactory.newLatLngZoom(ll, 15f)) }
                if (e.navigating) g.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(ll).zoom(17f).tilt(45f).bearing(e.bearing).build()))
            }
            if (e.routes !== drawn) { drawn = e.routes; drawRoutes(g) }
            lines.forEachIndexed { i, l -> l.color = if (i == e.sel) ACC else 0xFF6B6E78.toInt(); l.width = if (i == e.sel) 22f else 16f; l.zIndex = if (i == e.sel) 2f else 1f }
        }
        val nav = e.navigating
        top.visibility = if (nav) View.GONE else View.VISIBLE
        banner.visibility = if (nav) View.VISIBLE else View.GONE
        e.next()?.let { banner.text = "${e.fmt(e.distToNext())}  ${it.text}" }
        renderCard()
    }

    private fun drawRoutes(g: GoogleMap) {
        lines.forEach { it.remove() }
        val rs = NavEngine.routes
        lines = rs.mapIndexed { i, r -> g.addPolyline(PolylineOptions().addAll(r.path.map { LatLng(it.lat, it.lng) }).clickable(true)).also { it.tag = i } }
        if (rs.isNotEmpty() && !NavEngine.navigating) {
            val b = LatLngBounds.Builder(); rs[0].path.forEach { b.include(LatLng(it.lat, it.lng)) }
            try { g.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(80))) } catch (e: Exception) {}
        }
    }

    private fun renderCard() {
        val e = NavEngine; val r = e.route; val p = picked
        cBtns.removeAllViews(); cAlts.removeAllViews(); card.visibility = View.VISIBLE
        when {
            e.navigating && r != null -> {
                val t = e.remainingTime(); cTitle.text = "${mins(t)} · arrive ${clock(t)}"; cSub.text = "${e.fmt(e.remainingDist())} remaining"
                cBtns.addView(btn("Stop", 2) { e.stop() })
            }
            p != null -> {
                cTitle.text = p.name; cSub.text = p.addr
                cBtns.addView(btn("Directions", 1) { e.stops.clear(); e.stops.add(p); done(); e.compute() })
                cBtns.addView(btn("Add stop") {
                    if (e.stops.size >= 5) toast("You can add up to 5 stops.") else { if (!e.stops.contains(p)) e.stops.add(p); done(); e.compute() }
                })
                cBtns.addView(btn(if (e.isFav(p)) "★" else "☆") { e.toggleFav(p); renderCard() })
            }
            r != null -> {
                cTitle.text = "${mins(r.dur)} · arrive ${clock(r.dur)}"
                cSub.text = "${e.fmt(r.dist)} · " + e.stops.joinToString(" → ") { it.name }
                if (e.routes.size > 1) e.routes.forEachIndexed { i, x -> cAlts.addView(btn("${mins(x.dur)} · ${e.fmt(x.dist)}", if (i == e.sel) 1 else 0) { e.sel = i; e.changed() }) }
                cBtns.addView(btn("Start", 1) { e.startNav() })
                cBtns.addView(btn("Clear") { e.clear(); drawn = null; render() })
            }
            else -> card.visibility = View.GONE
        }
    }
    private fun done() { picked = null; pin?.remove(); pin = null }

    @Deprecated("back handling")
    override fun onBackPressed() {
        when {
            suggScroll.visibility == View.VISIBLE -> suggScroll.visibility = View.GONE
            picked != null -> { done(); render() }
            NavEngine.navigating -> NavEngine.stop()
            NavEngine.routes.isNotEmpty() -> { NavEngine.clear(); drawn = null; render() }
            else -> super.onBackPressed()
        }
    }

    // ---------- MapView lifecycle ----------
    override fun onStart() { super.onStart(); mv.onStart() }
    override fun onResume() {
        super.onResume(); mv.onResume(); NavEngine.listeners.add(refresh)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) NavEngine.startGps()
        render()
    }
    override fun onPause() { NavEngine.listeners.remove(refresh); mv.onPause(); super.onPause() }
    override fun onStop() { mv.onStop(); super.onStop() }
    override fun onDestroy() { mv.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mv.onLowMemory() }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); mv.onSaveInstanceState(o) }
}
