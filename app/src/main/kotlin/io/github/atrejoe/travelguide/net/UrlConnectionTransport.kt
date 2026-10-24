package io.github.atrejoe.travelguide.net

import io.github.atrejoe.travelguide.core.voice.HttpRequest
import io.github.atrejoe.travelguide.core.voice.HttpResponse
import io.github.atrejoe.travelguide.core.voice.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Sends API requests with headers and a body, e.g. to ElevenLabs (issue #75). */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.requestMethod = request.method
            connection.setRequestProperty("User-Agent", UrlConnectionFetcher.USER_AGENT)
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
