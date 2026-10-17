package io.github.atrejoe.travelguide.net

import io.github.atrejoe.travelguide.BuildConfig
import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection fetcher; no extra HTTP library needed for a handful of requests. */
class UrlConnectionFetcher : HttpFetcher {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // Wikimedia asks API clients to identify themselves.
        val USER_AGENT = "TravelGuide/${BuildConfig.VERSION_NAME} (https://github.com/Atrejoe/TravelGuide; Android)"
    }
}
