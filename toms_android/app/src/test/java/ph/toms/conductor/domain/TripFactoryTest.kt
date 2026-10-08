package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripFactoryTest {

    private val uuid = "7b1f2c3e-0000-4000-8000-000000000001"

    // Stops roughly 1 degree latitude apart per step => ~111 km, so distances are large and obvious.
    private val a = Stop("a", "A", 8.00, 124.0)
    private val b = Stop("b", "B", 8.03, 124.0) // ~3.3 km from A
    private val c = Stop("c", "C", 8.10, 124.0) // ~11.1 km from A

    private val config = TomsConfig(
        version = 3,
        stops = listOf(a, b, c),
        fare = FareConfig(version = 12, baseFareCentavos = 1200, baseDistanceKm = 4.0, perKmCentavos = 200, roundingStepCentavos = 100),
        discountCategories = listOf(
            DiscountCategory("student", "Student", 20),
            DiscountCategory("retired", "Retired category", 20, active = false),
        ),
        approvedCards = ApprovedCards(mapOf("04:A1:B2:C3:D4:E5:F6" to uuid)),
    )

    private fun create(
        uid: String = "04:a1:b2:c3:d4:e5:f6",
        state: CardState = CardState.AVAILABLE,
        dest: String = "b",
        category: String? = null,
        override: TripOverride? = null,
        passengers: List<PassengerLine> = listOf(PassengerLine(category, 1)),
    ) = TripFactory.create("trip-1", uid, state, "a", dest, passengers, override, config, nowMillis = 1000)

    private fun created(r: TripResult) = r as TripResult.Created

    @Test fun `tap builds trip with card uuid not the nfc uid`() {
        val t = created(create()).trip
        assertEquals(uuid, t.cardUuid)
        assertEquals("04A1B2C3D4E5F6", t.nfcUid)
        assertEquals(12, t.fareVersion)
        assertEquals(1200, t.fareCentavos)
        assertNull(t.actualDestinationStopId)
        assertNull(t.overrideReason)
    }

    @Test fun `successful assign moves card to assigned_unpaid`() {
        assertEquals(CardState.ASSIGNED_UNPAID, created(create()).cardState)
    }

    @Test fun `unknown uid is rejected`() {
        val r = create(uid = "04:00:00:00:00:00:00") as TripResult.Rejected
        assertEquals(TripRejection.UNKNOWN_CARD, r.reason)
    }

    @Test fun `already assigned card is blocked`() {
        val r = create(state = CardState.ASSIGNED_UNPAID) as TripResult.Rejected
        assertEquals(TripRejection.CARD_NOT_AVAILABLE, r.reason)
    }

    @Test fun `unknown stop and inactive category are rejected`() {
        assertEquals(TripRejection.UNKNOWN_STOP, (create(dest = "zzz") as TripResult.Rejected).reason)
        assertEquals(TripRejection.UNKNOWN_CATEGORY, (create(category = "retired") as TripResult.Rejected).reason)
        assertEquals(TripRejection.UNKNOWN_CATEGORY, (create(category = "nope") as TripResult.Rejected).reason)
    }

    @Test fun `discount category applied and recorded`() {
        val t = created(create(dest = "c", category = "student")).trip
        // ~11.12 km => 8 started km beyond 4 => 1200 + 1600 = 2800; 20% => 2240 -> 2200
        assertEquals(2200, t.fareCentavos)
        assertEquals("student", t.discountCategoryId)
        assertEquals(600, t.discountCentavos)
    }

    @Test fun `conductor can override destination and the declared stop is kept`() {
        val t = created(create(dest = "b", override = TripOverride(reason = "Passenger rides on to C", destinationStopId = "c"))).trip
        assertEquals("b", t.declaredDestinationStopId)
        assertEquals("c", t.actualDestinationStopId)
        assertEquals(2800, t.fareCentavos)
        assertEquals("Passenger rides on to C", t.overrideReason)
    }

    @Test fun `override to the same stop is not recorded as an actual destination`() {
        val t = created(create(dest = "b", override = TripOverride(reason = "confirmed", destinationStopId = "b"))).trip
        assertNull(t.actualDestinationStopId)
    }

    @Test fun `conductor can override the fare and the computed fare is still kept`() {
        val t = created(create(dest = "c", override = TripOverride(reason = "Fare waived by dispatcher", fareCentavos = 0))).trip
        assertEquals(0, t.fareCentavos)
        assertEquals(2800, t.computedFareCentavos)
    }

    @Test fun `override requires a reason and a sane fare`() {
        assertEquals(TripRejection.OVERRIDE_NEEDS_REASON, (create(override = TripOverride(reason = "  ", fareCentavos = 500)) as TripResult.Rejected).reason)
        assertEquals(TripRejection.INVALID_OVERRIDE_FARE, (create(override = TripOverride(reason = "x", fareCentavos = -1)) as TripResult.Rejected).reason)
        assertEquals(TripRejection.UNKNOWN_STOP, (create(override = TripOverride(reason = "x", destinationStopId = "zzz")) as TripResult.Rejected).reason)
    }
}

class ApprovedCardsAndReceiptTest {

    private val cards = ApprovedCards(mapOf("04:A1:B2:C3" to "uuid-1"))

    @Test fun `lookup ignores case and separators`() {
        assertEquals("uuid-1", cards.cardUuidFor("04a1b2c3"))
        assertEquals("uuid-1", cards.cardUuidFor("04-A1-B2-C3"))
        assertNotNull(cards.cardUuidFor("04 a1 b2 c3"))
        assertNull(cards.cardUuidFor("04A1B2C4"))
    }

    @Test fun `receipt urls are built from card uuid and trip token only`() {
        val card = ReceiptLinks.cardUrl("https://toms.example/", "uuid-1")
        assertEquals("https://toms.example/r/uuid-1", card)
        assertEquals("https://toms.example/t/tok123", ReceiptLinks.tripUrl("https://toms.example", "tok123"))
        assertTrue(!card.contains("04A1B2C3", ignoreCase = true))
    }
}
