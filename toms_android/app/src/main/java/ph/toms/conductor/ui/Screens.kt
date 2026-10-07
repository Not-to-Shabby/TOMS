package ph.toms.conductor.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ph.toms.conductor.data.RouteInfo
import ph.toms.conductor.data.Vehicle
import ph.toms.conductor.nfc.CardRead
import ph.toms.conductor.nfc.NfcAvailability
import ph.toms.conductor.nfc.NfcReader

@Composable
fun LoginScreen(error: String?, busy: Boolean, onLogin: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text("TOMS Conductor", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Button({ onLogin(user, pass) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Signing in..." else "Sign in")
        }
    }
}

@Composable
fun AssignmentScreen(
    vehicles: List<Vehicle>,
    routes: List<RouteInfo>,
    onConfirm: (Vehicle, RouteInfo) -> Unit,
) {
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var route by remember { mutableStateOf<RouteInfo?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vehicle", style = MaterialTheme.typography.titleMedium)
        vehicles.forEach { v ->
            Row { RadioButton(vehicle == v, { vehicle = v }); Text(v.plate, Modifier.padding(top = 12.dp)) }
        }
        Text("Route", style = MaterialTheme.typography.titleMedium)
        routes.forEach { r ->
            Row { RadioButton(route == r, { route = r }); Text(r.name, Modifier.padding(top = 12.dp)) }
        }
        Button(
            { onConfirm(vehicle!!, route!!) },
            enabled = vehicle != null && route != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start shift") }
    }
}

