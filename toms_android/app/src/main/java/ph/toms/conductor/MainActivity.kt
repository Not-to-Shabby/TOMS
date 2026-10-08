package ph.toms.conductor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ph.toms.conductor.nfc.NfcReader
import ph.toms.conductor.ui.AssignmentScreen
import ph.toms.conductor.ui.ConductorApp
import ph.toms.conductor.ui.ConductorViewModel
import ph.toms.conductor.ui.LoginScreen
import ph.toms.conductor.ui.SessionViewModel
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
                when {
                    s.conductor == null -> LoginScreen(s.loginError, s.busy, session::login)
                    assignment == null -> AssignmentScreen(s.vehicles, s.routes, session::assign)
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
