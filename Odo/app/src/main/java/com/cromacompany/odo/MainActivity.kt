package com.cromacompany.odo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cromacompany.odo.ui.theme.OdoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import android.graphics.Color as AndroidColor

private val TitleColor = Color(0xFF18212C)
private val BodyColor = Color(0xFF2D3743)
private val SecondaryTextColor = Color(0xFF4F5B68)
private val SimpleRoadMapTileSource = XYTileSource(
    "CartoDB Positron",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/",
    ),
    "© OpenStreetMap contributors © CARTO",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLogger.get(this).event("MainActivity creata")
        enableEdgeToEdge()
        setContent {
            OdoTheme {
                OdoApp()
            }
        }
    }
}

@Composable
private fun OdoApp() {
    val context = LocalContext.current
    val logger = remember { EventLogger.get(context) }
    val repository = remember { TripRepositoryProvider.get(context) }
    val trackingStateStore = remember { TrackingStateStore.get(context) }
    val trips by repository.trips.collectAsState()
    val trackingSnapshot by trackingStateStore.snapshot.collectAsState()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val isMonitoring = trackingSnapshot.isServiceAlive(nowMillis)
    var logSizeLabel by remember { mutableStateOf(logger.fileSizeLabel()) }
    var selectedTripId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTrip = trips.firstOrNull { it.id == selectedTripId } ?: trips.firstOrNull()
    val permissions = remember { requiredPermissions() }
    var hasPermissions by remember { mutableStateOf(context.hasRequiredPermissions(permissions)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasPermissions = results.values.all { it }
        logger.event("Risultato richiesta permessi", results)
        if (hasPermissions) {
            context.startTrackingService()
            trackingStateStore.setMonitoring(true)
        } else {
            logger.error("Permessi negati: monitoraggio non avviato", details = results)
        }
        logSizeLabel = logger.fileSizeLabel()
    }

    LaunchedEffect(trips) {
        if (selectedTripId == null && trips.isNotEmpty()) selectedTripId = trips.first().id
    }

    LaunchedEffect(Unit) {
        while (true) {
            trackingStateStore.refresh()
            nowMillis = System.currentTimeMillis()
            delay(5_000L)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF6F7F9))
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Header(
                    hasPermissions = hasPermissions,
                    isMonitoring = isMonitoring,
                    onStart = {
                        if (context.hasRequiredPermissions(permissions)) {
                            hasPermissions = true
                            logger.event("Avvio monitoraggio richiesto da UI")
                            context.startTrackingService()
                            trackingStateStore.setMonitoring(true)
                        } else {
                            logger.event("Richiesta permessi da UI")
                            permissionLauncher.launch(permissions)
                        }
                        logSizeLabel = logger.fileSizeLabel()
                    },
                    onStop = {
                        logger.event("Stop monitoraggio richiesto da UI")
                        context.stopTrackingService()
                        trackingStateStore.setMonitoring(false)
                        logSizeLabel = logger.fileSizeLabel()
                    },
                )
            }

            item {
                LogExportCard(
                    fileSizeLabel = logSizeLabel,
                    onShare = {
                        runCatching {
                            logger.event("Condivisione log richiesta da UI")
                            context.startActivity(Intent.createChooser(logger.shareIntent(), "Condividi log Odo"))
                        }.onFailure { throwable ->
                            logger.error("Errore condivisione log", throwable)
                        }
                        logSizeLabel = logger.fileSizeLabel()
                    },
                    onClear = {
                        logger.clear()
                        logSizeLabel = logger.fileSizeLabel()
                    },
                )
            }

            item {
                SelectedTripCard(trip = selectedTrip)
            }

            item {
                Text(
                    text = "Storico viaggi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TitleColor,
                )
            }

            if (trips.isEmpty()) {
                item {
                    EmptyHistory()
                }
            } else {
                items(trips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        isSelected = trip.id == selectedTrip?.id,
                        onClick = { selectedTripId = trip.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun LogExportCard(
    fileSizeLabel: String,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Log eventi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
                    Text(
                        text = "File esportabile .txt • $fileSizeLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryTextColor,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onShare) {
                    Text("Condividi .txt")
                }
                TextButton(onClick = onClear) {
                    Text("Svuota")
                }
            }
        }
    }
}

@Composable
private fun Header(
    hasPermissions: Boolean,
    isMonitoring: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Odo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TitleColor)
                    Text(
                        text = if (isMonitoring) "Pronto a registrare automaticamente i viaggi in auto" else "Monitoraggio non attivo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryTextColor,
                    )
                }
                StatusPill(isMonitoring)
            }
            if (!hasPermissions) {
                Text(
                    text = "Servono posizione e notifiche per registrare i percorsi mentre guidi.",
                    color = Color(0xFF9A5B00),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = !isMonitoring) {
                    Text(if (hasPermissions) "Avvia monitoraggio" else "Consenti e avvia")
                }
                TextButton(onClick = onStop, enabled = isMonitoring) {
                    Text("Ferma")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(isMonitoring: Boolean) {
    val color = if (isMonitoring) Color(0xFF1F8A4C) else Color(0xFF9B1C31)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (isMonitoring) "Attivo" else "Spento",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SelectedTripCard(trip: Trip?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Percorso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
            RouteMap(points = trip?.points.orEmpty())
            if (trip == null) {
                Text("Nessun viaggio registrato.", color = SecondaryTextColor)
            } else {
                TripMetrics(trip)
            }
        }
    }
}

@Composable
private fun RouteMap(points: List<RoutePoint>) {
    val context = LocalContext.current
    var isMapLoading by remember(points) { mutableStateOf(true) }
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(SimpleRoadMapTileSource)
            setMultiTouchControls(false)
            setBuiltInZoomControls(false)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, event -> event.action != MotionEvent.ACTION_CANCEL }
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(42.5, 12.5))
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(points) {
        isMapLoading = true
        delay(900L)
        isMapLoading = false
    }

    val mapShape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .clip(mapShape)
            .background(Color(0xFFE8EDF2), mapShape),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(mapShape),
            factory = { mapView },
            update = { view ->
                view.overlays.clear()
                if (points.size >= 2) {
                    val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                    view.overlays.add(
                        Polyline().apply {
                            setPoints(geoPoints)
                            outlinePaint.apply {
                                color = AndroidColor.rgb(20, 108, 148)
                                strokeWidth = 10f
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                pathEffect = null
                                isAntiAlias = true
                                alpha = 255
                            }
                        }
                    )
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.first()
                            title = "Partenza"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = startMarkerDrawable()
                        }
                    )
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.last()
                            title = "Arrivo"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = endMarkerDrawable()
                        }
                    )
                    view.post {
                        view.zoomToBoundingBox(geoPoints.boundingBox(), true, 80)
                    }
                } else {
                    view.controller.setZoom(6.0)
                    view.controller.setCenter(GeoPoint(42.5, 12.5))
                }
                view.invalidate()
            }
        )
        if (isMapLoading) {
            MapLoadingPlaceholder(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MapLoadingPlaceholder(modifier: Modifier = Modifier) {
    val mapShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(mapShape)
            .background(Color(0xFFE8EDF2), mapShape)
            .padding(16.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val roadColor = Color(0xFFC8D1DA)
            val secondaryRoadColor = Color(0xFFD7DEE6)
            drawLine(
                color = secondaryRoadColor,
                start = Offset(size.width * 0.08f, size.height * 0.25f),
                end = Offset(size.width * 0.92f, size.height * 0.25f),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = secondaryRoadColor,
                start = Offset(size.width * 0.18f, size.height * 0.72f),
                end = Offset(size.width * 0.88f, size.height * 0.72f),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = roadColor,
                start = Offset(size.width * 0.22f, size.height * 0.08f),
                end = Offset(size.width * 0.52f, size.height * 0.92f),
                strokeWidth = 8f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = roadColor,
                start = Offset(size.width * 0.82f, size.height * 0.05f),
                end = Offset(size.width * 0.34f, size.height * 0.94f),
                strokeWidth = 7f,
                cap = StrokeCap.Round,
            )
            drawCircle(Color(0xFFB6C1CC), radius = 12f, center = Offset(size.width * 0.5f, size.height * 0.5f))
        }
        Text(
            text = "Caricamento mappa",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.White.copy(alpha = 0.86f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = SecondaryTextColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun List<GeoPoint>.boundingBox(): BoundingBox {
    return BoundingBox(
        maxOf { it.latitude },
        maxOf { it.longitude },
        minOf { it.latitude },
        minOf { it.longitude },
    )
}

private fun startMarkerDrawable(): Drawable = routeMarkerDrawable(
    fillColor = AndroidColor.rgb(31, 138, 76),
    label = "P",
)

private fun endMarkerDrawable(): Drawable = routeMarkerDrawable(
    fillColor = AndroidColor.rgb(214, 40, 40),
    label = "A",
)

private fun routeMarkerDrawable(fillColor: Int, label: String): Drawable {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.WHITE
    canvas.drawCircle(size / 2f, size / 2f, 22f, paint)

    paint.color = fillColor
    canvas.drawCircle(size / 2f, size / 2f, 16f, paint)

    paint.color = AndroidColor.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = 18f
    paint.isFakeBoldText = true
    val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(label, size / 2f, textY, paint)

    return BitmapDrawable(null, bitmap)
}

@Composable
private fun TripMetrics(trip: Trip) {
    val context = LocalContext.current
    var startPlace by remember(trip.id) { mutableStateOf("Rilevamento luogo...") }
    var endPlace by remember(trip.id) { mutableStateOf("Rilevamento luogo...") }

    LaunchedEffect(trip.id, trip.points) {
        val startPoint = trip.points.firstOrNull()
        val endPoint = trip.points.lastOrNull()
        startPlace = startPoint?.let { context.resolvePlaceLabel(it) } ?: "Luogo non disponibile"
        endPlace = endPoint?.let { context.resolvePlaceLabel(it) } ?: "Luogo non disponibile"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Inizio", formatDateTime(trip.startMillis), Modifier.weight(1f), detail = startPlace)
            Metric("Arrivo", formatDateTime(trip.endMillis), Modifier.weight(1f), detail = endPlace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Durata", formatDuration(trip.durationMillis), Modifier.weight(1f))
            Metric("Distanza", formatDistance(trip.distanceMeters), Modifier.weight(1f))
        }
        Metric("Traffico", trip.trafficCondition.label, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .background(Color(0xFFF1F4F7), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SecondaryTextColor)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BodyColor, maxLines = 2)
        if (detail != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private suspend fun Context.resolvePlaceLabel(point: RoutePoint): String = withContext(Dispatchers.IO) {
    runCatching {
        @Suppress("DEPRECATION")
        val address = Geocoder(this@resolvePlaceLabel, Locale.ITALIAN)
            .getFromLocation(point.latitude, point.longitude, 1)
            ?.firstOrNull()
        address?.let {
            listOfNotNull(
                it.thoroughfare,
                it.locality ?: it.subAdminArea,
                it.adminArea,
            )
                .distinct()
                .joinToString(", ")
                .takeIf { label -> label.isNotBlank() }
                ?: it.getAddressLine(0)
        } ?: point.coordinateLabel()
    }.getOrElse {
        point.coordinateLabel()
    }
}

private fun RoutePoint.coordinateLabel(): String {
    return String.format(Locale.ITALIAN, "%.5f, %.5f", latitude, longitude)
}

@Composable
private fun TripRow(trip: Trip, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFFE4F1F8) else Color.White),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniRoute(points = trip.points)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatDateTime(trip.startMillis),
                    fontWeight = FontWeight.SemiBold,
                    color = TitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatDuration(trip.durationMillis)} • ${formatDistance(trip.distanceMeters)} • ${trip.trafficCondition.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniRoute(points: List<RoutePoint>) {
    Canvas(
        modifier = Modifier
            .size(56.dp)
            .background(Color(0xFFE8EDF2), RoundedCornerShape(8.dp)),
    ) {
        val bounds = routeBounds(points) ?: return@Canvas
        if (points.size < 2) return@Canvas
        val padding = 8f
        val drawableWidth = (size.width - padding * 2).coerceAtLeast(1f)
        val drawableHeight = (size.height - padding * 2).coerceAtLeast(1f)
        val middleLatitudeRadians = Math.toRadians((bounds.minLatitude + bounds.maxLatitude) / 2.0)
        val longitudeScale = Math.cos(middleLatitudeRadians).coerceAtLeast(0.01)
        val projectedWidth = (bounds.longitudeSpan * longitudeScale).coerceAtLeast(0.00001)
        val projectedHeight = bounds.latitudeSpan.coerceAtLeast(0.00001)
        val scale = minOf(drawableWidth / projectedWidth, drawableHeight / projectedHeight).toFloat()
        val routeWidth = (projectedWidth * scale).toFloat()
        val routeHeight = (projectedHeight * scale).toFloat()
        val offsetX = padding + (drawableWidth - routeWidth) / 2f
        val offsetY = padding + (drawableHeight - routeHeight) / 2f

        fun project(point: RoutePoint): Offset {
            val x = offsetX + ((point.longitude - bounds.minLongitude) * longitudeScale * scale).toFloat()
            val y = offsetY + routeHeight - ((point.latitude - bounds.minLatitude) * scale).toFloat()
            return Offset(x.coerceIn(padding, size.width - padding), y.coerceIn(padding, size.height - padding))
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            val offset = project(point)
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(path, color = Color(0xFF146C94), style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun EmptyHistory() {
    Surface(color = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Avvia il monitoraggio. Il primo viaggio comparira qui dopo almeno un minuto di guida e una fermata di circa tre minuti.",
            modifier = Modifier.padding(16.dp),
            color = SecondaryTextColor,
        )
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.hasRequiredPermissions(permissions: Array<String>): Boolean {
    return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.startTrackingService() {
    val intent = Intent(this, TrackingService::class.java)
    ContextCompat.startForegroundService(this, intent)
}

private fun Context.stopTrackingService() {
    val intent = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
    ContextCompat.startForegroundService(this, intent)
}

@Preview(showBackground = true)
@Composable
private fun OdoPreview() {
    OdoTheme {
        SelectedTripCard(
            Trip(
                id = "preview",
                startMillis = 1_700_000_000_000,
                endMillis = 1_700_003_600_000,
                points = listOf(
                    RoutePoint(45.4642, 9.1900, 1, 12f),
                    RoutePoint(45.4680, 9.2020, 2, 14f),
                    RoutePoint(45.4760, 9.2120, 3, 8f),
                ),
                trafficCondition = TrafficCondition.Moderato,
            )
        )
    }
}
