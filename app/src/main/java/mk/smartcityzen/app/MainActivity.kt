package mk.smartcityzen.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import mk.smartcityzen.app.databinding.ActivityMainBinding
import mk.smartcityzen.app.model.AccessibilityReport
import mk.smartcityzen.app.model.EntranceType
import mk.smartcityzen.app.model.Institution
import mk.smartcityzen.app.model.InstitutionCategory
import mk.smartcityzen.app.model.MobilityType
import mk.smartcityzen.app.model.ReportType
import mk.smartcityzen.app.routing.GraphNode
import mk.smartcityzen.app.routing.RouteResult
import mk.smartcityzen.app.routing.haversineMeters
import mk.smartcityzen.app.ui.GraphState
import mk.smartcityzen.app.ui.AddInstitutionDialog
import mk.smartcityzen.app.ui.MapViewModel
import mk.smartcityzen.app.ui.PendingReport
import mk.smartcityzen.app.ui.ReportObstacleDialog
import mk.smartcityzen.app.util.getCurrentGpsLocation
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * Smart CityZen — accessibility map & routing MVP.
 *
 * Report flow (FAB button): request CAMERA + location permission -> capture current
 * GPS position -> open camera -> show report form pre-filled with that position and
 * photo -> upload photo to Firebase Storage -> save the report to Firestore.
 *
 * Long-press on the map is a manual fallback (e.g. GPS unavailable indoors, or
 * reporting a spot the citizen isn't physically standing at).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MapViewModel by viewModels()

    private val bitolaCenter = GeoPoint(41.0297, 21.3347)

    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var pendingTapMode = TapMode.NONE

    private val reportMarkers = mutableListOf<Marker>()
    private val institutionMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null

    // ---- GPS + camera report flow state ----
    private var pendingPhotoUri: Uri? = null
    private var pendingGpsLat: Double? = null
    private var pendingGpsLng: Double? = null

    // ---- live "follow me" navigation state ----
    private var lastRouteResult: RouteResult? = null
    private var isFollowing = false
    private var hasArrived = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var myLocationOverlay: MyLocationNewOverlay

    private enum class TapMode { NONE, SET_START, SET_END, REPORT }

    companion object {
        /** How close (in meters) counts as "arrived" for the arrival dialog to trigger. */
        private const val ARRIVAL_THRESHOLD_METERS = 25.0
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val cameraOk = granted[Manifest.permission.CAMERA] == true
        val locationOk = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (cameraOk && locationOk) {
            startReportFlow()
        } else {
            Toast.makeText(this, "Потребна е дозвола за камера и локација", Toast.LENGTH_LONG).show()
        }
    }

    /** Separate, lighter-weight permission request just for the always-on "blue dot"
     *  location indicator — doesn't need CAMERA like the report flow does. */
    private val requestLocationOnly = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) enableLiveLocation() }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingPhotoUri != null) {
            openReportDialogWithPhoto()
        } else {
            // photo cancelled — still let them report with just GPS position
            openReportDialogWithPhoto()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupControls()
        observeViewModel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Always-on "where am I" blue dot, like Google Maps — independent of any
        // active route. Requests location permission immediately if not already granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            enableLiveLocation()
        } else {
            requestLocationOnly.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** OSMDroid's built-in blue-dot-with-heading overlay, backed by the device's real
     *  GPS provider — continuously tracks and redraws the citizen's position on the
     *  map exactly like Google Maps does, with no extra polling code of our own.
     *  Tinted to the citizen's saved mobility type so the color itself communicates
     *  how they move (black=пешак, blue=количка, pink=бебе количка, teal=тротинет). */
    private fun enableLiveLocation() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        applyLocationIconColor()
        myLocationOverlay.enableMyLocation()
        binding.mapView.overlays.add(myLocationOverlay)
        binding.mapView.invalidate()
    }

    /** Re-tints the location icon from the saved MobilityPrefs — called on startup and
     *  again any time the citizen changes their mobility type in the profile dialog. */
    private fun applyLocationIconColor() {
        if (!this::myLocationOverlay.isInitialized) return
        val color = mk.smartcityzen.app.util.MobilityPrefs.colorFor(mk.smartcityzen.app.util.MobilityPrefs.get(this))
        val personIcon = tintedBitmap(R.drawable.ic_walking_person, color)
        myLocationOverlay.setPersonIcon(personIcon)
        myLocationOverlay.setPersonAnchor(0.5f, 0.5f)
        myLocationOverlay.setDirectionIcon(personIcon)
        myLocationOverlay.setDirectionAnchor(0.5f, 0.5f)
        binding.mapView.invalidate()
    }

    /** Recolors a black-silhouette icon by swapping every opaque pixel for [color],
     *  keeping the original alpha shape — used so one source icon can represent every
     *  mobility type just by changing its tint. */
    private fun tintedBitmap(resId: Int, color: Int): android.graphics.Bitmap {
        val original = android.graphics.BitmapFactory.decodeResource(resources, resId)
        val result = android.graphics.Bitmap.createBitmap(original.width, original.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(original, 0f, 0f, paint)
        return result
    }

    /** Static reference dialog explaining the map's colors and each mobility role. */
    private fun showHelpDialog() {
        val message = """
            🔴 Црвен pin — пријавена пречка (дупка, нема рампа...)
            🟢 Зелен pin — позитивна точка (рампа, пристапен влез)
            🟣 Виолетов pin — инвалиден паркинг
            🔵 Син pin — институција

            Твојата GPS локација на мапата ја покажува иконата чија боја зависи од твојот избран тип мобилност (види копче до ова):
            • Црно — Пешак
            • Сино — Инвалидска количка
            • Розово — Количка за бебе
            • Тил-зелено — Тротинет

            Копчиња:
            "Почеток (А)" / "Крај (Б)" — избери две точки на мапата
            "Најди пристапна рута" — пресметува пат што ги избегнува пречките
            "Следи ме" — живо следење на растојанието додека одиш
            Долг допир на мапата — рачно пријавување пречка на таа точка
        """.trimIndent()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.help_title))
            .setMessage(message)
            .setPositiveButton("Разбрав", null)
            .show()
    }

    /** Lets the citizen pick their own way of moving through the city — saved once,
     *  used to color the live location icon and pre-fill the mobility field on reports. */
    private fun showMobilityProfileDialog() {
        val types = mk.smartcityzen.app.model.MobilityType.values()
        val labels = types.map { it.label }.toTypedArray()
        val current = mk.smartcityzen.app.util.MobilityPrefs.get(this)
        val currentIndex = types.indexOf(current).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mobility_profile))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                mk.smartcityzen.app.util.MobilityPrefs.set(this, types[which])
                applyLocationIconColor()
                dialog.dismiss()
            }
            .setNegativeButton("Затвори", null)
            .show()
    }

    private fun setupMap() = with(binding.mapView) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(16.5)
        controller.setCenter(bitolaCenter)

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                handleMapTap(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                // manual fallback: report at a tapped point, no photo/GPS
                showManualReportDialog(p.latitude, p.longitude)
                return true
            }
        }
        overlays.add(MapEventsOverlay(receiver))
    }

    private fun setupControls() {
        binding.btnSetStart.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Почетна точка (А)")
                .setItems(arrayOf("Моја моментална локација", "Избери на мапата")) { _, which ->
                    if (which == 0) {
                        useCurrentLocationAsStart()
                    } else {
                        pendingTapMode = TapMode.SET_START
                        binding.instructionsText.text = getString(R.string.instructions_pick_start)
                    }
                }
                .show()
        }
        binding.btnSetEnd.setOnClickListener {
            pendingTapMode = TapMode.SET_END
            binding.instructionsText.text = getString(R.string.instructions_pick_end)
        }
        binding.btnFindRoute.setOnClickListener {
            val start = startPoint
            val end = endPoint
            if (start == null || end == null) {
                Toast.makeText(this, "Прво избери А и Б точка", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val state = viewModel.graphState.value
            if (state !is GraphState.Ready) {
                Toast.makeText(this, "Мрежата сеуште не е вчитана ($state)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Пресметувам рута...", Toast.LENGTH_SHORT).show()
            viewModel.computeRoute(start.latitude, start.longitude, end.latitude, end.longitude)
        }
        binding.fabReportObstacle.setOnClickListener {
            ensurePermissionsThenReport()
        }
        binding.fabAddInstitution.setOnClickListener {
            ensurePermissionsThenAddInstitution()
        }
        binding.fabAddInstitution.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Демо податоци")
                .setMessage("Да додадам 10 пробни пријави и 5 институции околу центарот на Битола, за тестирање?")
                .setPositiveButton("Да") { _, _ ->
                    viewModel.seedDemoData()
                    Toast.makeText(this, "Демо податоците се праќаат...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Не", null)
                .show()
            true
        }
        setupFollowToggle()

        binding.btnHelp.setOnClickListener { showHelpDialog() }
        binding.btnMobilityProfile.setOnClickListener { showMobilityProfileDialog() }
        binding.btnCancelRoute.setOnClickListener { finishRoute() }
    }

    /** "Моја моментална локација" option for point A — checks permission, gets a fresh
     *  GPS fix, and sets it as the start point directly (no map tap needed). */
    private fun useCurrentLocationAsStart() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            requestLocationOnly.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            Toast.makeText(this, "Дозволи локација, потоа пробај повторно", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.locating_gps), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val location = try { getCurrentGpsLocation(this@MainActivity) } catch (e: Exception) { null }
            if (location == null) {
                Toast.makeText(this@MainActivity, getString(R.string.gps_unavailable), Toast.LENGTH_LONG).show()
                return@launch
            }
            startPoint = GeoPoint(location.first, location.second)
            refreshEndpointMarkers()
            binding.instructionsText.text = getString(R.string.instructions_pick_end)
        }
    }

    private fun ensurePermissionsThenAddInstitution() {
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        Toast.makeText(this, getString(R.string.locating_gps), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val location = try { getCurrentGpsLocation(this@MainActivity) } catch (e: Exception) { null }
            if (location == null) {
                Toast.makeText(this@MainActivity, getString(R.string.gps_unavailable), Toast.LENGTH_LONG).show()
                return@launch
            }
            AddInstitutionDialog.show(
                context = this@MainActivity,
                lat = location.first,
                lng = location.second,
                authorId = viewModel.currentUserId
            ) { institution ->
                viewModel.submitInstitution(institution)
                Toast.makeText(this@MainActivity, getString(R.string.institution_submitted), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensurePermissionsThenReport() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (cameraGranted && locationGranted) {
            startReportFlow()
        } else {
            requestPermissions.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    /** Step 1: get the citizen's real GPS position, then launch the camera. */
    private fun startReportFlow() {
        Toast.makeText(this, getString(R.string.locating_gps), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val location = try {
                getCurrentGpsLocation(this@MainActivity)
            } catch (e: Exception) {
                null
            }
            if (location == null) {
                Toast.makeText(this@MainActivity, getString(R.string.gps_unavailable), Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingGpsLat = location.first
            pendingGpsLng = location.second
            launchCamera()
        }
    }

    /** Step 2: open the camera, saving the photo to a FileProvider-shared file. */
    private fun launchCamera() {
        val photosDir = File(cacheDir, "report_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "report_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingPhotoUri = uri
        takePhoto.launch(uri)
    }

    /** Step 3: show the report form with GPS position + captured photo pre-filled. */
    private fun openReportDialogWithPhoto() {
        val lat = pendingGpsLat ?: return
        val lng = pendingGpsLng ?: return
        ReportObstacleDialog.show(
            context = this,
            lat = lat,
            lng = lng,
            photoUri = pendingPhotoUri
        ) { pending -> submitPendingReport(pending, lat, lng, pendingPhotoUri) }
    }

    /** Manual fallback report (long-press on map) — no GPS auto-capture, no photo. */
    private fun showManualReportDialog(lat: Double, lng: Double) {
        ReportObstacleDialog.show(
            context = this,
            lat = lat,
            lng = lng,
            photoUri = null
        ) { pending -> submitPendingReport(pending, lat, lng, null) }
    }

    private fun submitPendingReport(pending: PendingReport, lat: Double, lng: Double, photoUri: Uri?) {
        val report = AccessibilityReport(
            lat = lat,
            lng = lng,
            type = pending.type,
            comment = pending.comment,
            authorId = viewModel.currentUserId,
            authorName = "Граѓанин",
            reporterMobilityType = pending.mobilityType.name
        )
        Toast.makeText(this, getString(R.string.uploading_report), Toast.LENGTH_SHORT).show()
        viewModel.submitReportWithPhoto(applicationContext, report, photoUri)
        Toast.makeText(this, getString(R.string.report_submitted), Toast.LENGTH_SHORT).show()
    }

    private fun handleMapTap(point: GeoPoint) {
        when (pendingTapMode) {
            TapMode.SET_START -> {
                startPoint = point
                refreshEndpointMarkers()
                binding.instructionsText.text = getString(R.string.instructions_pick_end)
            }
            TapMode.SET_END -> {
                endPoint = point
                refreshEndpointMarkers()
                binding.instructionsText.text = getString(R.string.instructions_ready)
            }
            else -> { /* stray taps ignored — reporting now goes through the FAB/long-press flows */ }
        }
        pendingTapMode = TapMode.NONE
    }

    private fun refreshEndpointMarkers() {
        startMarker?.let { binding.mapView.overlays.remove(it) }
        endMarker?.let { binding.mapView.overlays.remove(it) }

        startPoint?.let {
            startMarker = Marker(binding.mapView).apply {
                position = it
                title = "А — Почеток"
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_start)
            }
            binding.mapView.overlays.add(startMarker)
        }
        endPoint?.let {
            endMarker = Marker(binding.mapView).apply {
                position = it
                title = "Б — Крај"
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_end)
            }
            binding.mapView.overlays.add(endMarker)
        }
        binding.btnCancelRoute.visibility = if (startPoint != null || endPoint != null) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.mapView.invalidate()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.graphState.collect { state ->
                binding.instructionsText.text = when (state) {
                    is GraphState.Loading -> getString(R.string.loading_network)
                    is GraphState.Ready -> getString(R.string.instructions_pick_start)
                    is GraphState.Error -> state.message
                }
                // Disable the route button until the network is actually ready — this
                // is what stops the "Мрежата сеуште не е вчитана" message from ever
                // appearing, instead of just showing it after the fact.
                binding.btnFindRoute.isEnabled = state is GraphState.Ready
            }
        }
        lifecycleScope.launch {
            viewModel.reports.collect { reports -> drawReportMarkers(reports) }
        }
        lifecycleScope.launch {
            viewModel.institutions.collect { institutions -> drawInstitutionMarkers(institutions) }
        }
        lifecycleScope.launch {
            viewModel.errorEvent.collect { message ->
                if (message != null) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    viewModel.errorShown()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.routeResult.collect { result ->
                if (result == null) return@collect
                if (result.points.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.route_not_found), Toast.LENGTH_LONG).show()
                    return@collect
                }
                lastRouteResult = result
                drawRoute(result.points)
                showRouteInfoCard(result, liveRemainingMeters = null)
            }
        }
    }

    /** Persistent bottom card — replaces the disappearing toast. Shows total distance
     *  and, once "Следи ме" is active, the live remaining distance as the citizen walks. */
    private fun showRouteInfoCard(result: RouteResult, liveRemainingMeters: Double?) {
        binding.routeInfoCard.visibility = android.view.View.VISIBLE
        val totalKm = "%.2f".format(result.totalDistanceMeters / 1000.0)
        binding.routeInfoText.text = if (liveRemainingMeters != null) {
            val remainingKm = "%.2f".format(liveRemainingMeters / 1000.0)
            "Вкупно: $totalKm км · Преостанато: $remainingKm км"
        } else {
            "Рута: $totalKm км · избегнати ${result.obstaclesAvoided} проблематични сегменти"
        }
    }

    private fun setupFollowToggle() {
        binding.btnToggleFollow.setOnClickListener {
            if (isFollowing) stopFollowing() else startFollowing()
        }
    }

    private fun startFollowing() {
        val end = endPoint
        if (end == null || lastRouteResult == null) {
            Toast.makeText(this, "Прво пронајди рута", Toast.LENGTH_SHORT).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        isFollowing = true
        hasArrived = false
        binding.btnToggleFollow.text = getString(R.string.follow_me_stop)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val loc = locationResult.lastLocation ?: return
                val remaining = lastRouteResult?.let {
                    remainingDistanceAlongRoute(loc.latitude, loc.longitude, it)
                } ?: 0.0
                lastRouteResult?.let { showRouteInfoCard(it, liveRemainingMeters = remaining) }
                // the always-on location overlay already shows where the citizen is —
                // just keep the map centered on them while following a route.
                binding.mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))

                // "You have arrived" — like Google Maps, shown once per follow session
                // when close enough to the destination, with a button to end the route.
                if (!hasArrived && remaining <= ARRIVAL_THRESHOLD_METERS) {
                    hasArrived = true
                    showArrivalDialog()
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun showArrivalDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Пристигна! 🎉")
            .setMessage("Стигна до дестинацијата.")
            .setPositiveButton("Заврши") { _, _ -> finishRoute() }
            .setNegativeButton("Продолжи", null)
            .setCancelable(false)
            .show()
    }

    /** Full "end navigation" reset — stops following, clears the drawn route and
     *  A/B points, ready for a brand new route to be picked. */
    private fun finishRoute() {
        stopFollowing()
        routeLine?.let { binding.mapView.overlays.remove(it) }
        routeLine = null
        binding.routeInfoCard.visibility = android.view.View.GONE
        startMarker?.let { binding.mapView.overlays.remove(it) }
        endMarker?.let { binding.mapView.overlays.remove(it) }
        startMarker = null
        endMarker = null
        startPoint = null
        endPoint = null
        lastRouteResult = null
        binding.btnCancelRoute.visibility = android.view.View.GONE
        binding.instructionsText.text = getString(R.string.instructions_pick_start)
        binding.mapView.invalidate()
    }

    /** Remaining distance measured ALONG the route path, not a straight line to the
     *  endpoint — finds the closest point on the route to where the citizen currently
     *  is, then sums the route segments from there to the end. This guarantees
     *  "remaining" can never exceed "total", unlike a naive haversine-to-endpoint
     *  calculation (which can overshoot if GPS jumps off the path). */
    private fun remainingDistanceAlongRoute(currentLat: Double, currentLon: Double, route: RouteResult): Double {
        if (route.points.size < 2) return 0.0
        var nearestIndex = 0
        var nearestDist = Double.MAX_VALUE
        route.points.forEachIndexed { index, node ->
            val d = haversineMeters(currentLat, currentLon, node.lat, node.lon)
            if (d < nearestDist) {
                nearestDist = d
                nearestIndex = index
            }
        }
        var remaining = 0.0
        for (i in nearestIndex until route.points.size - 1) {
            remaining += haversineMeters(
                route.points[i].lat, route.points[i].lon,
                route.points[i + 1].lat, route.points[i + 1].lon
            )
        }
        return remaining
    }

    private fun stopFollowing() {
        isFollowing = false
        binding.btnToggleFollow.text = getString(R.string.follow_me_start)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        lastRouteResult?.let { showRouteInfoCard(it, liveRemainingMeters = null) }
    }

    private fun drawReportMarkers(reports: List<AccessibilityReport>) {
        reportMarkers.forEach { binding.mapView.overlays.remove(it) }
        reportMarkers.clear()

        for (report in reports) {
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(report.lat, report.lng)
                title = report.type.label
                snippet = report.comment
                icon = when {
                    report.type == ReportType.ACCESSIBLE_PARKING -> ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_parking)
                    report.type.isPositive -> ContextCompat.getDrawable(this@MainActivity, android.R.drawable.presence_online)
                    else -> ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_problem)
                }
                setOnMarkerClickListener { _, _ ->
                    showReportDetailDialog(report)
                    true // consume the tap — we show our own dialog instead of the default info window
                }
            }
            reportMarkers.add(marker)
            binding.mapView.overlays.add(marker)
        }
        binding.mapView.invalidate()
    }

    private fun formatVerifiedTimestamp(millis: Long?): String {
        if (millis == null) return ""
        val formatted = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
        return "\n${getString(R.string.last_verified_prefix)}: $formatted"
    }

    /** Tapping a pin shows what was reported and lets neighbours confirm it's real (+10 points). */
    private fun showReportDetailDialog(report: AccessibilityReport) {
        val message = buildString {
            append(report.comment.ifBlank { "(нема коментар)" })
            append("\n\nСе движи со: ")
            append(MobilityType.values().find { it.name == report.reporterMobilityType }?.label ?: "?")
            append("\nВерификации: ${report.verificationCount}")
            append(formatVerifiedTimestamp(report.lastVerifiedAt))
        }
        showDetailDialogWithPhoto(
            title = report.type.label,
            message = message,
            photoUrl = report.photoUrl,
            onVerify = {
                viewModel.verifyReport(report.id)
                Toast.makeText(this, "Фала — верификувано!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun drawInstitutionMarkers(institutions: List<Institution>) {
        institutionMarkers.forEach { binding.mapView.overlays.remove(it) }
        institutionMarkers.clear()

        for (institution in institutions) {
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(institution.lat, institution.lng)
                title = institution.name
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_institution)
                setOnMarkerClickListener { _, _ ->
                    showInstitutionDetailDialog(institution)
                    true
                }
            }
            institutionMarkers.add(marker)
            binding.mapView.overlays.add(marker)
        }
        binding.mapView.invalidate()
    }

    private fun showInstitutionDetailDialog(institution: Institution) {
        val category = InstitutionCategory.values().find { it.name == institution.category }?.label ?: "?"
        val entrance = EntranceType.values().find { it.name == institution.accessibility.entranceType }?.label ?: "?"
        val message = buildString {
            append(category).append("\n\n")
            append("Влез: ").append(entrance).append("\n")
            append(if (institution.accessibility.hasAccessibleToilet) "✓ Пристапен тоалет\n" else "")
            append(if (institution.accessibility.hasAccessibleParking) "✓ Пристапен паркинг\n" else "")
            if (institution.accessibility.notes.isNotBlank()) {
                append("\n").append(institution.accessibility.notes)
            }
            append("\n\nВерификации: ${institution.verificationCount}")
            append(formatVerifiedTimestamp(institution.lastVerifiedAt))
        }
        showDetailDialogWithPhoto(
            title = institution.name,
            message = message,
            photoUrl = institution.photoUrl,
            onVerify = {
                viewModel.verifyInstitution(institution.id)
                Toast.makeText(this, "Фала — верификувано!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** Shared detail dialog for reports and institutions — shows a synchronously-built
     *  text message immediately, then asynchronously loads the photo (if any) into the
     *  same dialog once it's downloaded, rather than blocking the dialog on the network. */
    private fun showDetailDialogWithPhoto(title: String, message: String, photoUrl: String?, onVerify: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_detail, null)
        val imageView = view.findViewById<android.widget.ImageView>(R.id.imageDetail)
        val textView = view.findViewById<android.widget.TextView>(R.id.textDetailMessage)
        textView.text = message

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("Верификувај (+10 поени)") { _, _ -> onVerify() }
            .setNegativeButton("Затвори", null)
            .show()

        if (photoUrl != null) {
            lifecycleScope.launch {
                val bitmap = mk.smartcityzen.app.util.downloadBitmap(photoUrl)
                if (bitmap != null && dialog.isShowing) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun drawRoute(points: List<GraphNode>) {
        routeLine?.let { binding.mapView.overlays.remove(it) }
        routeLine = Polyline().apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.strokeWidth = 10f
            outlinePaint.color = 0xFF6C63FF.toInt()
        }
        binding.mapView.overlays.add(routeLine)
        binding.mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (this::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        if (this::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    /** Renders a drawable resource at an exact size (in dp) regardless of the source
     *  image's native pixel dimensions — change [sizeDp] here any time you want the
     *  marker icon bigger or smaller, no need to re-export or resize the image file. */
    private fun scaledMarkerIcon(resId: Int, sizeDp: Int): android.graphics.drawable.Drawable? {
        val original = ContextCompat.getDrawable(this, resId) ?: return null
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        original.setBounds(0, 0, sizePx, sizePx)
        original.draw(canvas)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }
}
