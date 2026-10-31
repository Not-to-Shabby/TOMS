package ph.toms.conductor.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ph.toms.conductor.data.db.ALL_MIGRATIONS
import ph.toms.conductor.data.db.PassengerJson
import ph.toms.conductor.data.db.TomsDatabase
import ph.toms.conductor.domain.CardState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TomsDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val name = "migration-test"

    private fun insertV1Trip(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, category: String?, fare: Int, discount: Int, gps: Boolean) {
        db.execSQL(
            """INSERT INTO trips (id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
               discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion, overrideReason,
               createdAtMillis, gpsLat, gpsLon, gpsAccuracyMeters, gpsFixAtMillis)
               VALUES (?, 'card-$id', 'UID$id', 's1', 's2', NULL, ?, ?, ?, ?, 3, NULL, 1000, ?, ?, ?, ?)""",
            arrayOf(id, category, fare, fare, discount, if (gps) 8.2 else null, if (gps) 124.2 else null, if (gps) 3.0 else null, if (gps) 900L else null),
        )
    }

    @Test fun `trips saved before groups become one passenger and keep their fare and GPS`() {
        helper.createDatabase(name, 1).apply {
            insertV1Trip(this, "a", null, 1500, 0, gps = true)
            insertV1Trip(this, "b", "student", 1200, 300, gps = false)
            execSQL("INSERT INTO cards (nfcUid, cardUuid, state, currentTripId, updatedAtMillis) VALUES ('UIDa', 'card-a', 'ASSIGNED_PAID', 'a', 1)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e1', 'dev', 'trip_created', '{}', 1)")
            execSQL("INSERT INTO outbox (eventId, attemptCount) VALUES ('e1', 2)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, *ALL_MIGRATIONS)

        db.query("SELECT id, passengersJson, fareCentavos, discountCentavos, gpsLat, discountCategoryId FROM trips ORDER BY id").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("a", c.getString(0))
            val a = PassengerJson.decode(c.getString(1)).single()
            assertEquals(null, a.categoryId); assertEquals(1, a.count); assertEquals(1500, a.fareCentavos); assertEquals(1500, a.perPersonCentavos)
            assertEquals(1500, c.getInt(2)); assertEquals(8.2, c.getDouble(4), 1e-9)

            assertTrue(c.moveToNext())
            assertEquals("b", c.getString(0))
            val b = PassengerJson.decode(c.getString(1)).single()
            assertEquals("student", b.categoryId); assertEquals(1200, b.fareCentavos); assertEquals(300, b.discountCentavos)
            assertTrue(c.isNull(4))
            assertEquals("student", c.getString(5))
        }
    }

    @Test fun `the other tables and the pending outbox survive untouched`() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO cards (nfcUid, cardUuid, state, currentTripId, updatedAtMillis) VALUES ('UID1', 'c1', 'RETURNED', 't', 5)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e1', 'dev', 'x', '{\"k\":1}', 7)")
            execSQL("INSERT INTO outbox (eventId, deliveryChannel, attemptCount) VALUES ('e1', NULL, 4)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, *ALL_MIGRATIONS)
        db.query("SELECT state FROM cards WHERE nfcUid = 'UID1'").use { assertTrue(it.moveToFirst()); assertEquals("RETURNED", it.getString(0)) }
        db.query("SELECT payload FROM events WHERE eventId = 'e1'").use { assertTrue(it.moveToFirst()); assertEquals("{\"k\":1}", it.getString(0)) }
        db.query("SELECT attemptCount, deliveredAtMillis FROM outbox WHERE eventId = 'e1'").use {
            assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0)); assertTrue(it.isNull(1))
        }
    }

    @Test fun `an empty version 1 database migrates`() {
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(name, 2, true, *ALL_MIGRATIONS).close()
    }

    @Test fun `a migrated database opens through Room and reads the old trip as one passenger`() = runBlocking {
        helper.createDatabase(name, 1).apply {
            insertV1Trip(this, "old", "pwd", 1200, 300, gps = false)
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, *ALL_MIGRATIONS).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, TomsDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val trip = TripRepository(room, "dev").loadTrips().single()
            assertEquals(1, trip.passengerCount)
            assertEquals("pwd", trip.passengers.single().categoryId)
            assertEquals(1200, trip.fareCentavos)
            assertNull(trip.gps)
        } finally {
            room.close()
        }
    }

    private fun insertV2Trip(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """INSERT INTO trips (id, cardUuid, nfcUid, boardingStopId, declaredDestinationStopId, actualDestinationStopId,
               discountCategoryId, computedFareCentavos, fareCentavos, discountCentavos, fareVersion, overrideReason,
               createdAtMillis, passengersJson)
               VALUES (?, 'card-$id', 'UID$id', 's1', 's2', NULL, NULL, 1500, 1500, 0, 3, NULL, 1000, '[]')""",
            arrayOf(id),
        )
    }

    @Test fun `version 3 keeps existing trips and events, adds shifts, and leaves old rows in no shift`() {
        helper.createDatabase(name, 2).apply {
            insertV2Trip(this, "a")
            execSQL("INSERT INTO cards (nfcUid, cardUuid, state, currentTripId, updatedAtMillis) VALUES ('UIDa', 'card-a', 'ASSIGNED_PAID', 'a', 1)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e1', 'dev', 'trip_created', '{\"tripId\":\"a\"}', 5)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e2', 'dev', 'card_state_changed', '{\"tripId\":\"a\",\"state\":\"ASSIGNED_PAID\"}', 6)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e3', 'dev', 'something_else', 'not json at all', 7)")
            execSQL("INSERT INTO outbox (eventId, attemptCount) VALUES ('e2', 3)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 3, true, *ALL_MIGRATIONS)

        db.query("SELECT id, fareCentavos, shiftId FROM trips").use {
            assertTrue(it.moveToFirst()); assertEquals("a", it.getString(0)); assertEquals(1500, it.getInt(1)); assertTrue(it.isNull(2))
        }
        db.query("SELECT eventId, shiftId, tripId, cardState FROM events ORDER BY seq").use {
            assertTrue(it.moveToNext()); assertEquals("e1", it.getString(0)); assertTrue(it.isNull(1)); assertEquals("a", it.getString(2)); assertTrue(it.isNull(3))
            assertTrue(it.moveToNext()); assertEquals("e2", it.getString(0)); assertEquals("a", it.getString(2)); assertEquals("ASSIGNED_PAID", it.getString(3))
            // An event the backfill cannot read is kept as it was, with nothing guessed.
            assertTrue(it.moveToNext()); assertEquals("e3", it.getString(0)); assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
        }
        db.query("SELECT attemptCount FROM outbox WHERE eventId = 'e2'").use { assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM shifts").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }

    @Test fun `a version 2 database opened through Room can start a shift and keeps its old trip out of it`() = runBlocking {
        helper.createDatabase(name, 2).apply {
            insertV2Trip(this, "old")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, *ALL_MIGRATIONS).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, TomsDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val repo = TripRepository(room, "dev")
            val shift = (repo.startShift("juan", "v1", "r1") as StartShiftResult.Started).shift
            val summary = repo.summaryFor(shift.id)
            assertEquals(0, summary.tripCount)
            assertEquals(0L, summary.faresChargedCentavos)
            assertEquals(0, summary.carriedIn.stillUnpaidCount)
            assertEquals("the old trip is still there", 1, repo.loadTrips().size)
        } finally {
            room.close()
        }
    }

    @Test fun `version 4 keeps pending and delivered outbox rows and leaves them unparked`() {
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e1', 'dev', 'x', '{}', 1)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e2', 'dev', 'x', '{}', 2)")
            execSQL("INSERT INTO outbox (eventId, attemptCount) VALUES ('e1', 3)")
            execSQL("INSERT INTO outbox (eventId, deliveryChannel, deliveredAtMillis, attemptCount) VALUES ('e2', 'data_a', 9, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 4, true, *ALL_MIGRATIONS)

        db.query("SELECT eventId, attemptCount, deliveredAtMillis, parkedAtMillis, parkedReason FROM outbox ORDER BY eventId").use {
            assertTrue(it.moveToNext()); assertEquals("e1", it.getString(0)); assertEquals(3, it.getInt(1)); assertTrue(it.isNull(2)); assertTrue(it.isNull(3)); assertTrue(it.isNull(4))
            assertTrue(it.moveToNext()); assertEquals("e2", it.getString(0)); assertEquals(9L, it.getLong(2)); assertTrue(it.isNull(3))
        }
    }

    @Test fun `version 5 adds an empty server config table and keeps everything else`() {
        helper.createDatabase(name, 4).apply {
            insertV2Trip(this, "a")
            execSQL("INSERT INTO config_cache (`key`, version, json, fetchedAtMillis) VALUES ('approved_cards', 1, '{}', 9)")
            execSQL("INSERT INTO events (eventId, deviceId, type, payload, createdAtMillis) VALUES ('e1', 'dev', 'x', '{}', 1)")
            execSQL("INSERT INTO outbox (eventId, attemptCount, parkedAtMillis, parkedReason) VALUES ('e1', 2, 7, 'rejected')")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, *ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM server_config").use { assertTrue(it.moveToFirst()); assertEquals("nothing is seeded", 0, it.getInt(0)) }
        db.query("SELECT id, fareCentavos FROM trips").use { assertTrue(it.moveToFirst()); assertEquals("a", it.getString(0)); assertEquals(1500, it.getInt(1)) }
        db.query("SELECT json FROM config_cache WHERE `key` = 'approved_cards'").use { assertTrue(it.moveToFirst()); assertEquals("{}", it.getString(0)) }
        db.query("SELECT attemptCount, parkedAtMillis, parkedReason FROM outbox WHERE eventId = 'e1'").use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals(7L, it.getLong(1)); assertEquals("rejected", it.getString(2))
        }
    }

    @Test fun `a version 4 database opened through Room has no fares until some are saved`() = runBlocking {
        helper.createDatabase(name, 4).apply {
            insertV2Trip(this, "old")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, *ALL_MIGRATIONS).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, TomsDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val repo = TripRepository(room, "dev")
            assertNull(repo.loadServerConfig())
            repo.saveServerConfig(serverConfigResponse(version = 12))
            assertEquals(12, repo.loadServerConfig()!!.version)
            assertEquals("the old trip is still there", 1, repo.loadTrips().size)
        } finally {
            room.close()
        }
    }

    @Test fun `a group saved after the migration round-trips with every line`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, TomsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = TripRepository(room, "dev")
            val trip = ph.toms.conductor.domain.Trip(
                id = "g", cardUuid = "c", nfcUid = "UIDG", boardingStopId = "s1", declaredDestinationStopId = "s2",
                actualDestinationStopId = null, discountCategoryId = null, computedFareCentavos = 6800, fareCentavos = 6800,
                discountCentavos = 600, fareVersion = 1, overrideReason = null, createdAtMillis = 5,
                passengers = listOf(
                    ph.toms.conductor.domain.LineQuote(null, 2, 2800, 5600, 0),
                    ph.toms.conductor.domain.LineQuote("student", 1, 2200, 2200, 600),
                ),
            )
            repo.recordTrip(trip, CardState.ASSIGNED_UNPAID, 5)
            val back = repo.loadTrips().single()
            assertEquals(3, back.passengerCount)
            assertEquals(trip.passengers, back.passengers)
        } finally {
            room.close()
        }
    }
}
