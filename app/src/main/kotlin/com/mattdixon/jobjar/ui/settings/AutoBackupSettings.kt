package com.mattdixon.jobjar.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.backup.AutoBackup
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings > Backup > Weekly backups: pick a folder once (Downloads, or one a cloud drive syncs)
 * and a backup lands there every week, with the last few kept. Shows when the last one was.
 */
@Composable
fun AutoBackupSettings(repository: JobRepository) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var folderName by remember { mutableStateOf(AutoBackup.folderName(context)) }
    var on by remember { mutableStateOf(AutoBackup.folder(context) != null) }
    var last by remember { mutableStateOf(AutoBackup.lastBackupAt(context)) }
    var failed by remember { mutableStateOf(false) }

    fun refresh(ok: Boolean) {
        on = AutoBackup.folder(context) != null
        folderName = AutoBackup.folderName(context)
        last = AutoBackup.lastBackupAt(context)
        failed = !ok
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch { refresh(AutoBackup.setFolder(context, tree, repository)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.settings_auto_backup), style = MaterialTheme.typography.bodyLarge)
        if (!on) {
            Text(
                stringResource(R.string.settings_auto_backup_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Text(stringResource(R.string.settings_auto_backup_choose), modifier = Modifier.padding(start = Spacing.sm))
            }
        } else {
            Text(
                stringResource(R.string.settings_auto_backup_to, folderName ?: stringResource(R.string.settings_auto_backup_folder)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                last?.let {
                    val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    stringResource(R.string.settings_auto_backup_last, date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale)))
                } ?: stringResource(R.string.settings_auto_backup_never),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = { scope.launch { refresh(AutoBackup.backUp(context, repository)) } }) {
                    Text(stringResource(R.string.settings_auto_backup_now))
                }
                TextButton(onClick = { pickFolder.launch(null) }) {
                    Text(stringResource(R.string.settings_auto_backup_change))
                }
                TextButton(onClick = {
                    AutoBackup.turnOff(context)
                    refresh(true)
                }) {
                    Text(stringResource(R.string.settings_auto_backup_off))
                }
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.settings_auto_backup_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
