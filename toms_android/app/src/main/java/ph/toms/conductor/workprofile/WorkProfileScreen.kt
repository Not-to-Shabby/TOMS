package ph.toms.conductor.workprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ph.toms.conductor.ui.theme.BigButton
import ph.toms.conductor.ui.theme.QuietButton
import ph.toms.conductor.ui.theme.Status
import ph.toms.conductor.ui.theme.StatusChip

/**
 * The first-run check: says in plain words whether TOMS runs in a work profile and which policies are in force. A release build
 * stops here until the phone is set up as intended; a debug build can carry on without a profile.
 */
@Composable
fun WorkProfileScreen(
    check: WorkProfileCheck,
    onContinue: () -> Unit,
    onContinueWithoutProfile: () -> Unit,
    onRecheck: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Phone setup check", style = MaterialTheme.typography.headlineMedium)
        StatusChip(
            if (check.status == ProfileStatus.OWNER_IN_WORK_PROFILE) "Work profile: on" else "Work profile: off",
            if (check.status == ProfileStatus.OWNER_IN_WORK_PROFILE) Status.Good else if (check.required) Status.Bad else Status.Warn,
        )
        Text(check.headline, style = MaterialTheme.typography.titleMedium)
        Text(check.detail, style = MaterialTheme.typography.bodyLarge)

        Text("Policies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        check.policies.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    when (item.state) {
                        PolicyState.ACTIVE -> "On"
                        PolicyState.INACTIVE -> "Off"
                        PolicyState.NOT_SUPPORTED -> "n/a"
                        PolicyState.FAILED -> "Failed"
                    },
                    when (item.state) {
                        PolicyState.ACTIVE -> Status.Good
                        PolicyState.INACTIVE -> Status.Warn
                        PolicyState.NOT_SUPPORTED -> Status.Neutral
                        PolicyState.FAILED -> Status.Bad
                    },
                )
                Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
        }

        if (!check.canContinue) {
            Text(
                "This build needs TOMS to run as the owner of its own work profile. Ask the researcher to follow the " +
                    "provisioning steps, then check again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        BigButton("Check again", onRecheck, Modifier.fillMaxWidth())
        if (check.canContinue) BigButton("Continue", onContinue, Modifier.fillMaxWidth())
        if (check.canContinueWithoutProfile) {
            QuietButton("Continue without a work profile (development phone)", onContinueWithoutProfile, Modifier.fillMaxWidth())
        }
    }
}
