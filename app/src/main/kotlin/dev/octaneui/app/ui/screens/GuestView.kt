package dev.octaneui.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.octaneui.app.R
import dev.octaneui.app.data.DisplayMode
import dev.octaneui.app.ui.InfoRow
import dev.octaneui.app.ui.LocalMonoFont
import dev.octaneui.app.ui.MainViewModel
import dev.octaneui.app.ui.backendName
import dev.octaneui.app.ui.fmtDuration
import dev.octaneui.core.ContainerState

/**
 * Guest environment display in three modes (embedded / window / full screen) through ONE shared layer, [GuestPane].
 * HONESTLY: there is no graphics output yet (Surface/SurfaceFlinger/Binder are not connected), so the guest
 * is shown by its real process status and log, and input is not handled. There is no fake desktop.
 */
@Composable
fun GuestCard(vm: MainViewModel, onOpenLaunchLog: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val mode = settings.displayMode

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.guest_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == DisplayMode.EMBEDDED, { vm.setDisplayMode(DisplayMode.EMBEDDED) }, { Text(stringResource(R.string.mode_embedded), maxLines = 1, softWrap = false) })
                FilterChip(mode == DisplayMode.WINDOW, { vm.setDisplayMode(DisplayMode.WINDOW) }, { Text(stringResource(R.string.mode_window), maxLines = 1, softWrap = false) })
                FilterChip(mode == DisplayMode.FULLSCREEN, { vm.setDisplayMode(DisplayMode.FULLSCREEN) }, { Text(stringResource(R.string.mode_fullscreen), maxLines = 1, softWrap = false) })
            }
            OutlinedButton(onClick = onOpenLaunchLog) { Text(stringResource(R.string.open_launch_log), maxLines = 1, softWrap = false) }
            if (mode == DisplayMode.EMBEDDED) GuestPane(vm)
            else Button(onClick = { open = true }) {
                Text(stringResource(if (mode == DisplayMode.WINDOW) R.string.open_window else R.string.open_fullscreen))
            }
        }
    }

    if (open && mode != DisplayMode.EMBEDDED) {
        val full = mode == DisplayMode.FULLSCREEN
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = !full)) {
            Surface(
                modifier = if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                shape = if (full) RoundedCornerShape(0.dp) else RoundedCornerShape(28.dp), tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.guest_title), style = MaterialTheme.typography.titleLarge)
                    GuestPane(vm)
                    OutlinedButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
fun GuestPane(vm: MainViewModel) {
    val status by vm.guestStatus.collectAsStateWithLifecycle()
    val kind by vm.effectiveBackend.collectAsStateWithLifecycle()
    val running by vm.runningKind.collectAsStateWithLifecycle()
    val lines by vm.guestLog.collectAsStateWithLifecycle()
    val state by vm.containerState.collectAsStateWithLifecycle()
    val na = stringResource(R.string.unavailable)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InfoRow(stringResource(R.string.row_engine), backendName(running ?: kind))
        InfoRow(stringResource(R.string.row_container), stateText(state))
        InfoRow("PID", status?.pid?.toString() ?: na)
        InfoRow(stringResource(R.string.guest_processes), status?.processes?.toString() ?: na)
        InfoRow(stringResource(R.string.guest_uptime), status?.uptimeSec?.let(::fmtDuration) ?: na)
        Text(stringResource(R.string.guest_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            Column(Modifier.padding(12.dp)) {
                if (lines.isEmpty()) Text(stringResource(R.string.console_empty), fontFamily = LocalMonoFont.current, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lines.takeLast(12).forEach { Text(it, fontFamily = LocalMonoFont.current, fontSize = 12.sp, lineHeight = 16.sp) }
            }
        }
    }
}
