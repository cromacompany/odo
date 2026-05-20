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
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
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
    var detailTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    val selectedTrip = trips.firstOrNull { it.id == selectedTripId } ?: trips.firstOrNull()
    val detailTrip = trips.firstOrNull { it.id == detailTripId }
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

    val onStartMonitoring = {
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
    }
    val onStopMonitoring = {
        logger.event("Stop monitoraggio richiesto da UI")
        context.stopTrackingService()
        trackingStateStore.setMonitoring(false)
        logSizeLabel = logger.fileSizeLabel()
    }
    val onShareLog = {
        runCatching {
            logger.event("Condivisione log richiesta da UI")
            context.startActivity(Intent.createChooser(logger.shareIntent(), "Condividi log Odo"))
        }.onFailure { throwable ->
            logger.error("Errore condivisione log", throwable)
        }
        logSizeLabel = logger.fileSizeLabel()
    }
    val onClearLog = {
        logger.clear()
        logSizeLabel = logger.fileSizeLabel()
    }

    BackHandler(enabled = detailTrip != null || isSettingsOpen) {
        if (detailTrip != null) {
            detailTripId = null
        } else {
            isSettingsOpen = false
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (detailTrip != null) {
            RouteDetailScreen(
                trip = detailTrip,
                modifier = Modifier.padding(innerPadding),
                onBack = { detailTripId = null },
            )
            return@Scaffold
        }

        if (isSettingsOpen) {
            SettingsScreen(
                hasPermissions = hasPermissions,
                isMonitoring = isMonitoring,
                logSizeLabel = logSizeLabel,
                modifier = Modifier.padding(innerPadding),
                onBack = { isSettingsOpen = false },
                onStart = onStartMonitoring,
                onStop = onStopMonitoring,
                onShareLog = onShareLog,
                onClearLog = onClearLog,
            )
            return@Scaffold
        }

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
                HomeHeader(onOpenSettings = { isSettingsOpen = true })
            }

            if (!isMonitoring) {
                item {
                    ServiceClosedWarning()
                }
            }

            item {
                SelectedTripCard(
                    trip = selectedTrip,
                    onOpenMap = {
                        if (selectedTrip != null) detailTripId = selectedTrip.id
                    },
                )
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
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Odo",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TitleColor,
        )
        IconButton(onClick = onOpenSettings) {
            SettingsGearIcon()
        }
    }
}

@Composable
private fun ServiceClosedWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFFFE9D6),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Servizio spento: i viaggi non vengono registrati.",
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF8A3D00),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsScreen(
    hasPermissions: Boolean,
    isMonitoring: Boolean,
    logSizeLabel: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Settings",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TitleColor,
                )
                IconButton(onClick = onBack) {
                    BackArrowIcon()
                }
            }
        }
        item {
            Header(
                hasPermissions = hasPermissions,
                isMonitoring = isMonitoring,
                onStart = onStart,
                onStop = onStop,
            )
        }
        item {
            LogExportCard(
                fileSizeLabel = logSizeLabel,
                onShare = onShareLog,
                onClear = onClearLog,
            )
        }
    }
}

@Composable
private fun SettingsGearIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val color = TitleColor
        val stroke = 2.1.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = 6.5.dp.toPx()
        val toothStart = 8.8.dp.toPx()
        val toothEnd = 11.2.dp.toPx()

        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        drawCircle(color = color, radius = 2.4.dp.toPx(), center = center)
        drawLine(color, Offset(center.x, center.y - toothStart), Offset(center.x, center.y - toothEnd), stroke)
        drawLine(color, Offset(center.x, center.y + toothStart), Offset(center.x, center.y + toothEnd), stroke)
        drawLine(color, Offset(center.x - toothStart, center.y), Offset(center.x - toothEnd, center.y), stroke)
        drawLine(color, Offset(center.x + toothStart, center.y), Offset(center.x + toothEnd, center.y), stroke)
        drawLine(color, Offset(center.x - 6.2.dp.toPx(), center.y - 6.2.dp.toPx()), Offset(center.x - 7.9.dp.toPx(), center.y - 7.9.dp.toPx()), stroke)
        drawLine(color, Offset(center.x + 6.2.dp.toPx(), center.y - 6.2.dp.toPx()), Offset(center.x + 7.9.dp.toPx(), center.y - 7.9.dp.toPx()), stroke)
        drawLine(color, Offset(center.x - 6.2.dp.toPx(), center.y + 6.2.dp.toPx()), Offset(center.x - 7.9.dp.toPx(), center.y + 7.9.dp.toPx()), stroke)
        drawLine(color, Offset(center.x + 6.2.dp.toPx(), center.y + 6.2.dp.toPx()), Offset(center.x + 7.9.dp.toPx(), center.y + 7.9.dp.toPx()), stroke)
    }
}

