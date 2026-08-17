package com.mattdixon.jobjar.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.ui.theme.AppShapes
import com.mattdixon.jobjar.util.formatMinutes

/** [compact] trims the padding for tighter contexts (e.g. the Jar screen's drawn-job cards)
 * without changing how the badge looks anywhere that doesn't opt in. */
@Composable
fun InfoBadge(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = AppShapes.pill,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        )
    }
}

@Composable
fun TimeBucketBadge(minutes: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    InfoBadge(text = formatMinutes(minutes), modifier = modifier, compact = compact)
}

@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    InfoBadge(text = category, modifier = modifier, compact = compact)
}
