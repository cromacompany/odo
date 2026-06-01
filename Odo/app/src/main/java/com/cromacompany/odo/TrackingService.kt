package com.cromacompany.odo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit
import java.util.UUID

class TrackingService : Service() {
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var repository: TripRepository
    private lateinit var logger: EventLogger
    private lateinit var trackingStateStore: TrackingStateStore
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentTripStartMillis: Long = 0L
    private val currentPoints = mutableListOf<RoutePoint>()
    private var lastAcceptedLocation: AcceptedLocation? = null
    private var movingSinceMillis: Long? = null
    private var stoppedSinceMillis: Long? = null
    private var isRecording = false
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val stopConfirmationRunnable = Runnable {
        val stoppedAt = stoppedSinceMillis ?: return@Runnable
        if (isRecording) {
            logger.event("Automatic stop for parked vehicle", mapOf("stoppedAtMillis" to stoppedAt))
            finishTripIfNeeded(stoppedAt)
        }
    }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            trackingStateStore.recordHeartbeat()
            acquireWakeLock()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MILLIS)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handleLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger = EventLogger.get(this)
        logger.event("TrackingService created")
        repository = TripRepositoryProvider.get(this)
        trackingStateStore = TrackingStateStore.get(this)
        trackingStateStore.setMonitoring(true)
        trackingStateStore.recordHeartbeat()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Automatic monitoring active"))
        acquireWakeLock()
        startHeartbeat()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                logger.event("Manual stop from service")
                finishTripIfNeeded(stoppedSinceMillis ?: System.currentTimeMillis())
                trackingStateStore.setMonitoring(false)
                stopSelf()
            }
            else -> logger.event("TrackingService started", mapOf("startId" to startId))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logger.event("TrackingService destroyed", mapOf("isRecording" to isRecording, "points" to currentPoints.size))
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.removeCallbacks(stopConfirmationRunnable)
        trackingStateStore.setMonitoring(false)
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatRunnable.run()
    }

    private fun acquireWakeLock() {
        val existingWakeLock = wakeLock
        if (existingWakeLock?.isHeld == true) {
            existingWakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:TrackingService")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
        logger.event("Tracking wake lock acquired")
    }

    private fun releaseWakeLock() {
        val heldWakeLock = wakeLock?.takeIf { it.isHeld } ?: return
        runCatching { heldWakeLock.release() }
            .onSuccess { logger.event("Tracking wake lock released") }
            .onFailure { throwable -> logger.error("Tracking wake lock release error", throwable) }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            logger.error("Location permissions missing: unable to start GPS updates")
            trackingStateStore.setMonitoring(false)
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MILLIS)
            .build()
        runCatching {
            fusedLocationProviderClient.requestLocationUpdates(request, locationCallback, mainLooper)
                .addOnSuccessListener {
                    logger.event(
                        "GPS updates started",
                        mapOf(
                            "intervalMillis" to LOCATION_INTERVAL_MILLIS,
                            "minDistanceMeters" to MIN_DISTANCE_METERS,
                        ),
                    )
                }
                .addOnFailureListener { throwable ->
                    logger.error("GPS update startup error", throwable)
                    trackingStateStore.setMonitoring(false)
                    stopSelf()
                }
        }.onFailure { throwable ->
            logger.error("GPS update request exception", throwable)
            trackingStateStore.setMonitoring(false)
            stopSelf()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleLocation(location: Location) {
        val now = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        if (!isUsableLocation(location)) {
            logger.event(
                "Location ignored due to poor quality",
                mapOf(
                    "accuracyMeters" to if (location.hasAccuracy()) location.accuracy else null,
                    "ageMillis" to locationAgeMillis(location),
                    "maxAccuracyMeters" to MAX_ACCURACY_METERS,
                    "maxAgeMillis" to MAX_LOCATION_AGE_MILLIS,
                ),
            )
            return
        }

        val previousAcceptedLocation = lastAcceptedLocation
        val candidatePoint = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestampMillis = now,
            speedMetersPerSecond = 0f,
        )
        if (previousAcceptedLocation != null && !isPlausibleTransition(previousAcceptedLocation, candidatePoint, location.accuracy)) {
            logger.event(
                "Location ignored due to impossible jump",
                mapOf(
                    "distanceMeters" to distanceBetween(previousAcceptedLocation.point, candidatePoint),
                    "elapsedMillis" to (now - previousAcceptedLocation.point.timestampMillis),
                    "accuracyMeters" to location.accuracy,
                ),
            )
            return
        }

        handleRouteGapIfNeeded(candidatePoint)

        val speedMetersPerSecond = speedFor(location, now, previousAcceptedLocation)
        val isDrivingSpeed = speedMetersPerSecond >= START_SPEED_METERS_PER_SECOND
        val acceptedPoint = candidatePoint.copy(speedMetersPerSecond = speedMetersPerSecond)
        lastAcceptedLocation = AcceptedLocation(acceptedPoint, location.accuracy)

        if (!isRecording) {
            if (isDrivingSpeed) {
                movingSinceMillis = movingSinceMillis ?: now
                if (now - (movingSinceMillis ?: now) >= START_CONFIRMATION_MILLIS) {
                    startTrip(now)
                }
            } else {
                movingSinceMillis = null
            }
        }

        if (isRecording) {
            if (currentPoints.isEmpty() || distanceFromLast(currentPoints, acceptedPoint) >= MIN_POINT_DISTANCE_METERS) {
                currentPoints.add(acceptedPoint)
            }

            if (speedMetersPerSecond <= STOP_SPEED_METERS_PER_SECOND) {
                if (stoppedSinceMillis == null) {
                    stoppedSinceMillis = now
                    scheduleStopConfirmation(now)
                }
                val stoppedAt = stoppedSinceMillis ?: now
                if (now - stoppedAt >= STOP_CONFIRMATION_MILLIS) {
                    logger.event("Automatic stop for parked vehicle", mapOf("stoppedAtMillis" to stoppedAt))
                    finishTripIfNeeded(stoppedAt)
                }
            } else {
                stoppedSinceMillis = null
                heartbeatHandler.removeCallbacks(stopConfirmationRunnable)
            }

            updateNotification()
        }
    }

    private fun isUsableLocation(location: Location): Boolean {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > MAX_ACCURACY_METERS) return false
        val ageMillis = locationAgeMillis(location)
        if (ageMillis != null && ageMillis > MAX_LOCATION_AGE_MILLIS) return false
        return true
    }

    private fun locationAgeMillis(location: Location): Long? {
        val elapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: return null
        return SystemClock.elapsedRealtime() - TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos)
    }

    private fun speedFor(location: Location, now: Long, previousAcceptedLocation: AcceptedLocation?): Float {
        val hasReliableSpeed = location.hasSpeed() &&
            location.speed in 0f..MAX_REASONABLE_SPEED_METERS_PER_SECOND &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !location.hasSpeedAccuracy() ||
                location.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_METERS_PER_SECOND)
        if (hasReliableSpeed) {
            return location.speed
        }
        val previous = previousAcceptedLocation?.point ?: return 0f
        val elapsedSeconds = ((now - previous.timestampMillis) / 1_000f).takeIf { it > 0f } ?: return 0f
        return (distanceBetween(previous, RoutePoint(location.latitude, location.longitude, now, 0f)) / elapsedSeconds)
            .coerceIn(0f, MAX_REASONABLE_SPEED_METERS_PER_SECOND)
    }

    private fun isPlausibleTransition(previous: AcceptedLocation, point: RoutePoint, accuracyMeters: Float): Boolean {
        val elapsedSeconds = ((point.timestampMillis - previous.point.timestampMillis) / 1_000f).takeIf { it > 0f }
            ?: return false
        val distanceMeters = distanceBetween(previous.point, point)
        val allowedDistance = MAX_REASONABLE_SPEED_METERS_PER_SECOND * elapsedSeconds +
            previous.accuracyMeters +
            accuracyMeters +
            GPS_NOISE_ALLOWANCE_METERS
        return distanceMeters <= allowedDistance
    }

    private fun handleRouteGapIfNeeded(point: RoutePoint) {
        if (!isRecording || currentPoints.isEmpty()) return
        val previous = currentPoints.last()
        val elapsedMillis = point.timestampMillis - previous.timestampMillis
        val distanceMeters = distanceBetween(previous, point)
        if (elapsedMillis > MAX_ROUTE_GAP_MILLIS && distanceMeters > MAX_CONTINUOUS_ROUTE_GAP_DISTANCE_METERS) {
            logger.event(
                "Route split due to long GPS gap",
                mapOf("elapsedMillis" to elapsedMillis, "distanceMeters" to distanceMeters),
            )
            finishTripIfNeeded(previous.timestampMillis)
            movingSinceMillis = point.timestampMillis - START_CONFIRMATION_MILLIS
        }
    }

    private fun scheduleStopConfirmation(stoppedAtMillis: Long) {
        heartbeatHandler.removeCallbacks(stopConfirmationRunnable)
        val delayMillis = (stoppedAtMillis + STOP_CONFIRMATION_MILLIS - System.currentTimeMillis())
            .coerceAtLeast(0L)
        heartbeatHandler.postDelayed(stopConfirmationRunnable, delayMillis)
    }

    private fun startTrip(startMillis: Long) {
        isRecording = true
        currentTripStartMillis = startMillis
        currentPoints.clear()
        stoppedSinceMillis = null
        logger.event("Trip started", mapOf("startMillis" to startMillis))
        updateNotification()
    }

    private fun finishTripIfNeeded(endMillis: Long) {
        if (!isRecording) return
        heartbeatHandler.removeCallbacks(stopConfirmationRunnable)
        val points = currentPoints.filter { it.timestampMillis <= endMillis }
        if (points.size >= MIN_TRIP_POINTS && endMillis - currentTripStartMillis >= MIN_TRIP_DURATION_MILLIS) {
            val distanceMeters = points.zipWithNext().sumOf { (from, to) -> distanceBetween(from, to).toDouble() }.toFloat()
            val trafficCondition = estimateTraffic(points, distanceMeters, endMillis - currentTripStartMillis)
            val trip = Trip(
                id = UUID.randomUUID().toString(),
                startMillis = currentTripStartMillis,
                endMillis = endMillis,
                points = points,
                trafficCondition = trafficCondition,
            )
            repository.addTrip(trip)
            logger.event(
                "Trip saved",
                mapOf(
                    "tripId" to trip.id,
                    "durationMillis" to trip.durationMillis,
                    "distanceMeters" to trip.distanceMeters,
                    "points" to points.size,
                    "traffic" to trafficCondition.label,
                ),
            )
        } else {
            logger.event(
                "Trip discarded due to insufficient data",
                mapOf(
                    "durationMillis" to (endMillis - currentTripStartMillis),
                    "points" to points.size,
                    "minDurationMillis" to MIN_TRIP_DURATION_MILLIS,
                    "minPoints" to MIN_TRIP_POINTS,
                ),
            )
        }
        isRecording = false
        currentTripStartMillis = 0L
        currentPoints.clear()
        movingSinceMillis = null
        stoppedSinceMillis = null
        updateNotification()
    }

    private fun updateNotification() {
        val status = if (isRecording) {
            "Recording trip: ${formatDuration(System.currentTimeMillis() - currentTripStartMillis)}"
        } else {
            "Automatic monitoring active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Odo")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trip monitoring",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "com.cromacompany.odo.STOP_TRACKING"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MILLIS = 5_000L
        private const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
        private const val FASTEST_LOCATION_INTERVAL_MILLIS = 2_000L
        private const val START_CONFIRMATION_MILLIS = 20_000L
        private const val STOP_CONFIRMATION_MILLIS = 180_000L
        private const val MIN_TRIP_DURATION_MILLIS = 60_000L
        private const val MIN_DISTANCE_METERS = 10f
        private const val MIN_POINT_DISTANCE_METERS = 15f
        private const val MAX_ACCURACY_METERS = 50f
        private const val MAX_LOCATION_AGE_MILLIS = 45_000L
        private const val MAX_REASONABLE_SPEED_METERS_PER_SECOND = 60f
        private const val MAX_SPEED_ACCURACY_METERS_PER_SECOND = 8f
        private const val GPS_NOISE_ALLOWANCE_METERS = 80f
        private const val MAX_ROUTE_GAP_MILLIS = 60_000L
        private const val MAX_CONTINUOUS_ROUTE_GAP_DISTANCE_METERS = 700f
        private const val START_SPEED_METERS_PER_SECOND = 8.3f
        private const val STOP_SPEED_METERS_PER_SECOND = 1.4f
        private const val MIN_TRIP_POINTS = 3
    }

    private data class AcceptedLocation(
        val point: RoutePoint,
        val accuracyMeters: Float,
    )
}
