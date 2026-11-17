package io.github.atrejoe.travelguide.service

import androidx.car.app.connection.CarConnection
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.TravelGuideApp
import io.github.atrejoe.travelguide.nav.NavigationState
import io.github.atrejoe.travelguide.settings.CarNavigationStories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Offers stories while a navigation app guides you in Android Auto (issue #94).
 *
 * When Android Auto is connected, a navigation app navigates (seen through its notifications) and
 * the guide is off, the listener's choice decides: [CarNavigationStories.ASK] shows the offer in
 * the car ([GuideMedia]), [CarNavigationStories.ALWAYS] starts the guide, and
 * [CarNavigationStories.NEVER] does nothing. The offer is made once per trip: answered, ignored
 * by stopping the guide, or "not now", it comes back with the next navigation.
 */
object CarStoryOffer {
    private val mutable = MutableStateFlow(false)

    /** True while the car offers to tell stories. */
    val offered: StateFlow<Boolean> = mutable.asStateFlow()

    /** This trip's offer was answered, or the guide ran: not offered again until the next trip. */
    private var handled = false

    private lateinit var app: TravelGuideApp

    private data class Inputs(
        val inCar: Boolean,
        val navigating: Boolean,
        val choice: CarNavigationStories,
        val running: Boolean,
    )

    /** Watches the car, navigation and the guide for as long as the app runs. Call on the main thread. */
    fun start(app: TravelGuideApp) {
        this.app = app
        val inCar = MutableStateFlow(false)
        CarConnection(app).type.observeForever { type ->
            inCar.value = type != null && type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(
                inCar,
                NavigationState.status.map { it != null }.distinctUntilChanged(),
                app.settings.settings.map { it.carNavigationStories }.distinctUntilChanged(),
                GuideStatus.state.map { it.running }.distinctUntilChanged(),
            ) { car, navigating, choice, running -> Inputs(car, navigating, choice, running) }
                .distinctUntilChanged()
                .collect { decide(it) }
        }
    }

    private fun decide(inputs: Inputs) {
        if (!inputs.inCar || !inputs.navigating) {
            // The trip is over: the next navigation may be offered again.
            handled = false
            mutable.value = false
            return
        }
        if (inputs.running) {
            handled = true
            mutable.value = false
            return
        }
        when (inputs.choice) {
            CarNavigationStories.NEVER -> mutable.value = false
            CarNavigationStories.ASK -> show(!handled)
            CarNavigationStories.ALWAYS -> if (!handled) {
                handled = true
                // Android may not let the guide start by itself; then the car offers it instead.
                if (!GuideMedia.play(app)) show(true)
            }
        }
    }

    private fun show(offer: Boolean) {
        // The car shows the offer through the media session, so make sure there is one.
        if (offer) GuideMedia.session(app)
        mutable.value = offer
    }

    /** "Not now": no offer until the next trip. */
    fun dismiss() {
        handled = true
        mutable.value = false
    }

    /** Remembers the listener's answer; [CarNavigationStories.ALWAYS] also starts the guide. */
    fun answer(choice: CarNavigationStories) {
        handled = true
        mutable.value = false
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            app.settings.update { it.copy(carNavigationStories = choice) }
        }
        if (choice == CarNavigationStories.ALWAYS) GuideMedia.play(app)
    }
}
