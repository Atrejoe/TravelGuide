package io.github.atrejoe.travelguide.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.nav.ImportedRoute
import io.github.atrejoe.travelguide.core.nav.RouteImportException
import io.github.atrejoe.travelguide.core.nav.RouteImporter
import io.github.atrejoe.travelguide.net.RedirectResolver
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Imports shared links and files and makes them the guide's planned route. */
object RouteImports {
    private const val TAG = "RouteImports"
    private const val MAX_FILE_BYTES = 30 * 1024 * 1024

    private val importer by lazy { RouteImporter(UrlConnectionFetcher(), RedirectResolver()) }

    /** Imports a shared text or link; throws [RouteImportException] with a message for the user. */
    suspend fun importText(context: Context, text: String): ImportedRoute {
        val mode = context.app.settings.current().modeOverride ?: GuideStatus.state.value.mode
        val route = withContext(Dispatchers.IO) { importer.importText(text, currentPosition(context), mode) }
        RouteRepository.set(context, route)
        return route
    }

    /** Imports a GPX or FIT file; throws [RouteImportException] with a message for the user. */
    suspend fun importUri(context: Context, uri: Uri): ImportedRoute {
        val route = withContext(Dispatchers.IO) {
            val bytes = try {
                read(context, uri)
            } catch (e: IOException) {
                throw RouteImportException(RouteImportException.Reason.UNSUPPORTED, "Could not open the file.", e)
            } catch (e: SecurityException) {
                throw RouteImportException(RouteImportException.Reason.UNSUPPORTED, "Travel Guide may not read this file.", e)
            }
            importer.importFile(bytes, displayName(context, uri))
        }
        RouteRepository.set(context, route)
        return route
    }

    private fun read(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No content for $uri")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) {
                    throw RouteImportException(RouteImportException.Reason.UNSUPPORTED, "This file is too large for a route.")
                }
            }
            out.toByteArray()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = queryName(context, uri) ?: uri.lastPathSegment

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "No display name for $uri", e)
        null
    }

    /** Where the user is, for routes that start at "your location". */
    @SuppressLint("MissingPermission") // Checked below; a SecurityException is caught too.
    private fun currentPosition(context: Context): GeoPoint? {
        GuideStatus.state.value.position?.let { return it }
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { it in manager.allProviders }
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission", e)
            null
        }
    }
}
