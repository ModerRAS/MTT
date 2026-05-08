package com.mtt.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtt.app.data.model.ExtractedTerm

/**
 * Extraction review dialog for AI-extracted items.
 * Shows candidates with type badges, categories, and checkboxes for selection.
 * Supports three types: character (角色), term (术语), non_translate (禁翻项).
 */
@Composable
fun ExtractionReviewDialog(
    terms: List<ExtractedTerm>,
    existingTerms: List<String> = emptyList(),  // list of existing source terms for dedup check
    onDismiss: () -> Unit,
    onConfirm: (selected: List<ExtractedTerm>) -> Unit
) {
    var selected by remember { mutableStateOf(terms.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入 (${selected.size}/${terms.size})") },
        text = {
            LazyColumn {
                items(terms) { term ->
                    val alreadyExists = existingTerms.contains(term.sourceTerm)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = term in selected && !alreadyExists,
                            onCheckedChange = { if (!alreadyExists) {
                                selected = if (term in selected) {
                                    selected - term
                                } else {
                                    selected + term
                                }
                            } },
                            enabled = !alreadyExists
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            // Source term (bold)
                            Text(term.sourceTerm, fontWeight = FontWeight.Bold)

                            // Type badge + suggested target
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeBadge(term.type)
                                Spacer(modifier = Modifier.width(4.dp))
                                if (term.suggestedTarget.isNotBlank()) {
                                    Text("→ ${term.suggestedTarget}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Already exists warning
                            if (alreadyExists) {
                                Text("已存在", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }

                            // Category + explanation
                            val details = mutableListOf<String>()
                            if (term.category.isNotBlank()) details.add(term.category)
                            if (term.explanation.isNotBlank() && term.explanation != term.category) {
                                details.add(term.explanation)
                            }
                            if (details.isNotEmpty()) {
                                Text(details.joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("确认导入 (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Small colored badge showing the item type.
 */
@Composable
fun TypeBadge(type: String) {
    val (label, bgColor) = when (type) {
        ExtractedTerm.TYPE_CHARACTER -> "角色" to Color(0xFFE3F2FD)
        ExtractedTerm.TYPE_TERM -> "术语" to Color(0xFFE8F5E9)
        ExtractedTerm.TYPE_NON_TRANSLATE -> "禁翻" to Color(0xFFFFF3E0)
        else -> "术语" to Color(0xFFE8F5E9)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            color = Color(0xFF616161)
        )
    }
}
