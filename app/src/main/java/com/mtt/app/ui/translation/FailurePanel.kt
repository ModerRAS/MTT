package com.mtt.app.ui.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtt.app.data.model.FailedItem

/**
 * Displays failed translation items with stats and retry options.
 * Shown inline in HomeScreen when there are failed items.
 */
@Composable
fun FailurePanel(
    failedItems: List<FailedItem>,
    totalItems: Int,
    onRetryAll: () -> Unit,
    onRetrySingle: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    isRetrying: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "翻译失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "${failedItems.size}/$totalItems 条",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                    )
                }

                // Circular progress indicator
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 1f - (failedItems.size.toFloat() / totalItems.coerceAtLeast(1)) },
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 4.dp
                    )
                    Text(
                        text = "${((1f - (failedItems.size.toFloat() / totalItems.coerceAtLeast(1))) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Retry all button
            Button(
                onClick = onRetryAll,
                enabled = !isRetrying,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRetrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试中...")
                } else {
                    Text("重试全部")
                }
            }

            // Failure item list
            if (failedItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "失败项:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(failedItems, key = { it.globalIndex }) { item ->
                        FailureItemRow(
                            item = item,
                            onRetry = { onRetrySingle(item.globalIndex) },
                            onSkip = { onSkip(item.globalIndex) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single row displaying a failed item with retry/skip options.
 */
@Composable
fun FailureItemRow(
    item: FailedItem,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Source text preview
            Text(
                text = if (item.sourceText.length > 50) {
                    item.sourceText.take(50) + "..."
                } else {
                    item.sourceText
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            // Status badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.retryCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "第 ${item.retryCount} 次重试",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (item.permanentlyFailed) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text(
                            text = "已放弃",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!item.permanentlyFailed) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("重试", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(
                onClick = onSkip,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("跳过", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
