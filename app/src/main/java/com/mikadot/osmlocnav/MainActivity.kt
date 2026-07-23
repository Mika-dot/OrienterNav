package com.mikadot.osmlocnav

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mikadot.osmlocnav.databinding.ActivityMainBinding
import com.mikadot.osmlocnav.databinding.DialogSettingsBinding
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var ins: InertialNavigator
    private lateinit var cameraSampler: CameraSampler
    private var map: MapLibreMap? = null
    private var startPoint: GeoPoint? = null
    private var destination: GeoPoint? = null
    private var gpsPoint: GeoPoint? = null
    private var gpsSampleTimeMs = 0L
    private var currentMarker: Marker? = null
    private var startMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var route: RouteResult? = null
    private var projector: RouteProjector? = null
    private var manualStartMode = false
    private var navigating = false
    private var cameraReady = false
    private var gpsTrusted = true
    private var spoofCounter = 0
    private var lastServerStatus = "OSMLoc: не подключён"
    private var lastMapFollowMs = 0L
    private val requestBusy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val point = GeoPoint(location.latitude, location.longitude)
            gpsPoint = point
            gpsSampleTimeMs = System.currentTimeMillis()
            if (navigating) {
                val reportedSpeed = if (location.hasSpeed()) location.speed.toDouble() else null
                ins.supplementGps(point, reportedSpeed, gpsTrusted)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) ensureCamera()
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) requestGpsStart()
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (navigating) updateFromIns()
            handler.postDelayed(this, 100)
        }
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (navigating && cameraReady) captureAndLocalize()
            handler.postDelayed(this, frameIntervalMs().toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        ins = InertialNavigator(this)
        cameraSampler = CameraSampler(this, this, binding.cameraPreview)
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)
        binding.gpsButton.setOnClickListener { requestGpsStart() }
        binding.manualButton.setOnClickListener {
            manualStartMode = true
            setStatus("Укажите старт", "Долго нажмите на карту в точке старта")
        }
        binding.goButton.setOnClickListener { buildRouteAndStart() }
        binding.settingsButton.setOnClickListener { showSettings() }
        handler.post(uiTick)
        handler.post(frameTick)
        requestPermissionsIfNeeded()
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        mapLibreMap.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE)) {
            mapLibreMap.cameraPosition = CameraPosition.Builder().target(LatLng(55.751244, 37.618423)).zoom(11.0).build()
            mapLibreMap.addOnMapLongClickListener { p ->
                val selected = GeoPoint(p.latitude, p.longitude)
                if (manualStartMode) {
                    setStart(selected, "ручной")
                    manualStartMode = false
                } else setDestination(selected)
                true
            }
            setStatus("Выберите старт и цель", "Долгое нажатие — цель; кнопка «Старт вручную» меняет режим")
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else ensureCamera()
    }

    private fun ensureCamera() {
        if (cameraReady || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraSampler.start { ok, error ->
            runOnUiThread {
                cameraReady = ok
                if (!ok) setStatus("Камера недоступна", error ?: "Неизвестная ошибка")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded(); return
        }
        setStatus("Проверяю GPS…", "GPS используется только как подсказка")
        val token = CancellationTokenSource()
        fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).addOnSuccessListener { loc ->
            if (loc == null) {
                manualStartMode = true
                setStatus("GPS не дал координату", "Укажите старт долгим нажатием на карту")
                return@addOnSuccessListener
            }
            val p = GeoPoint(loc.latitude, loc.longitude)
            gpsPoint = p
            gpsSampleTimeMs = System.currentTimeMillis()
            AlertDialog.Builder(this)
                .setTitle("GPS показывает эту точку")
                .setMessage(String.format(Locale.US, "%.6f, %.6f\nТочность: ±%.0f м\nИспользовать как старт?", p.lat, p.lon, loc.accuracy))
                .setPositiveButton("Да") { _, _ -> setStart(p, "GPS") }
                .setNegativeButton("Нет") { _, _ ->
                    manualStartMode = true
                    setStatus("GPS отклонён", "Укажите старт долгим нажатием на карту")
                }.show()
        }.addOnFailureListener {
            manualStartMode = true
            setStatus("Ошибка GPS", "${it.message}. Укажите старт вручную")
        }
    }

    private fun setStart(p: GeoPoint, source: String) {
        startPoint = p
        val m = map ?: return
        startMarker?.let { m.removeMarker(it) }
        startMarker = m.addMarker(MarkerOptions().position(LatLng(p.lat, p.lon)).title("Старт ($source)"))
        m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 16.0))
        setStatus("Старт установлен", "Теперь долго нажмите на карте для выбора цели")
    }

    private fun setDestination(p: GeoPoint) {
        destination = p
        val m = map ?: return
        destinationMarker?.let { m.removeMarker(it) }
        destinationMarker = m.addMarker(MarkerOptions().position(LatLng(p.lat, p.lon)).title("Цель"))
        setStatus("Цель установлена", "Нажмите «Поехали»")
    }

    private fun buildRouteAndStart() {
        val start = startPoint ?: return setStatus("Нет старта", "Используйте GPS или задайте старт вручную")
        val dest = destination ?: return setStatus("Нет цели", "Долго нажмите на карту в точке назначения")
        setStatus("Строю маршрут…", "OSRM + данные OpenStreetMap")
        RouteClient(BuildConfig.ROUTER_URL).route(start, dest) { result ->
            runOnUiThread {
                result.onFailure { setStatus("Маршрут не построен", it.message ?: "Ошибка OSRM") }
                result.onSuccess { r ->
                    if (r.points.size < 2) return@onSuccess setStatus("Маршрут пуст", "Выберите другую точку")
                    route = r
                    projector = RouteProjector(r.points)
                    drawRoute(r.points)
                    ins.setRoute(r.points, start)
                    ins.start()
                    navigating = true
                    startNavigationGpsUpdates()
                    ensureCamera()
                    binding.goButton.text = "Маршрут активен"
                    setStatus(String.format(Locale.US, "Маршрут %.1f км · %.0f мин", r.distanceMeters / 1000.0, r.durationSeconds / 60.0), "ИНС активна, ожидаю визуальную привязку")
                }
            }
        }
    }

    private fun drawRoute(points: List<GeoPoint>) {
        val m = map ?: return
        routeLine?.let { m.removePolyline(it) }
        routeLine = m.addPolyline(PolylineOptions().addAll(points.map { LatLng(it.lat, it.lon) }).color(Color.rgb(29, 78, 216)).width(6f))
    }

    private fun updateFromIns() {
        val s = ins.snapshot() ?: return
        val m = map ?: return
        val latLng = LatLng(s.position.lat, s.position.lon)
        if (currentMarker == null) currentMarker = m.addMarker(MarkerOptions().position(latLng).title("Оценка положения"))
        else {
            currentMarker!!.position = latLng
            m.updateMarker(currentMarker!!)
        }
        val now = System.currentTimeMillis()
        if (now - lastMapFollowMs > 700) {
            lastMapFollowMs = now
            m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(latLng).zoom(17.0).bearing(s.routeBearingDeg).tilt(35.0).build()
            ), 500)
        }
        val gpsState = if (gpsTrusted) "GPS: подсказка" else "GPS: СПУФИНГ/ОТКЛОНЁН"
        binding.detailText.text = String.format(Locale.US, "%s · %s · %.1f км/ч · ИНС %.0f м", gpsState, lastServerStatus, s.speedMps * 3.6, s.progressMeters)
    }

    private fun captureAndLocalize() {
        if (!requestBusy.compareAndSet(false, true)) return
        val snapshot = ins.snapshot()
        val prj = projector
        if (snapshot == null || prj == null) { requestBusy.set(false); return }
        cameraSampler.captureJpeg { frameResult ->
            frameResult.onFailure {
                requestBusy.set(false)
                runOnUiThread { lastServerStatus = "камера: ${it.message}" }
            }
            frameResult.onSuccess { bytes ->
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                val url = prefs.getString("server_url", "http://172.31.64.235:8000")!!
                val key = prefs.getString("api_key", "demo-change-me")!!
                NetworkClient(url, key).localize(bytes, snapshot, prj.corridor(snapshot.nearestRouteIndex)) { result ->
                    requestBusy.set(false)
                    runOnUiThread {
                        result.onFailure { lastServerStatus = "сервер: ${it.message}" }
                        result.onSuccess { fix ->
                            lastServerStatus = "${fix.backend} ${(fix.confidence * 100).toInt()}% · ${fix.processingMs}мс · авто:${fix.vehicleCount}"
                            if (fix.accepted && fix.confidence >= 0.08) {
                                val visual = GeoPoint(fix.lat, fix.lon)
                                ins.visualCorrection(visual, fix.confidence)
                                gpsPoint?.let { gps ->
                                    if (System.currentTimeMillis() - gpsSampleTimeMs < 5_000L) {
                                        val disagreement = Geo.distance(gps, visual)
                                        spoofCounter = if (disagreement > 100.0 && fix.confidence > 0.20) (spoofCounter + 1).coerceAtMost(5) else (spoofCounter - 1).coerceAtLeast(0)
                                        gpsTrusted = spoofCounter < 3
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigationGpsUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocation.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(1_500L)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun frameIntervalMs(): Int = getSharedPreferences("settings", MODE_PRIVATE).getInt("frame_interval", 1000).coerceIn(500, 10_000)

    private fun showSettings() {
        val b = DialogSettingsBinding.inflate(layoutInflater)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        b.serverUrl.setText(prefs.getString("server_url", "http://172.31.64.235:8000"))
        b.apiKey.setText(prefs.getString("api_key", "demo-change-me"))
        b.frameInterval.setText(prefs.getInt("frame_interval", 1000).toString())
        AlertDialog.Builder(this).setTitle("Телефон ↔ сервер").setView(b.root)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit()
                    .putString("server_url", b.serverUrl.text.toString().trim())
                    .putString("api_key", b.apiKey.text.toString())
                    .putInt("frame_interval", b.frameInterval.text.toString().toIntOrNull()?.coerceIn(500, 10_000) ?: 1000)
                    .apply()
                lastServerStatus = "настройки сохранены"
            }.setNegativeButton("Отмена", null).show()
    }

    private fun setStatus(title: String, detail: String) {
        binding.statusText.text = title
        binding.detailText.text = detail
    }

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() { binding.mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        navigating = false
        handler.removeCallbacksAndMessages(null)
        fusedLocation.removeLocationUpdates(locationCallback)
        ins.stop(); cameraSampler.close(); binding.mapView.onDestroy()
        super.onDestroy()
    }
}
