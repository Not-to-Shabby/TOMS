package ph.toms.conductor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupFareTest {

    private val a = Stop("a", "A", 8.00, 124.0)
    private val c = Stop("c", "C", 8.10, 124.0) // ~11.1 km: base 1200 + 8 km * 200 = 2800 regular
    private val fare = FareConfig(version = 5, baseFareCentavos = 1200, baseDistanceKm = 4.0, perKmCentavos = 200, roundingStepCentavos = 100)
    private val config = TomsConfig(
        version = 1,
        stops = listOf(a, c),
        fare = fare,
        discountCategories = listOf(
            DiscountCategory("student", "Student", 20),
            DiscountCategory("pwd", "PWD", 20),
            DiscountCategory("gone", "Gone", 20, active = false),
        ),
        approvedCards = ApprovedCards(mapOf("04AA" to "uuid-1")),
    )

    private fun ok(lines: List<PassengerLine>) = (GroupFare.quote(a, c, lines, config) as GroupQuoteResult.Ok).quote
    private fun invalid(lines: List<PassengerLine>) = (GroupFare.quote(a, c, lines, config) as GroupQuoteResult.Invalid).reason

    @Test fun `a family is the sum of each person's own fare`() {
        val q = ok(listOf(PassengerLine(null, 2), PassengerLine("student", 1)))
        // regular 2800 each; student 2800 * 0.8 = 2240 -> 2200
        assertEquals(2 * 2800 + 2200, q.totalCentavos)
        assertEquals(3, q.passengerCount)
        assertEquals(600, q.discountCentavos)
    }

    @Test fun `rounding is per person, not on the group`() {
        val q = ok(listOf(PassengerLine("student", 2)))
        // per person 2240 -> 2200; two together 4400. A rounded pair (4480) would give 4500.
        assertEquals(4400, q.totalCentavos)
        assertEquals(2200, q.lines.single().perPersonCentavos)
    }

    @Test fun `with a fine rounding step each person is rounded on their own`() {
        // Step 0.50: one student pays 2240 -> 2250. A group of three is 3 * 2250 = 6750.
        // Rounding the group's unrounded 3 * 2240 = 6720 would give 6700, so the two differ.
        val fine = config.copy(fare = fare.copy(roundingStepCentavos = 50))
        val q = (GroupFare.quote(a, c, listOf(PassengerLine("student", 3)), fine) as GroupQuoteResult.Ok).quote
        assertEquals(2250, q.lines.single().perPersonCentavos)
        assertEquals(6750, q.totalCentavos)
    }

    @Test fun `each line reports its count, per-person fare and total`() {
        val line = ok(listOf(PassengerLine(null, 3))).lines.single()
        assertEquals(3, line.count)
        assertEquals(2800, line.perPersonCentavos)
        assertEquals(8400, line.fareCentavos)
    }

    @Test fun `the order the conductor tapped in does not change the result`() {
        val one = ok(listOf(PassengerLine("student", 1), PassengerLine(null, 2), PassengerLine("pwd", 1)))
        val two = ok(listOf(PassengerLine("pwd", 1), PassengerLine(null, 2), PassengerLine("student", 1)))
        assertEquals(one, two)
    }

    @Test fun `lines of the same type are merged`() {
        val q = ok(listOf(PassengerLine(null, 1), PassengerLine(null, 2)))
        assertEquals(1, q.lines.size)
        assertEquals(3, q.lines.single().count)
    }

    @Test fun `regular comes first and categories are alphabetical`() {
        val q = ok(listOf(PassengerLine("student", 1), PassengerLine("pwd", 1), PassengerLine(null, 1)))
        assertEquals(listOf<String?>(null, "pwd", "student"), q.lines.map { it.categoryId })
    }

    @Test fun `a free fare group quotes zero without failing`() {
        val free = config.copy(fare = fare.copy(baseFareCentavos = 0, perKmCentavos = 0))
        val q = (GroupFare.quote(a, c, listOf(PassengerLine(null, 4)), free) as GroupQuoteResult.Ok).quote
        assertEquals(0, q.totalCentavos)
        assertEquals(4, q.passengerCount)
    }

    @Test fun `empty group, unknown or inactive type and an oversized group are rejected`() {
        assertEquals(TripRejection.NO_PASSENGERS, invalid(emptyList()))
        assertEquals(TripRejection.UNKNOWN_CATEGORY, invalid(listOf(PassengerLine("nope", 1))))
        assertEquals(TripRejection.UNKNOWN_CATEGORY, invalid(listOf(PassengerLine("gone", 1))))
        assertEquals(TripRejection.GROUP_TOO_LARGE, invalid(listOf(PassengerLine(null, MAX_GROUP_SIZE + 1))))
        assertEquals(TripRejection.GROUP_TOO_LARGE, invalid(listOf(PassengerLine(null, 20), PassengerLine("student", 11))))
        assertTrue(GroupFare.quote(a, c, listOf(PassengerLine(null, MAX_GROUP_SIZE)), config) is GroupQuoteResult.Ok)
    }

    @Test fun `a passenger line needs at least one person`() {
        assertTrue(runCatching { PassengerLine(null, 0) }.isFailure)
        assertTrue(runCatching { PassengerLine(null, -2) }.isFailure)
    }

    @Test fun `the fare version is recorded`() {
        assertEquals(5, ok(listOf(PassengerLine(null, 1))).fareVersion)
    }
}

