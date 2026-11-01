package ph.toms.ota

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "firmware.bin"

    @Composable
    private fun Screen() {
        val s by vm.state.collectAsState()
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) vm.scan()
        }
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.loadImage(uri, displayName(uri))
        }
        val logState = rememberLazyListState()
        LaunchedEffect(s.log.size) { if (s.log.isNotEmpty()) logState.animateScrollToItem(s.log.size - 1) }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TOMS machine updater", style = MaterialTheme.typography.headlineSmall)
            Text(s.headline, style = MaterialTheme.typography.bodyLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = s.phase != Phase.UPLOADING) {
                    Text("1. Choose file")
                }
                OutlinedButton(onClick = { askPermissions.launch(permissions) }, enabled = s.phase != Phase.UPLOADING && s.phase != Phase.SCANNING) {
                    Text("2. Find machine")
                }
            }
            s.imageName?.let { Text("File: $it" + (s.image?.let { i -> "  (version ${i.version}, ${i.size} bytes)" } ?: "")) }
            s.imageProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (s.machines.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        s.machines.forEach { m ->
                            val chosen = m.address == s.selected?.address
                            Text(
                                (if (chosen) "● " else "○ ") + "${m.name}   ${m.address}   ${m.rssi} dBm",
                                Modifier.fillMaxWidth().clickable { vm.select(m) }.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            if (s.phase == Phase.UPLOADING || s.phase == Phase.DONE) {
                LinearProgressIndicator(
                    progress = { if (s.total == 0L) 0f else s.sent.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${s.sent} / ${s.total} bytes")
            }

            if (s.phase == Phase.UPLOADING) {
                Button(onClick = { vm.cancel() }) { Text("Cancel") }
            } else {
                Button(onClick = { vm.start() }, enabled = s.canStart) { Text("3. Update machine") }
            }

            Text("Log", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = logState) {
                items(s.log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}
