package io.github.atrejoe.travelguide.core.story

import kotlin.random.Random

/**
 * Picks a phrasing at random from a pool, avoiding the ones used recently, so the guide doesn't
 * keep saying the same thing (issue #82). Pools are told apart by a [pick]'s category, and their
 * phrasings by position: the same phrasing with another place's name in it counts as the same.
 */
class PhrasePicker(private val random: Random = Random.Default, private val memory: Int = 40) {
    private val recent = ArrayDeque<String>()

    fun pick(category: String, options: List<String>): String {
        require(options.isNotEmpty()) { "No phrasings for $category" }
        val keys = options.indices.map { "$category#$it" }
        val fresh = options.indices.filter { keys[it] !in recent }
        // All used lately: the one used longest ago.
        val index = if (fresh.isNotEmpty()) fresh[random.nextInt(fresh.size)] else options.indices.minBy { recent.indexOf(keys[it]) }
        recent.remove(keys[index])
        recent.addLast(keys[index])
        while (recent.size > memory) recent.removeFirst()
        return options[index]
    }
}
