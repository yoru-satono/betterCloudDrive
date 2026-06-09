package com.betterclouddrive.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

data class BreadcrumbItem(val id: Long?, val name: String)

@Composable
fun BreadcrumbBar(
    items: List<BreadcrumbItem>,
    onCrumbClick: (BreadcrumbItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index == items.lastIndex) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onCrumbClick(item) },
                )
            }
        }
    }
}
