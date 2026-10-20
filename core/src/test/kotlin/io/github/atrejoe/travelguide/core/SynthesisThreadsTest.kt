package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.voice.SynthesisThreads
import kotlin.test.Test
import kotlin.test.assertEquals

class SynthesisThreadsTest {
    @Test
    fun `big and little cores use only the big ones`() {
        val twoBig = List(6) { 1_800_000L } + List(2) { 2_200_000L }
        assertEquals(2, SynthesisThreads.forCores(twoBig, fallback = 8))
    }

    @Test
    fun `prime, big and little cores use prime and big, at most four`() {
        val prime = listOf(3_200_000L) + List(3) { 2_500_000L } + List(4) { 1_800_000L }
        assertEquals(4, SynthesisThreads.forCores(prime, fallback = 8))
        val many = List(6) { 2_800_000L } + List(2) { 2_000_000L }
        assertEquals(4, SynthesisThreads.forCores(many, fallback = 8))
    }

    @Test
    fun `equal cores use them all, at most four`() {
        assertEquals(2, SynthesisThreads.forCores(List(2) { 2_000_000L }, fallback = 2))
        assertEquals(4, SynthesisThreads.forCores(List(8) { 2_000_000L }, fallback = 8))
    }

    @Test
    fun `unknown frequencies fall back to the core count`() {
        assertEquals(4, SynthesisThreads.forCores(emptyList(), fallback = 8))
        assertEquals(3, SynthesisThreads.forCores(listOf(2_000_000L, 0L, 1_000_000L), fallback = 3))
        assertEquals(1, SynthesisThreads.forCores(emptyList(), fallback = 0))
    }
}
