package io.github.atrejoe.travelguide.settings

import android.content.Context
import android.util.Log
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.guide.HistoryRetention
import io.github.atrejoe.travelguide.core.guide.StoryHistory
import io.github.atrejoe.travelguide.core.guide.StoryLog
import io.github.atrejoe.travelguide.core.guide.ToldStory
import io.github.atrejoe.travelguide.core.story.Poi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The stories told on earlier trips (issue #84), in a JSON file on the phone, so the guide doesn't
 * tell them again and the guide screen can show and replay them. How long they are kept is the
 * listener's choice; the history can be erased. Never leaves the phone.
 */
class FileStoryHistory(context: Context) : StoryHistory {
    private val file = File(context.filesDir, "story-history.json")
    private val log = StoryLog(maxStories = MAX_STORIES)
    private var loaded = false

    private val mutableCount = MutableStateFlow(0)

    /** How many stories are remembered. */
    val count: StateFlow<Int> = mutableCount.asStateFlow()

    /** Reads the file; call it off the main thread, early. Later calls do nothing. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) log.restore(parse(file.readText()))
        } catch (e: Exception) {
            // A damaged file only costs us the history.
            Log.w(TAG, "Could not read the story history", e)
        }
        mutableCount.value = log.size
    }

    @Synchronized
    override fun wasTold(poi: Poi): Boolean {
        load()
        return log.wasTold(poi)
    }

    /** Remembers [stories], told just now; writes the file, so call it off the main thread. */
    @Synchronized
    fun record(stories: List<ToldStory>) {
        load()
        stories.forEach(log::add)
        changed()
    }

    @Synchronized
    fun setRetention(retention: HistoryRetention) {
        load()
        if (log.retention == retention) return
        log.retention = retention
        changed()
    }

    /** Forgets every story told. */
    @Synchronized
    fun clear() {
        load()
        log.clear()
        changed()
    }

    /** The latest stories that were told in full, newest first. */
    @Synchronized
    fun recent(limit: Int): List<ToldStory> {
        load()
        return log.entries.asReversed().filter { it.sentences.isNotEmpty() }.take(limit)
    }

    private fun changed() {
        mutableCount.value = log.size
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json(log.entries))
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the story history", e)
        }
    }

    private companion object {
        const val TAG = "FileStoryHistory"

        /** About a megabyte or two of stories. */
        const val MAX_STORIES = 2_000

        fun json(stories: List<ToldStory>): String {
            val array = JSONArray()
            for (s in stories) {
                array.put(
                    JSONObject()
                        .put("title", s.title)
                        .put("lang", s.lang)
                        .put("page", s.pageId)
                        .put("item", s.wikidataId ?: JSONObject.NULL)
                        .put("time", s.timeMillis)
                        .put("url", s.url)
                        .put("sentences", JSONArray(s.sentences))
                        .put("toldBy", s.toldBy)
                        .apply { s.point?.let { put("lat", it.lat).put("lon", it.lon) } },
                )
            }
            return JSONObject().put("version", 1).put("stories", array).toString()
        }

        fun parse(text: String): List<ToldStory> {
            val array = JSONObject(text).optJSONArray("stories") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val sentences = o.optJSONArray("sentences")
                ToldStory(
                    title = o.optString("title"),
                    lang = o.optString("lang", "en"),
                    pageId = o.optLong("page"),
                    wikidataId = if (o.isNull("item")) null else o.optString("item"),
                    timeMillis = o.optLong("time"),
                    url = o.optString("url"),
                    sentences = sentences?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    point = if (o.has("lat") && o.has("lon")) GeoPoint(o.getDouble("lat"), o.getDouble("lon")) else null,
                    toldBy = o.optString("toldBy"),
                )
            }
        }
    }
}
