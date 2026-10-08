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
