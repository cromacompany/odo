package com.cromacompany.odo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TrackingSnapshot(
    val isMonitoringRequested: Boolean,
    val lastHeartbeatMillis: Long,
) {
    fun isServiceAlive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return isMonitoringRequested && lastHeartbeatMillis > 0L && nowMillis - lastHeartbeatMillis <= HEARTBEAT_TIMEOUT_MILLIS
    }

    companion object {
        const val HEARTBEAT_TIMEOUT_MILLIS = 45_000L
    }
}

class TrackingStateStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("odo_tracking_state", Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(loadSnapshot())

    val snapshot: StateFlow<TrackingSnapshot> = _snapshot

    fun setMonitoring(value: Boolean) {
        preferences.edit()
            .putBoolean(KEY_IS_MONITORING, value)
            .putLong(KEY_LAST_HEARTBEAT_MILLIS, if (value) preferences.getLong(KEY_LAST_HEARTBEAT_MILLIS, 0L) else 0L)
            .apply()
        refresh()
    }

    fun recordHeartbeat() {
        preferences.edit()
            .putBoolean(KEY_IS_MONITORING, true)
            .putLong(KEY_LAST_HEARTBEAT_MILLIS, System.currentTimeMillis())
            .apply()
        refresh()
    }

    fun refresh() {
        _snapshot.value = loadSnapshot()
    }

    private fun loadSnapshot(): TrackingSnapshot {
        return TrackingSnapshot(
            isMonitoringRequested = preferences.getBoolean(KEY_IS_MONITORING, false),
            lastHeartbeatMillis = preferences.getLong(KEY_LAST_HEARTBEAT_MILLIS, 0L),
        )
    }

    companion object {
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val KEY_LAST_HEARTBEAT_MILLIS = "last_heartbeat_millis"

        @Volatile
        private var instance: TrackingStateStore? = null

        fun get(context: Context): TrackingStateStore {
            return instance ?: synchronized(this) {
                instance ?: TrackingStateStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