@Composable
private fun BackArrowIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val color = TitleColor
        val stroke = 2.4.dp.toPx()
        val y = size.height / 2f
        drawLine(color, Offset(7.dp.toPx(), y), Offset(20.dp.toPx(), y), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(7.dp.toPx(), y), Offset(13.dp.toPx(), 6.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(7.dp.toPx(), y), Offset(13.dp.toPx(), 18.dp.toPx()), stroke, cap = StrokeCap.Round)
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
private fun SelectedTripCard(trip: Trip?, onOpenMap: () -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = trip != null, onClick = onOpenMap),
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
private fun RouteDetailScreen(trip: Trip, modifier: Modifier = Modifier, onBack: () -> Unit) {
    var selectedRoutePoint by remember(trip.id) { mutableStateOf(trip.routePointInfoAt(0)) }
    val lastPointIndex = (trip.points.size - 1).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Percorso", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TitleColor)
                Text(
                    text = "${formatDate(trip.startMillis)}, ${formatTime(trip.startMillis)} - ${formatTime(trip.endMillis)}, ${formatDuration(trip.durationMillis)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onBack) {
                BackArrowIcon()
            }
        }

        selectedRoutePoint?.let { pointInfo ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactRouteValue("Km", formatDistance(pointInfo.distanceMeters))
                    CompactRouteValue("Ora", formatTime(pointInfo.timestampMillis))
                    CompactRouteValue("Tempo", formatDuration(pointInfo.elapsedMillis))
                }
            }
        }

        RouteMap(
            points = trip.points,
            modifier = Modifier
                .fillMaxWidth()
            .weight(1f),
            interactive = true,
            selectedPoint = selectedRoutePoint?.point,
            onRoutePointSelected = { selectedRoutePoint = trip.routePointInfoNear(it) },
        )

        selectedRoutePoint?.let { pointInfo ->
            if (trip.points.size <= 1) return@let
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RouteEndpointDot(color = Color(0xFF1F8A4C))
                RouteProgressSlider(
                    value = pointInfo.index,
                    lastIndex = lastPointIndex,
                    onValueChange = { index -> selectedRoutePoint = trip.routePointInfoAt(index) },
                    modifier = Modifier.weight(1f),
                )
                RouteEndpointDot(color = Color(0xFFD62828))
            }
        }
    }
}

@Composable
private fun RouteProgressSlider(
    value: Int,
    lastIndex: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1) }

    fun indexAt(x: Float): Int {
        if (lastIndex <= 0) return 0
        val fraction = (x / widthPx).coerceIn(0f, 1f)
        return (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
    }

    Canvas(
        modifier = modifier
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(lastIndex) {
                detectTapGestures { offset -> onValueChange(indexAt(offset.x)) }
            }
            .pointerInput(lastIndex) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange(indexAt(change.position.x))
                }
            },
    ) {
        val fraction = if (lastIndex > 0) value.toFloat() / lastIndex else 0f
        val thumbCenterX = size.width * fraction.coerceIn(0f, 1f)
        val y = size.height / 2f
        val trackStroke = 14f
        val thumbWidth = 7.dp.toPx()
        val thumbHeight = 26.dp.toPx()
        val thumbLeft = (thumbCenterX - thumbWidth / 2f).coerceIn(0f, size.width - thumbWidth)
        val thumbTop = y - thumbHeight / 2f

        drawLine(
            color = Color(0xFFC8D1DA),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFF146C94),
            start = Offset(0f, y),
            end = Offset(thumbCenterX, y),
            strokeWidth = trackStroke,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = Color(0xFFF5B700),
            topLeft = Offset(thumbLeft, thumbTop),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(999f, 999f),
        )
    }
}

@Composable
private fun RouteEndpointDot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color, RoundedCornerShape(999.dp)),
    )
}

@Composable
private fun CompactRouteValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SecondaryTextColor)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = BodyColor)
    }
}

