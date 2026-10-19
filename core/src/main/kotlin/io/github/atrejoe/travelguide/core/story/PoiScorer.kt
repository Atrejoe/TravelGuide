package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.interest.InterestClassifier
import io.github.atrejoe.travelguide.core.route.PathPosition
import io.github.atrejoe.travelguide.core.route.PredictedPath
import kotlin.math.log10
import kotlin.math.sqrt

data class ScoredPoi(val poi: Poi, val score: Double, val position: PathPosition, val distanceM: Double)

/** Ranks places by how relevant they are to tell about right now. */
class PoiScorer(
    private val profile: DetailProfile,
    private val interestWeights: Map<Interest, Double> = emptyMap(),
) {
    fun score(poi: Poi, path: PredictedPath): ScoredPoi? {
        val position = path.locate(poi.point)
        val distance = GeoMath.distance(path.start, poi.point)
        val size = poi.dimM ?: 0.0
        val visibility = profile.visibilityM + size / 2
        val moving = path.points.size > 1

        val inside = poi.type.isArea && distance <= insideRadius(poi)
        // Already passed: not worth starting a story about it (unless we are still inside it).
        if (moving && !inside && position.alongM < -0.25 * visibility) return null

        val lateral = 1.0 / (1.0 + (position.crossM / visibility).let { it * it })
        val beyond = (position.alongM - path.lengthM).coerceAtLeast(0.0)
        val reach = 1.0 / (1.0 + beyond / visibility)
        val geometry = if (inside) 1.0 else lateral * reach

        val score = prominence(poi) *
            profile.typeWeight(poi.type) *
            InterestClassifier.weight(poi, interestWeights) *
            geometry *
            (if (inside) 1.3 else 1.0)
        if (score <= 0.0) return null
        return ScoredPoi(poi, score, position, distance)
    }

    companion object {
        /** Radius within which we consider the user to be "in" an area such as a town. */
        fun insideRadius(poi: Poi): Double {
            val fromPopulation = poi.population?.let { sqrt(it.toDouble()) * 8 } ?: 0.0
            val fromDim = (poi.dimM ?: 0.0) / 2
            return maxOf(1_000.0, fromPopulation, fromDim).coerceAtMost(15_000.0)
        }

        fun prominence(poi: Poi): Double {
            // A building known from OpenStreetMap: what it is and how much we know about it.
            if (poi.extract.isBlank()) poi.facts?.let { return it.prominence(poi.hasArticle) }
            var p = poi.type.prominence
            poi.population?.takeIf { it > 0 }?.let { pop ->
                // 1k people -> x0.8, 100k -> x1.2, 1M+ -> x1.4
                p *= (0.2 * log10(pop.toDouble()) + 0.2).coerceIn(0.6, 1.4)
            }
            // Longer lead sections usually mean more notable subjects (and better stories).
            p *= (0.6 + poi.extract.length / 1_500.0).coerceAtMost(1.2)
            return p
        }
    }
}
