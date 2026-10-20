package io.github.atrejoe.travelguide.story

import android.content.Context
import android.os.Build
import io.github.atrejoe.travelguide.core.enhance.Generated
import io.github.atrejoe.travelguide.core.enhance.TextGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

/** How fast a model was on this phone, in tokens per second. */
data class LlamaStats(val promptTokens: Int, val promptSeconds: Double, val storyTokens: Int, val storySeconds: Double) {
    val readPerSecond: Double get() = if (promptSeconds > 0) promptTokens / promptSeconds else 0.0
    val writePerSecond: Double get() = if (storySeconds > 0) storyTokens / storySeconds else 0.0
}

/**
 * An open model loaded with llama.cpp (issue #51; see app/src/main/cpp). Loading takes a few
 * seconds and the model's size in memory, so keep one and [close] it when done.
 */
class LlamaModel private constructor(private var handle: Long) : TextGenerator, AutoCloseable {
    private val mutex = Mutex()

    /** Guards against cancelling a model that was already freed. */
    private val lock = Any()
    private var freed = false

    /** Timings of the last story. */
    var lastStats: LlamaStats? = null
        private set

    override suspend fun generate(system: String, user: String, maxTokens: Int): Generated = mutex.withLock {
        val h = handle
        check(h != 0L) { "The model was closed" }
        coroutineScope {
            val finished = AtomicBoolean(false)
            // Stops llama.cpp mid-story when the coroutine is cancelled, e.g. by a timeout.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) cancel(h)
                }
            }
            try {
                val (bytes, stats) = runInterruptible(LlamaEngine.dispatcher) {
                    // Freeing runs on this same thread, so the model is either still there or gone.
                    if (synchronized(lock) { freed }) throw IOException("The model was closed")
                    LlamaEngine.nativeGenerate(h, system, user, maxTokens, TEMPERATURE, SEED) to LlamaEngine.nativeStats(h)
                }
                lastStats = LlamaStats(stats[0].toInt(), stats[1], stats[2].toInt(), stats[3])
                Generated(bytes.decodeToString(), cutOff = stats[2].toInt() >= maxTokens)
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    }

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        // Stop a story being written; the dispatcher runs one thing at a time, so it frees after.
        cancel(h)
        LlamaEngine.dispatcher.dispatch(EmptyCoroutineContext) {
            synchronized(lock) {
                freed = true
                LlamaEngine.nativeFree(h)
            }
        }
    }

    private fun cancel(h: Long) = synchronized(lock) {
        if (!freed) LlamaEngine.nativeCancel(h)
    }

    companion object {
        private const val TEMPERATURE = 0.5f
        private const val SEED = 42
        private const val CONTEXT_TOKENS = 2_048

        /** Loads [file]; throws when the phone can't run it. Blocks for a few seconds. */
        suspend fun load(context: Context, file: File): LlamaModel {
            LlamaEngine.init(context)
            val handle = runInterruptible(LlamaEngine.dispatcher) {
                LlamaEngine.nativeLoad(file.absolutePath, CONTEXT_TOKENS, LlamaEngine.threads())
            }
            return LlamaModel(handle)
        }
    }
}

/** The native side; one thread runs all model work. */
internal object LlamaEngine {
    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /** llama.cpp is only built for 64-bit ARM, which nearly all phones are. */
    val supported: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        check(supported) { "Open models need a 64-bit ARM phone" }
        System.loadLibrary("travelguide_llama")
        // The CPU code comes in variants for older and newer ARM cores, as separate libraries.
        nativeInit(context.applicationInfo.nativeLibraryDir)
        initialized = true
    }

    /**
     * Half the cores, at most four: phones mix fast and slow cores, and llama.cpp waits for the
     * slowest thread.
     */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    @JvmStatic external fun nativeInit(nativeLibDir: String)
    @JvmStatic external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Long
    @JvmStatic external fun nativeGenerate(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, seed: Int): ByteArray
    @JvmStatic external fun nativeStats(handle: Long): DoubleArray
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}
