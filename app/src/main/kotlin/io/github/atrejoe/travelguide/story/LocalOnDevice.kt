package io.github.atrejoe.travelguide.story

import io.github.atrejoe.travelguide.core.enhance.LocalStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancer

/** The on-device storyteller, which frees its model's memory when closed. */
class LocalOnDevice(enhancer: LocalStoryEnhancer, private val model: LlamaModel) : StoryEnhancer by enhancer, AutoCloseable {
    override fun close() = model.close()
}
