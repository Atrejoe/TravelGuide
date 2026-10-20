package io.github.atrejoe.travelguide.core.voice

/**
 * How many threads a voice model gets. onnxruntime splits every step evenly over its threads and
 * waits for the slowest, so on a phone with big and little cores a thread on a little core holds
 * the others up. So: one thread per core outside the slowest cluster, at most [MAX].
 */
object SynthesisThreads {
    const val MAX = 4

    /** [maxFrequencies] holds each core's highest clock (any unit); empty or unknown gives [fallback]. */
    fun forCores(maxFrequencies: List<Long>, fallback: Int): Int {
        val known = maxFrequencies.filter { it > 0 }
        if (known.isEmpty() || known.size < maxFrequencies.size) return fallback.coerceIn(1, MAX)
        val slowest = known.min()
        val fast = known.count { it > slowest }
        // All cores alike: use them all.
        return (if (fast == 0) known.size else fast).coerceIn(1, MAX)
    }
}
