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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}


private const val ThemePreferencesName = "odo_theme_preferences"
private const val ThemeModeKey = "theme_mode"

private val TitleColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val BodyColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
private val SecondaryTextColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
private val AppBackgroundColor: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val AppSurfaceColor: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val AppMetricColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF222D38) else Color(0xFFF1F4F7)
private val SelectedTripColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF173142) else Color(0xFFE4F1F8)
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
        EventLogger.get(this).event("MainActivity created")
        setContent {
            val context = LocalContext.current
            var themeMode by rememberSaveable { mutableStateOf(context.readThemeMode()) }
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            SideEffect {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            OdoTheme(darkTheme = darkTheme) {
                OdoApp(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        context.writeThemeMode(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun OdoApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val logger = remember { EventLogger.get(context) }
    val repository = remember { TripRepositoryProvider.get(context) }
    val trackingStateStore = remember { TrackingStateStore.get(context) }
    val appVersionLabel = remember { context.appVersionLabel() }
    val trips by repository.trips.collectAsState()
    val trackingSnapshot by trackingStateStore.snapshot.collectAsState()
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val isMonitoring = trackingSnapshot.isServiceAlive(nowMillis)
    var logSizeLabel by remember { mutableStateOf(logger.fileSizeLabel()) }
    var selectedTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var mapTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isHistoryOpen by rememberSaveable { mutableStateOf(false) }
    val selectedTrip = trips.firstOrNull { it.id == selectedTripId } ?: trips.firstOrNull()
    val detailTrip = trips.firstOrNull { it.id == detailTripId }
    val mapTrip = trips.firstOrNull { it.id == mapTripId }
    val permissions = remember { requiredPermissions() }
    var hasPermissions by remember { mutableStateOf(context.hasRequiredPermissions(permissions)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasPermissions = results.values.all { it }
        logger.event("Permission request result", results)
        if (hasPermissions) {
            context.requestBatteryOptimizationExemption(logger)
            context.startTrackingService()
            trackingStateStore.setMonitoring(true)
        } else {
            logger.error("Permissions denied: monitoring not started", details = results)
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
            logger.event("Monitoring start requested from UI")
            context.requestBatteryOptimizationExemption(logger)
            context.startTrackingService()
            trackingStateStore.setMonitoring(true)
        } else {
            logger.event("Permission request from UI")
            permissionLauncher.launch(permissions)
        }
        logSizeLabel = logger.fileSizeLabel()
    }
    val onStopMonitoring = {
        logger.event("Monitoring stop requested from UI")
        context.stopTrackingService()
        trackingStateStore.setMonitoring(false)
        logSizeLabel = logger.fileSizeLabel()
    }
    val onShareLog = {
        runCatching {
            logger.event("Log sharing requested from UI")
            context.startActivity(Intent.createChooser(logger.shareIntent(), "Share Odo log"))
        }.onFailure { throwable ->
            logger.error("Log sharing error", throwable)
        }
        logSizeLabel = logger.fileSizeLabel()
    }
    val onClearLog = {
        logger.clear()
        logSizeLabel = logger.fileSizeLabel()
    }

    BackHandler(enabled = mapTrip != null || detailTrip != null || isSettingsOpen || isHistoryOpen) {
        if (mapTrip != null) {
            mapTripId = null
        } else if (detailTrip != null) {
            detailTripId = null
        } else if (isHistoryOpen) {
            isHistoryOpen = false
        } else {
            isSettingsOpen = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = AppBackgroundColor,
    ) { innerPadding ->
        if (mapTrip != null) {
            RouteMapScreen(
                trip = mapTrip,
                modifier = Modifier.padding(innerPadding),
                onBack = { mapTripId = null },
            )
            return@Scaffold
        }

        if (detailTrip != null) {
            TripDetailScreen(
                trip = detailTrip,
                modifier = Modifier.padding(innerPadding),
                onBack = { detailTripId = null },
                onOpenMap = { mapTripId = detailTrip.id },
            )
            return@Scaffold
        }

        if (isSettingsOpen) {
            SettingsScreen(
                hasPermissions = hasPermissions,
                isMonitoring = isMonitoring,
                logSizeLabel = logSizeLabel,
                appVersionLabel = appVersionLabel,
                themeMode = themeMode,
                modifier = Modifier.padding(innerPadding),
                onBack = { isSettingsOpen = false },
                onStart = onStartMonitoring,
                onStop = onStopMonitoring,
                onShareLog = onShareLog,
                onClearLog = onClearLog,
                onThemeModeChange = onThemeModeChange,
            )
            return@Scaffold
        }

        if (isHistoryOpen) {
            TripHistoryScreen(
                trips = trips,
                modifier = Modifier.padding(innerPadding),
                onBack = { isHistoryOpen = false },
                onOpenTrip = { trip ->
                    selectedTripId = trip.id
                    detailTripId = trip.id
                },
                onDeleteTrip = { trip ->
                    repository.deleteTrip(trip.id)
                    if (selectedTripId == trip.id) selectedTripId = null
                    if (detailTripId == trip.id) detailTripId = null
                    if (mapTripId == trip.id) mapTripId = null
                },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackgroundColor)
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
                        if (selectedTrip != null) mapTripId = selectedTrip.id
                    },
                )
            }

            item {
                HistorySectionHeader(
                    showArchive = trips.isNotEmpty(),
                    onOpenArchive = { isHistoryOpen = true },
                )
            }

            if (trips.isEmpty()) {
                item {
                    EmptyHistory()
                }
            } else {
                items(trips.take(3), key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        isSelected = trip.id == selectedTrip?.id,
                        onClick = {
                            selectedTripId = trip.id
                            detailTripId = trip.id
                        },
                        onDelete = {
                            repository.deleteTrip(trip.id)
                            if (selectedTripId == trip.id) selectedTripId = null
                            if (detailTripId == trip.id) detailTripId = null
                            if (mapTripId == trip.id) mapTripId = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySectionHeader(showArchive: Boolean, onOpenArchive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Trip history",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TitleColor,
        )
        if (showArchive) {
            TextButton(onClick = onOpenArchive) {
                Text("View all")
            }
        }
    }
}

@Composable
private fun TripHistoryScreen(
    trips: List<Trip>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenTrip: (Trip) -> Unit,
    onDeleteTrip: (Trip) -> Unit,
) {
    val context = LocalContext.current
    val logger = remember { EventLogger.get(context) }
    var filterStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterEndMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilterDialog by rememberSaveable { mutableStateOf(false) }
    val filteredTrips = remember(trips, filterStartMillis, filterEndMillis) {
        if (filterStartMillis == null || filterEndMillis == null) trips else trips.filterByDateRange(filterStartMillis ?: 0L, filterEndMillis ?: 0L)
    }
    val tripsByDate = remember(filteredTrips) { filteredTrips.groupBy { formatDate(it.startMillis) } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundColor)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trip archive",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TitleColor,
                    )
                    Text(
                        text = "${filteredTrips.size} of ${trips.size} trips",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryTextColor,
                    )
                }
                IconButton(onClick = onBack) {
                    BackArrowIcon()
                }
            }
        }

        item {
            ArchiveFilterBar(
                filterStartMillis = filterStartMillis,
                filterEndMillis = filterEndMillis,
                trips = trips,
                canExport = filteredTrips.isNotEmpty(),
                onOpenFilter = { showFilterDialog = true },
                onExport = { context.shareTripsCsv(filteredTrips, filterStartMillis, filterEndMillis, logger) },
            )
        }

        if (filteredTrips.isEmpty()) {
            item {
                EmptyHistory()
            }
        } else {
            tripsByDate.forEach { (dateLabel, dateTrips) ->
                item(key = "date-$dateLabel") {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondaryTextColor,
                    )
                }
                items(dateTrips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        isSelected = false,
                        onClick = { onOpenTrip(trip) },
                        onDelete = { onDeleteTrip(trip) },
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        TripArchiveFilterDialog(
            initialStartMillis = filterStartMillis ?: trips.minOfOrNull { it.startMillis } ?: currentMonthStartMillis(),
            initialEndMillis = filterEndMillis ?: trips.maxOfOrNull { it.startMillis } ?: currentMonthEndMillis(),
            onDismiss = { showFilterDialog = false },
            onApply = { startMillis, endMillis ->
                filterStartMillis = startMillis
                filterEndMillis = endMillis
                showFilterDialog = false
            },
        )
    }
}

@Composable
private fun ArchiveFilterBar(
    filterStartMillis: Long?,
    filterEndMillis: Long?,
    trips: List<Trip>,
    canExport: Boolean,
    onOpenFilter: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = archiveDateRangeLabel(filterStartMillis, filterEndMillis, trips),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BodyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onOpenFilter) {
                    FilterIcon()
                }
                IconButton(enabled = canExport, onClick = onExport) {
                    ExportIcon(enabled = canExport)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripArchiveFilterDialog(
    initialStartMillis: Long,
    initialEndMillis: Long,
    onDismiss: () -> Unit,
    onApply: (Long, Long) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMillis,
        initialSelectedEndDateMillis = initialEndMillis,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null,
                onClick = {
                    val startMillis = pickerState.selectedStartDateMillis ?: return@TextButton
                    val endMillis = pickerState.selectedEndDateMillis ?: startMillis
                    onApply(startMillis, endMillis)
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            title = {
                Text(
                    text = "Filter trips",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            headline = {
                Text(
                    text = "Select start and end",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextColor,
                )
            },
        )
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
            text = "Service off: trips are not being recorded.",
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
    appVersionLabel: String,
    themeMode: ThemeMode,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShareLog: () -> Unit,
    onClearLog: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundColor)
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
            ThemeModeCard(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
            )
        }
        item {
            LogExportCard(
                fileSizeLabel = logSizeLabel,
                onShare = onShareLog,
                onClear = onClearLog,
            )
        }
        item {
            AppInfoCard(versionLabel = appVersionLabel)
        }
    }
}

@Composable
private fun SettingsGearIcon() {
    val color = TitleColor
    Canvas(modifier = Modifier.size(24.dp)) {
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
    val color = TitleColor
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 2.4.dp.toPx()
        val y = size.height / 2f
        drawLine(color, Offset(7.dp.toPx(), y), Offset(20.dp.toPx(), y), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(7.dp.toPx(), y), Offset(13.dp.toPx(), 6.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(7.dp.toPx(), y), Offset(13.dp.toPx(), 18.dp.toPx()), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun FilterIcon() {
    val color = TitleColor
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 2.2.dp.toPx()
        val topY = 6.dp.toPx()
        val middleY = 12.dp.toPx()
        val bottomY = 18.dp.toPx()
        drawLine(color, Offset(5.dp.toPx(), topY), Offset(19.dp.toPx(), topY), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(8.dp.toPx(), middleY), Offset(16.dp.toPx(), middleY), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(11.dp.toPx(), bottomY), Offset(13.dp.toPx(), bottomY), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ExportIcon(enabled: Boolean) {
    val color = if (enabled) TitleColor else SecondaryTextColor.copy(alpha = 0.45f)
    Canvas(modifier = Modifier.size(24.dp)) {
        val stroke = 2.2.dp.toPx()
        val centerX = size.width / 2f
        drawLine(color, Offset(centerX, 5.dp.toPx()), Offset(centerX, 15.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(centerX, 5.dp.toPx()), Offset(8.dp.toPx(), 9.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(centerX, 5.dp.toPx()), Offset(16.dp.toPx(), 9.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(6.dp.toPx(), 17.dp.toPx()), Offset(6.dp.toPx(), 20.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(6.dp.toPx(), 20.dp.toPx()), Offset(18.dp.toPx(), 20.dp.toPx()), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(18.dp.toPx(), 20.dp.toPx()), Offset(18.dp.toPx(), 17.dp.toPx()), stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ThemeModeCard(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val followsSystem = themeMode == ThemeMode.System
    val darkEnabled = themeMode == ThemeMode.Dark

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppMetricColor, RoundedCornerShape(8.dp))
                    .clickable { onThemeModeChange(if (followsSystem) ThemeMode.Light else ThemeMode.System) }
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use phone theme", color = TitleColor, fontWeight = FontWeight.SemiBold)
                    Text("Follows the device light/dark setting", color = SecondaryTextColor, style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(
                    checked = followsSystem,
                    onCheckedChange = { checked ->
                        onThemeModeChange(if (checked) ThemeMode.System else ThemeMode.Light)
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (followsSystem) AppMetricColor.copy(alpha = 0.55f) else AppMetricColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dark mode", color = if (followsSystem) SecondaryTextColor else TitleColor, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (followsSystem) "Disabled by system theme" else if (darkEnabled) "Dark theme active" else "Light theme active",
                        color = SecondaryTextColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = darkEnabled,
                    enabled = !followsSystem,
                    onCheckedChange = { checked ->
                        onThemeModeChange(if (checked) ThemeMode.Dark else ThemeMode.Light)
                    },
                )
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
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Event log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
                    Text(
                        text = "Exportable .txt file • $fileSizeLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecondaryTextColor,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onShare) {
                    Text("Share .txt")
                }
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun AppInfoCard(versionLabel: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "App version",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TitleColor,
            )
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SecondaryTextColor,
            )
        }
    }
}

private fun Context.appVersionLabel(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    val versionName = packageInfo.versionName ?: "unknown"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return "$versionName ($versionCode)"
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
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Odo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TitleColor)
                    Text(
                        text = if (isMonitoring) "Ready to automatically record car trips" else "Monitoring is not active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryTextColor,
                    )
                }
                StatusPill(isMonitoring)
            }
            if (!hasPermissions) {
                Text(
                    text = "Location and notifications are required to record routes while you drive.",
                    color = Color(0xFF9A5B00),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = !isMonitoring) {
                    Text(if (hasPermissions) "Start monitoring" else "Allow and start")
                }
                TextButton(onClick = onStop, enabled = isMonitoring) {
                    Text("Stop")
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
            text = if (isMonitoring) "Active" else "Off",
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SelectedTripCard(trip: Trip?, onOpenMap: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppSurfaceColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
            RouteMap(
                points = trip?.points.orEmpty(),
                onMapClick = if (trip != null) onOpenMap else null,
            )
            if (trip == null) {
                Text("No trips recorded.", color = SecondaryTextColor)
            } else {
                TripMetrics(trip)
            }
        }
    }
}

@Composable
private fun TripDetailScreen(
    trip: Trip,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundColor)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trip details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TitleColor)
                    Text(
                        text = "${formatDate(trip.startMillis)}, ${formatTime(trip.startMillis)} - ${formatTime(trip.endMillis)}",
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
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppSurfaceColor,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
                    RouteMap(points = trip.points, onMapClick = onOpenMap)
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppSurfaceColor,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Trip data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TitleColor)
                    TripMetrics(trip)
                }
            }
        }
    }
}

@Composable
private fun RouteMapScreen(trip: Trip, modifier: Modifier = Modifier, onBack: () -> Unit) {
    var selectedRoutePoint by remember(trip.id) { mutableStateOf(trip.routePointInfoAt(0)) }
    val lastPointIndex = (trip.points.size - 1).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackgroundColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Route", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TitleColor)
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
                color = AppSurfaceColor,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactRouteValue("Km", formatDistance(pointInfo.distanceMeters))
                    CompactRouteValue("Time", formatTime(pointInfo.timestampMillis))
                    CompactRouteValue("Elapsed", formatDuration(pointInfo.elapsedMillis))
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
    onMapClick: (() -> Unit)? = null,
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
                if (interactive) {
                    view.setOnTouchListener(null)
                } else {
                    view.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            onMapClick?.invoke()
                        }
                        event.action != MotionEvent.ACTION_CANCEL
                    }
                }
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
                                title = "Route point"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = selectedMarkerDrawable()
                            }
                        )
                    }
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.first()
                            title = "Start"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = startMarkerDrawable()
                        }
                    )
                    view.overlays.add(
                        Marker(view).apply {
                            position = geoPoints.last()
                            title = "Finish"
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
            text = "Loading map",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(AppSurfaceColor.copy(alpha = 0.86f), RoundedCornerShape(8.dp))
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
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun formatDate(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

private fun archiveDateRangeLabel(startMillis: Long?, endMillis: Long?, trips: List<Trip> = emptyList()): String {
    if (startMillis == null || endMillis == null) {
        if (trips.isEmpty()) return "All trips"
        return dateRangeLabel(
            startMillis = trips.minOf { it.startMillis },
            endMillis = trips.maxOf { it.startMillis },
        )
    }
    return dateRangeLabel(startMillis, endMillis)
}

private fun dateRangeLabel(startMillis: Long, endMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate().format(formatter)
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate().format(formatter)
    return "$start - $end"
}

private fun currentMonthStartMillis(): Long {
    val zone = ZoneId.systemDefault()
    return YearMonth.now(zone).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

private fun currentMonthEndMillis(): Long {
    val zone = ZoneId.systemDefault()
    return YearMonth.now(zone).atEndOfMonth().atStartOfDay(zone).toInstant().toEpochMilli()
}

private fun List<Trip>.filterByDateRange(startMillis: Long, endMillis: Long): List<Trip> {
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
    val rangeStartMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val rangeEndMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return filter { trip -> trip.startMillis >= rangeStartMillis && trip.startMillis < rangeEndMillis }
}

private fun RoutePoint.distanceTo(position: GeoPoint): Float {
    val results = FloatArray(1)
    Location.distanceBetween(latitude, longitude, position.latitude, position.longitude, results)
    return results[0]
}

@Composable
private fun TripMetrics(trip: Trip) {
    val context = LocalContext.current
    var startPlace by remember(trip.id) { mutableStateOf("Detecting place...") }
    var endPlace by remember(trip.id) { mutableStateOf("Detecting place...") }

    LaunchedEffect(trip.id, trip.points) {
        val startPoint = trip.points.firstOrNull()
        val endPoint = trip.points.lastOrNull()
        startPlace = startPoint?.let { context.resolvePlaceLabel(it) } ?: "Place unavailable"
        endPlace = endPoint?.let { context.resolvePlaceLabel(it) } ?: "Place unavailable"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Start", formatDateTime(trip.startMillis), Modifier.weight(1f), detail = startPlace)
            Metric("Finish", formatDateTime(trip.endMillis), Modifier.weight(1f), detail = endPlace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("Duration", formatDuration(trip.durationMillis), Modifier.weight(1f))
            Metric("Distance", formatDistance(trip.distanceMeters), Modifier.weight(1f))
        }
        Metric("Traffic", trip.trafficCondition.label, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .background(AppMetricColor, RoundedCornerShape(8.dp))
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
        val address = Geocoder(this@resolvePlaceLabel, Locale.ENGLISH)
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
    return String.format(Locale.ENGLISH, "%.5f, %.5f", latitude, longitude)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripRow(trip: Trip, isSelected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirmation by remember(trip.id) { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                showDeleteConfirmation = true
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete trip?") },
            text = { Text("This trip will be removed from history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFB42318))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = if (isSelected) SelectedTripColor else AppSurfaceColor),
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
    Surface(color = AppSurfaceColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Start monitoring. The first trip will appear here after at least one minute of driving and a stop of about three minutes.",
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

private fun Context.readThemeMode(): ThemeMode {
    val name = getSharedPreferences(ThemePreferencesName, Context.MODE_PRIVATE)
        .getString(ThemeModeKey, ThemeMode.System.name)
    return ThemeMode.values().firstOrNull { it.name == name } ?: ThemeMode.System
}

private fun Context.writeThemeMode(mode: ThemeMode) {
    getSharedPreferences(ThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(ThemeModeKey, mode.name)
        .apply()
}

private fun Context.hasRequiredPermissions(permissions: Array<String>): Boolean {
    return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

private fun Context.requestBatteryOptimizationExemption(logger: EventLogger) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
        logger.event("Battery optimization exemption already granted")
        return
    }
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:$packageName"))
    runCatching {
        if (intent.resolveActivity(packageManager) != null) {
            logger.event("Battery optimization exemption requested")
            startActivity(intent)
        } else {
            logger.error("Battery optimization exemption request unavailable")
        }
    }.onFailure { throwable ->
        logger.error("Battery optimization exemption request error", throwable)
    }
}

private fun Context.shareTripsCsv(
    trips: List<Trip>,
    filterStartMillis: Long?,
    filterEndMillis: Long?,
    logger: EventLogger,
) {
    if (trips.isEmpty()) return
    runCatching {
        val directory = File(filesDir, "exports")
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, "odo-trips-${trips.exportFileDateRange()}.csv")
        file.writeText(trips.toCsv(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val title = "Odo trips - ${archiveDateRangeLabel(filterStartMillis, filterEndMillis, trips)}"
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, title)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        logger.event("Trip CSV export requested", mapOf("trips" to trips.size, "range" to archiveDateRangeLabel(filterStartMillis, filterEndMillis, trips)))
        startActivity(Intent.createChooser(intent, "Export trips CSV"))
    }.onFailure { throwable ->
        logger.error("Trip CSV export error", throwable)
    }
}

private fun List<Trip>.toCsv(): String {
    val trips = this
    return buildString {
        appendCsvRow(
            listOf(
                "id",
                "start",
                "end",
                "duration_minutes",
                "distance_meters",
                "traffic",
                "points",
                "start_latitude",
                "start_longitude",
                "end_latitude",
                "end_longitude",
            ),
        )
        for (trip in trips) {
            val startPoint = trip.points.firstOrNull()
            val endPoint = trip.points.lastOrNull()
            appendCsvRow(
                listOf(
                    trip.id,
                    isoDateTime(trip.startMillis),
                    isoDateTime(trip.endMillis),
                    String.format(Locale.ENGLISH, "%.1f", trip.durationMillis / 60_000f),
                    String.format(Locale.ENGLISH, "%.1f", trip.distanceMeters),
                    trip.trafficCondition.label,
                    trip.points.size.toString(),
                    startPoint?.latitude?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                    startPoint?.longitude?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                    endPoint?.latitude?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                    endPoint?.longitude?.let { String.format(Locale.ENGLISH, "%.6f", it) }.orEmpty(),
                ),
            )
        }
    }
}

private fun List<Trip>.exportFileDateRange(): String {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH)
    val firstDate = minOf { it.startMillis }.let { Instant.ofEpochMilli(it).atZone(zone).format(formatter) }
    val lastDate = maxOf { it.startMillis }.let { Instant.ofEpochMilli(it).atZone(zone).format(formatter) }
    return "$firstDate-$lastDate"
}

private fun StringBuilder.appendCsvRow(values: List<String>) {
    append(values.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" })
    append('\n')
}

private fun isoDateTime(millis: Long): String {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
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
