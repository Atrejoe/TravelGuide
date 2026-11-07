package io.github.atrejoe.travelguide.service

import android.content.Context
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.core.guide.GuideTexts

/** What the guide shows about what it is doing, in the app's language (issue #92). */
class ResourceGuideTexts(private val context: Context) : GuideTexts {
    override fun railways() = context.getString(R.string.guide_railways)
    override fun roads() = context.getString(R.string.guide_roads)
    override fun routeTowards(name: String) = context.getString(R.string.guide_route_towards, name)
    override fun searching(radius: String) = context.getString(R.string.guide_searching, radius)
    override fun buildings() = context.getString(R.string.guide_buildings)
    override fun town() = context.getString(R.string.guide_town)
    override fun translating(title: String) = context.getString(R.string.guide_translating, title)
    override fun reading(title: String) = context.getString(R.string.guide_reading, title)
    override fun nextStoryIn(duration: String) = context.getString(R.string.guide_next_story, duration)
    override fun nothingYet(duration: String) = context.getString(R.string.guide_nothing_yet, duration)
    override fun widerIn(radius: String, duration: String) = context.getString(R.string.guide_wider_in, radius, duration)
    override fun widest(radius: String) = context.getString(R.string.guide_widest, radius)
}
