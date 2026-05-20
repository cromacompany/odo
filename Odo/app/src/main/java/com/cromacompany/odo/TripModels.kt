package com.cromacompany.odo

import android.location.Location
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val speedMetersPerSecond: Float,
)

data class Trip(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val points: List<RoutePoint>,
    val trafficCondition: TrafficCondition,
) {
    val durationMillis: Long = (endMillis - startMillis).coerceAtLeast(0)
    val distanceMeters: Float = points.zipWithNext().sumOf { (from, to) ->
        distanceBetween(from, to).toDouble()
    }.toFloat()
}

enum class TrafficCondition(val label: String) {
    Scorrevole("Traffico scorrevole"),
    Moderato("Traffico moderato"),
    Intenso("Traffico intenso"),
}

fun formatDateTime(millis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ITALIAN)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatDistance(meters: Float): String {
    return if (meters >= 1_000f) {
        String.format(Locale.ITALIAN, "%.1f km", meters / 1_000f)
    } else {
        String.format(Locale.ITALIAN, "%.0f m", meters)
    }
}

fun estimateTraffic(points: List<RoutePoint>, distanceMeters: Float, durationMillis: Long): TrafficCondition {
    if (points.size < 2 || durationMillis <= 0L) return TrafficCondition.Scorrevole
    val durationHours = durationMillis / 3_600_000f
    val averageKmh = if (durationHours > 0f) distanceMeters / 1_000f / durationHours else 0f
    val slowSamples = points.count { it.speedMetersPerSecond in 0.5f..5.5f }
    val slowRatio = slowSamples.toFloat() / points.size
    return when {
        averageKmh < 18f || slowRatio > 0.45f -> TrafficCondition.Intenso
        averageKmh < 35f || slowRatio > 0.25f -> TrafficCondition.Moderato
        else -> TrafficCondition.Scorrevole
    }
}

fun distanceBetween(from: RoutePoint, to: RoutePoint): Float {
    val results = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
    return results[0]
}

fun bearingBetween(from: RoutePoint, to: RoutePoint): Float {
    val fromLat = Math.toRadians(from.latitude)
    val toLat = Math.toRadians(to.latitude)
    val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
    val y = sin(longitudeDelta) * cos(toLat)
    val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(longitudeDelta)
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

fun routeBounds(points: List<RoutePoint>): RouteBounds? {
    if (points.isEmpty()) return null
    return RouteBounds(
        minLatitude = points.minOf { it.latitude },
        maxLatitude = points.maxOf { it.latitude },
        minLongitude = points.minOf { it.longitude },
        maxLongitude = points.maxOf { it.longitude },
    )
}

data class RouteBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    val latitudeSpan: Double = (maxLatitude - minLatitude).takeIf { it > 0.00001 } ?: 0.00001
    val longitudeSpan: Double = (maxLongitude - minLongitude).takeIf { it > 0.00001 } ?: 0.00001
}

fun distanceFromLast(points: List<RoutePoint>, point: RoutePoint): Float {
    val last = points.lastOrNull() ?: return Float.MAX_VALUE
    return distanceBetween(last, point)
}
