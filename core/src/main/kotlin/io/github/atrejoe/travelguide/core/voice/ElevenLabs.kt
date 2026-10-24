package io.github.atrejoe.travelguide.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An ElevenLabs speech model to choose in the settings (issue #75). */
enum class ElevenLabsModel(
    val id: String,
    val label: String,
    val description: String,
    /** Credits per character of text. */
    val creditsPerChar: Double,
    /** Whether it takes a language code; Multilingual v2 detects the language itself. */
    val takesLanguage: Boolean,
) {
    FLASH("eleven_flash_v2_5", "Flash v2.5", "Fast, and half the credits per character", 0.5, true),
    MULTILINGUAL("eleven_multilingual_v2", "Multilingual v2", "Most natural, slower to start", 1.0, false),
    ;

    companion object {
        val DEFAULT = FLASH
        fun byId(id: String?): ElevenLabsModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** A voice in the listener's ElevenLabs account. */
data class CloudVoice(
    val id: String,
    val name: String,
    val description: String,
    /** Languages ElevenLabs verified the voice in; empty when it doesn't say. */
    val languages: Set<String> = emptySet(),
)

/** Credits used and allowed in the current billing period. */
data class CloudUsage(val used: Long, val limit: Long, val resetsAtUnix: Long?) {
    val left: Long get() = (limit - used).coerceAtLeast(0)
}

/** Why ElevenLabs refused a request, so the guide can say what to do about it. */
class ElevenLabsException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason {
        /** The key is wrong or was revoked. */
        INVALID_KEY,

        /** No credits left this month. */
        QUOTA_EXCEEDED,

        /** Too many requests at once; worth trying again shortly. */
        BUSY,

        OTHER,
    }

    /** True when trying again later in this trip can't help. */
    val permanent: Boolean get() = reason == Reason.INVALID_KEY || reason == Reason.QUOTA_EXCEEDED
}

/** One HTTP request to the ElevenLabs API. */
data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

/** Its response: the status code and the raw body. */
class HttpResponse(val code: Int, val body: ByteArray)

/** Sends requests; throws [IOException] when the network fails, not for HTTP errors. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * The ElevenLabs API, with the listener's own key (issue #75). The key only ever goes to
 * api.elevenlabs.io.
 */
class ElevenLabsClient(private val apiKey: String, private val transport: HttpTransport) {

    /** The voices in the account, those verified in [lang] first. */
    suspend fun voices(lang: String? = null): List<CloudVoice> {
        val voices = mutableListOf<CloudVoice>()
        var token: String? = null
        do {
            val page = json.parseToJsonElement(call(voicesRequest(token)).decodeToString()).jsonObject
            voices += parseVoices(page)
            token = page["next_page_token"]?.jsonPrimitive?.contentOrNull?.takeIf { page.bool("has_more") }
        } while (token != null && voices.size < MAX_VOICES)
        return if (lang == null) voices else voices.sortedByDescending { lang in it.languages }
    }

    suspend fun usage(): CloudUsage = parseUsage(call(request("GET", "$BASE/v1/user/subscription")).decodeToString())

    /**
     * [text] read by [voiceId], as mono 16-bit PCM at [SAMPLE_RATE] Hz (see [pcm16ToFloat]). [previous] and [next] are the
     * sentences around it, which make the sentences flow into each other; they aren't billed.
     */
    suspend fun speech(
        voiceId: String,
        text: String,
        model: ElevenLabsModel,
        lang: String,
        speed: Float,
        previous: String? = null,
        next: String? = null,
    ): ByteArray = call(speechRequest(voiceId, text, model, lang, speed, previous, next))

    fun speechRequest(
        voiceId: String,
        text: String,
        model: ElevenLabsModel,
        lang: String,
        speed: Float,
        previous: String? = null,
        next: String? = null,
    ): HttpRequest {
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model.id)
            if (model.takesLanguage) put("language_code", lang)
            put("voice_settings", buildJsonObject { put("speed", speed.coerceIn(MIN_SPEED, MAX_SPEED)) })
            previous?.let { put("previous_text", it) }
            next?.let { put("next_text", it) }
        }
        return request("POST", "$BASE/v1/text-to-speech/${encode(voiceId)}?output_format=pcm_$SAMPLE_RATE", body.toString())
    }

    fun voicesRequest(pageToken: String? = null): HttpRequest =
        request("GET", "$BASE/v2/voices?page_size=100" + (pageToken?.let { "&next_page_token=${encode(it)}" } ?: ""))

    private fun request(method: String, url: String, body: String? = null) = HttpRequest(
        method,
        url,
        buildMap {
            put("xi-api-key", apiKey)
            put("Accept", if (url.contains("text-to-speech")) "audio/pcm" else "application/json")
            if (body != null) put("Content-Type", "application/json")
        },
        body,
    )

    private suspend fun call(request: HttpRequest): ByteArray {
        val response = transport.send(request)
        if (response.code in 200..299) return response.body
        throw error(response.code, response.body.decodeToString())
    }

    companion object {
        const val BASE = "https://api.elevenlabs.io"

        /** 24 kHz 16-bit PCM is available on every plan, and is what the voices are made at. */
        const val SAMPLE_RATE = 24_000

        /** The speed range ElevenLabs accepts. */
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.2f

        private const val MAX_VOICES = 500

        private val json = Json { ignoreUnknownKeys = true }

        fun parseVoices(page: JsonObject): List<CloudVoice> = page["voices"]?.jsonArray.orEmpty().mapNotNull { element ->
            val voice = element as? JsonObject ?: return@mapNotNull null
            val id = voice.string("voice_id") ?: return@mapNotNull null
            val labels = voice["labels"] as? JsonObject
            val label = { key: String -> labels?.string(key)?.replace('_', ' ')?.takeIf { it.isNotBlank() } }
            val description = listOfNotNull(label("accent"), label("gender"), label("age"), label("descriptive") ?: label("description"))
                .joinToString(", ")
                .ifEmpty { voice.string("description")?.take(80).orEmpty() }
            val languages = (voice["verified_languages"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("language") }
                .toSet()
            CloudVoice(id, voice.string("name") ?: id, description.replaceFirstChar { it.uppercase() }, languages)
        }

        fun parseUsage(body: String): CloudUsage {
            val o = json.parseToJsonElement(body).jsonObject
            return CloudUsage(
                used = o["character_count"]?.jsonPrimitive?.longOrNull ?: 0,
                limit = o["character_limit"]?.jsonPrimitive?.longOrNull ?: 0,
                resetsAtUnix = o["next_character_count_reset_unix"]?.jsonPrimitive?.longOrNull,
            )
        }

        /** Errors come as `{"detail": {"status": "quota_exceeded", "message": "…"}}`, or a plain message. */
        fun error(code: Int, body: String): ElevenLabsException {
            val detail = try {
                json.parseToJsonElement(body).jsonObject["detail"]
            } catch (e: Exception) {
                null
            }
            val status = (detail as? JsonObject)?.string("status").orEmpty()
            val message = (detail as? JsonObject)?.string("message")
                ?: (detail as? JsonPrimitive)?.contentOrNull
                ?: "HTTP $code"
            val reason = when {
                status == "quota_exceeded" || "quota" in status -> ElevenLabsException.Reason.QUOTA_EXCEEDED
                code == 401 || status == "invalid_api_key" -> ElevenLabsException.Reason.INVALID_KEY
                code == 429 -> ElevenLabsException.Reason.BUSY
                else -> ElevenLabsException.Reason.OTHER
            }
            return ElevenLabsException(reason, message)
        }

        /** 16-bit little-endian PCM to floats in -1..1, as AudioTrack plays them. */
        fun pcm16ToFloat(bytes: ByteArray): FloatArray {
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }

        private fun encode(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
    }
}
