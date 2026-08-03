package com.mikadot.osmlocnav

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity(), OnMapReadyCallback, TextToSpeech.OnInitListener {
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
    private var stepProgress = emptyList<Double>()
    private var currentStepIndex = 0
    private var spokenStepIndex = -1
    private var manualStartMode = false
    private var navigating = false
    private var cameraReady = false
    private var gpsTrusted = true
    private var spoofCounter = 0
    private var lastServerStatus = "камера не настроена"
    private var lastMapFollowMs = 0L
    private var rerouteBusy = false
    private val requestBusy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val routeMonitor = RouteMonitor()
    private val searchClient = SearchClient()
    private var tts: TextToSpeech? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onGpsLocation)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) startGpsUpdates()
        if (permissions[Manifest.permission.CAMERA] == true && navigating) ensureCamera()
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (navigating) updateFromIns()
            handler.postDelayed(this, 100)
        }
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (navigating && cameraReady && visualEnabled()) captureAndLocalize()
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
        tts = TextToSpeech(this, this)
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)
        bindUi()
        handler.post(uiTick)
        handler.post(frameTick)
        requestPermissionsIfNeeded()
    }

    private fun bindUi() = with(binding) {
        gpsButton.setOnClickListener { requestGpsStart() }
        manualButton.setOnClickListener {
            manualStartMode = true
            setStatus("Укажите старт на карте", "Долго нажмите в точке, откуда начнётся поездка")
        }
        searchButton.setOnClickListener { searchDestination() }
        goButton.setOnClickListener { if (navigating) stopNavigation() else buildRouteAndStart() }
        settingsButton.setOnClickListener { showSettings() }
        recenterButton.setOnClickListener {
            val point = ins.snapshot()?.position ?: gpsPoint ?: startPoint
            point?.let { centerMap(it, ins.snapshot()?.headingDeg) }
        }
        statusPanel.setOnLongClickListener {
            cameraPreview.visibility = if (cameraPreview.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            true
        }
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        map = mapLibreMap
        mapLibreMap.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE)) {
            mapLibreMap.cameraPosition = CameraPosition.Builder().target(LatLng(55.751244, 37.618423)).zoom(11.0).build()
            mapLibreMap.addOnMapLongClickListener { p ->
                val selected = GeoPoint(p.latitude, p.longitude)
                if (manualStartMode) {
                    setStart(selected, "вручную")
                    manualStartMode = false
                } else {
                    setDestination(selected, "Точка на карте")
                }
                true
            }
            setStatus("Куда едем?", "Введите адрес или долго нажмите на карту")
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else startGpsUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocation.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(1_500L)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun onGpsLocation(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        gpsPoint = point
        gpsSampleTimeMs = System.currentTimeMillis()
        if (startPoint == null && !navigating) {
            startPoint = point
            updateStartMarker(point, "GPS")
        }
        if (navigating) {
            ins.supplementGps(
                measured = point,
                reportedSpeed = location.speed.takeIf { location.hasSpeed() }?.toDouble(),
                reportedBearing = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
                reportedAccuracy = location.accuracy.toDouble().coerceAtLeast(1.0),
                trusted = gpsTrusted,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        val point = gpsPoint
        if (point != null) {
            setStart(point, "GPS")
            return
        }
        setStatus("Ищу GPS…", "Обычно это занимает несколько секунд")
        fusedLocation.lastLocation.addOnSuccessListener { location ->
            if (location == null) setStatus("GPS ещё не готов", "Подождите или укажите старт вручную")
            else setStart(GeoPoint(location.latitude, location.longitude), "GPS")
        }
    }

    private fun setStart(point: GeoPoint, source: String) {
        startPoint = point
        updateStartMarker(point, source)
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), 16.0))
        setStatus("Старт выбран", "Теперь укажите адрес или точку назначения")
    }

    private fun updateStartMarker(point: GeoPoint, source: String) {
        val activeMap = map ?: return
        startMarker?.let(activeMap::removeMarker)
        startMarker = activeMap.addMarker(MarkerOptions().position(LatLng(point.lat, point.lon)).title("Старт ($source)"))
    }

    private fun setDestination(point: GeoPoint, title: String) {
        destination = point
        binding.destinationInput.setText(title)
        val activeMap = map ?: return
        destinationMarker?.let(activeMap::removeMarker)
        destinationMarker = activeMap.addMarker(MarkerOptions().position(LatLng(point.lat, point.lon)).title("Назначение"))
        binding.goButton.isEnabled = true
        setStatus("Маршрут готов к построению", "Нажмите «Поехали»")
    }

    private fun searchDestination() {
        val query = binding.destinationInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return setStatus("Введите адрес", "Например: Москва, Красная площадь")
        setStatus("Ищу адрес…", query)
        searchClient.search(query) { result ->
            runOnUiThread {
                result.onFailure { setStatus("Адрес не найден", it.message ?: "Ошибка поиска") }
                result.onSuccess { places ->
                    if (places.isEmpty()) {
                        setStatus("Ничего не найдено", "Попробуйте уточнить город и улицу")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Выберите место")
                            .setItems(places.map { it.title }.toTypedArray()) { _, index ->
                                setDestination(places[index].point, places[index].title.substringBefore(','))
                                map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                    LatLng(places[index].point.lat, places[index].point.lon), 15.5,
                                ))
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun buildRouteAndStart() {
        val start = startPoint ?: gpsPoint ?: return setStatus("Не определён старт", "Нажмите «Моё место» или задайте старт вручную")
        val finish = destination ?: return setStatus("Не выбрана цель", "Введите адрес или долго нажмите на карту")
        setStatus("Строю маршрут…", "Использую дороги OpenStreetMap")
        RouteClient(BuildConfig.ROUTER_URL).route(start, finish, null) { result ->
            runOnUiThread {
                result.onFailure { setStatus("Маршрут не построен", it.message ?: "Ошибка маршрутизатора") }
                result.onSuccess { beginNavigation(it, start) }
            }
        }
    }

    private fun beginNavigation(result: RouteResult, start: GeoPoint) {
        if (result.points.size < 2) return setStatus("Маршрут пуст", "Выберите другую точку")
        route = result
        projector = RouteProjector(result.points)
        stepProgress = result.steps.map { projector!!.project(it.point).progressMeters }
        currentStepIndex = 0
        spokenStepIndex = -1
        drawRoute(result.points)
        ins.setRoute(result.points, start, preservePose = false)
        ins.start()
        navigating = true
        routeMonitor.reset(System.currentTimeMillis())
        binding.searchPanel.visibility = View.GONE
        binding.navigationPanel.visibility = View.VISIBLE
        binding.gpsButton.visibility = View.GONE
        binding.manualButton.visibility = View.GONE
        binding.goButton.text = getString(R.string.stop_navigation)
        binding.goButton.setBackgroundColor(ContextCompat.getColor(this, R.color.nav_danger))
        binding.routeSummary.text = "${formatDistance(result.distanceMeters)} · ${formatDuration(result.durationSeconds)}"
        binding.maneuverText.text = result.steps.firstOrNull()?.instruction ?: "Двигайтесь по маршруту"
        if (visualEnabled()) ensureCamera() else lastServerStatus = "визуальная коррекция выключена"
        setStatus("Маршрут начат", "Позиция уточняется по GPS, ИНС и камере")
    }

    private fun stopNavigation() {
        navigating = false
        rerouteBusy = false
        ins.stop()
        binding.searchPanel.visibility = View.VISIBLE
        binding.navigationPanel.visibility = View.GONE
        binding.gpsButton.visibility = View.VISIBLE
        binding.manualButton.visibility = View.VISIBLE
        binding.goButton.text = getString(R.string.start_navigation)
        binding.goButton.setBackgroundColor(ContextCompat.getColor(this, R.color.nav_primary))
        binding.cameraPreview.visibility = View.INVISIBLE
        setStatus("Навигация остановлена", "Можно выбрать новый адрес")
    }

    private fun updateFromIns() {
        val snapshot = ins.snapshot() ?: return
        updateMarker(snapshot)
        updateInstruction(snapshot)
        updateStatus(snapshot)
        if (!rerouteBusy && routeMonitor.shouldReroute(snapshot, System.currentTimeMillis())) reroute(snapshot)
        destination?.let { finish ->
            if (Geo.distance(snapshot.position, finish) < 28.0 && snapshot.speedMps < 8.0) {
                binding.maneuverText.text = "Вы прибыли"
                tts?.speak("Вы прибыли", TextToSpeech.QUEUE_FLUSH, null, "arrived")
                stopNavigation()
            }
        }
    }

    private fun updateMarker(snapshot: MotionSnapshot) {
        val activeMap = map ?: return
        val latLng = LatLng(snapshot.position.lat, snapshot.position.lon)
        if (currentMarker == null) currentMarker = activeMap.addMarker(MarkerOptions().position(latLng).title("OrienterNav"))
        else {
            currentMarker!!.position = latLng
            activeMap.updateMarker(currentMarker!!)
        }
        val now = System.currentTimeMillis()
        if (now - lastMapFollowMs > 650) {
            lastMapFollowMs = now
            activeMap.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(latLng).zoom(17.2).bearing(snapshot.headingDeg).tilt(42.0).build(),
                ),
                550,
            )
        }
    }

    private fun updateInstruction(snapshot: MotionSnapshot) {
        val activeRoute = route ?: return
        if (activeRoute.steps.isEmpty()) return
        while (currentStepIndex < activeRoute.steps.lastIndex) {
            val step = activeRoute.steps[currentStepIndex]
            val passed = stepProgress.getOrNull(currentStepIndex)?.let { snapshot.progressMeters > it + 18.0 } ?: false
            if (passed || Geo.distance(snapshot.position, step.point) < 26.0) currentStepIndex++ else break
        }
        val step = activeRoute.steps[currentStepIndex]
        val distance = Geo.distance(snapshot.position, step.point)
        binding.maneuverText.text = "Через ${formatDistance(distance)} · ${step.instruction}"
        if (distance < 120.0 && spokenStepIndex != currentStepIndex) {
            spokenStepIndex = currentStepIndex
            tts?.speak(step.instruction, TextToSpeech.QUEUE_FLUSH, null, "step-$currentStepIndex")
        }
    }

    private fun updateStatus(snapshot: MotionSnapshot) {
        val sourceText = when (snapshot.source) {
            PositionSource.GPS -> "GPS"
            PositionSource.VISION -> "камера"
            PositionSource.FUSED -> "GPS + камера"
            PositionSource.INERTIAL -> "ИНС"
        }
        val warning = snapshot.accuracyMeters > 70.0 || snapshot.lastAbsoluteFixAgeMillis > 20_000
        binding.statusIndicator.setBackgroundResource(if (warning) R.drawable.status_dot_warn else R.drawable.status_dot_ok)
        binding.statusText.text = if (warning) "Уточняю позицию" else "Позиция надёжная"
        binding.detailText.text = String.format(
            Locale.US,
            "%s · ±%.0f м · %.0f км/ч · %s",
            sourceText,
            snapshot.accuracyMeters,
            snapshot.speedMps * 3.6,
            lastServerStatus,
        )
    }

    private fun reroute(snapshot: MotionSnapshot) {
        val finish = destination ?: return
        rerouteBusy = true
        binding.maneuverText.text = "Перестраиваю маршрут…"
        setStatus("Вы изменили маршрут", "Строю новый путь от текущего положения")
        RouteClient(BuildConfig.ROUTER_URL).route(snapshot.position, finish, snapshot.headingDeg) { result ->
            runOnUiThread {
                rerouteBusy = false
                result.onFailure {
                    routeMonitor.reset(System.currentTimeMillis())
                    setStatus("Не удалось перестроить", "Продолжаю отслеживать положение; попробую снова")
                }
                result.onSuccess { updated ->
                    if (updated.points.size < 2) return@onSuccess
                    route = updated
                    projector = RouteProjector(updated.points)
                    stepProgress = updated.steps.map { projector!!.project(it.point).progressMeters }
                    currentStepIndex = 0
                    spokenStepIndex = -1
                    drawRoute(updated.points)
                    ins.setRoute(updated.points, snapshot.position, preservePose = true)
                    routeMonitor.reset(System.currentTimeMillis())
                    binding.routeSummary.text = "${formatDistance(updated.distanceMeters)} · ${formatDuration(updated.durationSeconds)}"
                    setStatus("Маршрут перестроен", "Продолжайте движение")
                }
            }
        }
    }

    private fun drawRoute(points: List<GeoPoint>) {
        val activeMap = map ?: return
        routeLine?.let(activeMap::removePolyline)
        routeLine = activeMap.addPolyline(
            PolylineOptions().addAll(points.map { LatLng(it.lat, it.lon) }).color(Color.rgb(47, 107, 255)).width(7f),
        )
    }

    private fun ensureCamera() {
        if (!visualEnabled()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (cameraReady) return
        cameraSampler.start { ok, error ->
            runOnUiThread {
                cameraReady = ok
                binding.cameraPreview.visibility = if (ok && debugPreviewEnabled()) View.VISIBLE else View.INVISIBLE
                if (!ok) lastServerStatus = "камера: ${error ?: "недоступна"}"
            }
        }
    }

    private fun captureAndLocalize() {
        if (!requestBusy.compareAndSet(false, true)) return
        val snapshot = ins.snapshot()
        val activeProjector = projector
        if (snapshot == null || activeProjector == null) {
            requestBusy.set(false)
            return
        }
        cameraSampler.captureJpeg { frameResult ->
            frameResult.onFailure {
                requestBusy.set(false)
                runOnUiThread { lastServerStatus = "камера: ${it.message}" }
            }
            frameResult.onSuccess { bytes ->
                val quality = FrameQualityGate.assess(bytes)
                if (!quality.acceptable) {
                    cameraSampler.nudgeExposure(quality.exposureNudge)
                    requestBusy.set(false)
                    runOnUiThread { lastServerStatus = quality.message }
                    return@onSuccess
                }
                val preferences = getSharedPreferences("settings", MODE_PRIVATE)
                val url = preferences.getString("server_url", "").orEmpty()
                val key = preferences.getString("api_key", "").orEmpty()
                NetworkClient(url, key).localize(bytes, snapshot, activeProjector.corridor(snapshot.nearestRouteIndex)) { result ->
                    requestBusy.set(false)
                    runOnUiThread {
                        result.onFailure { lastServerStatus = "сервер: ${it.message}" }
                        result.onSuccess { fix ->
                            lastServerStatus = "${fix.backend} ${(fix.confidence * 100).toInt()}% · ${fix.processingMs} мс"
                            if (fix.accepted && fix.confidence >= 0.30) {
                                val visual = GeoPoint(fix.lat, fix.lon)
                                val corrected = ins.visualCorrection(visual, fix.headingDeg, fix.confidence, fix.sigmaMeters)
                                gpsPoint?.let { gps ->
                                    if (System.currentTimeMillis() - gpsSampleTimeMs < 5_000L) {
                                        val disagreement = Geo.distance(gps, visual)
                                        spoofCounter = if (disagreement > 100.0 && fix.confidence > 0.45) {
                                            (spoofCounter + 1).coerceAtMost(6)
                                        } else {
                                            (spoofCounter - 1).coerceAtLeast(0)
                                        }
                                        gpsTrusted = spoofCounter < 3
                                    }
                                }
                                if (!corrected) lastServerStatus += " · жду подтверждение"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun centerMap(point: GeoPoint, bearing: Double?) {
        map?.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(point.lat, point.lon)).zoom(17.0).bearing(bearing ?: 0.0).build(),
            ),
        )
    }

    private fun showSettings() {
        val dialog = DialogSettingsBinding.inflate(layoutInflater)
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        dialog.serverUrl.setText(preferences.getString("server_url", ""))
        dialog.apiKey.setText(preferences.getString("api_key", ""))
        dialog.frameInterval.setText(preferences.getInt("frame_interval", 2_000).toString())
        dialog.visualEnabled.isChecked = preferences.getBoolean("visual_enabled", true)
        dialog.debugPreview.isChecked = preferences.getBoolean("debug_preview", false)
        AlertDialog.Builder(this)
            .setTitle("Навигация и камера")
            .setView(dialog.root)
            .setPositiveButton("Сохранить") { _, _ ->
                preferences.edit()
                    .putString("server_url", dialog.serverUrl.text.toString().trim())
                    .putString("api_key", dialog.apiKey.text.toString())
                    .putInt("frame_interval", dialog.frameInterval.text.toString().toIntOrNull()?.coerceIn(800, 10_000) ?: 2_000)
                    .putBoolean("visual_enabled", dialog.visualEnabled.isChecked)
                    .putBoolean("debug_preview", dialog.debugPreview.isChecked)
                    .apply()
                binding.cameraPreview.visibility = if (cameraReady && debugPreviewEnabled()) View.VISIBLE else View.INVISIBLE
                lastServerStatus = if (visualEnabled()) "настройки камеры сохранены" else "визуальная коррекция выключена"
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun visualEnabled(): Boolean {
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        return preferences.getBoolean("visual_enabled", true) && preferences.getString("server_url", "").orEmpty().isNotBlank()
    }

    private fun debugPreviewEnabled(): Boolean = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("debug_preview", false)

    private fun frameIntervalMs(): Int = getSharedPreferences("settings", MODE_PRIVATE).getInt("frame_interval", 2_000).coerceIn(800, 10_000)

    private fun setStatus(title: String, detail: String) {
        binding.statusText.text = title
        binding.detailText.text = detail
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1_000) "${meters.toInt()} м" else String.format(Locale.US, "%.1f км", meters / 1_000.0)

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
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
        ins.stop()
        cameraSampler.close()
        tts?.shutdown()
        binding.mapView.onDestroy()
        super.onDestroy()
    }
}
