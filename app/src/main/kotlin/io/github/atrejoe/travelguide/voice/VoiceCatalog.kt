package io.github.atrejoe.travelguide.voice

enum class VoiceEngine { PIPER, KOKORO }

/** One downloadable sherpa-onnx model archive; a multi-speaker model serves several voices. */
data class VoicePackage(val id: String, val engine: VoiceEngine, val approxSizeMb: Int) {
    val archiveUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"
}

/** An on-device voice: a package plus, for multi-speaker models, which speaker to use. */
data class NeuralVoice(
    val id: String,
    val name: String,
    val description: String,
    val pkg: VoicePackage,
    val speakerId: Int = 0,
) {
    val voiceId: String get() = "${VoiceCatalog.NEURAL_PREFIX}$id"
}

object VoiceCatalog {
    const val NEURAL_PREFIX = "neural:"
    const val SYSTEM_PREFIX = "system:"

    /**
     * Kokoro (82M parameters, Apache 2.0): by far the most natural free on-device voices. One
     * ~99 MB int8 download contains all speakers. Speaker ids follow the model's voices.bin:
     * 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky, 5 am_adam, 6 am_michael, 7 bf_emma,
     * 8 bf_isabella, 9 bm_george, 10 bm_lewis.
     */
    val KOKORO_EN = VoicePackage("kokoro-int8-en-v0_19", VoiceEngine.KOKORO, 99)

    val neuralVoices = listOf(
        NeuralVoice("kokoro-bm_george", "George", "Deep, warm British storyteller", KOKORO_EN, speakerId = 9),
        NeuralVoice("kokoro-bm_lewis", "Lewis", "Mature, resonant British voice", KOKORO_EN, speakerId = 10),
        NeuralVoice("kokoro-am_michael", "Michael", "Warm, relaxed American voice", KOKORO_EN, speakerId = 6),
        NeuralVoice("kokoro-am_adam", "Adam", "Low, calm American voice", KOKORO_EN, speakerId = 5),
        // Piper: less natural, but small and fast on older phones.
        NeuralVoice(
            "piper-norman", "Norman", "American narrator · lite",
            VoicePackage("vits-piper-en_US-norman-medium-fp16", VoiceEngine.PIPER, 34),
        ),
        NeuralVoice(
            "piper-ryan", "Ryan", "American, expressive · lite",
            // The fp16 export of this model fails in onnxruntime on some sentences.
            VoicePackage("vits-piper-en_US-ryan-high", VoiceEngine.PIPER, 110),
        ),
        NeuralVoice(
            "piper-alan", "Alan", "British, soft-spoken · lite",
            VoicePackage("vits-piper-en_GB-alan-medium-fp16", VoiceEngine.PIPER, 34),
        ),
    )

    val packages: List<VoicePackage> = neuralVoices.map { it.pkg }.distinct()

    val DEFAULT_VOICE_ID = neuralVoices.first().voiceId

    fun neural(voiceId: String): NeuralVoice? =
        neuralVoices.firstOrNull { it.voiceId == voiceId }

    /** The Android voice name for a `system:` id; empty means the engine default. */
    fun systemVoiceName(voiceId: String): String? =
        voiceId.takeIf { it.startsWith(SYSTEM_PREFIX) }?.removePrefix(SYSTEM_PREFIX)
}
