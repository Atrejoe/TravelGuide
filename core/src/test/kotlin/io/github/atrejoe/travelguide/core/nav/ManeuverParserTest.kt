package io.github.atrejoe.travelguide.core.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManeuverParserTest {

    private fun parse(text: String) = assertNotNull(ManeuverParser.parse(text), text)

    @Test
    fun `distances in metric and imperial units`() {
        assertEquals(300.0, ManeuverParser.distance("300 m"))
        assertEquals(300.0, ManeuverParser.distance("In 300m turn left"))
        assertEquals(1_200.0, ManeuverParser.distance("1.2 km")!!, 1e-6)
        assertEquals(1_200.0, ManeuverParser.distance("Over 1,2 km linksaf")!!, 1e-6)
        assertEquals(804.672, ManeuverParser.distance("0.5 mi")!!, 1e-3)
        assertEquals(152.4, ManeuverParser.distance("500 ft")!!, 1e-6)
        assertEquals(365.76, ManeuverParser.distance("1,200 ft")!!, 1e-6)
        assertEquals(91.44, ManeuverParser.distance("100 yd")!!, 1e-6)
        assertEquals(250.0, ManeuverParser.distance("Nach 250 Metern rechts"))
        assertNull(ManeuverParser.distance("15 min"))
        assertNull(ManeuverParser.distance("Turn left onto A2"))
    }

    @Test
    fun `english google maps instructions`() {
        val m = parse("300 m · Turn left onto Main Street")
        assertEquals(300.0, m.distanceM)
        assertEquals(TurnDirection.LEFT, m.turn)
        assertEquals("300 m · turn left onto main street", m.text)

        assertEquals(TurnDirection.SLIGHT_RIGHT, parse("Slight right toward A2").turn)
        assertEquals("A2", parse("Slight right toward A2").toward)
        assertEquals(TurnDirection.KEEP_LEFT, parse("Keep left to continue on A12").turn)
        assertEquals(TurnDirection.SHARP_RIGHT, parse("Sharp right onto Church Rd").turn)
        assertEquals(TurnDirection.EXIT, parse("Take exit 16 toward Utrecht/Den Haag").turn)
        assertEquals("Utrecht", parse("Take exit 16 toward Utrecht/Den Haag").toward)
        assertEquals(TurnDirection.STRAIGHT, parse("Continue onto N201").turn)
        assertEquals(TurnDirection.U_TURN, parse("Make a U-turn").turn)
        // A street name containing a direction word doesn't confuse it.
        assertEquals(TurnDirection.RIGHT, parse("Turn right onto Leftwich Road").turn)
    }

    @Test
    fun `dutch instructions`() {
        val m = parse("Over 300 m · Sla linksaf naar Kerkstraat")
        assertEquals(300.0, m.distanceM)
        assertEquals(TurnDirection.LEFT, m.turn)
        assertEquals(TurnDirection.RIGHT, parse("Sla rechtsaf").turn)
        assertEquals(TurnDirection.KEEP_RIGHT, parse("Houd rechts aan richting A2").turn)
        assertEquals("A2", parse("Houd rechts aan richting A2").toward)
        assertEquals(TurnDirection.SLIGHT_LEFT, parse("Flauwe bocht naar links").turn)
        assertEquals(TurnDirection.STRAIGHT, parse("Ga rechtdoor").turn)
        assertEquals(TurnDirection.EXIT, parse("Neem afrit 12 richting Amersfoort").turn)
        assertEquals("Amersfoort", parse("Neem afrit 12 richting Amersfoort").toward)

        val roundabout = parse("1,2 km · Neem op de rotonde de 2e afslag naar Dorpsweg")
        assertEquals(TurnDirection.ROUNDABOUT, roundabout.turn)
        assertEquals(2, roundabout.roundaboutExit)
        assertEquals(1_200.0, roundabout.distanceM!!, 1e-6)
        assertEquals(3, parse("Neem op de rotonde de derde afslag").roundaboutExit)
    }

    @Test
    fun `german instructions`() {
        val m = parse("In 500 m · Links abbiegen auf Hauptstraße")
        assertEquals(500.0, m.distanceM)
        assertEquals(TurnDirection.LEFT, m.turn)
        assertEquals("in 500 m · links abbiegen auf hauptstrasse", m.text)
        assertEquals(TurnDirection.RIGHT, parse("Rechts abbiegen").turn)
        assertEquals(TurnDirection.KEEP_LEFT, parse("Links halten Richtung Köln").turn)
        assertEquals("Köln", parse("Links halten Richtung Köln").toward)
        assertEquals(TurnDirection.SHARP_LEFT, parse("Scharf links abbiegen").turn)
        assertEquals(TurnDirection.SLIGHT_RIGHT, parse("Leicht rechts abbiegen").turn)
        assertEquals(TurnDirection.STRAIGHT, parse("Geradeaus weiterfahren").turn)
        val roundabout = parse("Im Kreisverkehr die 3. Ausfahrt nehmen")
        assertEquals(TurnDirection.ROUNDABOUT, roundabout.turn)
        assertEquals(3, roundabout.roundaboutExit)
        assertEquals(TurnDirection.U_TURN, parse("Bitte wenden").turn)
    }

    @Test
    fun `title and text are read, the trip summary only as a last resort`() {
        val m = assertNotNull(ManeuverParser.parse("200 m", "Turn right onto Elm St", "15 min · 12 km · 10:45"))
        assertEquals(200.0, m.distanceM)
        assertEquals(TurnDirection.RIGHT, m.turn)
        assertNull(ManeuverParser.parse(null, " "))
        assertNull(ManeuverParser.parse(""))
    }

    @Test
    fun `notifications carry the maneuver`() {
        val status = assertNotNull(
            NavigationNotifications.parse(
                "com.google.android.apps.maps", "navigation", ongoing = true,
                title = "300 m", text = "Sla linksaf naar Kerkstraat", receivedAtMillis = 7,
            ),
        )
        assertEquals(TurnDirection.LEFT, status.maneuver?.turn)
        assertEquals(300.0, status.maneuver?.distanceM)
        assertEquals(7, status.receivedAtMillis)
    }
}
