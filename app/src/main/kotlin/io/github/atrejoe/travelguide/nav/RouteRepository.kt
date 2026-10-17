package io.github.atrejoe.travelguide.nav

import android.content.Context
import android.util.Log
import io.github.atrejoe.travelguide.core.nav.ImportedRoute
import io.github.atrejoe.travelguide.core.nav.NavigationStatus
import io.github.atrejoe.travelguide.core.nav.RouteCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The one planned route the guide follows, imported from a navigation app or a file. Kept on disk
 * so a route shared before pressing Start (or before the app was restarted) is still used.
 */
object RouteRepository {
    private const val TAG = "RouteRepository"
    private const val FILE_NAME = "planned-route.txt"

    private val mutable = MutableStateFlow<ImportedRoute?>(null)
    val route: StateFlow<ImportedRoute?> = mutable.asStateFlow()

    @Volatile
    private var loaded = false

    /** Restores the stored route, once per process. */
    suspend fun load(context: Context) {
        if (loaded) return
        val stored = withContext(Dispatchers.IO) {
            try {
                file(context).takeIf { it.exists() }?.readText()?.let(RouteCodec::decode)
            } catch (e: IOException) {
                Log.w(TAG, "Could not read the stored route", e)
                null
            }
        }
        // A route imported meanwhile wins over the stored one.
        if (!loaded) {
            loaded = true
            if (stored != null) mutable.compareAndSet(null, stored)
        }
    }

    suspend fun set(context: Context, route: ImportedRoute) {
        loaded = true
        mutable.value = route
        withContext(Dispatchers.IO) {
            try {
                file(context).writeText(RouteCodec.encode(route))
            } catch (e: IOException) {
                Log.w(TAG, "Could not store the route", e)
            }
        }
    }

    suspend fun clear(context: Context) {
        loaded = true
        mutable.value = null
        withContext(Dispatchers.IO) { file(context).delete() }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}

/** Turn-by-turn navigation seen in another app's notification, if notification access is granted. */
object NavigationState {
    private val mutable = MutableStateFlow<NavigationStatus?>(null)
    val status: StateFlow<NavigationStatus?> = mutable.asStateFlow()

    fun update(status: NavigationStatus?) {
        mutable.value = status
    }
}
