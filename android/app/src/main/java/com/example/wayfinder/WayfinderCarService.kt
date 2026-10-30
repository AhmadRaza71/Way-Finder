package com.example.wayfinder

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import androidx.car.app.*
import androidx.car.app.model.*
import androidx.car.app.navigation.model.*
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import java.time.ZonedDateTime
import kotlin.math.max

class WayfinderCarService : CarAppService() {
    // Debug-friendly. Before publishing, replace with an allow-list of Google's hosts.
    override fun createHostValidator() = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            NavEngine.init(carContext); NavEngine.startGps()
            return HomeScreen(carContext)
        }
    }
}

abstract class LiveScreen(c: CarContext) : Screen(c) {
    private var last = 0L
    private val cb: () -> Unit = {
        onTick()
        val t = System.currentTimeMillis()
        if (t - last >= 1000) { last = t; invalidate() }
    }
    open fun onTick() {}
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(o: LifecycleOwner) { NavEngine.listeners.add(cb) }
            override fun onStop(o: LifecycleOwner) { NavEngine.listeners.remove(cb) }
        })
    }
}

class HomeScreen(c: CarContext) : LiveScreen(c) {
    override fun onGetTemplate(): Template {
        val has = NavEngine.route != null
        val msg = if (NavEngine.status.isNotEmpty()) NavEngine.status else if (has) "Route ready." else "Search for a destination, or pick one on your phone."
        val b = MessageTemplate.Builder(msg).setTitle("Wayfinder").setHeaderAction(Action.APP_ICON)
            .addAction(Action.Builder().setTitle("Search").setOnClickListener { screenManager.push(SearchScreen(carContext)) }.build())
        if (has) b.addAction(Action.Builder().setTitle("Start").setOnClickListener { screenManager.push(NavScreen(carContext)) }.build())
        return b.build()
    }
}

class SearchScreen(c: CarContext) : Screen(c) {
    private var items: List<Sug> = emptyList()
    private var loading = false
    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) {
                loading = true; invalidate()
                NavEngine.suggest(searchText) { items = it.take(6); loading = false; invalidate() }
            }
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(true).setSearchHint("Where to?")
        if (loading) b.setLoading(true)
        else if (items.isNotEmpty()) {
            val list = ItemList.Builder()
            items.forEach { s ->
                list.addItem(Row.Builder().setTitle(s.main.take(80)).addText(s.sub.take(80))
                    .setOnClickListener { NavEngine.go(s.id); screenManager.popToRoot() }.build())
            }
            b.setItemList(list.build())
        }
        return b.build()
    }
}

/** Google map drawn onto the car display through a virtual display. */
class MapPresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
    private lateinit var mv: MapView
    private var gm: GoogleMap? = null
    private var line: Polyline? = null
    private var dot: Circle? = null
    private var drawn: Rte? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        mv = MapView(context); setContentView(mv)
        mv.onCreate(null); mv.onStart(); mv.onResume()
        mv.getMapAsync { g ->
            gm = g
            g.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.dark_map))
            g.uiSettings.apply { setAllGesturesEnabled(false); isZoomControlsEnabled = false; isMyLocationButtonEnabled = false }
            update()
        }
    }
    override fun onStop() { mv.onPause(); mv.onStop(); mv.onDestroy(); super.onStop() }

    fun update() {
        val g = gm ?: return; val m = NavEngine.me ?: return; val r = NavEngine.route
        val ll = LatLng(m.lat, m.lng)
        if (r !== drawn) {
            drawn = r; line?.remove(); line = null
            if (r != null) line = g.addPolyline(PolylineOptions().addAll(r.path.map { LatLng(it.lat, it.lng) }).color(0xFFE7B95A.toInt()).width(22f))
        }
        if (dot == null) dot = g.addCircle(CircleOptions().center(ll).radius(9.0).fillColor(0xFFE7B95A.toInt()).strokeColor(0xFFFFFFFF.toInt()).strokeWidth(4f).zIndex(5f))
        else dot!!.center = ll
        g.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(ll).zoom(17f).tilt(45f).bearing(NavEngine.bearing).build()))
    }
}

class NavScreen(c: CarContext) : LiveScreen(c), SurfaceCallback {
    private var vd: VirtualDisplay? = null
    private var pres: MapPresentation? = null

    init {
        NavEngine.startNav()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(o: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null); release()
            }
        })
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val dm = carContext.getSystemService(DisplayManager::class.java)
        vd = dm.createVirtualDisplay("wayfinder-map", container.width, container.height, container.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        pres = MapPresentation(carContext, vd!!.display).also { it.show() }
    }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { release() }
    private fun release() { pres?.dismiss(); pres = null; vd?.release(); vd = null }
    override fun onTick() { pres?.update() }

    private fun dist(m: Double) = if (m >= 1000) Distance.create(m / 1000, Distance.UNIT_KILOMETERS) else Distance.create(max(m, 0.0), Distance.UNIT_METERS)

    private fun mtype(i: Item): Int = if (i.arrive) Maneuver.TYPE_DESTINATION else when (i.man) {
        "DEPART" -> Maneuver.TYPE_DEPART
        "TURN_LEFT" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        "TURN_RIGHT" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        "TURN_SLIGHT_LEFT" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        "TURN_SLIGHT_RIGHT" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        "TURN_SHARP_LEFT" -> Maneuver.TYPE_TURN_SHARP_LEFT
        "TURN_SHARP_RIGHT" -> Maneuver.TYPE_TURN_SHARP_RIGHT
        "UTURN_LEFT" -> Maneuver.TYPE_U_TURN_LEFT
        "UTURN_RIGHT" -> Maneuver.TYPE_U_TURN_RIGHT
        "KEEP_LEFT" -> Maneuver.TYPE_KEEP_LEFT
        "KEEP_RIGHT" -> Maneuver.TYPE_KEEP_RIGHT
        "FORK_LEFT" -> Maneuver.TYPE_FORK_LEFT
        "FORK_RIGHT" -> Maneuver.TYPE_FORK_RIGHT
        "MERGE" -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        else -> Maneuver.TYPE_STRAIGHT
    }

    override fun onGetTemplate(): Template {
        val e = NavEngine; val n = e.next()
        val stop = Action.Builder().setTitle("Stop").setOnClickListener { e.stop(); screenManager.popToRoot() }.build()
        val t = NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(stop).build())
        if (n != null) {
            val step = Step.Builder(n.text).setManeuver(Maneuver.Builder(mtype(n)).build()).build()
            t.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(step, dist(e.distToNext())).build())
            val secs = e.remainingTime().toLong().coerceAtLeast(0)
            t.setDestinationTravelEstimate(TravelEstimate.Builder(dist(e.remainingDist()), ZonedDateTime.now().plusSeconds(secs)).setRemainingTimeSeconds(secs).build())
        } else t.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
        return t.build()
    }
}
