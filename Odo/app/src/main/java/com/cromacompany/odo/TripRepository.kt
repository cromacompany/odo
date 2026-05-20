package com.cromacompany.odo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class TripRepository(context: Context) {
    private val preferences = context.getSharedPreferences("odo_trips", Context.MODE_PRIVATE)
    private val _trips = MutableStateFlow(loadTrips())

    val trips: StateFlow<List<Trip>> = _trips

    fun addTrip(trip: Trip) {
        val updated = (listOf(trip) + _trips.value).take(200)
        _trips.value = updated
        preferences.edit().putString(KEY_TRIPS, updated.tripsToJson().toString()).apply()
    }

    fun replaceTrips(trips: List<Trip>) {
        _trips.value = trips
        preferences.edit().putString(KEY_TRIPS, trips.tripsToJson().toString()).apply()
    }

    private fun loadTrips(): List<Trip> {
        val raw = preferences.getString(KEY_TRIPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.getJSONObject(index).toTrip())
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun List<Trip>.tripsToJson(): JSONArray = JSONArray().also { array ->
        forEach { array.put(it.toJson()) }
    }

    private fun Trip.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("startMillis", startMillis)
        .put("endMillis", endMillis)
        .put("trafficCondition", trafficCondition.name)
        .put("points", points.pointsToJson())

    private fun List<RoutePoint>.pointsToJson(): JSONArray = JSONArray().also { array ->
        forEach { point ->
            array.put(
                JSONObject()
                    .put("latitude", point.latitude)
                    .put("longitude", point.longitude)
                    .put("timestampMillis", point.timestampMillis)
                    .put("speedMetersPerSecond", point.speedMetersPerSecond.toDouble())
            )
        }
    }

    private fun JSONObject.toTrip(): Trip {
        val pointsArray = getJSONArray("points")
        val points = buildList {
            for (index in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(index)
                add(
                    RoutePoint(
                        latitude = point.getDouble("latitude"),
                        longitude = point.getDouble("longitude"),
                        timestampMillis = point.getLong("timestampMillis"),
                        speedMetersPerSecond = point.optDouble("speedMetersPerSecond", 0.0).toFloat(),
                    )
                )
            }
        }
        return Trip(
            id = getString("id"),
            startMillis = getLong("startMillis"),
            endMillis = getLong("endMillis"),
            points = points,
            trafficCondition = TrafficCondition.valueOf(optString("trafficCondition", TrafficCondition.Scorrevole.name)),
        )
    }

    companion object {
        private const val KEY_TRIPS = "trips"
    }
}
