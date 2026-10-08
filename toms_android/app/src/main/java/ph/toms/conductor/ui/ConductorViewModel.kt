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
import ph.toms.conductor.data.TestConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.domain.ApprovedCards
import ph.toms.conductor.domain.BoardFlow
import ph.toms.conductor.domain.CalcMode
import ph.toms.conductor.domain.CardEvent
import ph.toms.conductor.domain.CardState
import ph.toms.conductor.domain.CardStateMachine
import ph.toms.conductor.domain.CollectList
import ph.toms.conductor.domain.GeoFix
import ph.toms.conductor.domain.GroupQuote
import ph.toms.conductor.domain.OpenTrip
import ph.toms.conductor.domain.PassengerTally
import ph.toms.conductor.domain.StopMatch
import ph.toms.conductor.domain.StopMatcher
import ph.toms.conductor.domain.TapDebouncer
import ph.toms.conductor.domain.TapResult
import ph.toms.conductor.domain.TomsConfig
import ph.toms.conductor.domain.Transition
import ph.toms.conductor.domain.Trip
import ph.toms.conductor.domain.TripRejection
import ph.toms.conductor.feedback.Cue
import ph.toms.conductor.feedback.Feedback
import ph.toms.conductor.location.LocationTracker
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.SettingsStore

enum class Tab { Board, Collect, Calc, More }

/** What the big result area on the Board shows after a tap. Cleared when the conductor starts the next group. */
sealed interface BoardResult {
    data class Started(val trip: Trip) : BoardResult
    data class Existing(val trip: Trip, val state: CardState) : BoardResult
    data class Refused(val message: String) : BoardResult
}

data class ConductorState(
    val config: TomsConfig = TestConfig.config,
    val tab: Tab = Tab.Board,
    val boardingStopId: String = TestConfig.config.stops.first().id,
    val destinationStopId: String = TestConfig.config.stops.getOrElse(1) { TestConfig.config.stops.first() }.id,
    val tally: PassengerTally = PassengerTally.starting(),
    val result: BoardResult? = null,
    val openTrips: List<OpenTrip> = emptyList(),
    val pendingCount: Int = 0,
    val fix: GeoFix? = null,
    val nearestStop: StopMatch? = null,
    val calcMode: CalcMode = CalcMode.CHANGE,
    /** Fare shown in the calculator, in centavos. Set from a trip when the conductor opens Calc from Collect. */
    val calcDueCentavos: Int = 0,
    val handedness: Handedness = Handedness.Right,
    val recentReads: List<CardRead> = emptyList(),
    /** On Collect, the trip of the card that was just tapped, so its row can be highlighted. */
    val focusTripId: String? = null,
    /** A short message for tabs other than Board, such as "Open Board to start a trip". */
    val notice: String? = null,
) {
    val preview: GroupQuote? get() = BoardFlow.previewTotal(tally, boardingStopId, destinationStopId, config)
}

