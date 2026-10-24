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
    /** The language it speaks, a StoryLanguage code. */
    val lang: String = "en",
    /** The espeak-ng language a multilingual Kokoro model reads with, e.g. "pt-br"; empty otherwise. */
    val espeakLang: String = "",
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

    /**
     * Kokoro v1.0, multilingual (issue #73): natural voices for Spanish, French, Italian and
     * Brazilian Portuguese in one 132 MB download, read with espeak-ng in their language. Speaker
     * ids follow the model's metadata: 28 ef_dora, 29 em_alex, 30 ff_siwis, 35 if_sara,
     * 36 im_nicola, 42 pf_dora, 43 pm_alex, 44 pm_santa, 53 em_santa. It has no Dutch or German.
     */
    val KOKORO_MULTI = VoicePackage("kokoro-int8-multi-lang-v1_0", VoiceEngine.KOKORO, 132)

    val neuralVoices = listOf(
        NeuralVoice("kokoro-bm_george", "George", "Deep, warm British storyteller", KOKORO_EN, speakerId = 9),
        NeuralVoice("kokoro-bm_lewis", "Lewis", "Mature, resonant British voice", KOKORO_EN, speakerId = 10),
        NeuralVoice("kokoro-am_michael", "Michael", "Warm, relaxed American voice", KOKORO_EN, speakerId = 6),
        NeuralVoice("kokoro-am_adam", "Adam", "Low, calm American voice", KOKORO_EN, speakerId = 5),
        NeuralVoice("kokoro-em_alex", "Alex", "Spanish, male", KOKORO_MULTI, speakerId = 29, lang = "es", espeakLang = "es"),
        NeuralVoice("kokoro-em_santa", "Santa", "Spanish, deep male", KOKORO_MULTI, speakerId = 53, lang = "es", espeakLang = "es"),
        NeuralVoice("kokoro-ef_dora", "Dora", "Spanish, female", KOKORO_MULTI, speakerId = 28, lang = "es", espeakLang = "es"),
        NeuralVoice("kokoro-ff_siwis", "Siwis", "French, female", KOKORO_MULTI, speakerId = 30, lang = "fr", espeakLang = "fr"),
        NeuralVoice("kokoro-im_nicola", "Nicola", "Italian, male", KOKORO_MULTI, speakerId = 36, lang = "it", espeakLang = "it"),
        NeuralVoice("kokoro-if_sara", "Sara", "Italian, female", KOKORO_MULTI, speakerId = 35, lang = "it", espeakLang = "it"),
        NeuralVoice("kokoro-pm_alex", "Alex", "Portuguese (Brazil), male", KOKORO_MULTI, speakerId = 43, lang = "pt", espeakLang = "pt-br"),
        NeuralVoice("kokoro-pm_santa", "Santa", "Portuguese (Brazil), deep male", KOKORO_MULTI, speakerId = 44, lang = "pt", espeakLang = "pt-br"),
        NeuralVoice("kokoro-pf_dora", "Dora", "Portuguese (Brazil), female", KOKORO_MULTI, speakerId = 42, lang = "pt", espeakLang = "pt-br"),
        // Piper: less natural, but small and fast on older phones.
        NeuralVoice(
            "piper-norman", "Norman", "American narrator · lite",
            VoicePackage("vits-piper-en_US-norman-medium-fp16", VoiceEngine.PIPER, 34),
        ),
        NeuralVoice(
            "piper-ryan", "Ryan", "American, expressive · lite",
            // Medium, not high: high renders five times slower, too slow for mid-range phones. The
            // fp16 export of this model doesn't load in onnxruntime.
            VoicePackage("vits-piper-en_US-ryan-medium", VoiceEngine.PIPER, 67),
        ),
        NeuralVoice(
            "piper-alan", "Alan", "British, soft-spoken · lite",
            VoicePackage("vits-piper-en_GB-alan-medium-fp16", VoiceEngine.PIPER, 34),
        ),
        // Other languages (issue #68). The fp16 exports of these models don't load in onnxruntime.
        NeuralVoice(
            "piper-nl-pim", "Pim", "Dutch (Netherlands), male",
            VoicePackage("vits-piper-nl_NL-pim-medium", VoiceEngine.PIPER, 67), lang = "nl",
        ),
        NeuralVoice(
            "piper-nl-ronnie", "Ronnie", "Dutch (Netherlands), male",
            VoicePackage("vits-piper-nl_NL-ronnie-medium", VoiceEngine.PIPER, 67), lang = "nl",
        ),
        NeuralVoice(
            "piper-nl-nathalie", "Nathalie", "Flemish (Belgium), female",
            VoicePackage("vits-piper-nl_BE-nathalie-medium", VoiceEngine.PIPER, 67), lang = "nl",
        ),
        NeuralVoice(
            "piper-de-thorsten", "Thorsten", "German, male",
            VoicePackage("vits-piper-de_DE-thorsten-medium", VoiceEngine.PIPER, 67), lang = "de",
        ),
        NeuralVoice(
            "piper-de-thorsten-high", "Thorsten (high quality)", "German, male · more natural, renders slower",
            VoicePackage("vits-piper-de_DE-thorsten-high", VoiceEngine.PIPER, 116), lang = "de",
        ),
        NeuralVoice(
            "piper-de-kerstin", "Kerstin", "German, female",
            VoicePackage("vits-piper-de_DE-kerstin-low", VoiceEngine.PIPER, 67), lang = "de",
        ),
        NeuralVoice(
            "piper-fr-tom", "Tom", "French, male",
            VoicePackage("vits-piper-fr_FR-tom-medium", VoiceEngine.PIPER, 67), lang = "fr",
        ),
        NeuralVoice(
            "piper-fr-siwis", "Siwis", "French, female",
            VoicePackage("vits-piper-fr_FR-siwis-medium", VoiceEngine.PIPER, 67), lang = "fr",
        ),
        NeuralVoice(
            "piper-es-davefx", "Davefx", "Spanish (Spain), male",
            VoicePackage("vits-piper-es_ES-davefx-medium", VoiceEngine.PIPER, 67), lang = "es",
        ),
        NeuralVoice(
            "piper-es-ald", "Ald", "Spanish (Mexico), male",
            VoicePackage("vits-piper-es_MX-ald-medium", VoiceEngine.PIPER, 67), lang = "es",
        ),
        NeuralVoice(
            "piper-it-paola", "Paola", "Italian, female",
            VoicePackage("vits-piper-it_IT-paola-medium", VoiceEngine.PIPER, 67), lang = "it",
        ),
        NeuralVoice(
            "piper-it-riccardo", "Riccardo", "Italian, male",
            VoicePackage("vits-piper-it_IT-riccardo-x_low", VoiceEngine.PIPER, 27), lang = "it",
        ),
        NeuralVoice(
            "piper-pt-tugao", "Tugão", "Portuguese (Portugal), male",
            VoicePackage("vits-piper-pt_PT-tugao-medium", VoiceEngine.PIPER, 67), lang = "pt",
        ),
        NeuralVoice(
            "piper-pt-faber", "Faber", "Portuguese (Brazil), male",
            VoicePackage("vits-piper-pt_BR-faber-medium", VoiceEngine.PIPER, 67), lang = "pt",
        ),
    )

    val packages: List<VoicePackage> = neuralVoices.map { it.pkg }.distinct()

    val DEFAULT_VOICE_ID = neuralVoices.first().voiceId

    /** On-device voices that speak [lang]. */
    fun voicesFor(lang: String): List<NeuralVoice> = neuralVoices.filter { it.lang == lang }

    /** The first on-device voice for [lang], or the default system voice when there is none. */
    fun defaultVoiceId(lang: String): String = voicesFor(lang).firstOrNull()?.voiceId ?: SYSTEM_PREFIX

    fun neural(voiceId: String): NeuralVoice? =
        neuralVoices.firstOrNull { it.voiceId == voiceId }

    /** The Android voice name for a `system:` id; empty means the engine default. */
    fun systemVoiceName(voiceId: String): String? =
        voiceId.takeIf { it.startsWith(SYSTEM_PREFIX) }?.removePrefix(SYSTEM_PREFIX)
}