class GroupTripTest {

    private val a = Stop("a", "A", 8.00, 124.0)
    private val c = Stop("c", "C", 8.10, 124.0)
    private val config = TomsConfig(
        version = 1,
        stops = listOf(a, c),
        fare = FareConfig(5, 1200, 4.0, 200, 100),
        discountCategories = listOf(DiscountCategory("student", "Student", 20), DiscountCategory("pwd", "PWD", 20)),
        approvedCards = ApprovedCards(mapOf("04AA" to "uuid-1")),
    )

    private fun create(passengers: List<PassengerLine>, override: TripOverride? = null, state: CardState = CardState.AVAILABLE) =
        TripFactory.create("t1", "04aa", state, "a", "c", passengers, override, config, 1000)

    private fun trip(passengers: List<PassengerLine>, override: TripOverride? = null) =
        (create(passengers, override) as TripResult.Created).trip

    @Test fun `one card covers the whole family and the trip keeps every line`() {
        val t = trip(listOf(PassengerLine(null, 2), PassengerLine("student", 2)))
        assertEquals(4, t.passengerCount)
        assertEquals(2 * 2800 + 2 * 2200, t.fareCentavos)
        assertEquals(listOf<String?>(null, "student"), t.passengers.map { it.categoryId })
    }

    @Test fun `a single-type group keeps that type, a mixed group has none`() {
        assertEquals("student", trip(listOf(PassengerLine("student", 3))).discountCategoryId)
        assertNull(trip(listOf(PassengerLine(null, 2))).discountCategoryId)
        assertNull(trip(listOf(PassengerLine(null, 1), PassengerLine("student", 1))).discountCategoryId)
    }

    @Test fun `an override replaces the total and the computed total is kept`() {
        val t = trip(listOf(PassengerLine(null, 2)), TripOverride("fare waived", fareCentavos = 0))
        assertEquals(0, t.fareCentavos)
        assertEquals(5600, t.computedFareCentavos)
        assertEquals(2, t.passengerCount)
    }

    @Test fun `no passengers means no trip, and a card already out cannot start a second one`() {
        assertEquals(TripRejection.NO_PASSENGERS, (create(emptyList()) as TripResult.Rejected).reason)
        assertEquals(TripRejection.CARD_NOT_AVAILABLE, (create(listOf(PassengerLine(null, 1)), state = CardState.ASSIGNED_UNPAID) as TripResult.Rejected).reason)
    }

    @Test fun `a mixed group has no single category even when the first line is a discount type`() {
        val t = trip(listOf(PassengerLine("student", 1), PassengerLine("pwd", 1)))
        assertNull(t.discountCategoryId)
        assertEquals(2, t.passengers.size)
    }

    @Test fun `a default trip is one passenger`() {
        assertEquals(1, trip(listOf(PassengerLine(null, 1))).passengerCount)
    }
}

class PassengerTallyTest {

    @Test fun `each tap adds one passenger of that type`() {
        val t = PassengerTally().add(null).add(null).add("student")
        assertEquals(3, t.total)
        assertEquals(setOf(PassengerLine(null, 2), PassengerLine("student", 1)), t.toLines().toSet())
    }

    @Test fun `a new group starts as one regular passenger`() {
        assertEquals(listOf(PassengerLine(null, 1)), PassengerTally.starting().toLines())
    }

