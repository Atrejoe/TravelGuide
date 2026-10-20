package io.github.atrejoe.travelguide.core.enhance

/** An open model the app can download and run on the phone (issue #51). */
data class LocalModel(
    val id: String,
    val name: String,
    val description: String,
    val license: String,
    /** A 4-bit GGUF file. */
    val url: String,
    val sizeMb: Int,
    /** Checked after downloading; the Model links workflow prints it. */
    val sha256: String? = null,
    /** Appended to the request, e.g. to turn off Qwen3's thinking. */
    val userSuffix: String = "",
)

object LocalModels {
    val QWEN3 = LocalModel(
        id = "qwen3-1.7b-q4km",
        name = "Qwen3 1.7B",
        description = "The best stories of the three. Apache 2.0.",
        license = "Apache 2.0",
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
        sizeMb = 1_107,
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        userSuffix = " /no_think",
    )
    val GEMMA3 = LocalModel(
        id = "gemma3-1b-q4km",
        name = "Gemma 3 1B",
        description = "Smaller and faster, shorter stories. Gemma Terms of Use.",
        license = "Gemma Terms of Use",
        url = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
        sizeMb = 806,
        sha256 = "8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd",
    )
    val SMOLLM2 = LocalModel(
        id = "smollm2-1.7b-q4km",
        name = "SmolLM2 1.7B",
        description = "Fully open, training data included. English only. Apache 2.0.",
        license = "Apache 2.0",
        url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
        sizeMb = 1_055,
        sha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
    )

    val all = listOf(QWEN3, GEMMA3, SMOLLM2)
    val DEFAULT = QWEN3

    fun byId(id: String?): LocalModel = all.firstOrNull { it.id == id } ?: DEFAULT
}

/** What a local model wrote, and whether it stopped at the token limit. */
data class Generated(val text: String, val cutOff: Boolean)

/** Runs a model on the phone; the app implements it with llama.cpp. */
fun interface TextGenerator {
    suspend fun generate(system: String, user: String, maxTokens: Int): Generated
}

/**
 * Retells stories with an open model on the phone: free, offline, private. Small models read
 * slowly on a phone, so they get a shorter source text than Claude.
 */
class LocalStoryEnhancer(
    private val generator: TextGenerator,
    private val model: LocalModel,
) : StoryEnhancer {
    override val id = "local"
    override val maxCostUsd = 0.0
    override val maxSourceChars = MAX_SOURCE_CHARS

    override suspend fun retell(request: StoryRequest): RetoldStory {
        val generated = generator.generate(StoryPrompt.SYSTEM, StoryPrompt.user(request) + model.userSuffix, MAX_TOKENS)
        val text = clean(generated) ?: throw IllegalStateException("${model.name} wrote no usable story")
        return RetoldStory(text, 0.0)
    }

    companion object {
        const val MAX_TOKENS = 320
        const val MAX_SOURCE_CHARS = 2_500
        private val thinking = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)

        /** The story without any thinking, cut back to complete sentences when it hit the limit. */
        fun clean(generated: Generated): String? {
            val text = generated.text.replace(thinking, "").trim()
            if (text.isEmpty()) return null
            return if (generated.cutOff) StoryPrompt.completeSentences(text) else text
        }
    }
}
