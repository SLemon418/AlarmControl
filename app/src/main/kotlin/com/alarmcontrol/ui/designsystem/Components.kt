package com.alarmcontrol.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val ScreenHorizontalPadding = 16.dp
val ScreenContentMaxWidth = 840.dp

/** Centers readable content on expanded windows without changing compact-phone behavior. */
@Composable
fun MaxWidthContent(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = ScreenContentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = maxWidth)) { content() }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        action?.invoke()
    }
}

@Composable
fun StatusPill(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val container =
        if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun ExpressiveHeroCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    illustration: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (illustration == null) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(20.dp).size(40.dp))
            }
        } else {
            illustration()
        }
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

/** Decorative local illustration for the rules empty state; no image asset or network is involved. */
@Composable
fun FilterShieldGraphic(modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    val filter = MaterialTheme.colorScheme.secondary
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Canvas(modifier.padding(16.dp).size(64.dp)) {
            val shield =
                Path().apply {
                    moveTo(size.width / 2f, 2.dp.toPx())
                    lineTo(size.width - 7.dp.toPx(), 12.dp.toPx())
                    lineTo(size.width - 10.dp.toPx(), size.height * SHIELD_LOWER_EDGE_Y)
                    quadraticTo(
                        size.width * SHIELD_RIGHT_CONTROL_X,
                        size.height * SHIELD_BOTTOM_CONTROL_Y,
                        size.width / 2f,
                        size.height - 3.dp.toPx(),
                    )
                    quadraticTo(
                        size.width * SHIELD_LEFT_CONTROL_X,
                        size.height * SHIELD_BOTTOM_CONTROL_Y,
                        10.dp.toPx(),
                        size.height * SHIELD_LOWER_EDGE_Y,
                    )
                    lineTo(7.dp.toPx(), 12.dp.toPx())
                    close()
                }
            drawPath(shield, color = fill)
            drawPath(shield, color = outline, style = Stroke(width = 2.dp.toPx()))
            val stroke = 3.dp.toPx()
            filterLines.forEach { (y, endX) ->
                drawLine(
                    color = filter,
                    start =
                        androidx.compose.ui.geometry
                            .Offset(size.width * FILTER_START_X, size.height * y),
                    end =
                        androidx.compose.ui.geometry
                            .Offset(size.width * endX, size.height * y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private const val SHIELD_LOWER_EDGE_Y = 0.62f
private const val SHIELD_RIGHT_CONTROL_X = 0.72f
private const val SHIELD_LEFT_CONTROL_X = 0.28f
private const val SHIELD_BOTTOM_CONTROL_Y = 0.88f
private const val FILTER_START_X = 0.34f
private const val FILTER_TOP_Y = 0.34f
private const val FILTER_TOP_END_X = 0.66f
private const val FILTER_MIDDLE_Y = 0.46f
private const val FILTER_MIDDLE_END_X = 0.58f
private const val FILTER_BOTTOM_Y = 0.58f
private const val FILTER_BOTTOM_END_X = 0.50f
private val filterLines =
    listOf(
        FILTER_TOP_Y to FILTER_TOP_END_X,
        FILTER_MIDDLE_Y to FILTER_MIDDLE_END_X,
        FILTER_BOTTOM_Y to FILTER_BOTTOM_END_X,
    )
