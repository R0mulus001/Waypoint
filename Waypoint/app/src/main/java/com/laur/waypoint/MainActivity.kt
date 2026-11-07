package com.laur.waypoint

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    private lateinit var stores: CityStores
    private val gps = mutableStateOf<LatLng?>(null)
    private val locationOn = mutableStateOf(false)
    private var lm: LocationManager? = null
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        override fun onProviderEnabled(provider: String) { locationOn.value = locationUsable() }
        override fun onProviderDisabled(provider: String) { locationOn.value = locationUsable() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationOn.value = locationUsable()
        if (r.values.any { it }) startLocation()
    }

    /** Asks for location; if Android won't show the prompt any more (denied twice), opens the app's settings. */
    private fun requestLocation() {
        if (hasLocation()) {
            // Allowed, but location is switched off on the phone: open the switch instead of doing nothing.
            if (!servicesOn()) openLocationSettings() else { locationOn.value = true; startLocation() }
            return
        }
        val askedBefore = Hub.getBool("locAsked")
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openSettings(); return
        }
        Hub.putBool("locAsked", true)
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: ActivityNotFoundException) { openSettings() }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bars(false)
        Fonts.init(this)
        Europe.load(applicationContext)
        Packs.init(applicationContext)
        Hub.init(applicationContext)
        val cities = City.loadAll(applicationContext)
        cities.firstOrNull()?.let { Trip.city = it } // set again when a city is opened; there may be none yet
        stores = CityStores(applicationContext, cities)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationOn.value = locationUsable()
        // Location is asked for in the welcome screens (with an explanation), not as a bare popup at launch.
        setContent { WaypointApp(stores, gps.value, locationOn.value, ::requestLocation, ::openUrl, ::bars) }
    }

    private var barsDark: Boolean? = null

    /** Edge to edge: the app draws behind transparent system bars, whose icons follow the app's light or dark look. */
    private fun bars(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        val t = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onStart() { super.onStart(); locationOn.value = locationUsable(); startLocation() }
    override fun onStop() { super.onStop(); stopLocation() }

    /** Location services switched on in the phone's settings. */
    private fun servicesOn(): Boolean {
        val m = lm ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** Permission given and location switched on: only then can a fix arrive. */
    private fun locationUsable() = hasLocation() && servicesOn()

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val m = lm ?: return
        if (listening || !hasLocation()) return
        // Listen to every provider the phone has, even ones switched off right now: they start sending fixes as soon
        // as the user turns location on. Before, a provider that was off at launch was never listened to.
        // Android 12+ also has the "fused" provider (GPS, Wi-Fi and cell towers combined): it gets a fix indoors and much faster.
        val names = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER)
        }
        val providers = names.filter { it in m.allProviders }
        // The freshest saved position (up to 30 minutes old) shows distances at once, until a new fix arrives.
        providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 30 * 60_000 }.maxByOrNull { it.time }?.let(::onFix)
        providers.forEach { pr ->
            if (runCatching { m.requestLocationUpdates(pr, 15_000L, 20f, listener, mainLooper) }.isSuccess) listening = true
        }
        // Ask once for a fresh position right away instead of waiting for the first update.
        if (android.os.Build.VERSION.SDK_INT >= 30) providers.firstOrNull()?.let { pr ->
            runCatching { m.getCurrentLocation(pr, null, mainExecutor) { l -> if (l != null) onFix(l) } }
        }
    }

    private fun stopLocation() {
        if (!listening) return
        listening = false
        runCatching { lm?.removeUpdates(listener) }
    }

    private fun onFix(l: Location) {
        val n = LatLng(l.latitude, l.longitude)
        val old = gps.value
        if (old == null || km(old.lat, old.lng, n.lat, n.lng) > 0.03) gps.value = n
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) {}
    }
}
