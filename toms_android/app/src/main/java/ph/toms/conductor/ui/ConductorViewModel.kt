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
import ph.toms.conductor.data.SavedTrip
import ph.toms.conductor.data.StartShiftResult
import ph.toms.conductor.data.InitialConfig
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.domain.Shift
import ph.toms.conductor.domain.ShiftSummary
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
import ph.toms.conductor.settings.DeviceConfigStore
import ph.toms.conductor.settings.Handedness
import ph.toms.conductor.settings.SettingsStore
import ph.toms.conductor.domain.ConfigInfo
import ph.toms.conductor.domain.ServerConfig
import ph.toms.conductor.sync.CardRegistrySync
import ph.toms.conductor.sync.ConfigRefresh
import ph.toms.conductor.sync.ConfigRefresher

enum class Tab { Board, Collect, Calc, More }

/** What the big result area on the Board shows after a tap. Cleared when the conductor starts the next group. */
sealed interface BoardResult {
    /** [state] is the card's state now, so the card on screen follows Paid and Return like any other trip. */
    data class Started(val trip: Trip, val state: CardState = CardState.ASSIGNED_UNPAID) : BoardResult
    data class Existing(val trip: Trip, val state: CardState) : BoardResult
    data class Refused(val message: String) : BoardResult
}

/** What End Shift has to ask before it can close a shift. Built from the shift's own open trips. */
data class EndShiftPrompt(
    val shiftId: String,
    val shiftConductor: String,
    val unpaidCount: Int,
    val unpaidCentavos: Long,
    /** Paid trips whose card is still out. They stay open and carry into the next shift. */
    val paidOutCount: Int,
)

data class ConductorState(
    val shift: Shift? = null,
    /** An open shift of someone else, or on another vehicle or route. It must be ended before a new shift starts. */
    val blockedBy: Shift? = null,
    val endPrompt: EndShiftPrompt? = null,
    val ending: Boolean = false,
    val showSummary: Boolean = false,
    val summary: ShiftSummary? = null,
    /** Set when a shift has just been ended, so its figures are shown once before moving on. */
    val endedSummary: ShiftSummary? = null,
    val endedContinues: Boolean = false,
    /** The fares, stops and discounts in use. Empty until the server's have been downloaded. */
    val config: TomsConfig = TomsConfig.EMPTY,
    /** Which downloaded fares are loaded and when they arrived. Null while none are. */
    val configInfo: ConfigInfo? = null,
    /** Why the last attempt to download fares changed nothing, for the More screen. Null when it worked. */
    val configMessage: String? = null,
    val tab: Tab = Tab.Board,
    val boardingStopId: String = "",
    val destinationStopId: String = "",
    val tally: PassengerTally = PassengerTally.starting(),
    val result: BoardResult? = null,
    val openTrips: List<OpenTrip> = emptyList(),
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

    /** A trip needs two stops to be priced. Without them no fare was ever published to this phone. */
    val faresLoaded: Boolean get() = config.stops.size >= 2
}

/** What the Board says, and the tap shows, when no fares have been downloaded. A paid trip is not started. */
const val NO_FARES_MESSAGE = "No fares loaded. Connect and sync."

/** Keeps the chosen stops when the new config still has them; otherwise the first stop, and the second as the destination. */
private fun TomsConfig.pickStops(boarding: String, destination: String): Pair<String, String> {
    val b = boarding.takeIf { stop(it) != null } ?: stops.firstOrNull()?.id ?: ""
    val d = destination.takeIf { stop(it) != null } ?: stops.getOrNull(1)?.id ?: stops.firstOrNull()?.id ?: ""
    return b to d
}

