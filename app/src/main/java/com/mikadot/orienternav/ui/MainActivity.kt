package com.mikadot.orienternav.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mikadot.orienternav.R
import com.mikadot.orienternav.camera.VisualFrameSampler
import com.mikadot.orienternav.databinding.ActivityMainBinding
import com.mikadot.orienternav.location.FusionEngine
import com.mikadot.orienternav.model.FusedPosition
import com.mikadot.orienternav.model.GeoPoint
import com.mikadot.orienternav.model.GpsSample
import com.mikadot.orienternav.model.RoutePlan
import com.mikadot.orienternav.model.TrustState
import com.mikadot.orienternav.network.NominatimClient
import com.mikadot.orienternav.network.OrienterClient
import com.mikadot.orienternav.network.OsrmClient
import com.mikadot.orienternav.util.SettingsStore
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity :
    AppCompatActivity(),
    TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val fusion = FusionEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val http by lazy { NominatimClient.defaultHttp() }
    private val localizationInFlight = AtomicBoolean(false)

    private var map: MapLibreMap? = null
    private var currentGps: GpsSample? = null
    private var fused: FusedPosition? = null
    private var manualStart: GeoPoint? = null
    private var destination: GeoPoint? = null
    private var route: RoutePlan? = null
    private var routeStartPoint: GeoPoint? = null
    private var choosingDestination = false
    private var navigating = false
    private var currentStepIndex = 0
    private var spokenStepIndex = -1
    private var tts: TextToSpeech? = null
    private var frameSampler: VisualFrameSampler? = null
    private var lastTrustedPoint: GeoPoint? = null
    private var lastTrustedAt: Long = 0L
    private var lastTrustedSpeed = 0.0

    private val permissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocationUpdates()
            if (grants[Manifest.permission.CAMERA] == true && navigating) startCamera()
        }

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::onGpsLocation)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { mapLibre ->
            map = mapLibre
            loadMapStyle()
            mapLibre.addOnMapClickListener { latLng ->
                if (choosingDestination) {
                    choosingDestination = false
                    binding.destinationInput.setText("${"%.6f".format(latLng.latitude)}, ${"%.6f".format(latLng.longitude)}")
                    destination = GeoPoint(latLng.latitude, latLng.longitude)
                    binding.navigationInstruction.text = "Точка назначения выбрана"
                    renderMarkers()
                    true
                } else {
                    false
                }
            }
        }
        bindUi()
        requestPermissionsIfNeeded()
    }

    private fun bindUi() =
        with(binding) {
            useGpsButton.setOnClickListener {
                sourceInput.setText("Текущее местоположение GPS")
                manualStart = null
                toast("Старт будет взят из текущей проверенной позиции")
            }
            mapPointButton.setOnClickListener {
                choosingDestination = true
                navigationInstruction.text = "Коснитесь карты — это будет точка назначения"
            }
            searchButton.setOnClickListener { searchDestination() }
            sourceInput.doAfterTextChanged { manualStart = null }
            destinationInput.doAfterTextChanged { destination = null }
            routeButton.setOnClickListener { buildRoute() }
            navigationButton.setOnClickListener { if (navigating) stopNavigation() else startNavigation() }
            settingsButton.setOnClickListener { showSettings() }
            recenterButton.setOnClickListener { fused?.point?.let { centerMap(it, 17.0) } }
        }

    private fun requestPermissionsIfNeeded() {
        val requested =
            buildList {
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            }
        if (requested.isEmpty()) startLocationUpdates() else permissions.launch(requested.toTypedArray())
    }

    private fun startLocationUpdates() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setMaxUpdateDelayMillis(2_000L)
                .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun onGpsLocation(location: Location) {
        val sample =
            GpsSample(
                GeoPoint(location.latitude, location.longitude),
                location.accuracy.toDouble().coerceAtLeast(1.0),
                location.speed.takeIf { location.hasSpeed() }?.toDouble(),
                location.bearing.takeIf { location.hasBearing() }?.toDouble(),
                System.currentTimeMillis(),
            )
        currentGps = sample
        updateFused(fusion.addGps(sample))
        if (map?.cameraPosition?.zoom ?: 0.0 < 5.0) centerMap(sample.point, 16.0)
    }

    private fun updateFused(position: FusedPosition) {
        fused = position
        val visionConfigured = navigating && settings.visualEnabled && settings.visualServiceUrl.isNotBlank()
        if (position.state == TrustState.GPS_TRUSTED && (!visionConfigured || position.gpsVisualDeltaMeters != null)) {
            lastTrustedPoint = position.point
            lastTrustedAt = System.currentTimeMillis()
            lastTrustedSpeed = currentGps?.speedMps?.coerceIn(0.0, 45.0) ?: lastTrustedSpeed
        }
        val color =
            when (position.state) {
                TrustState.GPS_TRUSTED -> R.color.nav_ok
                TrustState.GPS_SUSPECTED, TrustState.DEGRADED -> R.color.nav_warn
                TrustState.SPOOF_CONFIRMED -> R.color.nav_danger
                TrustState.VISUAL_ONLY -> R.color.nav_primary
                TrustState.WAITING -> R.color.nav_warn
            }
        binding.localizationStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.localizationStatus.text = "Позиция: ${position.explanation}"
        renderPosition(position)
        if (navigating) updateNavigationInstruction(position.point)
    }

    private fun searchDestination() {
        val query =
            binding.destinationInput.text
                .toString()
                .trim()
        if (query.isBlank()) return toast("Введите адрес назначения")
        binding.navigationInstruction.text = "Поиск адреса…"
        lifecycleScope.launch {
            runCatching { NominatimClient(http, settings.geocoderUrl).search(query) }
                .onSuccess { places ->
                    if (places.isEmpty()) toast("Адрес не найден") else showPlaces(places)
                }.onFailure { showError(it) }
        }
    }

    private fun showPlaces(places: List<NominatimClient.Place>) {
        AlertDialog
            .Builder(this)
            .setTitle("Выберите место")
            .setItems(places.map { it.title }.toTypedArray()) { _, index ->
                val place = places[index]
                binding.destinationInput.setText(place.title)
                destination = place.point
                binding.navigationInstruction.text = "Найдено: ${place.title.substringBefore(',')}"
                centerMap(place.point, 16.0)
                renderMarkers()
            }.show()
    }

    private fun buildRoute() {
        lifecycleScope.launch {
            val start = resolveStartPoint() ?: return@launch
            val finish = resolveDestination() ?: return@launch
            binding.navigationInstruction.text = "Построение маршрута…"
            runCatching { OsrmClient(http, settings.routerUrl).route(start, finish) }
                .onSuccess { plan ->
                    route = plan
                    routeStartPoint = start
                    currentStepIndex = 0
                    spokenStepIndex = -1
                    binding.routeSummary.text = "${formatDistance(plan.distanceMeters)} • ${formatDuration(plan.durationSeconds)}"
                    binding.navigationInstruction.text = plan.steps.firstOrNull()?.instruction ?: "Маршрут построен"
                    binding.navigationButton.isEnabled = true
                    renderRoute(plan)
                    fitRoute(plan)
                }.onFailure { showError(it) }
        }
    }

    private suspend fun resolveStartPoint(): GeoPoint? {
        manualStart?.let { return it }
        val text =
            binding.sourceInput.text
                .toString()
                .trim()
        if (text.isBlank() || text.contains("GPS", true)) {
            return fused?.takeIf { it.state != TrustState.WAITING }?.point
                ?: currentGps?.point
                ?: run {
                    toast("GPS ещё не определил позицию")
                    null
                }
        }
        return runCatching { NominatimClient(http, settings.geocoderUrl).search(text).firstOrNull() }
            .onFailure { showError(it) }
            .getOrNull()
            ?.also {
                binding.sourceInput.setText(it.title)
                manualStart = it.point
            }?.point ?: run {
            toast("Не найден адрес отправления")
            null
        }
    }

    private suspend fun resolveDestination(): GeoPoint? {
        destination?.let { return it }
        val text =
            binding.destinationInput.text
                .toString()
                .trim()
        if (text.isBlank()) {
            return run {
                toast("Укажите точку назначения")
                null
            }
        }
        return runCatching { NominatimClient(http, settings.geocoderUrl).search(text).firstOrNull() }
            .onFailure { showError(it) }
            .getOrNull()
            ?.also { destination = it.point }
            ?.point
            ?: run {
                toast("Не найден адрес назначения")
                null
            }
    }

    private fun startNavigation() {
        if (route == null) return
        navigating = true
        lastTrustedPoint = routeStartPoint ?: fused?.point
        lastTrustedAt = System.currentTimeMillis()
        lastTrustedSpeed = currentGps?.speedMps?.coerceIn(0.0, 45.0) ?: 0.0
        binding.navigationButton.text = getString(R.string.stop_navigation)
        binding.searchPanel.visibility = View.GONE
        if (settings.visualEnabled && settings.visualServiceUrl.isNotBlank()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                startCamera()
            } else {
                permissions.launch(arrayOf(Manifest.permission.CAMERA))
            }
        } else {
            binding.localizationStatus.text = "Позиция: GPS; визуальный сервис не настроен"
        }
        scheduleVisualCapture(1_000)
    }

    private fun stopNavigation() {
        navigating = false
        handler.removeCallbacksAndMessages(null)
        frameSampler?.stop()
        binding.cameraPreview.visibility = View.GONE
        binding.searchPanel.visibility = View.VISIBLE
        binding.navigationButton.text = getString(R.string.start_navigation)
    }

    private fun startCamera() {
        if (frameSampler == null) frameSampler = VisualFrameSampler(this, this, binding.cameraPreview)
        binding.cameraPreview.visibility = View.VISIBLE
        frameSampler?.start { ok -> if (!ok) runOnUiThread { toast("Не удалось открыть заднюю камеру") } }
    }

    private fun scheduleVisualCapture(delayMillis: Long) {
        handler.postDelayed({
            if (!navigating || !settings.visualEnabled || settings.visualServiceUrl.isBlank()) return@postDelayed
            captureAndLocalize()
            val fast = fused?.state in setOf(TrustState.GPS_SUSPECTED, TrustState.SPOOF_CONFIRMED, TrustState.DEGRADED)
            scheduleVisualCapture(if (fast) 4_000 else 8_000)
        }, delayMillis)
    }

    private fun captureAndLocalize() {
        val prior = visualPrior() ?: return
        if (!localizationInFlight.compareAndSet(false, true)) return
        val sampler = frameSampler
        if (sampler == null) {
            localizationInFlight.set(false)
            return
        }
        sampler.capture { bytesResult ->
            bytesResult
                .onSuccess { jpeg ->
                    lifecycleScope.launch {
                        runCatching {
                            OrienterClient(settings.visualServiceUrl, settings.visualApiKey).localize(
                                jpeg,
                                prior,
                                fused?.headingDegrees ?: currentGps?.bearingDegrees,
                                if (fused?.state == TrustState.GPS_TRUSTED) 96 else 160,
                            )
                        }.onSuccess { estimate ->
                            if (navigating) updateFused(fusion.addVisual(estimate))
                        }.onFailure { binding.localizationStatus.text = "Камера: ${it.message}" }
                        localizationInFlight.set(false)
                    }
                }.onFailure {
                    localizationInFlight.set(false)
                    runOnUiThread { binding.localizationStatus.text = "Камера: ${it.message}" }
                }
        }
    }

    private fun visualPrior(): GeoPoint? {
        val position = fused
        if (position?.state == TrustState.GPS_TRUSTED && position.gpsVisualDeltaMeters != null) return position.point
        val trusted = lastTrustedPoint ?: manualStart ?: currentGps?.point ?: return null
        val elapsed = max(0L, System.currentTimeMillis() - lastTrustedAt) / 1000.0
        return advanceAlongRoute(trusted, (elapsed * lastTrustedSpeed).coerceAtMost(350.0))
    }

    private fun advanceAlongRoute(
        start: GeoPoint,
        meters: Double,
    ): GeoPoint {
        val geometry = route?.geometry ?: return start
        if (geometry.size < 2 || meters <= 0.0) return start
        var index = geometry.indices.minByOrNull { geometry[it].distanceTo(start) } ?: return start
        var remaining = meters
        while (index < geometry.lastIndex) {
            val segment = geometry[index].distanceTo(geometry[index + 1])
            if (segment >= remaining) {
                val ratio = if (segment == 0.0) 0.0 else remaining / segment
                val east =
                    geometry[index].distanceTo(GeoPoint(geometry[index].latitude, geometry[index + 1].longitude)) *
                        if (geometry[index + 1].longitude >= geometry[index].longitude) 1 else -1
                val north =
                    geometry[index].distanceTo(GeoPoint(geometry[index + 1].latitude, geometry[index].longitude)) *
                        if (geometry[index + 1].latitude >= geometry[index].latitude) 1 else -1
                return geometry[index].offset(east * ratio, north * ratio)
            }
            remaining -= segment
            index++
        }
        return geometry.last()
    }

    private fun updateNavigationInstruction(point: GeoPoint) {
        val steps = route?.steps ?: return
        if (currentStepIndex >= steps.size) return
        var step = steps[currentStepIndex]
        var distance = point.distanceTo(step.point)
        if (distance < 28.0 && currentStepIndex < steps.lastIndex) {
            currentStepIndex++
            step = steps[currentStepIndex]
            distance = point.distanceTo(step.point)
        }
        binding.navigationInstruction.text = "Через ${formatDistance(distance)}: ${step.instruction}"
        if (distance < 90.0 && spokenStepIndex != currentStepIndex) {
            spokenStepIndex = currentStepIndex
            tts?.speak(step.instruction, TextToSpeech.QUEUE_FLUSH, null, "step-$currentStepIndex")
        }
        destination?.let { destinationPoint ->
            if (point.distanceTo(destinationPoint) < 25.0) {
                binding.navigationInstruction.text = "Вы прибыли"
                tts?.speak("Вы прибыли", TextToSpeech.QUEUE_FLUSH, null, "arrived")
                stopNavigation()
            }
        }
    }

    private fun loadMapStyle() {
        map?.setStyle(Style.Builder().fromUri(settings.mapStyleUrl)) {
            ensureOverlayLayers(it)
            renderMarkers()
            route?.let(::renderRoute)
        }
    }

    private fun ensureOverlayLayers(style: Style) {
        if (style.getSource("route-source") == null) style.addSource(GeoJsonSource("route-source"))
        if (style.getLayer("route-line") == null) {
            style.addLayer(
                LineLayer("route-line", "route-source").withProperties(
                    lineColor("#0A5CFF"),
                    lineWidth(7f),
                    lineOpacity(.9f),
                ),
            )
        }
        if (style.getSource("position-source") == null) style.addSource(GeoJsonSource("position-source"))
        if (style.getLayer("position-circle") == null) {
            style.addLayer(
                CircleLayer("position-circle", "position-source").withProperties(
                    circleRadius(9f),
                    circleColor("#0A5CFF"),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(3f),
                ),
            )
        }
        if (style.getSource("markers-source") == null) style.addSource(GeoJsonSource("markers-source"))
        if (style.getLayer("markers-circle") == null) {
            style.addLayer(
                CircleLayer("markers-circle", "markers-source").withProperties(
                    circleRadius(7f),
                    circleColor("#C83349"),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2f),
                ),
            )
        }
    }

    private fun renderRoute(plan: RoutePlan) {
        val points = plan.geometry.map { Point.fromLngLat(it.longitude, it.latitude) }
        map?.style?.getSourceAs<GeoJsonSource>("route-source")?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        renderMarkers()
    }

    private fun renderPosition(position: FusedPosition) {
        if (position.state == TrustState.WAITING) return
        map
            ?.style
            ?.getSourceAs<GeoJsonSource>("position-source")
            ?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.point.longitude, position.point.latitude)))
    }

    private fun renderMarkers() {
        val features =
            buildList {
                manualStart?.let { add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
                destination?.let { add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
            }
        map?.style?.getSourceAs<GeoJsonSource>("markers-source")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun fitRoute(plan: RoutePlan) {
        val points = plan.geometry
        if (points.isEmpty()) return
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        map?.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(
                org.maplibre.android.geometry.LatLngBounds
                    .Builder()
                    .include(LatLng(minLat, minLon))
                    .include(LatLng(maxLat, maxLon))
                    .build(),
                80,
            ),
        )
    }

    private fun centerMap(
        point: GeoPoint,
        zoom: Double,
    ) {
        map?.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition
                    .Builder()
                    .target(LatLng(point.latitude, point.longitude))
                    .zoom(zoom)
                    .build(),
            ),
        )
    }

    private fun showSettings() {
        val visualUrl =
            EditText(this).apply {
                hint = "OrienterNet HTTPS URL"
                setText(settings.visualServiceUrl)
            }
        val apiKey =
            EditText(this).apply {
                hint = "API-ключ (необязательно)"
                setText(settings.visualApiKey)
            }
        val router =
            EditText(this).apply {
                hint = "OSRM URL"
                setText(settings.routerUrl)
            }
        val mapStyle =
            EditText(this).apply {
                hint = "MapLibre style URL"
                setText(settings.mapStyleUrl)
            }
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 8, 36, 0)
                addView(visualUrl)
                addView(apiKey)
                addView(router)
                addView(mapStyle)
            }
        AlertDialog
            .Builder(this)
            .setTitle("Сетевые настройки")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                settings.visualServiceUrl = visualUrl.text.toString()
                settings.visualApiKey = apiKey.text.toString()
                settings.routerUrl = router.text.toString()
                settings.mapStyleUrl = mapStyle.text.toString()
                loadMapStyle()
            }.setNegativeButton("Отмена", null)
            .show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1_000) "${meters.toInt()} м" else "${"%.1f".format(meters / 1_000)} км"

    private fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }

    private fun showError(error: Throwable) {
        binding.navigationInstruction.text = error.message ?: "Неизвестная ошибка"
        toast(error.message ?: "Ошибка")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        handler.removeCallbacksAndMessages(null)
        frameSampler?.close()
        tts?.shutdown()
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
