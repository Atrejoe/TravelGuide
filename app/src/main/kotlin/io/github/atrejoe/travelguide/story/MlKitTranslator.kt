package io.github.atrejoe.travelguide.story

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.atrejoe.travelguide.core.lang.Translator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.nl.translate.Translator as MlKitClient

/**
 * Translates on the phone with ML Kit (issue #71): free and offline, after a one-time download of
 * about 30 MB per language. Each language pair's model is downloaded when first needed.
 */
class MlKitTranslator : Translator, AutoCloseable {
    private val clients = HashMap<Pair<String, String>, MlKitClient>()

    override suspend fun translate(texts: List<String>, from: String, to: String): List<String> {
        val source = requireNotNull(TranslateLanguage.fromLanguageTag(from)) { "Can't translate from $from" }
        val target = requireNotNull(TranslateLanguage.fromLanguageTag(to)) { "Can't translate into $to" }
        val client = synchronized(clients) {
            clients.getOrPut(from to to) {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
            }
        }
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return texts.map { client.translate(it).await() }
    }

    override fun close() {
        synchronized(clients) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
