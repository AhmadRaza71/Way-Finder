package com.example.wayfinder

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.google.maps.android.PolyUtil
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

data class Pt(val lat: Double, val lng: Double)
data class Place(val name: String, val addr: String, val pt: Pt)
data class Sug(val id: String, val main: String, val sub: String)
data class Item(val text: String, val man: String, val at: Pt, val dist: Double, val arrive: Boolean = false, val leg: Int = -1)
class Rte(val dur: Double, val dist: Double, val label: String, val path: List<Pt>, val items: List<Item>)

/** Shared brain for the phone UI and the Android Auto screens. All data comes from Google Maps Platform. */
object NavEngine {
    private const val PLACES = "https://places.googleapis.com/v1/places"
    private const val ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val MASK = "routes.duration,routes.distanceMeters,routes.description,routes.polyline.encodedPolyline," +
        "routes.legs.endLocation,routes.legs.steps.distanceMeters,routes.legs.steps.startLocation,routes.legs.steps.navigationInstruction"

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var lm: LocationManager? = null
    private var app: Context? = null
    private var spoken = -1
    val listeners = mutableListOf<() -> Unit>()

    var me: Pt? = null
    var bearing = 0f
    val stops = mutableListOf<Place>()
    var routes: List<Rte> = emptyList()
    var sel = 0
    var idx = 0
    var reached = 0
    var navigating = false
    var busy = false
    var status = ""
    val route: Rte? get() = routes.getOrNull(sel)

    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) = update(Pt(l.latitude, l.longitude), if (l.hasBearing()) l.bearing else bearing)
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    fun init(ctx: Context) {
        if (lm != null) return
        app = ctx.applicationContext
        tts = TextToSpeech(app) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        lm = app!!.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    fun startGps() {
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm?.isProviderEnabled(p) == true) lm?.requestLocationUpdates(p, 1000L, 3f, gps, Looper.getMainLooper())
            if (me == null) (lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { update(Pt(it.latitude, it.longitude), bearing) }
        } catch (e: SecurityException) { status = "Allow location access in the Wayfinder phone app"; changed() }
    }

    fun changed() { main.post { listeners.toList().forEach { it() } } }
    fun say(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "wf") }

    private fun update(p: Pt, b: Float) {
        me = p; bearing = b
        if (navigating && route != null && !busy) tick()
        changed()
    }

    private fun tick() {
        val m = me ?: return; val r = route ?: return
        if (r.path.minOf { d(m, it) } > 80) {
            say("Rerouting")
            repeat(reached) { if (stops.size > 1) stops.removeAt(0) }; reached = 0
            compute(true); return
        }
        val n = r.items[min(idx + 1, r.items.lastIndex)]; val dn = d(m, n.at)
        if (dn < 200 && spoken != idx) { spoken = idx; say(if (n.arrive) "Your destination is ${fmt(dn)} ahead" else "In ${fmt(dn)}, ${n.text}") }
        if (n.arrive && dn < 35) { say("You have arrived"); stop(); return }
        if (dn < 35 && idx < r.items.lastIndex) { if (n.leg >= 0 && !n.arrive) reached = n.leg + 1; idx++ }
    }

    fun next(): Item? = route?.let { it.items[min(idx + 1, it.items.lastIndex)] }
    fun distToNext(): Double { val m = me; val n = next(); return if (m != null && n != null) d(m, n.at) else 0.0 }
    fun remainingDist(): Double { val r = route ?: return 0.0; return distToNext() + r.items.drop(idx + 1).sumOf { it.dist } }
    fun remainingTime(): Double { val r = route ?: return 0.0; return r.dur * remainingDist() / max(r.dist, 1.0) }

    fun startNav() {
        if (route == null) return
        navigating = true; idx = 0; spoken = -1
        try { app?.let { ContextCompat.startForegroundService(it, Intent(it, NavService::class.java)) } } catch (e: Exception) {}
        say("Starting navigation"); changed()
    }
    fun stop() { navigating = false; tts?.stop(); try { app?.stopService(Intent(app, NavService::class.java)) } catch (e: Exception) {}; changed() }
    fun clear() { stop(); stops.clear(); routes = emptyList(); sel = 0; reached = 0; status = ""; changed() }
    fun go(id: String) = details(id) { p -> if (p != null) { stops.clear(); stops.add(p); compute() } }

    // ---------- Google Routes API ----------
    private fun wp(p: Pt) = JSONObject().put("location", JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lng)))
    private fun ll(o: JSONObject) = o.getJSONObject("latLng").let { Pt(it.getDouble("latitude"), it.getDouble("longitude")) }

    fun compute(re: Boolean = false) {
        val from = me ?: run { status = "Waiting for your location…"; changed(); return }
        if (stops.isEmpty()) { clear(); return }
        busy = true; status = "Finding route…"; changed()
        val ss = stops.toList()
        thread {
            try {
                val body = JSONObject().put("origin", wp(from)).put("destination", wp(ss.last().pt))
                    .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_AWARE")
                    .put("computeAlternativeRoutes", ss.size == 1).put("languageCode", Locale.getDefault().toLanguageTag())
                if (ss.size > 1) body.put("intermediates", JSONArray(ss.dropLast(1).map { wp(it.pt) }))
                val ra = call(ROUTES, "POST", body, MASK).optJSONArray("routes") ?: throw Exception("No driving route found to that place.")
                val rs = (0 until ra.length()).map { parse(ra.getJSONObject(it)) }
                main.post { routes = rs; sel = 0; idx = 0; spoken = -1; if (!re) reached = 0; status = ""; busy = false; remember(ss.last()); changed() }
            } catch (e: Exception) { main.post { status = e.message ?: "Routing failed"; busy = false; changed() } }
        }
    }

    private fun parse(r: JSONObject): Rte {
        val path = PolyUtil.decode(r.getJSONObject("polyline").getString("encodedPolyline")).map { Pt(it.latitude, it.longitude) }
        val legs = r.getJSONArray("legs"); val out = mutableListOf<Item>()
        for (li in 0 until legs.length()) {
            val lg = legs.getJSONObject(li); val ss = lg.getJSONArray("steps")
            for (si in 0 until ss.length()) {
                val s = ss.getJSONObject(si); val ni = s.optJSONObject("navigationInstruction")
                out += Item(ni?.optString("instructions")?.ifEmpty { null } ?: "Continue", ni?.optString("maneuver") ?: "", ll(s.getJSONObject("startLocation")), s.optDouble("distanceMeters", 0.0))
            }
            val last = li == legs.length() - 1
            out += Item(if (last) "You have arrived" else "You have reached stop ${li + 1}", "", ll(lg.getJSONObject("endLocation")), 0.0, last, li)
        }
        return Rte(r.getString("duration").removeSuffix("s").toDouble(), r.getDouble("distanceMeters"), r.optString("description", "Route"), path, out)
    }

    // ---------- Google Places API (New) ----------
    fun suggest(q: String, cb: (List<Sug>) -> Unit) { thread {
        val res = try {
            val b = JSONObject().put("input", q)
            me?.let { b.put("locationBias", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", it.lat).put("longitude", it.lng)).put("radius", 50000.0))) }
            val a = call("$PLACES:autocomplete", "POST", b, "").optJSONArray("suggestions") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                a.getJSONObject(i).optJSONObject("placePrediction")?.let { p ->
                    val f = p.getJSONObject("structuredFormat")
                    Sug(p.getString("placeId"), f.getJSONObject("mainText").getString("text"), f.optJSONObject("secondaryText")?.optString("text") ?: "")
                }
            }
        } catch (e: Exception) { main.post { status = "Search failed: ${e.message}"; changed() }; emptyList() }
        main.post { cb(res) }
    } }

    fun details(id: String, cb: (Place?) -> Unit) { thread {
        val p = try { placeOf(call("$PLACES/$id", "GET", null, "id,displayName,formattedAddress,location")) }
        catch (e: Exception) { main.post { status = "Could not load place: ${e.message}"; changed() }; null }
        main.post { cb(p) }
    } }

    fun nearby(type: String, center: Pt, cb: (List<Place>) -> Unit) { thread {
        val res = try {
            val b = JSONObject().put("includedPrimaryTypes", JSONArray(listOf(type))).put("maxResultCount", 8)
                .put("locationRestriction", JSONObject().put("circle", JSONObject().put("center", JSONObject().put("latitude", center.lat).put("longitude", center.lng)).put("radius", 5000.0)))
            val a = call("$PLACES:searchNearby", "POST", b, "places.id,places.displayName,places.formattedAddress,places.location").optJSONArray("places") ?: JSONArray()
            (0 until a.length()).map { placeOf(a.getJSONObject(it)) }
        } catch (e: Exception) { main.post { status = "Nearby search failed: ${e.message}"; changed() }; emptyList() }
        main.post { cb(res) }
    } }

    private fun placeOf(j: JSONObject): Place {
        val l = j.getJSONObject("location")
        return Place(j.getJSONObject("displayName").getString("text"), j.optString("formattedAddress"), Pt(l.getDouble("latitude"), l.getDouble("longitude")))
    }

    private fun call(url: String, method: String, body: JSONObject?, mask: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
        if (mask.isNotEmpty()) c.setRequestProperty("X-Goog-FieldMask", mask)
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val txt = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        val j = JSONObject(txt)
        j.optJSONObject("error")?.let { throw Exception(it.optString("message", "Google API error")) }
        return j
    }

    // ---------- favourites & recents ----------
    private fun prefs() = app!!.getSharedPreferences("wf", Context.MODE_PRIVATE)
    fun saved(key: String): List<Place> = try {
        val a = JSONArray(prefs().getString(key, "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Place(o.getString("n"), o.getString("a"), Pt(o.getDouble("y"), o.getDouble("x"))) }
    } catch (e: Exception) { emptyList() }
    private fun store(key: String, l: List<Place>) {
        prefs().edit().putString(key, JSONArray(l.map { JSONObject().put("n", it.name).put("a", it.addr).put("y", it.pt.lat).put("x", it.pt.lng) }).toString()).apply()
    }
    fun isFav(p: Place) = saved("fav").contains(p)
    fun toggleFav(p: Place) { val f = saved("fav"); store("fav", if (f.contains(p)) f - p else listOf(p) + f) }
    private fun remember(p: Place) { store("rec", (listOf(p) + saved("rec").filter { it != p }).take(8)) }

    // ---------- helpers ----------
    fun fmt(m: Double) = if (m >= 1000) "%.1f km".format(m / 1000) else "${max(10, (m / 10).roundToInt() * 10)} m"
    fun d(a: Pt, b: Pt): Double {
        val x = sin(Math.toRadians(b.lat - a.lat) / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(Math.toRadians(b.lng - a.lng) / 2).pow(2)
        return 2 * 6371000.0 * asin(sqrt(x))
    }
}
