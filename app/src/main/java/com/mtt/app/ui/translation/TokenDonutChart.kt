package com.mtt.app.ui.translation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

/**
 * Token usage data for the donut chart.
 */
data class TokenChartData(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheTokens: Long
) {
    val totalTokens: Long get() = inputTokens + outputTokens + cacheTokens
    val hasData: Boolean get() = totalTokens > 0
}

/**
 * A beautiful donut chart showing token usage breakdown (input / output / cache).
 *
 * Draws a donut (arc segments) using Compose Canvas with a legend below.
 * The center of the donut shows total tokens.
 */
@Composable
fun TokenDonutChart(
    data: TokenChartData,
    modifier: Modifier = Modifier
) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    val inputColor = MaterialTheme.colorScheme.primary
    val outputColor = MaterialTheme.colorScheme.secondary
    val cacheColor = MaterialTheme.colorScheme.tertiary

    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Donut canvas ──
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val strokeWidth = 28.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val topLeft = Offset(
                    (size.width - radius * 2) / 2f,
                    (size.height - radius * 2) / 2f
                )
                val arcSize = Size(radius * 2, radius * 2)

                val total = data.totalTokens.toFloat()
                val sweepInput = if (total > 0) (data.inputTokens.toFloat() / total) * 360f else 0f
                val sweepOutput = if (total > 0) (data.outputTokens.toFloat() / total) * 360f else 0f
                val sweepCache = if (total > 0) (data.cacheTokens.toFloat() / total) * 360f else 0f

                if (total == 0f) {
                    // Empty state: full circle in muted color
                    drawArc(
                        color = emptyColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                } else {
                    // Draw arcs: start at -90 (12 o'clock), go clockwise
                    var startAngle = -90f

                    // Input tokens (primary)
                    if (sweepInput > 0f) {
                        drawArc(
                            color = inputColor,
                            startAngle = startAngle,
                            sweepAngle = sweepInput,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                        startAngle += sweepInput
                    }

                    // Output tokens (secondary)
                    if (sweepOutput > 0f) {
                        drawArc(
                            color = outputColor,
                            startAngle = startAngle,
                            sweepAngle = sweepOutput,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                        startAngle += sweepCache
                    }

                    // Cache tokens (tertiary)
                    if (sweepCache > 0f) {
                        drawArc(
                            color = cacheColor,
                            startAngle = startAngle,
                            sweepAngle = sweepCache,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                    }
                }
            }

            // Center text: total tokens
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (data.hasData) numberFormat.format(data.totalTokens) else "0",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── Legend ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LegendItem(
                color = inputColor,
                label = "输入",
                value = numberFormat.format(data.inputTokens),
                visible = data.hasData
            )
            LegendItem(
                color = outputColor,
                label = "输出",
                value = numberFormat.format(data.outputTokens),
                visible = data.hasData
            )
            LegendItem(
                color = cacheColor,
                label = "缓存",
                value = numberFormat.format(data.cacheTokens),
                visible = data.hasData
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    value: String,
    visible: Boolean
) {
    if (!visible) return
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
