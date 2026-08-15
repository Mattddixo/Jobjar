package com.mattdixon.jobjar.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mattdixon.jobjar.util.formatMinutes

@Composable
fun InfoBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TimeBucketBadge(minutes: Int, modifier: Modifier = Modifier) {
    InfoBadge(text = formatMinutes(minutes), modifier = modifier)
}

@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier) {
    InfoBadge(text = category, modifier = modifier)
}
