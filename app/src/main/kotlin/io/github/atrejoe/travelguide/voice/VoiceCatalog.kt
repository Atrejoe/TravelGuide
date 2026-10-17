package io.github.atrejoe.travelguide.voice

/** An on-device Piper voice, packaged for sherpa-onnx. */
data class NeuralVoice(
    val id: String,
    val name: String,
    val description: String,
    val approxSizeMb: Int,
) {
    val voiceId: String get() = "${VoiceCatalog.NEURAL_PREFIX}$id"
    val archiveUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2"
}

object VoiceCatalog {
    const val NEURAL_PREFIX = "neural:"
    const val SYSTEM_PREFIX = "system:"

    /** Deep, calm male narrators first: the guide's signature sound. */
    val neuralVoices = listOf(
        NeuralVoice("en_US-ryan-medium", "Ryan", "Warm, unhurried American narrator (default)", 64),
        NeuralVoice("en_GB-alan-medium", "Alan", "Soft-spoken British narrator", 64),
        NeuralVoice("en_US-joe-medium", "Joe", "Relaxed American voice", 64),
        NeuralVoice("en_US-ryan-high", "Ryan HD", "Richest quality; larger and slower on older phones", 120),
    )

    val DEFAULT_VOICE_ID = neuralVoices.first().voiceId

    fun neural(voiceId: String): NeuralVoice? =
        neuralVoices.firstOrNull { it.voiceId == voiceId }

    /** The Android voice name for a `system:` id; empty means the engine default. */
    fun systemVoiceName(voiceId: String): String? =
        voiceId.takeIf { it.startsWith(SYSTEM_PREFIX) }?.removePrefix(SYSTEM_PREFIX)
}