@HiltViewModel
class ConductorViewModel @Inject constructor(
    private val trips: TripRepository,
    private val location: LocationTracker,
    private val feedback: Feedback,
    private val settings: SettingsStore,
    private val deviceConfig: DeviceConfigStore,
    private val cardSync: CardRegistrySync,
    private val configRefresher: ConfigRefresher,
    initial: InitialConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(
        initial.config.pickStops("", "").let { (boarding, destination) ->
            ConductorState(
                handedness = settings.handedness.value,
                config = initial.config,
                boardingStopId = boarding,
                destinationStopId = destination,
            )
        },
    )
    val state: StateFlow<ConductorState> = _state.asStateFlow()

    private val debouncer = TapDebouncer()
    private val cardStates = HashMap<String, CardState>()
    private val tripByCard = HashMap<String, Trip>()
    private var locationJob: Job? = null

    init {
        viewModelScope.launch {
            trips.loadApprovedCardList()?.let { cached ->
                if (cached.cards.isNotEmpty() || cached.statuses.isNotEmpty()) {
                    _state.update { it.copy(config = it.config.copy(approvedCards = ApprovedCards(cached.cards, cached.statuses))) }
                }
            }
            if (!deviceConfig.deviceToken.value.isNullOrBlank()) {
                syncApprovedCards()
            }
        }
        viewModelScope.launch { reloadCards() }
        viewModelScope.launch { settings.handedness.collect { h -> _state.update { it.copy(handedness = h) } } }
        // The saved fares: what is on the phone now, and any copy a sync saves later, even one made by the background worker.
        viewModelScope.launch { trips.observeServerConfig().collect { saved -> if (saved != null) applyServerConfig(saved) } }
        // Enrolling a phone (a token appears), and starting the app with one, fetches the card list and the fares.
        viewModelScope.launch {
            deviceConfig.deviceToken.collect { tok ->
                if (!tok.isNullOrBlank()) {
                    syncApprovedCards()
                    refreshConfig()
                }
            }
        }
    }

    /**
     * Switches to the downloaded fares. Trips already made keep the fare and version they were made with; only
     * trips started from now on are priced with these. The approved cards are not part of the fares, so they stay.
     */
    private fun applyServerConfig(saved: ServerConfig) = _state.update { s ->
        val config = saved.applyTo(s.config)
        val (boarding, destination) = config.pickStops(s.boardingStopId, s.destinationStopId)
        s.copy(config = config, configInfo = saved.info(), boardingStopId = boarding, destinationStopId = destination)
    }

    /** Asks the server for this phone's fares. A failed or empty answer changes nothing: the saved fares stay in use. */
    fun refreshConfig() {
        viewModelScope.launch {
            val message = when (val r = runCatching { configRefresher.refresh() }.getOrElse { ConfigRefresh.Kept(it.message ?: "Could not save the fares.") }) {
                is ConfigRefresh.Updated -> null
                is ConfigRefresh.Kept -> r.reason
            }
            _state.update { it.copy(configMessage = message) }
        }
    }

    /** Reads card states and each card's newest trip back from the database. */
    private suspend fun reloadCards() {
        cardStates.clear()
        tripByCard.clear()
        cardStates.putAll(trips.loadCardStates())
        trips.loadTrips().forEach { t ->
            // Newest trip per card wins; loadTrips returns newest first.
            tripByCard.putIfAbsent(t.nfcUid, t)
        }
        refreshOpen()
    }

    // ---- shifts

    /** Who is signed in. Written on every closing event as the person who ended the shift. */
    private var actor: String = ""
    private var pendingStart: Triple<String, String, String>? = null

    /**
     * Opens the shift for this conductor, vehicle and route, or resumes the open one. If another shift is
     * still open, the shift is not started: its End Shift prompt is shown first, so its unpaid trips are asked about.
     */
    fun startShift(conductor: String, vehicleId: String, routeId: String) {
        actor = conductor.trim()
        pendingStart = Triple(conductor, vehicleId, routeId)
        viewModelScope.launch { applyStart(trips.startShift(conductor, vehicleId, routeId)) }
    }

    private suspend fun applyStart(result: StartShiftResult) {
        when (result) {
            is StartShiftResult.Started -> _state.update { it.copy(shift = result.shift, blockedBy = null, endPrompt = null) }
            is StartShiftResult.Resumed -> _state.update { it.copy(shift = result.shift, blockedBy = null, endPrompt = null) }
            is StartShiftResult.NeedsEnding -> {
                val prompt = promptFor(result.open)
                _state.update { it.copy(shift = null, blockedBy = result.open, endPrompt = prompt) }
            }
        }
    }

    private suspend fun promptFor(shift: Shift): EndShiftPrompt {
        val open: List<SavedTrip> = trips.openTripsOf(shift.id)
        val unpaid = open.filter { it.cardState == CardState.ASSIGNED_UNPAID || it.cardState == CardState.RETURNED_UNPAID }
        return EndShiftPrompt(
            shiftId = shift.id,
            shiftConductor = shift.conductor,
            unpaidCount = unpaid.size,
            unpaidCentavos = unpaid.sumOf { it.trip.fareCentavos.toLong() },
            paidOutCount = open.count { it.cardState == CardState.ASSIGNED_PAID },
        )
    }

    fun requestEndShift() {
        val shift = _state.value.shift ?: return
        viewModelScope.launch { _state.update { it.copy(endPrompt = promptFor(shift)) } }
    }

    /** "Go back and collect": closes the prompt and opens Collect. Nothing is changed. */
    fun goBackToCollect() = _state.update { it.copy(endPrompt = null, showSummary = false, tab = Tab.Collect, notice = null, focusTripId = null) }

    fun dismissEndPrompt() = _state.update { it.copy(endPrompt = null) }

    /** Gives up starting a shift that is blocked by an open one. The conductor goes back to choosing a vehicle. */
    fun abandonBlockedStart() {
        pendingStart = null
        _state.update { it.copy(blockedBy = null, endPrompt = null) }
    }

    /**
     * Ends the shift the prompt is about. With [writeOff], each unpaid trip is written off and its card released
     * first; the write-off is recorded as a write-off, never as a terminal return. Ignored while already ending,
     * so a double tap cannot end twice.
     */
    fun confirmEndShift(writeOff: Boolean) {
        val prompt = _state.value.endPrompt ?: return
        if (_state.value.ending) return
        _state.update { it.copy(ending = true) }
        viewModelScope.launch {
            if (writeOff) {
                trips.openTripsOf(prompt.shiftId)
                    .filter { it.cardState == CardState.ASSIGNED_UNPAID || it.cardState == CardState.RETURNED_UNPAID }
                    .forEach { trips.writeOffAndRelease(prompt.shiftId, it.trip.id, actor) }
            }
            val summary = trips.endShift(prompt.shiftId, actor)
            reloadCards()
            val wasBlocked = _state.value.blockedBy != null
            _state.update {
                it.copy(
                    ending = false, endPrompt = null, showSummary = false, summary = null,
                    shift = if (wasBlocked) it.shift else null,
                    blockedBy = null,
                    endedSummary = summary,
                    endedContinues = wasBlocked,
                )
            }
            if (wasBlocked) pendingStart?.let { (c, v, r) -> applyStart(trips.startShift(c, v, r)) }
        }
    }

    fun acknowledgeEnded() = _state.update { it.copy(endedSummary = null, endedContinues = false) }

    fun openSummary() {
        val shift = _state.value.shift ?: return
        viewModelScope.launch { _state.update { it.copy(summary = trips.summaryFor(shift.id), showSummary = true) } }
    }

    fun closeSummary() = _state.update { it.copy(showSummary = false) }

    // ---- navigation and board choices

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab, notice = null, focusTripId = null) }
    fun selectDestination(id: String) = _state.update { it.copy(destinationStopId = id, result = null) }
    fun selectBoarding(id: String) = _state.update { it.copy(boardingStopId = id) }

    /** Each tap adds one passenger of that type. Starting a new group clears the last result. */
    fun addPassenger(categoryId: String?) = _state.update {
        val base = if (it.result != null) PassengerTally() else it.tally
        it.copy(tally = base.add(categoryId), result = null)
    }

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

        // Every trip belongs to a shift, so no trip is started until one is open.
        val shiftId = s0.shift?.id ?: return refuse("No shift open")

        // No fares were ever downloaded, so a new trip could only be charged from invented numbers. It is refused.
        // A card that is already out still shows its trip, because that charges nobody.
        if (!s0.faresLoaded && (cardStates[key] ?: CardState.AVAILABLE) == CardState.AVAILABLE) return refuse(NO_FARES_MESSAGE)

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
                // The result card now carries the group, so the next group starts fresh (one regular, the
                // common case). The destination stays: the next passengers usually go to the same place.
                _state.update { it.copy(result = BoardResult.Started(result.trip), tally = PassengerTally.starting()) }
                refreshOpen()
                viewModelScope.launch { trips.recordTrip(result.trip, result.cardState, read.readAtMillis, shiftId) }
            }
            is TapResult.Existing -> {
                feedback.play(Cue.Opened)
                _state.update { it.copy(result = BoardResult.Existing(result.trip, result.cardState)) }
            }
            is TapResult.UnknownCard -> refuse("Card not recognised")
            is TapResult.CardBlocked -> refuse(blockedCardText(result.status))
            is TapResult.NeedsAttention -> refuse(
                if (result.cardState == CardState.LOST) "Card marked lost. See Collect." else "Unpaid return. See Collect.",
            )
            is TapResult.Invalid -> refuse(reasonText(result.reason))
        }
    }

    /** What the conductor reads when a card that the office has taken out of service is tapped. */
    private fun blockedCardText(status: String): String = when (status) {
        "lost" -> "Card reported lost. Keep it and tell the office."
        "retired" -> "Card retired. Do not use."
        "suspended" -> "Card suspended. Do not use."
        else -> "Card not in service."
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
                // If the result on screen is this trip, show what just happened to it. Both a trip just started
                // and one opened by tapping its card are shown, so Paid and Return always show on the Board.
                _state.update {
                    val shown = it.result
                    it.copy(
                        result = when {
                            shown is BoardResult.Started && shown.trip.id == tripId -> shown.copy(state = steps.first())
                            shown is BoardResult.Existing && shown.trip.id == tripId -> shown.copy(state = steps.first())
                            else -> shown
                        },
                    )
                }
                refreshOpen()
                // Tagged with the shift open now, so a payment counts in the shift where it happens.
                val shiftId = _state.value.shift?.id
                viewModelScope.launch {
                    steps.forEach { trips.recordCardState(key, trip.cardUuid, trip.id, it, System.currentTimeMillis(), shiftId) }
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

    // ---- approved cards (uploads, enrollment and the server URL are in SyncViewModel)

    fun syncApprovedCards() {
        viewModelScope.launch {
            // Only a successful answer changes the list. A failed fetch (no signal, server down) leaves the saved
            // list alone, so the conductor keeps working. An answer with no cards is real: every card was taken
            // out of service, and the phone must stop accepting them.
            cardSync.fetchApprovedCards()
                .onSuccess { list ->
                    trips.saveApprovedCardList(list)
                    _state.update {
                        it.copy(config = it.config.copy(approvedCards = ApprovedCards(list.cards, list.statuses)))
                    }
                }
        }
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
