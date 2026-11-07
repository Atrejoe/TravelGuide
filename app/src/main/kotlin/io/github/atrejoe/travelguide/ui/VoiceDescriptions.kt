package io.github.atrejoe.travelguide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.voice.NeuralVoice

/** What an on-device voice is like, in the app's language (issue #92); the catalog's English otherwise. */
@Composable
fun voiceDescription(voice: NeuralVoice): String = descriptions[voice.id]?.let { stringResource(it) } ?: voice.description

private val descriptions: Map<String, Int> = mapOf(
    "kokoro-bm_george" to R.string.voice_desc_kokoro_bm_george,
    "kokoro-bm_lewis" to R.string.voice_desc_kokoro_bm_lewis,
    "kokoro-am_michael" to R.string.voice_desc_kokoro_am_michael,
    "kokoro-am_adam" to R.string.voice_desc_kokoro_am_adam,
    "kokoro-em_alex" to R.string.voice_desc_kokoro_em_alex,
    "kokoro-em_santa" to R.string.voice_desc_kokoro_em_santa,
    "kokoro-ef_dora" to R.string.voice_desc_kokoro_ef_dora,
    "kokoro-ff_siwis" to R.string.voice_desc_kokoro_ff_siwis,
    "kokoro-im_nicola" to R.string.voice_desc_kokoro_im_nicola,
    "kokoro-if_sara" to R.string.voice_desc_kokoro_if_sara,
    "kokoro-pm_alex" to R.string.voice_desc_kokoro_pm_alex,
    "kokoro-pm_santa" to R.string.voice_desc_kokoro_pm_santa,
    "kokoro-pf_dora" to R.string.voice_desc_kokoro_pf_dora,
    "piper-norman" to R.string.voice_desc_piper_norman,
    "piper-ryan" to R.string.voice_desc_piper_ryan,
    "piper-alan" to R.string.voice_desc_piper_alan,
    "piper-nl-pim" to R.string.voice_desc_piper_nl_pim,
    "piper-nl-ronnie" to R.string.voice_desc_piper_nl_ronnie,
    "piper-nl-nathalie" to R.string.voice_desc_piper_nl_nathalie,
    "piper-de-thorsten" to R.string.voice_desc_piper_de_thorsten,
    "piper-de-thorsten-high" to R.string.voice_desc_piper_de_thorsten_high,
    "piper-de-kerstin" to R.string.voice_desc_piper_de_kerstin,
    "piper-fr-tom" to R.string.voice_desc_piper_fr_tom,
    "piper-fr-siwis" to R.string.voice_desc_piper_fr_siwis,
    "piper-es-davefx" to R.string.voice_desc_piper_es_davefx,
    "piper-es-ald" to R.string.voice_desc_piper_es_ald,
    "piper-it-paola" to R.string.voice_desc_piper_it_paola,
    "piper-it-riccardo" to R.string.voice_desc_piper_it_riccardo,
    "piper-pt-tugao" to R.string.voice_desc_piper_pt_tugao,
    "piper-pt-faber" to R.string.voice_desc_piper_pt_faber,
)
