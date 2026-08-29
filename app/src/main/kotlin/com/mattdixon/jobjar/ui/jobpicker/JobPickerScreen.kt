package com.mattdixon.jobjar.ui.jobpicker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobPickerScreen(
    repository: JobRepository,
    returnJobId: Long,
    onBack: () -> Unit
) {
    val viewModel: JobPickerViewModel = viewModel(factory = JobPickerViewModel.Factory(repository, returnJobId))
    val items by viewModel.pickableJobs.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jobpicker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(Spacing.xxxl)
            ) {
                Text(stringResource(R.string.jobpicker_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(items, key = { it.job.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.linkTo(item.job) { linkedJob ->
                                fireLinkedCallback(context, trackerJobId = returnJobId, jobJarId = linkedJob.id)
                                onBack()
                            }
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(item.job.title, style = MaterialTheme.typography.bodyLarge)
                            item.parentTitle?.let {
                                Text(
                                    stringResource(R.string.label_part_of, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Not excluded from the list - see JobPickerViewModel.pickableJobs -
                            // but flagged so picking it is an informed choice: it'll overwrite
                            // whatever this job was linked to before.
                            if (item.job.linkedTrackerJobId != null) {
                                Text(
                                    stringResource(R.string.jobpicker_already_linked_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fireLinkedCallback(context: Context, trackerJobId: Long, jobJarId: Long) {
    val uri = Uri.parse("hometracker://linked").buildUpon()
        .appendQueryParameter("jobId", trackerJobId.toString())
        .appendQueryParameter("otherId", jobJarId.toString())
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.toast_tracker_not_installed), Toast.LENGTH_SHORT).show()
    }
}
