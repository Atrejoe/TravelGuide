package io.github.atrejoe.travelguide.core.motion

/** How the user is travelling. Drives how far ahead we look and how much detail we tell. */
enum class TravelMode {
    STATIONARY,
    WALKING,
    CYCLING,
    DRIVING,
    TRAIN,
    FLYING,
    ;

    companion object {
        /**
         * Rough speed-based classification. Car and train overlap heavily between 60 and 140 km/h,
         * so above that we assume rail; the user can override the mode manually.
         */
        fun fromSpeed(speedMps: Double): TravelMode {
            val kmh = speedMps * 3.6
            return when {
                kmh < 1.5 -> STATIONARY
                kmh < 8.0 -> WALKING
                kmh < 30.0 -> CYCLING
                kmh < 145.0 -> DRIVING
                kmh < 350.0 -> TRAIN
                else -> FLYING
            }
        }
    }
}
