package com.cromacompany.odo

import android.content.Context

object TripRepositoryProvider {
    @Volatile
    private var repository: TripRepository? = null

    fun get(context: Context): TripRepository {
        return repository ?: synchronized(this) {
            repository ?: TripRepository(context.applicationContext).also { repository = it }
        }
    }
}
