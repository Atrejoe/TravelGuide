package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.motion.TravelMode

/**
 * How we tell stories at a given travel mode: the faster you go, the wider we look and the more
 * we favour big features (towns, rivers, mountains) over individual buildings, with shorter stories
 * so we finish before the place is behind you.
 */
data class DetailProfile(
    /** Wikipedia geosearch radius (the API caps it at 10 km). */
    val searchRadiusM: Int,
    /** How far ahead (in time) we predict the route. */
    val lookaheadSeconds: Int,
    /** Maximum number of article sentences in one story. */
    val maxSentences: Int,
    /** Minimum silence between two stories. */
    val minGapSeconds: Int,
    /** Distance from the path at which interest has dropped to half. */
    val visibilityM: Double,
    /** Stories scoring below this are skipped; silence beats filler. */
    val minScore: Double,
    private val typeWeights: Map<PoiType, Double> = emptyMap(),
) {
    fun typeWeight(type: PoiType): Double = typeWeights[type] ?: 1.0

    companion object {
        fun forMode(mode: TravelMode): DetailProfile = when (mode) {
            TravelMode.STATIONARY -> DetailProfile(
                searchRadiusM = 1_000, lookaheadSeconds = 0, maxSentences = 6, minGapSeconds = 60,
                visibilityM = 400.0, minScore = 0.15,
            )
            TravelMode.WALKING -> DetailProfile(
                searchRadiusM = 1_000, lookaheadSeconds = 120, maxSentences = 6, minGapSeconds = 30,
                visibilityM = 250.0, minScore = 0.12,
                typeWeights = mapOf(PoiType.REGION to 0.6, PoiType.COUNTRY to 0.4),
            )
            TravelMode.CYCLING -> DetailProfile(
                searchRadiusM = 2_500, lookaheadSeconds = 180, maxSentences = 5, minGapSeconds = 30,
                visibilityM = 600.0, minScore = 0.12,
                typeWeights = mapOf(PoiType.EDUCATION to 0.6, PoiType.RAILWAY_STATION to 0.6),
            )
            TravelMode.DRIVING -> DetailProfile(
                searchRadiusM = 10_000, lookaheadSeconds = 300, maxSentences = 4, minGapSeconds = 45,
                visibilityM = 2_500.0, minScore = 0.1,
                typeWeights = mapOf(
                    PoiType.BUILDING to 0.25, PoiType.EDUCATION to 0.2, PoiType.RAILWAY_STATION to 0.2,
                    PoiType.LANDMARK to 0.6, PoiType.UNKNOWN to 0.5,
                ),
            )
            TravelMode.TRAIN -> DetailProfile(
                searchRadiusM = 10_000, lookaheadSeconds = 300, maxSentences = 3, minGapSeconds = 60,
                visibilityM = 3_500.0, minScore = 0.1,
                typeWeights = mapOf(
                    PoiType.BUILDING to 0.15, PoiType.EDUCATION to 0.1, PoiType.RAILWAY_STATION to 0.3,
                    PoiType.LANDMARK to 0.5, PoiType.UNKNOWN to 0.4,
                ),
            )
            TravelMode.FLYING -> DetailProfile(
                searchRadiusM = 10_000, lookaheadSeconds = 600, maxSentences = 2, minGapSeconds = 120,
                visibilityM = 15_000.0, minScore = 0.1,
                typeWeights = mapOf(
                    PoiType.BUILDING to 0.0, PoiType.EDUCATION to 0.0, PoiType.RAILWAY_STATION to 0.0,
                    PoiType.LANDMARK to 0.2, PoiType.UNKNOWN to 0.2, PoiType.AIRPORT to 0.5,
                ),
            )
        }
    }
}
