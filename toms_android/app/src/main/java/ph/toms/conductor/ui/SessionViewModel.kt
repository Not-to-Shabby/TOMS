package ph.toms.conductor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.toms.conductor.data.Assignment
import ph.toms.conductor.data.RouteInfo
import ph.toms.conductor.data.SessionRepository
import ph.toms.conductor.data.TestConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.Vehicle
import ph.toms.conductor.domain.ApprovedCards
import ph.toms.conductor.domain.CardEvent
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.CardStateMachine
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.domain.PassengerLine
import ph.toms.conductor.domain.StopMatch
import ph.toms.conductor.domain.StopMatcher
import ph.toms.conductor.domain.TapDebouncer
import ph.toms.conductor.domain.TomsConfig
import ph.toms.conductor.domain.Transition
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.domain.TripFactory
import ph.toms.conductor.domain.TripRejection
import ph.toms.conductor.domain.TripResult
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.nfc.CardRead

data class TripRow(val trip: Trip, val cardState: CardState)

data class SessionUiState(
    val conductor: String? = null,
    val loginError: String? = null,
    val busy: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val routes: List<RouteInfo> = emptyList(),
    val assignment: Assignment? = null,
    val config: TomsConfig = TestConfig.config,
    val boardingStopId: String = TestConfig.config.stops.first().id,
    val destinationStopId: String = TestConfig.config.stops.getOrElse(1) { TestConfig.config.stops.first() }.id,
    val categoryId: String? = null,
    val reads: List<CardRead> = emptyList(),
    val trips: List<TripRow> = emptyList(),
    val pendingCount: Int = 0,
    val fix: GeoFix? = null,
    val nearestStop: StopMatch? = null,
    val gpsEnabled: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val trips: TripRepository,
    private val location: LocationTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val debouncer = TapDebouncer()
    private val cardStates = HashMap<String, CardState>()

    init {
        viewModelScope.launch {
            cardStates.putAll(trips.loadCardStates())
            val restored = trips.loadTrips().map { TripRow(it, cardStates[it.nfcUid] ?: CardState.AVAILABLE) }
            _state.update { it.copy(trips = restored) }
        }
        viewModelScope.launch {
            trips.pendingCount().collect { n -> _state.update { it.copy(pendingCount = n) } }
        }
    }

    fun login(username: String, password: String) {
        _state.update { it.copy(busy = true, loginError = null) }
        viewModelScope.launch {
            repo.login(username, password)
                .onSuccess { name ->
                    _state.update {
                        it.copy(busy = false, conductor = name, vehicles = repo.vehicles(), routes = repo.routes())
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, loginError = e.message) } }
        }
    }

    fun assign(vehicle: Vehicle, route: RouteInfo) {
        val conductor = _state.value.conductor ?: return
        _state.update { it.copy(assignment = Assignment(conductor, vehicle, route)) }
    }

    private var locationJob: Job? = null

    /** Called when location permission is granted and the screen is in the foreground. */
    fun startLocation() {
        if (locationJob?.isActive == true) return
        _state.update { it.copy(gpsEnabled = location.gnssEnabled()) }
        locationJob = viewModelScope.launch {
            location.fixes().collect { fix ->
                val now = System.currentTimeMillis()
                val match = StopMatcher.nearest(fix, _state.value.config.stops, now)
                _state.update { it.copy(fix = fix, nearestStop = match, gpsEnabled = true) }
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    /** Sets the boarding stop from the nearest matched stop; leaves it alone when there is no good match. */
    fun useNearestAsBoarding() {
        _state.value.nearestStop?.let { m -> _state.update { it.copy(boardingStopId = m.stop.id) } }
    }

    fun selectBoarding(id: String) = _state.update { it.copy(boardingStopId = id) }
    fun selectDestination(id: String) = _state.update { it.copy(destinationStopId = id) }
    fun selectCategory(id: String?) = _state.update { it.copy(categoryId = id) }

    fun onCardRead(read: CardRead) {
        _state.update { it.copy(reads = (listOf(read) + it.reads).take(50)) }
        if (!debouncer.accept(read.uidHex, read.readAtMillis)) return

        val s = _state.value
        val key = ApprovedCards.normalizeUid(read.uidHex)
        val result = TripFactory.create(
            tripId = UUID.randomUUID().toString(),
            nfcUid = read.uidHex,
            cardState = cardStates[key] ?: CardState.AVAILABLE,
            boardingStopId = s.boardingStopId,
            declaredDestinationStopId = s.destinationStopId,
            passengers = listOf(PassengerLine(s.categoryId, 1)),
            override = null,
            config = s.config,
            nowMillis = read.readAtMillis,
            fix = s.fix,
        )
        when (result) {
            is TripResult.Created -> {
                cardStates[key] = result.cardState
                _state.update {
                    it.copy(
                        trips = listOf(TripRow(result.trip, result.cardState)) + it.trips,
                        message = null,
                    )
                }
                viewModelScope.launch { trips.recordTrip(result.trip, result.cardState, read.readAtMillis) }
            }
            is TripResult.Rejected -> _state.update {
                it.copy(message = rejectionText(result.reason, read.uidHex, cardStates[key]))
            }
        }
    }

    fun markPaid(tripId: String) = applyEvent(tripId, CardEvent.MARK_PAID)

    /** Manual return button, standing in for the Return Terminal until Phase 1.5. */
    fun returnCard(tripId: String) = applyEvent(tripId, CardEvent.RETURNED_AT_TERMINAL)

    fun resolve(tripId: String) = applyEvent(tripId, CardEvent.CONDUCTOR_RESOLVE)
    fun release(tripId: String) = applyEvent(tripId, CardEvent.RELEASE_RETURNED)

    private fun applyEvent(tripId: String, event: CardEvent) {
        val row = _state.value.trips.firstOrNull { it.trip.id == tripId } ?: return
        val key = row.trip.nfcUid
        val current = cardStates[key] ?: return
        when (val t = CardStateMachine.apply(current, event)) {
            is Transition.Ok -> {
                cardStates[key] = t.state
                _state.update { st ->
                    st.copy(
                        trips = st.trips.map { r ->
                            if (r.trip.nfcUid == key) r.copy(cardState = t.state) else r
                        },
                        message = null,
                    )
                }
                viewModelScope.launch {
                    trips.recordCardState(key, row.trip.cardUuid, row.trip.id, t.state, System.currentTimeMillis())
                }
            }
            is Transition.Rejected -> _state.update { it.copy(message = "Not allowed: ${t.from} cannot take $event") }
        }
    }

    fun clearReads() = _state.update { it.copy(reads = emptyList(), message = null) }

    private fun rejectionText(reason: TripRejection, uid: String, cardState: CardState?) = when (reason) {
        TripRejection.UNKNOWN_CARD -> "Card $uid is not in the approved list"
        TripRejection.CARD_NOT_AVAILABLE -> "Card $uid is not available (${cardState?.name ?: "in use"})"
        TripRejection.UNKNOWN_STOP -> "Unknown stop selected"
        TripRejection.UNKNOWN_CATEGORY -> "Passenger type is not active"
        TripRejection.OVERRIDE_NEEDS_REASON -> "Override needs a reason"
        TripRejection.INVALID_OVERRIDE_FARE -> "Override fare is invalid"
        TripRejection.NO_PASSENGERS -> "Add at least one passenger"
        TripRejection.GROUP_TOO_LARGE -> "Too many passengers on one card"
    }
}
