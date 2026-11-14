package ph.toms.conductor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.toms.conductor.data.TripRepository
import ph.toms.conductor.data.WrittenOffTrip
import ph.toms.conductor.domain.Shift
import ph.toms.conductor.domain.ShiftHistoryExport
import ph.toms.conductor.domain.ShiftSummary
import ph.toms.conductor.domain.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One ended shift in the history list, with the summary End Shift froze for it. */
data class EndedShiftRow(val shift: Shift, val summary: ShiftSummary?)

/** Everything the history screen shows about one ended shift. */
data class ShiftDetail(
    val shift: Shift,
    val summary: ShiftSummary?,
    val trips: List<Trip>,
    val writtenOff: List<WrittenOffTrip>,
)

/** What the share sheet is handed, or null when there is nothing to share right now. */
data class ShiftExport(val fileName: String, val json: String)

/**
 * Behind the Shift history screen: the list of ended shifts, one shift's detail, and the JSON export.
 * The history is read-only: the frozen summaries are never rewritten, and nothing on it changes card
 * states or money. (Recording a late payment against a written-off trip was considered and deferred —
 * see documentation/v2_later.md.)
 */
@HiltViewModel
class ShiftHistoryViewModel @Inject constructor(
    private val trips: TripRepository,
) : ViewModel() {

    data class State(
        val rows: List<EndedShiftRow> = emptyList(),
        val loading: Boolean = false,
        /** True while the history screen replaces the tab content. */
        val visible: Boolean = false,
        val detail: ShiftDetail? = null,
        val detailLoading: Boolean = false,
        /** A short line shown under the list or detail when something needs saying. */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Opens the history screen and reloads the list, in case a shift ended since last time. */
    fun open() {
        _state.update { it.copy(visible = true, message = null) }
        refresh()
    }

    /** Closes the history screen and whatever was open on it. Nothing recorded is changed. */
    fun close() = _state.update { it.copy(visible = false, detail = null, message = null) }

    init {
        refresh()
    }

    /** Reloads the list of ended shifts, newest first, with their frozen summaries. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val rows = trips.endedShifts().map { EndedShiftRow(it, trips.frozenSummary(it.id)) }
            _state.update { it.copy(rows = rows, loading = false) }
        }
    }

    fun openDetail(shiftId: String) {
        viewModelScope.launch {
            _state.update { it.copy(detailLoading = true) }
            val detail = loadDetail(shiftId)
            _state.update { it.copy(detail = detail, detailLoading = false, message = null) }
        }
    }

    fun closeDetail() = _state.update { it.copy(detail = null, message = null) }

    private suspend fun loadDetail(shiftId: String): ShiftDetail? {
        val shift = trips.endedShifts().firstOrNull { it.id == shiftId } ?: return null
        return ShiftDetail(
            shift = shift,
            summary = trips.frozenSummary(shiftId),
            trips = trips.tripsOfShift(shiftId),
            writtenOff = trips.writtenOffTripsOf(shiftId),
        )
    }

    /**
     * Builds the shareable JSON for the open shift. Null (with a message) when the shift predates frozen
     * summaries, so an export is never a half-empty guess at what the shift came to.
     */
    suspend fun exportJson(): ShiftExport? {
        val detail = _state.value.detail ?: return null
        val summary = detail.summary ?: run {
            _state.update { it.copy(message = "This shift has no frozen summary, so there is nothing to export.") }
            return null
        }
        val json = ShiftHistoryExport.build(detail.shift, summary, detail.trips)
        return ShiftExport(exportFileName(detail.shift), json)
    }

    /** "toms-shift-2026-11-14-1a2b3c.json" — the date it started plus the first of the shift id. */
    private fun exportFileName(shift: Shift): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(shift.startedAtMillis))
        val short = shift.id.take(6).lowercase(Locale.US)
        return "toms-shift-$day-$short.json"
    }
}
