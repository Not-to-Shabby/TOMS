package ph.toms.conductor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import ph.toms.conductor.nfc.NfcReader
import ph.toms.conductor.ui.AssignmentScreen
import ph.toms.conductor.ui.LoginScreen
import ph.toms.conductor.ui.SessionViewModel
import ph.toms.conductor.ui.TapScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels()

    @Inject lateinit var nfcReader: NfcReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                val assignment = state.assignment
                when {
                    state.conductor == null -> LoginScreen(state.loginError, state.busy, viewModel::login)
                    assignment == null -> AssignmentScreen(state.vehicles, state.routes, viewModel::assign)
                    else -> TapScreen(
                        header = "${assignment.conductor} | ${assignment.vehicle.plate} | ${assignment.route.name}",
                        state = state,
                        nfcReader = nfcReader,
                        onRead = viewModel::onCardRead,
                        onBoarding = viewModel::selectBoarding,
                        onDestination = viewModel::selectDestination,
                        onCategory = viewModel::selectCategory,
                        onPaid = viewModel::markPaid,
                        onReturn = viewModel::returnCard,
                        onResolve = viewModel::resolve,
                        onRelease = viewModel::release,
                        onClear = viewModel::clearReads,
                        onStartLocation = viewModel::startLocation,
                        onStopLocation = viewModel::stopLocation,
                        onUseNearest = viewModel::useNearestAsBoarding,
                    )
                }
            }
        }
    }
}
