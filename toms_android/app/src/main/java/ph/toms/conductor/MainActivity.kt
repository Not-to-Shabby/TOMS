package ph.toms.conductor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ph.toms.conductor.nfc.NfcReader
import ph.toms.conductor.ui.AssignmentScreen
import ph.toms.conductor.ui.ConductorApp
import ph.toms.conductor.ui.ConductorViewModel
import ph.toms.conductor.ui.EndShiftDialog
import ph.toms.conductor.ui.LoginScreen
import ph.toms.conductor.ui.SessionViewModel
import ph.toms.conductor.ui.ShiftEndedDialog
import ph.toms.conductor.ui.theme.TomsTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Sign-in and the choice of vehicle and route. The stub accepts any name until real sign-in (Phase 1.4). */
    private val session: SessionViewModel by viewModels()
    private val conductor: ConductorViewModel by viewModels()

    @Inject lateinit var nfcReader: NfcReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TomsTheme {
                val s by session.state.collectAsState()
                val c by conductor.state.collectAsState()
                val assignment = s.assignment

                // Choosing a vehicle and route opens the shift (or resumes the same person's open one).
                LaunchedEffect(assignment) {
                    if (assignment != null) {
                        conductor.startShift(assignment.conductor, assignment.vehicle.id, assignment.route.id)
                    }
                }
                // When the shift has ended, or a blocked start was given up, go back to choosing a vehicle.
                LaunchedEffect(c.shift, c.blockedBy, c.endPrompt) {
                    if (assignment != null && c.shift == null && c.blockedBy == null && c.endPrompt == null) {
                        session.endAssignment()
                    }
                }

                when {
                    s.conductor == null -> LoginScreen(s.loginError, s.busy, session::login)
                    assignment == null -> {
                        AssignmentScreen(s.vehicles, s.routes, session::assign)
                        // The final figures of the shift that just ended stay visible on the vehicle screen.
                        c.endedSummary?.let { ShiftEndedDialog(it, c.config, c.endedContinues, conductor::acknowledgeEnded) }
                    }
                    c.shift == null -> {
                        // Waiting for the shift to open, or for an earlier shift to be ended first.
                        AssignmentScreen(s.vehicles, s.routes, session::assign)
                        c.endPrompt?.let { prompt ->
                            EndShiftDialog(
                                prompt = prompt,
                                forPreviousShift = c.blockedBy != null,
                                busy = c.ending,
                                onGoBack = conductor::abandonBlockedStart,
                                onWriteOff = { conductor.confirmEndShift(writeOff = true) },
                                onEnd = { conductor.confirmEndShift(writeOff = false) },
                                onCancel = conductor::abandonBlockedStart,
                            )
                        }
                    }
                    else -> ConductorApp(
                        vm = conductor,
                        nfcReader = nfcReader,
                        header = "${assignment.vehicle.plate}  ·  ${assignment.route.name}",
                        state = c,
                    )
                }
            }
        }
    }
}
