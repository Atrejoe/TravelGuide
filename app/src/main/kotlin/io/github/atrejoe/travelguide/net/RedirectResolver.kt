package io.github.atrejoe.travelguide.net

import io.github.atrejoe.travelguide.core.nav.LinkResolver
import io.github.atrejoe.travelguide.core.nav.RouteLink
import io.github.atrejoe.travelguide.core.nav.RouteLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Follows the redirects of a short link such as maps.app.goo.gl/… without loading the page,
 * stopping as soon as the target is a link we understand.
 */
class RedirectResolver : LinkResolver {
    override suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_HOPS) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", UrlConnectionFetcher.USER_AGENT)
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code !in 300..399 || location.isNullOrBlank()) {
                    if (code in 200..299) return@withContext current
                    throw IOException("HTTP $code for $current")
                }
                current = URL(URL(current), location).toString()
            } finally {
                connection.disconnect()
            }
            val link = RouteLinks.classifyUrl(current)
            if (link !is RouteLink.ShortLink && link !is RouteLink.Unknown) return@withContext current
        }
        current
    }

    private companion object {
        const val MAX_HOPS = 6
    }
}