    @Test fun `undo removes the most recent passenger, whatever type`() {
        val t = PassengerTally().add(null).add("student").add(null).undo()
        assertEquals(setOf(PassengerLine(null, 1), PassengerLine("student", 1)), t.toLines().toSet())
        val u = t.undo()
        assertEquals(listOf(PassengerLine(null, 1)), u.toLines())
    }

    @Test fun `undo on an empty tally and clear are safe`() {
        assertEquals(0, PassengerTally().undo().total)
        assertEquals(0, PassengerTally().add(null).add("pwd").clear().total)
    }

    @Test fun `undoing the last of a type removes the line`() {
        val t = PassengerTally().add("pwd").undo()
        assertTrue(t.toLines().isEmpty())
        assertFalse(t.counts.containsKey("pwd"))
    }

    @Test fun `the group stops growing at the maximum`() {
        var t = PassengerTally()
        repeat(MAX_GROUP_SIZE + 10) { t = t.add(null) }
        assertEquals(MAX_GROUP_SIZE, t.total)
    }

    @Test fun `undo after hitting the maximum does not lose track of history`() {
        var t = PassengerTally()
        repeat(MAX_GROUP_SIZE + 5) { t = t.add(null) }
        assertEquals(MAX_GROUP_SIZE - 1, t.undo().total)
    }
}

class TapResolverTest {

    private val cfg = TomsConfig(
        version = 1, stops = emptyList(), fare = FareConfig(0, 1500, 4.0, 250, 100), discountCategories = emptyList(),
        approvedCards = ApprovedCards(mapOf("04AA" to "uuid-1")),
    )
    private val open = Trip(
        id = "t", cardUuid = "uuid-1", nfcUid = "04AA", boardingStopId = "a", declaredDestinationStopId = "c",
        actualDestinationStopId = null, discountCategoryId = null, computedFareCentavos = 1500, fareCentavos = 1500,
        discountCentavos = 0, fareVersion = 0, overrideReason = null, createdAtMillis = 0,
    )

    private fun resolve(uid: String, state: CardState, trip: Trip? = open) =
        TapResolver.resolve(uid, cfg, { state }, { trip })

    @Test fun `a free card starts a trip`() {
        assertEquals(TapOutcome.StartTrip("04AA"), resolve("04:aa", CardState.AVAILABLE))
    }

    @Test fun `a card already out shows its trip instead of an error`() {
        assertEquals(TapOutcome.ShowTrip(open, CardState.ASSIGNED_UNPAID), resolve("04aa", CardState.ASSIGNED_UNPAID))
        assertEquals(TapOutcome.ShowTrip(open, CardState.ASSIGNED_PAID), resolve("04aa", CardState.ASSIGNED_PAID))
        assertEquals(TapOutcome.ShowTrip(open, CardState.RETURNED), resolve("04aa", CardState.RETURNED))
    }

    @Test fun `an unknown card is reported by its normalized uid`() {
        assertEquals(TapOutcome.UnknownCard("DEADBEEF"), resolve("de:ad:be:ef", CardState.AVAILABLE))
    }

    @Test fun `a card that is out but has no trip on record needs attention, not a guess`() {
        assertEquals(TapOutcome.NeedsAttention(null, CardState.ASSIGNED_PAID), resolve("04aa", CardState.ASSIGNED_PAID, trip = null))
    }

    @Test fun `alarm and lost cards need the conductor`() {
        assertEquals(TapOutcome.NeedsAttention(open, CardState.RETURNED_UNPAID), resolve("04aa", CardState.RETURNED_UNPAID))
        assertEquals(TapOutcome.NeedsAttention(open, CardState.LOST), resolve("04aa", CardState.LOST))
    }

    @Test fun `an unpaid card shows its trip too, not only a paid one`() {
        val r = resolve("04aa", CardState.ASSIGNED_UNPAID)
        assertTrue(r is TapOutcome.ShowTrip)
        assertEquals(CardState.ASSIGNED_UNPAID, (r as TapOutcome.ShowTrip).state)
    }

    @Test fun `resolving never starts a trip for a card that is out`() {
        for (s in listOf(CardState.ASSIGNED_UNPAID, CardState.ASSIGNED_PAID, CardState.RETURNED, CardState.RETURNED_UNPAID, CardState.LOST)) {
            assertFalse(s.name, resolve("04aa", s) is TapOutcome.StartTrip)
        }
    }
}