@HiltViewModel
class ConductorViewModel @Inject constructor(
    private val trips: TripRepository,
    private val location: LocationTracker,
    private val feedback: Feedback,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ConductorState(handedness = settings.handedness.value))
    val state: StateFlow<ConductorState> = _state.asStateFlow()

    private val debouncer = TapDebouncer()
    private val cardStates = HashMap<String, CardState>()
    private val tripByCard = HashMap<String, Trip>()
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            cardStates.putAll(trips.loadCardStates())
            trips.loadTrips().forEach { t ->
                // Newest trip per card wins; loadTrips returns newest first.
                tripByCard.putIfAbsent(t.nfcUid, t)
            }
            refreshOpen()
        }
        viewModelScope.launch { trips.pendingCount().collect { n -> _state.update { it.copy(pendingCount = n) } } }
        viewModelScope.launch { settings.handedness.collect { h -> _state.update { it.copy(handedness = h) } } }
    }

    // ---- navigation and board choices

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab, notice = null, focusTripId = null) }
    fun selectDestination(id: String) = _state.update { it.copy(destinationStopId = id, result = null) }
    fun selectBoarding(id: String) = _state.update { it.copy(boardingStopId = id) }

    /** Each tap adds one passenger of that type. Starting a new group clears the last result. */
    fun addPassenger(categoryId: String?) = _state.update {
        val base = if (it.result != null) PassengerTally() else it.tally
        it.copy(tally = base.add(categoryId), result = null)
    }

    fun undoPassenger() = _state.update { it.copy(tally = it.tally.undo()) }
    fun clearPassengers() = _state.update { it.copy(tally = PassengerTally(), result = null) }
    fun dismissResult() = _state.update { it.copy(result = null) }

    fun setHandedness(value: Handedness) = settings.setHandedness(value)

    // ---- the tap

    fun onCardRead(read: CardRead) {
        val s0 = _state.value
        _state.update { it.copy(recentReads = (listOf(read) + it.recentReads).take(30)) }
        if (!debouncer.accept(read.uidHex, read.readAtMillis)) return

        val key = ApprovedCards.normalizeUid(read.uidHex)

        // A trip is only ever started from the Board. Elsewhere a tap finds the card's trip, or says why not,
        // so a stray tap while collecting cannot create a trip for whatever group was left on the Board.
        if (s0.tab != Tab.Board) {
            val trip = tripByCard[key]
            if (trip != null && (cardStates[key] ?: CardState.AVAILABLE) != CardState.AVAILABLE) {
                feedback.play(Cue.Opened)
                _state.update { it.copy(focusTripId = trip.id, notice = null) }
            } else {
                feedback.play(Cue.Refused)
                _state.update { it.copy(focusTripId = null, notice = "Open Board to start a trip") }
            }
            return
        }

        val result = BoardFlow.handleTap(
            nfcUid = read.uidHex,
            passengers = s0.tally,
            boardingStopId = s0.boardingStopId,
            destinationStopId = s0.destinationStopId,
            config = s0.config,
            stateOf = { cardStates[it] ?: CardState.AVAILABLE },
            tripFor = { tripByCard[it] },
            newTripId = { UUID.randomUUID().toString() },
            nowMillis = read.readAtMillis,
            fix = s0.fix,
        )
        when (result) {
            is TapResult.Started -> {
                cardStates[key] = result.cardState
                tripByCard[key] = result.trip
                feedback.play(Cue.Done)
                _state.update { it.copy(result = BoardResult.Started(result.trip)) }
                refreshOpen()
                viewModelScope.launch { trips.recordTrip(result.trip, result.cardState, read.readAtMillis) }
            }
            is TapResult.Existing -> {
                feedback.play(Cue.Opened)
                _state.update { it.copy(result = BoardResult.Existing(result.trip, result.cardState)) }
            }
            is TapResult.UnknownCard -> refuse("Card not recognised")
            is TapResult.NeedsAttention -> refuse(
                if (result.cardState == CardState.LOST) "Card marked lost. See Collect." else "Unpaid return. See Collect.",
            )
            is TapResult.Invalid -> refuse(reasonText(result.reason))
        }
    }

    private fun refuse(message: String) {
        feedback.play(Cue.Refused)
        _state.update { it.copy(result = BoardResult.Refused(message)) }
    }

    // ---- collect

    fun markPaid(tripId: String) = apply(tripId, CardEvent.MARK_PAID)
    fun returnCard(tripId: String) = apply(tripId, CardEvent.RETURNED_AT_TERMINAL)
    fun clearAlarm(tripId: String) = apply(tripId, CardEvent.CONDUCTOR_RESOLVE)

    private fun apply(tripId: String, event: CardEvent) {
        val trip = tripByCard.values.firstOrNull { it.id == tripId } ?: return
        val key = trip.nfcUid
        val current = cardStates[key] ?: return
        when (val t = CardStateMachine.apply(current, event)) {
            is Transition.Ok -> {
                // A paid card that comes back is free again straight away: there is no separate "release"
                // step for the conductor. Both changes are recorded, in order, so the server sees the return.
                val steps = mutableListOf(t.state)
                if (t.state == CardState.RETURNED) {
                    (CardStateMachine.apply(t.state, CardEvent.RELEASE_RETURNED) as? Transition.Ok)?.let { steps += it.state }
                }
                val finalState = steps.last()
                cardStates[key] = finalState
                if (finalState == CardState.AVAILABLE) tripByCard.remove(key)
                feedback.play(Cue.Done)
                // If the result on screen is this trip, show what just happened to it.
                _state.update {
                    val shown = it.result
                    it.copy(
                        result = if (shown is BoardResult.Existing && shown.trip.id == tripId) shown.copy(state = steps.first()) else shown,
                    )
                }
                refreshOpen()
                viewModelScope.launch {
                    steps.forEach { trips.recordCardState(key, trip.cardUuid, trip.id, it, System.currentTimeMillis()) }
                }
            }
            is Transition.Rejected -> feedback.play(Cue.Refused)
        }
    }

    private fun refreshOpen() {
        val open = tripByCard.values.map { OpenTrip(it, cardStates[it.nfcUid] ?: CardState.AVAILABLE) }
        _state.update { it.copy(openTrips = CollectList.of(open)) }
    }

    // ---- calculator

    fun setCalcMode(mode: CalcMode) = _state.update { it.copy(calcMode = mode) }

    /** Opens Calc already showing this trip's fare, so the conductor only picks the bill. */
    fun openCalcFor(trip: Trip) = _state.update { it.copy(tab = Tab.Calc, calcMode = CalcMode.CHANGE, calcDueCentavos = trip.fareCentavos) }

    fun openCalcForBoard() = _state.update {
        it.copy(tab = Tab.Calc, calcMode = CalcMode.CHANGE, calcDueCentavos = it.preview?.totalCentavos ?: 0)
    }

    // ---- location

    fun startLocation() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            location.fixes().collect { fix ->
                val match = StopMatcher.nearest(fix, _state.value.config.stops, System.currentTimeMillis())
                _state.update { it.copy(fix = fix, nearestStop = match) }
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    fun useNearestAsBoarding() {
        _state.value.nearestStop?.let { m -> _state.update { it.copy(boardingStopId = m.stop.id) } }
    }

    private fun reasonText(reason: TripRejection) = when (reason) {
        TripRejection.NO_PASSENGERS -> "Add a passenger first"
        TripRejection.GROUP_TOO_LARGE -> "Too many passengers for one card"
        TripRejection.UNKNOWN_STOP -> "Choose a destination"
        TripRejection.UNKNOWN_CATEGORY -> "That passenger type is not available"
        TripRejection.UNKNOWN_CARD -> "Card not recognised"
        TripRejection.CARD_NOT_AVAILABLE -> "Card is in use"
        TripRejection.OVERRIDE_NEEDS_REASON, TripRejection.INVALID_OVERRIDE_FARE -> "Fare could not be set"
    }
}