@Composable
private fun RouteMap(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1.7f),
    interactive: Boolean = false,
    selectedPoint: RoutePoint? = null,
    onRoutePointSelected: ((GeoPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    var isMapLoading by remember(points) { mutableStateOf(true) }
    var fittedTripId by remember { mutableStateOf<Int?>(null) }
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(SimpleRoadMapTileSource)
            setMultiTouchControls(interactive)
            setBuiltInZoomControls(false)
            isClickable = interactive
            isFocusable = interactive
            isFocusableInTouchMode = interactive
            if (!interactive) {
                setOnTouchListener { _, event -> event.action != MotionEvent.ACTION_CANCEL }
            }
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
        fittedTripId = null
        delay(900L)
        isMapLoading = false
    }

    val mapShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
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
                        Polyline(view).apply {
                            setPoints(geoPoints)
                            setOnClickListener { polyline, map, eventPos ->
                                onRoutePointSelected?.invoke(eventPos)
                                polyline.setInfoWindowLocation(eventPos)
                                map.invalidate()
                                true
                            }
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
                    selectedPoint?.let { point ->
                        view.overlays.add(
                            Marker(view).apply {
                                position = GeoPoint(point.latitude, point.longitude)
                                title = "Punto percorso"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = selectedMarkerDrawable()
                            }
                        )
                    }
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
                    val fitKey = points.hashCode()
                    if (fittedTripId != fitKey) {
                        view.post {
                            view.zoomToBoundingBox(geoPoints.boundingBox(), true, 80)
                            fittedTripId = fitKey
                        }
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

private fun selectedMarkerDrawable(): Drawable = routeMarkerDrawable(
    fillColor = AndroidColor.rgb(245, 183, 0),
    label = "i",
    size = 76,
    outerRadius = 36f,
    innerRadius = 27f,
    textSize = 28f,
    textColor = AndroidColor.rgb(24, 33, 44),
)

private fun routeMarkerDrawable(
    fillColor: Int,
    label: String,
    size: Int = 48,
    outerRadius: Float = 22f,
    innerRadius: Float = 16f,
    textSize: Float = 18f,
    textColor: Int = AndroidColor.WHITE,
): Drawable {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.style = Paint.Style.FILL
    paint.color = textColor
    canvas.drawCircle(size / 2f, size / 2f, outerRadius, paint)

    paint.color = fillColor
    canvas.drawCircle(size / 2f, size / 2f, innerRadius, paint)

    paint.color = AndroidColor.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = textSize
    paint.isFakeBoldText = true
    val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(label, size / 2f, textY, paint)

    return BitmapDrawable(null, bitmap)
}

private data class RoutePointInfo(
    val index: Int,
    val point: RoutePoint,
    val distanceMeters: Float,
    val timestampMillis: Long,
    val elapsedMillis: Long,
)

private fun Trip.routePointInfoNear(position: GeoPoint): RoutePointInfo? {
    if (points.isEmpty()) return null
    var distanceMeters = 0f
    var bestDistanceMeters = Float.MAX_VALUE
    var bestRouteDistanceMeters = 0f
    var bestIndex = 0
    var bestPoint = points.first()

    points.forEachIndexed { index, point ->
        if (index > 0) {
            distanceMeters += distanceBetween(points[index - 1], point)
        }
        val pointDistanceMeters = point.distanceTo(position)
        if (pointDistanceMeters < bestDistanceMeters) {
            bestDistanceMeters = pointDistanceMeters
            bestRouteDistanceMeters = distanceMeters
            bestIndex = index
            bestPoint = point
        }
    }

    return RoutePointInfo(
        index = bestIndex,
        point = bestPoint,
        distanceMeters = bestRouteDistanceMeters,
        timestampMillis = bestPoint.timestampMillis,
        elapsedMillis = (bestPoint.timestampMillis - startMillis).coerceAtLeast(0L),
    )
}

private fun Trip.routePointInfoAt(index: Int): RoutePointInfo? {
    if (points.isEmpty()) return null
    val pointIndex = index.coerceIn(points.indices)
    val distanceMeters = points
        .take(pointIndex + 1)
        .zipWithNext()
        .sumOf { (from, to) -> distanceBetween(from, to).toDouble() }
        .toFloat()
    val point = points[pointIndex]
    return RoutePointInfo(
        index = pointIndex,
        point = point,
        distanceMeters = distanceMeters,
        timestampMillis = point.timestampMillis,
        elapsedMillis = (point.timestampMillis - startMillis).coerceAtLeast(0L),
    )
}

private fun formatTime(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun formatDate(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ITALIAN)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun RoutePoint.distanceTo(position: GeoPoint): Float {
    val results = FloatArray(1)
    Location.distanceBetween(latitude, longitude, position.latitude, position.longitude, results)
    return results[0]
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
