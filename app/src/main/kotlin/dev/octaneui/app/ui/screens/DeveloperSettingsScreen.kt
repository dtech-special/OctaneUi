package dev.octaneui.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.octaneui.app.R
import dev.octaneui.app.system.ResourceIntegrity
import dev.octaneui.app.ui.InfoRow
import dev.octaneui.app.ui.MainViewModel
import dev.octaneui.app.ui.SectionTitle
import dev.octaneui.container.PreflightVerdict

/**
 * Developer Settings are diagnostics only and live apart from the safety logic.
 * Nothing on this screen can disable the lock-screen check, the watchdog, the preflight, the protective patches
 * or allow access to host data; the only switch here is verbose logging.
 */
@Composable
fun DeveloperSettingsScreen(vm: MainViewModel, onOpenLaunchLog: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val info by vm.devInfo.collectAsStateWithLifecycle()
    val dry by vm.dryRun.collectAsStateWithLifecycle()
    val integrity by vm.integrity.collectAsStateWithLifecycle()
    val emergency by vm.emergencyResult.collectAsStateWithLifecycle()
    var confirmEmergency by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val colors = CardDefaults.outlinedCardColors(containerColor = cs.surfaceContainerLow)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "verbose") {
            SectionTitle(stringResource(R.string.dev_logging))
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 4.dp), shape = shape, colors = colors) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dev_verbose)) },
                    supportingContent = { Text(stringResource(R.string.dev_verbose_desc)) },
                    trailingContent = { Switch(settings.verboseLogging, { vm.setVerbose(it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = onOpenLaunchLog) { Text(stringResource(R.string.dev_session_log), maxLines = 1, softWrap = false) }
                }
            }
        }
        item(key = "diag") {
            SectionTitle(stringResource(R.string.dev_diag))
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 4.dp), shape = shape, colors = colors) {
                Column(Modifier.padding(16.dp)) {
                    info.forEach { (k, v) -> InfoRow(k, v, mono = true) }
                    Button(onClick = vm::loadDevDiagnostics, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dev_diag_run), maxLines = 1, softWrap = false) }
                    Text(stringResource(R.string.dev_diag_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item(key = "dry") {
            SectionTitle(stringResource(R.string.dev_dry_run))
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 4.dp), shape = shape, colors = colors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.dev_dry_run_desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    dry?.steps?.forEach { s ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                            val ok = s.verdict == PreflightVerdict.PASS
                            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Error, null, tint = if (ok) cs.tertiary else cs.error, modifier = Modifier.size(20.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(dryTitle(s.id), style = MaterialTheme.typography.bodyMedium)
                                s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                            }
                        }
                    }
                    dry?.let { Text(stringResource(R.string.dev_dry_none_started), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
                    Button(onClick = vm::runDryRun, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dev_dry_run_run), maxLines = 1, softWrap = false) }
                }
            }
        }
        item(key = "integrity") {
            SectionTitle(stringResource(R.string.dev_integrity))
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 4.dp), shape = shape, colors = colors) {
                Column(Modifier.padding(16.dp)) {
                    integrity?.forEach { e ->
                        InfoRow(e.name, stringResource(when (e.status) {
                            ResourceIntegrity.Status.OK -> R.string.integrity_ok
                            ResourceIntegrity.Status.MISMATCH -> R.string.integrity_mismatch
                            ResourceIntegrity.Status.UNREADABLE -> R.string.integrity_unreadable
                        }))
                    }
                    Button(onClick = vm::checkIntegrity, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dev_integrity_run), maxLines = 1, softWrap = false) }
                }
            }
        }
        item(key = "emergency") {
            SectionTitle(stringResource(R.string.dev_emergency))
            OutlinedCard(Modifier.fillMaxWidth().padding(top = 4.dp), shape = shape, colors = colors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.dev_emergency_desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    emergency?.let { Text(stringResource(if (it) R.string.dev_emergency_ok else R.string.dev_emergency_alive), color = if (it) cs.tertiary else cs.error) }
                    OutlinedButton(onClick = { confirmEmergency = true }) { Text(stringResource(R.string.dev_emergency_run), maxLines = 1, softWrap = false) }
                }
            }
        }
    }

    if (confirmEmergency) AlertDialog(
        onDismissRequest = { confirmEmergency = false },
        title = { Text(stringResource(R.string.dev_emergency)) },
        text = { Text(stringResource(R.string.dev_emergency_confirm)) },
        confirmButton = { TextButton(onClick = { confirmEmergency = false; vm.emergencyReturn() }) { Text(stringResource(R.string.dev_emergency_run)) } },
        dismissButton = { TextButton(onClick = { confirmEmergency = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun dryTitle(id: dev.octaneui.container.DryRunId): String = stringResource(
    when (id) {
        dev.octaneui.container.DryRunId.PREFLIGHT -> R.string.dry_preflight
        dev.octaneui.container.DryRunId.DEVICE_SUPPORTED -> R.string.dry_device
        dev.octaneui.container.DryRunId.GUEST_SUPPORTED -> R.string.dry_guest
        dev.octaneui.container.DryRunId.INSTALLD_ABI_TABLE -> R.string.dry_installd
        dev.octaneui.container.DryRunId.STORAGE_GUARD_PATCH -> R.string.dry_guard
        dev.octaneui.container.DryRunId.ISOLATED_USERDATA -> R.string.dry_userdata
        dev.octaneui.container.DryRunId.DEV_POLICY -> R.string.dry_dev
        dev.octaneui.container.DryRunId.SELINUX_ENFORCING -> R.string.dry_selinux
        dev.octaneui.container.DryRunId.SUPERVISOR_WATCHDOG -> R.string.dry_watchdog
    }
)
