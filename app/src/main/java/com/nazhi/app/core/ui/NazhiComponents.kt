package com.nazhi.app.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nazhi.app.R

data class PixelFrameSpec(
    val borderWidth: Dp = 3.dp,
    val cornerStep: Dp = 8.dp,
    val shadowOffset: Dp = 2.dp,
    val highlightWidth: Dp = 1.dp,
    val contentPadding: Dp = 14.dp
)

@Composable
fun PixelFrame(
    modifier: Modifier = Modifier,
    backgroundColor: Color = NazhiTokens.colors.surfaceRaised,
    borderColor: Color = NazhiTokens.colors.soil,
    highlightColor: Color = Color.White.copy(alpha = 0.58f),
    shadowColor: Color = NazhiTokens.colors.soil.copy(alpha = 0.28f),
    spec: PixelFrameSpec = PixelFrameSpec(),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier.pixelFrameBackground(
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            highlightColor = highlightColor,
            shadowColor = shadowColor,
            spec = spec
        )
    ) {
        Column(
            modifier = Modifier.padding(spec.contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

fun Modifier.pixelFrameBackground(
    backgroundColor: Color,
    borderColor: Color,
    highlightColor: Color,
    shadowColor: Color,
    spec: PixelFrameSpec = PixelFrameSpec()
): Modifier {
    return drawBehind {
        val borderWidth = spec.borderWidth.toPx().coerceAtLeast(1f)
        val cornerStep = spec.cornerStep.toPx().coerceAtLeast(borderWidth * 2f)
        val shadowOffset = spec.shadowOffset.toPx()
        val highlightWidth = spec.highlightWidth.toPx().coerceAtLeast(1f)

        drawSteppedFrame(
            topLeft = Offset(shadowOffset, shadowOffset),
            width = size.width - shadowOffset,
            height = size.height - shadowOffset,
            cornerStep = cornerStep,
            borderWidth = borderWidth,
            fillColor = shadowColor,
            borderColor = shadowColor,
            highlightColor = Color.Transparent,
            highlightWidth = 0f
        )
        drawSteppedFrame(
            topLeft = Offset.Zero,
            width = size.width - shadowOffset,
            height = size.height - shadowOffset,
            cornerStep = cornerStep,
            borderWidth = borderWidth,
            fillColor = backgroundColor,
            borderColor = borderColor,
            highlightColor = highlightColor,
            highlightWidth = highlightWidth
        )
    }
}

@Composable
fun NazhiCard(
    modifier: Modifier = Modifier,
    containerColor: Color = NazhiTokens.colors.surfaceRaised,
    borderColor: Color = NazhiTokens.colors.panelBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    PixelFrame(
        modifier = modifier,
        backgroundColor = containerColor,
        borderColor = borderColor,
        shadowColor = NazhiTokens.colors.soil.copy(alpha = 0.18f),
        spec = PixelFrameSpec(contentPadding = NazhiTokens.spacing.card),
        content = content
    )
}

@Composable
fun NazhiPanel(
    modifier: Modifier = Modifier,
    containerColor: Color = NazhiTokens.colors.panel,
    borderColor: Color = NazhiTokens.colors.panelBorder,
    shadowColor: Color = NazhiTokens.colors.soil.copy(alpha = 0.2f),
    content: @Composable ColumnScope.() -> Unit
) {
    PixelFrame(
        modifier = modifier,
        borderColor = borderColor,
        backgroundColor = containerColor,
        shadowColor = shadowColor,
        spec = PixelFrameSpec(contentPadding = NazhiTokens.spacing.card),
        content = content
    )
}

@Composable
fun NazhiStatusChip(
    label: String,
    count: Int,
    kind: NazhiStatusKind,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = kind.palette()
    Box(
        modifier = modifier
            .aspectRatio(StatusChipSpec.width.toFloat() / StatusChipSpec.height.toFloat())
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(id = kind.chipBackgroundRes()),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val safeStart = maxWidth * (StatusChipSpec.safeX.toFloat() / StatusChipSpec.width)
            val safeEnd = maxWidth *
                ((StatusChipSpec.width - StatusChipSpec.safeX - StatusChipSpec.safeWidth).toFloat() / StatusChipSpec.width)
            val safeTop = maxHeight * (StatusChipSpec.safeY.toFloat() / StatusChipSpec.height)
            val safeBottom = maxHeight *
                ((StatusChipSpec.height - StatusChipSpec.safeY - StatusChipSpec.safeHeight).toFloat() / StatusChipSpec.height)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = safeStart, end = safeEnd, top = safeTop, bottom = safeBottom),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$label：$count",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) palette.accent else NazhiTokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FarmNoticeCard(
    title: String,
    message: String,
    statusKind: NazhiStatusKind,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    progressContent: (@Composable () -> Unit)? = null
) {
    val colors = NazhiTokens.colors
    val noticePalette = statusKind.palette()
    Box(modifier = modifier.heightIn(min = 260.dp)) {
        Image(
            painter = painterResource(id = R.drawable.notice_board),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Column(
            modifier = Modifier.padding(start = 50.dp, end = 50.dp, top = 42.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (statusKind == NazhiStatusKind.ISSUE) noticePalette.accent else colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                progressContent?.invoke()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-10).dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPrimaryAction,
                    enabled = primaryActionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.grassDark,
                        contentColor = colors.surfaceRaised,
                        disabledContainerColor = colors.grassSoft,
                        disabledContentColor = colors.textSecondary
                    )
                ) {
                    Text(text = primaryActionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, colors.wheat),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.wheatSoft.copy(alpha = 0.55f),
                            contentColor = colors.soil
                        )
                    ) {
                        Text(text = secondaryActionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private object StatusChipSpec {
    const val width = 360
    const val height = 128
    const val safeX = 112
    const val safeY = 24
    const val safeWidth = 220
    const val safeHeight = 80
}

private fun NazhiStatusKind.chipBackgroundRes(): Int {
    return when (this) {
        NazhiStatusKind.CAPTURED -> R.drawable.chip_pending
        NazhiStatusKind.PENDING -> R.drawable.chip_pending
        NazhiStatusKind.DRAFT -> R.drawable.chip_draft
        NazhiStatusKind.SETTLED -> R.drawable.chip_settled
        NazhiStatusKind.ISSUE -> R.drawable.chip_issue
    }
}

private fun DrawScope.drawSteppedFrame(
    topLeft: Offset,
    width: Float,
    height: Float,
    cornerStep: Float,
    borderWidth: Float,
    fillColor: Color,
    borderColor: Color,
    highlightColor: Color,
    highlightWidth: Float
) {
    if (width <= 0f || height <= 0f) return
    val outerPath = steppedRectPath(topLeft, width, height, cornerStep)
    drawPath(path = outerPath, color = borderColor)
    val innerInset = borderWidth
    val innerPath = steppedRectPath(
        topLeft = Offset(topLeft.x + innerInset, topLeft.y + innerInset),
        width = (width - innerInset * 2f).coerceAtLeast(0f),
        height = (height - innerInset * 2f).coerceAtLeast(0f),
        cornerStep = (cornerStep - innerInset).coerceAtLeast(innerInset)
    )
    drawPath(path = innerPath, color = fillColor)

    if (highlightWidth > 0f && highlightColor.alpha > 0f) {
        drawRect(
            color = highlightColor,
            topLeft = Offset(topLeft.x + cornerStep, topLeft.y + borderWidth),
            size = Size((width - cornerStep * 2f).coerceAtLeast(0f), highlightWidth)
        )
        drawRect(
            color = highlightColor,
            topLeft = Offset(topLeft.x + borderWidth, topLeft.y + cornerStep),
            size = Size(highlightWidth, (height - cornerStep * 2f).coerceAtLeast(0f))
        )
    }
}

private fun steppedRectPath(
    topLeft: Offset,
    width: Float,
    height: Float,
    cornerStep: Float
): Path {
    val x = topLeft.x
    val y = topLeft.y
    val right = x + width
    val bottom = y + height
    val step = cornerStep.coerceAtMost(width / 2f).coerceAtMost(height / 2f)
    return Path().apply {
        moveTo(x + step, y)
        lineTo(right - step, y)
        lineTo(right - step, y + step)
        lineTo(right, y + step)
        lineTo(right, bottom - step)
        lineTo(right - step, bottom - step)
        lineTo(right - step, bottom)
        lineTo(x + step, bottom)
        lineTo(x + step, bottom - step)
        lineTo(x, bottom - step)
        lineTo(x, y + step)
        lineTo(x + step, y + step)
        close()
    }
}

@Composable
fun PixelStatusIcon(
    kind: NazhiStatusKind,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val colors = NazhiTokens.colors
    val palette = kind.palette()
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 8f
        fun block(x: Float, y: Float, w: Float, h: Float, color: Color) {
            drawRect(
                color = color,
                topLeft = Offset(x * unit, y * unit),
                size = Size(w * unit, h * unit)
            )
        }

        when (kind) {
            NazhiStatusKind.CAPTURED -> {
                block(1f, 5.5f, 6f, 1.1f, colors.soil)
                block(2f, 4.5f, 4f, 1f, colors.soilSoft)
                block(3.2f, 2.6f, 1.6f, 1.4f, palette.accent)
                block(2.6f, 3.4f, 2.8f, 0.9f, colors.skySoft)
            }
            NazhiStatusKind.PENDING -> {
                block(1.4f, 6f, 5.2f, 0.9f, colors.soil)
                block(3.7f, 3.2f, 0.7f, 2.8f, colors.grassDark)
                block(2.4f, 3.2f, 1.5f, 1f, colors.grass)
                block(4.2f, 2.5f, 1.6f, 1.1f, Color(0xFF7DBE6E))
            }
            NazhiStatusKind.DRAFT -> {
                block(1.2f, 6f, 5.6f, 0.9f, colors.soil)
                block(3.6f, 2.1f, 0.8f, 3.9f, colors.grassDark)
                block(2.3f, 3.2f, 1.5f, 1.1f, colors.grass)
                block(4.2f, 2.7f, 1.7f, 1.2f, Color(0xFF75B765))
                block(2.2f, 1.2f, 3.6f, 0.8f, colors.wheat)
            }
            NazhiStatusKind.SETTLED -> {
                block(3.4f, 4.5f, 1.2f, 1.7f, colors.soil)
                block(2f, 2.3f, 4f, 2.2f, colors.grassDark)
                block(1.4f, 3.1f, 5.2f, 1.7f, colors.grass)
                block(2.4f, 1.6f, 3.1f, 1.4f, Color(0xFF66AD65))
                block(2.4f, 3.2f, 0.7f, 0.7f, colors.fruit)
                block(5f, 3.7f, 0.7f, 0.7f, colors.fruit)
            }
            NazhiStatusKind.ISSUE -> {
                drawWarningSign(colors)
            }
        }
    }
}

private fun DrawScope.drawWarningSign(colors: NazhiColorTokens) {
    val unit = size.minDimension / 8f
    fun block(x: Float, y: Float, w: Float, h: Float, color: Color) {
        drawRect(
            color = color,
            topLeft = Offset(x * unit, y * unit),
            size = Size(w * unit, h * unit)
        )
    }
    block(3.6f, 4.9f, 0.8f, 2.1f, colors.soil)
    block(1.4f, 2.2f, 5.2f, 2.8f, colors.issueSoft)
    block(1.4f, 2.2f, 5.2f, 0.45f, colors.issue)
    block(1.4f, 4.55f, 5.2f, 0.45f, colors.issue)
    block(2.7f, 3.05f, 2.6f, 0.55f, colors.issue)
    block(3.2f, 3.85f, 1.6f, 0.45f, colors.issue)
}
