package io.github.atrejoe.travelguide.settings

import android.content.Context
import io.github.atrejoe.travelguide.core.enhance.StoryStore
import org.json.JSONObject
import java.io.File

/**
 * Retold stories and the money spent on them per month, in a small JSON file, so a place is
 * never paid for twice, not even after a restart. Only the most recent [MAX_STORIES] are kept.
 */
class FileStoryStore(context: Context) : StoryStore {
    private val file = File(context.filesDir, "retold-stories.json")
    private val stories = LinkedHashMap<String, String>()
    private val spending = HashMap<String, Double>()
    private var loaded = false

    /** Read on first use, which is off the main thread. */
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.optJSONObject("stories")?.let { s -> s.keys().forEach { stories[it] = s.getString(it) } }
                json.optJSONObject("spent")?.let { s -> s.keys().forEach { spending[it] = s.getDouble(it) } }
            }
        } catch (e: Exception) {
            // A damaged file only costs us the cache.
        }
    }

    @Synchronized
    override fun story(key: String): String? {
        load()
        return stories[key]
    }

    @Synchronized
    override fun putStory(key: String, text: String) {
        load()
        stories[key] = text
        while (stories.size > MAX_STORIES) stories.remove(stories.keys.first())
        save()
    }

    @Synchronized
    override fun spent(month: String): Double {
        load()
        return spending[month] ?: 0.0
    }

    @Synchronized
    override fun addSpent(month: String, usd: Double) {
        spending[month] = spent(month) + usd
        save()
    }

    private fun save() {
        val json = JSONObject()
            .put("stories", JSONObject(stories as Map<*, *>))
            .put("spent", JSONObject(spending as Map<*, *>))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private companion object {
        const val MAX_STORIES = 2_000
    }
}
