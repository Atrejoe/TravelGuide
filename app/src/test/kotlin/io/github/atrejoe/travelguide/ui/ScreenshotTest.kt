package io.github.atrejoe.travelguide.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.MapPlace
import io.github.atrejoe.travelguide.StoryItem
import io.github.atrejoe.travelguide.UpcomingItem
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.route.PathSource
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Screenshots of the main screens for the README, rendered on the JVM. CI records them after every
 * merge to main (`./gradlew :app:recordRoborazziDebug`) and offers changed ones for a PR; see
 * CONTRIBUTING.md. A plain test run doesn't write anything.
 *
 * The content is fixed (sample stories, fixed clock times, map tiles from a committed archive, see
 * scripts/fetch-screenshot-tiles.sh), so an image only changes when the screen itself changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun fixClock() {
        Locale.setDefault(Locale.UK)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    @Config(qualifiers = "+h1250dp") // Tall enough to show the story being told and earlier ones.
    fun guide() {
        offlineTiles()
        GuideStatus.update { sample(System.currentTimeMillis()) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                TravelGuideTheme { GuideScreen(onOpenSettings = {}) }
            }
        }
        awaitTiles()
        compose.onRoot().captureRoboImage(screenshot("guide.png"))
    }

    @Test
    fun settings() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                TravelGuideTheme { SettingsScreen(onBack = {}) }
            }
        }
        compose.onRoot().captureRoboImage(screenshot("settings.png"))
    }

    /**
     * Puts the committed tile archive where osmdroid looks for offline archives (its base path,
     * set in GuideMap), so the map shows real streets without the network.
     */
    private fun offlineTiles() {
        val tiles = javaClass.getResourceAsStream("/screenshot-tiles.zip") ?: return
        val dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "osmdroid").apply { mkdirs() }
        tiles.use { input -> File(dir, "screenshot-tiles.zip").outputStream().use { input.copyTo(it) } }
    }

    /**
     * The map only asks for tiles while it is drawn, and loads them on its own threads, redrawing
     * through the main looper. So: draw, let the tiles load, and draw again for the ones the first
     * tiles made visible.
     */
    private fun awaitTiles() {
        repeat(2) {
            compose.onRoot().captureToImage()
            repeat(20) {
                Thread.sleep(100)
                ShadowLooper.idleMainLooper()
            }
            compose.waitForIdle()
        }
    }

    private fun screenshot(name: String): String =
        File(System.getProperty("user.dir"), "../docs/screenshots/$name").canonicalPath

    private fun sample(now: Long): GuideUiState {
        val here = GeoPoint(52.3812, 4.6368) // Haarlem, Grote Markt
        val bavo = StoryItem(
            title = "Grote Kerk, Haarlem",
            text = "On your left rises the Grote Kerk, the church of Saint Bavo. Building began around 1370 " +
                "and took more than a century and a half. Its Müller organ was played by a ten-year-old Mozart in 1766.",
            url = "https://en.wikipedia.org/wiki/Grote_Kerk,_Haarlem",
            timeMillis = FIXED_TIME,
            places = listOf(MapPlace("Grote Kerk", GeoPoint(52.3811, 4.6373))),
            sentences = listOf("On your left rises the Grote Kerk."),
        )
        val earlier = StoryItem(
            title = "Siege of Haarlem",
            text = "In the winter of 1572 the Spanish army laid siege to the city. For seven months its citizens held out.",
            url = "https://en.wikipedia.org/wiki/Siege_of_Haarlem",
            timeMillis = FIXED_TIME - 9 * 60_000,
            sentences = listOf("In the winter of 1572 the Spanish army laid siege to the city."),
        )
        return GuideUiState(
            running = true,
            speaking = true,
            mode = TravelMode.WALKING,
            speedKmh = 4.8,
            knownPlaces = 42,
            current = bavo,
            history = listOf(bavo, earlier),
            upcoming = listOf(
                UpcomingItem("Teylers Museum", "ahead · 600 m", now + 7 * 60_000 + 10_000, GeoPoint(52.3803, 4.6403)),
                UpcomingItem("De Adriaan", "right · 900 m", now + 12 * 60_000 + 10_000, GeoPoint(52.3838, 4.6427)),
            ),
            position = here,
            headingDeg = 90.0,
            route = listOf(here, GeoPoint(52.3810, 4.6400), GeoPoint(52.3804, 4.6430)),
            routeSource = PathSource.ROAD,
            voiceLabel = "George (on-device)",
        )
    }

    private companion object {
        /** 19 October 2026, 10:15 UTC. */
        const val FIXED_TIME = 1_792_404_900_000L
    }
}
