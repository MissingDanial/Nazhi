package com.nazhi.app.feature.farm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nazhi.app.R
import com.nazhi.app.core.model.DailyFarmSnapshot
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.ui.NazhiStatusKind
import com.nazhi.app.core.ui.PixelStatusIcon
import kotlin.math.abs
import kotlin.math.min

private const val FARM_SIZE = 5
private const val FARM_CENTER = 2
private const val MAX_FARM_PLOTS = FARM_SIZE * FARM_SIZE
private const val PRIORITY_STAGE_SOFT_LIMIT = 8
private const val FARM_MIN_SCALE = 1f
private const val FARM_MAX_SCALE = 2.2f

enum class FarmStage {
    SAPLING,
    PLANT,
    MATURE
}

enum class FarmOwnerType {
    NOTE,
    DRAFT,
    KNOWLEDGE_ENTRY
}

data class FarmContentItem(
    val ownerType: FarmOwnerType,
    val ownerId: String,
    val stage: FarmStage,
    val title: String,
    val preview: String,
    val charCount: Int,
    val createdAt: Long
)

data class FarmPlotUiModel(
    val row: Int,
    val col: Int,
    val stage: FarmStage,
    val level: Int,
    val items: List<FarmContentItem>,
    val plotId: String = "$row:$col"
)

@Composable
fun DailyFarmPreview(
    snapshot: DailyFarmSnapshot,
    modifier: Modifier = Modifier,
    plots: List<FarmPlotUiModel> = emptyList(),
    selectedPlotId: String? = null,
    onPlotClick: (FarmPlotUiModel) -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 352.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.farm_panel_bg),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Column(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "知识农场",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = snapshot.dateId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FarmMetric(label = "待整理", count = snapshot.saplingCount, kind = NazhiStatusKind.PENDING)
                    FarmMetric(label = "待确认", count = snapshot.plantCount, kind = NazhiStatusKind.DRAFT)
                    FarmMetric(label = "已沉淀", count = snapshot.matureCount, kind = NazhiStatusKind.SETTLED)
                }
            }

            DailyFarmCanvas(
                snapshot = snapshot,
                plots = plots,
                selectedPlotId = selectedPlotId,
                onPlotClick = onPlotClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(244.dp)
            )
        }
    }
}

@Composable
private fun FarmMetric(
    label: String,
    count: Int,
    kind: NazhiStatusKind
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PixelStatusIcon(kind = kind, size = 16.dp)
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DailyFarmCanvas(
    snapshot: DailyFarmSnapshot,
    plots: List<FarmPlotUiModel>,
    selectedPlotId: String?,
    onPlotClick: (FarmPlotUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayPlots = remember(snapshot, plots) {
        plots.ifEmpty { buildCountFarmPlots(snapshot) }
    }
    var zoomScale by remember(snapshot.dateId) { mutableStateOf(FARM_MIN_SCALE) }
    var pan by remember(snapshot.dateId) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
            .pointerInput(displayPlots, zoomScale, pan, canvasSize, onPlotClick) {
                detectTapGestures { tapOffset ->
                    val plot = hitTestFarmPlot(
                        tapOffset = tapOffset,
                        canvasSize = canvasSize,
                        scale = zoomScale,
                        pan = pan,
                        plots = displayPlots
                    )
                    if (plot != null && plot.items.isNotEmpty()) {
                        onPlotClick(plot)
                    }
                }
            }
            .pointerInput(canvasSize) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var anyPressed: Boolean
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        anyPressed = pressedCount > 0
                        val shouldHandleFarmGesture = pressedCount > 1 || zoomScale > FARM_MIN_SCALE

                        if (shouldHandleFarmGesture) {
                            val newScale = (zoomScale * event.calculateZoom()).coerceIn(FARM_MIN_SCALE, FARM_MAX_SCALE)
                            zoomScale = newScale
                            pan = if (newScale == FARM_MIN_SCALE) {
                                Offset.Zero
                            } else {
                                clampPan(pan + event.calculatePan(), canvasSize, newScale)
                            }
                            event.changes
                                .filter { it.positionChanged() }
                                .forEach { it.consume() }
                        }
                    } while (anyPressed)
                }
            }
    ) {
        val layout = buildFarmLayout(size.width, size.height)
        val plotByKey = displayPlots.associateBy { PlotKey(it.row, it.col) }
        val selectedPlotKey = displayPlots
            .firstOrNull { it.plotId == selectedPlotId }
            ?.let { PlotKey(it.row, it.col) }

        clipRect {
            withTransform({
                translate(left = pan.x, top = pan.y)
                scale(
                    scaleX = zoomScale,
                    scaleY = zoomScale,
                    pivot = Offset(size.width / 2f, size.height / 2f)
                )
            }) {
                drawPixelFarmBackdrop()

                layout.tiles.forEach { tile ->
                    val baseColor = if ((tile.key.row + tile.key.col) % 2 == 0) {
                        Color(0xFFE5CAA0)
                    } else {
                        Color(0xFFDDBF90)
                    }
                    drawIsoTile(
                        center = tile.center,
                        tileWidth = layout.tileWidth,
                        tileHeight = layout.tileHeight,
                        fill = baseColor,
                        border = Color(0xFF9E7956)
                    )
                }

                if (selectedPlotKey != null) {
                    layout.tiles.firstOrNull { it.key == selectedPlotKey }?.let { tile ->
                        drawSelectedIsoTile(
                            center = tile.center,
                            tileWidth = layout.tileWidth,
                            tileHeight = layout.tileHeight
                        )
                    }
                }

                layout.tiles.forEach { tile ->
                    plotByKey[tile.key]?.let { plot ->
                        drawFarmCrop(
                            plot = plot,
                            center = tile.center,
                            tileWidth = layout.tileWidth,
                            tileHeight = layout.tileHeight,
                            dateSeed = snapshot.dateId
                        )
                    }
                }

                if (snapshot.issueCount > 0) {
                    drawFarmIssueSign(layout = layout)
                }
            }
        }
    }
}

fun buildFarmPlotModels(
    dateId: String,
    notes: List<Note>,
    drafts: List<KnowledgeEntryDraft>,
    knowledgeEntries: List<KnowledgeEntry>
): List<FarmPlotUiModel> {
    val draftItems = drafts
        .sortedByDescending { it.updatedAt }
        .map { it.toFarmContentItem() }
    val noteItems = notes
        .sortedByDescending { it.updatedAt }
        .map { it.toFarmContentItem() }
    val matureItems = knowledgeEntries
        .sortedByDescending { it.confirmedAt }
        .map { it.toFarmContentItem() }
    val allocation = allocateSlots(
        draftCount = draftItems.size,
        noteCount = noteItems.size,
        matureCount = matureItems.size
    )
    val centerOut = centerOutPlotKeys()
    val available = centerOut.toMutableList()
    val result = mutableListOf<FarmPlotUiModel>()

    fun appendStage(items: List<FarmContentItem>, slots: Int, stage: FarmStage) {
        if (items.isEmpty() || slots <= 0 || available.isEmpty()) return
        val chunks = distributeItems(items, min(slots, available.size))
        chunks.forEach { chunk ->
            val key = available.removeAt(0)
            result += FarmPlotUiModel(
                row = key.row,
                col = key.col,
                stage = stage,
                level = farmPlotLevel(chunk),
                items = chunk,
                plotId = "$dateId:${key.row}:${key.col}"
            )
        }
    }

    appendStage(draftItems, allocation.draftSlots, FarmStage.PLANT)
    appendStage(noteItems, allocation.noteSlots, FarmStage.SAPLING)
    appendStage(matureItems, allocation.matureSlots, FarmStage.MATURE)

    return result
}

private fun buildFarmLayout(
    width: Float,
    height: Float
): FarmLayout {
    val tileWidth = min(width / 5.7f, height / 3.75f)
    val tileHeight = tileWidth * 0.52f
    val gridHeight = tileHeight * FARM_SIZE
    val originY = ((height - gridHeight) / 2f + tileHeight * 0.35f).coerceAtLeast(22f)
    val originX = width / 2f
    return FarmLayout(
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        tiles = buildFarmTiles(originX, originY, tileWidth, tileHeight)
    )
}

private fun buildFarmTiles(
    originX: Float,
    originY: Float,
    tileWidth: Float,
    tileHeight: Float
): List<FarmTile> {
    return buildList {
        for (row in 0 until FARM_SIZE) {
            for (col in 0 until FARM_SIZE) {
                add(
                    FarmTile(
                        key = PlotKey(row, col),
                        center = Offset(
                            x = originX + (col - row) * tileWidth / 2f,
                            y = originY + (row + col) * tileHeight / 2f + tileHeight / 2f
                        )
                    )
                )
            }
        }
    }.sortedWith(compareBy<FarmTile> { it.key.row + it.key.col }.thenBy { it.key.row })
}

private fun buildCountFarmPlots(snapshot: DailyFarmSnapshot): List<FarmPlotUiModel> {
    val allocation = allocateSlots(
        draftCount = snapshot.plantCount,
        noteCount = snapshot.saplingCount,
        matureCount = snapshot.matureCount
    )
    val centerOut = centerOutPlotKeys()
    val result = mutableListOf<FarmPlot>()
    val available = centerOut.toMutableList()

    fun takePlots(units: Int, slots: Int, stage: FarmStage) {
        if (units <= 0 || available.isEmpty()) return
        val keys = centerOut.filter { it in available }
        val requestedSlots = min(slots, MAX_FARM_PLOTS)
        if (requestedSlots <= 0) return
        val cellCount = min(min(units, requestedSlots), keys.size)
        if (cellCount <= 0) return
        val baseLevel = units / cellCount
        val bonusCount = units % cellCount

        keys.take(cellCount).forEachIndexed { index, key ->
            val level = (baseLevel + if (index < bonusCount) 1 else 0).coerceIn(1, 3)
            result += FarmPlot(
                key = key,
                model = FarmPlotUiModel(
                    row = key.row,
                    col = key.col,
                    stage = stage,
                    level = level,
                    items = emptyList(),
                    plotId = "${snapshot.dateId}:${key.row}:${key.col}"
                )
            )
            available.remove(key)
        }
    }

    takePlots(snapshot.plantCount, allocation.draftSlots, FarmStage.PLANT)
    takePlots(snapshot.saplingCount, allocation.noteSlots, FarmStage.SAPLING)
    takePlots(snapshot.matureCount, allocation.matureSlots, FarmStage.MATURE)

    return result.map { it.model }
}

private fun centerOutPlotKeys(): List<PlotKey> {
    return allPlotKeys().sortedWith(
        compareBy<PlotKey> { it.distanceFromCenter() }
            .thenBy { it.row + it.col }
            .thenBy { it.col }
    )
}

private fun allPlotKeys(): List<PlotKey> {
    return buildList {
        for (row in 0 until FARM_SIZE) {
            for (col in 0 until FARM_SIZE) {
                add(PlotKey(row, col))
            }
        }
    }
}

private fun DrawScope.drawIsoTile(
    center: Offset,
    tileWidth: Float,
    tileHeight: Float,
    fill: Color,
    border: Color
) {
    val path = isoTilePath(center, tileWidth, tileHeight)
    val shadowPath = isoTilePath(Offset(center.x + tileWidth * 0.035f, center.y + tileHeight * 0.08f), tileWidth, tileHeight)
    drawPath(path = shadowPath, color = Color(0x22000000))
    drawPath(path = path, color = fill)
    drawPath(
        path = path,
        color = border.copy(alpha = 0.82f),
        style = Stroke(width = (tileWidth * 0.035f).coerceAtLeast(2.2f))
    )
    drawPath(
        path = path,
        color = Color(0xFFFFF4D7).copy(alpha = 0.64f),
        style = Stroke(width = (tileWidth * 0.012f).coerceAtLeast(1f))
    )
}

private fun DrawScope.drawSelectedIsoTile(
    center: Offset,
    tileWidth: Float,
    tileHeight: Float
) {
    val path = isoTilePath(center, tileWidth, tileHeight)
    drawPath(path = path, color = Color(0x442F7D57))
    drawPath(
        path = path,
        color = Color(0xFF1E573D),
        style = Stroke(width = (tileWidth * 0.07f).coerceAtLeast(4.2f))
    )
    drawPath(
        path = path,
        color = Color(0xFFFFF8EA).copy(alpha = 0.92f),
        style = Stroke(width = (tileWidth * 0.026f).coerceAtLeast(1.8f))
    )
}

private fun DrawScope.drawPixelFarmBackdrop() {
    val step = size.minDimension * 0.045f
    val shadow = step * 0.28f
    val border = step * 0.18f
    drawPath(
        path = steppedCanvasPath(Offset(shadow, shadow), size.width - shadow, size.height - shadow, step),
        color = Color(0x2A8A6243)
    )
    drawPath(
        path = steppedCanvasPath(Offset.Zero, size.width - shadow, size.height - shadow, step),
        color = Color(0xFF8A6243)
    )
    drawPath(
        path = steppedCanvasPath(
            Offset(border, border),
            size.width - shadow - border * 2f,
            size.height - shadow - border * 2f,
            (step - border).coerceAtLeast(border * 2f)
        ),
        color = Color(0xFFF3E7CC)
    )
    drawRect(
        color = Color(0x66FFF4D7),
        topLeft = Offset(step, border),
        size = Size((size.width - step * 2f - shadow).coerceAtLeast(0f), border * 0.72f)
    )
}

private fun steppedCanvasPath(
    topLeft: Offset,
    width: Float,
    height: Float,
    step: Float
): Path {
    val x = topLeft.x
    val y = topLeft.y
    val right = x + width
    val bottom = y + height
    val corner = step.coerceAtMost(width / 2f).coerceAtMost(height / 2f)
    return Path().apply {
        moveTo(x + corner, y)
        lineTo(right - corner, y)
        lineTo(right - corner, y + corner)
        lineTo(right, y + corner)
        lineTo(right, bottom - corner)
        lineTo(right - corner, bottom - corner)
        lineTo(right - corner, bottom)
        lineTo(x + corner, bottom)
        lineTo(x + corner, bottom - corner)
        lineTo(x, bottom - corner)
        lineTo(x, y + corner)
        lineTo(x + corner, y + corner)
        close()
    }
}

private fun isoTilePath(
    center: Offset,
    tileWidth: Float,
    tileHeight: Float
): Path {
    return Path().apply {
        moveTo(center.x, center.y - tileHeight / 2f)
        lineTo(center.x + tileWidth / 2f, center.y)
        lineTo(center.x, center.y + tileHeight / 2f)
        lineTo(center.x - tileWidth / 2f, center.y)
        close()
    }
}

private fun DrawScope.drawFarmCrop(
    plot: FarmPlotUiModel,
    center: Offset,
    tileWidth: Float,
    tileHeight: Float,
    dateSeed: String
) {
    val salt = plot.row * 31 + plot.col * 17 + plot.stage.ordinal * 13
    val jitterX = stableNoise(dateSeed, salt) * tileWidth * 0.07f
    val jitterY = stableNoise(dateSeed, salt + 7) * tileHeight * 0.14f
    val base = Offset(center.x + jitterX, center.y + jitterY + tileHeight * 0.08f)

    drawOval(
        color = Color(0x22000000),
        topLeft = Offset(base.x - tileWidth * 0.16f, base.y - tileHeight * 0.10f),
        size = Size(tileWidth * 0.32f, tileHeight * 0.18f)
    )

    when (plot.stage) {
        FarmStage.SAPLING -> drawSapling(base, tileWidth, tileHeight, plot.level)
        FarmStage.PLANT -> drawPlant(base, tileWidth, tileHeight, plot.level)
        FarmStage.MATURE -> drawMaturePlant(base, tileWidth, tileHeight, plot.level, dateSeed, salt)
    }
}

private fun DrawScope.drawSapling(
    base: Offset,
    tileWidth: Float,
    tileHeight: Float,
    level: Int
) {
    val stemColor = Color(0xFF5E7F4A)
    val leafColor = Color(0xFF6EAD64)
    val top = Offset(base.x, base.y - tileHeight * (0.44f + level * 0.08f))
    val block = (tileWidth * 0.075f).coerceAtLeast(3f)
    drawPixelBlock(center = Offset(base.x, (base.y + top.y) / 2f), width = block, height = base.y - top.y, color = stemColor)
    drawPixelBlock(center = Offset(top.x - block, top.y + block * 0.2f), width = block * 1.5f, height = block, color = leafColor)
    drawPixelBlock(center = Offset(top.x + block, top.y - block * 0.2f), width = block * 1.5f, height = block, color = Color(0xFF83C477))
}

private fun DrawScope.drawPlant(
    base: Offset,
    tileWidth: Float,
    tileHeight: Float,
    level: Int
) {
    val stemColor = Color(0xFF4F7742)
    val leafColor = Color(0xFF3F9160)
    val lightLeaf = Color(0xFF69B979)
    val height = tileHeight * (0.82f + level * 0.13f)
    val top = Offset(base.x, base.y - height)
    val block = (tileWidth * 0.082f).coerceAtLeast(3.2f)
    drawPixelBlock(center = Offset(base.x, (base.y + top.y) / 2f), width = block, height = height, color = stemColor)
    drawPixelBlock(center = Offset(top.x - block * 1.2f, top.y + block * 1.6f), width = block * 2f, height = block * 1.2f, color = leafColor)
    drawPixelBlock(center = Offset(top.x + block * 1.2f, top.y + block), width = block * 2.1f, height = block * 1.3f, color = lightLeaf)
    drawPixelBlock(center = Offset(top.x, top.y), width = block * 2f, height = block * 1.5f, color = Color(0xFF2F7D57))
}

private fun DrawScope.drawMaturePlant(
    base: Offset,
    tileWidth: Float,
    tileHeight: Float,
    level: Int,
    dateSeed: String,
    salt: Int
) {
    val trunkColor = Color(0xFF7A6A4D)
    val darkLeaf = Color(0xFF276E4B)
    val midLeaf = Color(0xFF348B5E)
    val lightLeaf = Color(0xFF61AD70)
    val height = tileHeight * (1.04f + level * 0.16f)
    val top = Offset(base.x, base.y - height)
    val block = (tileWidth * 0.09f).coerceAtLeast(3.4f)
    drawPixelBlock(center = Offset(base.x, (base.y + top.y) / 2f), width = block * 1.15f, height = height, color = trunkColor)
    val crown = tileWidth * (0.20f + level * 0.018f)
    drawPixelBlock(center = Offset(top.x, top.y), width = crown * 1.5f, height = crown * 1.1f, color = darkLeaf)
    drawPixelBlock(center = Offset(top.x - crown * 0.62f, top.y + crown * 0.32f), width = crown * 1.1f, height = crown * 0.9f, color = midLeaf)
    drawPixelBlock(center = Offset(top.x + crown * 0.62f, top.y + crown * 0.22f), width = crown * 1.1f, height = crown * 0.85f, color = lightLeaf)
    drawPixelBlock(center = Offset(top.x, top.y - crown * 0.58f), width = crown * 1.05f, height = crown * 0.8f, color = Color(0xFF2F7D57))

    val fruitColor = Color(0xFFE16B6F)
    repeat(level) { index ->
        val angleX = stableNoise(dateSeed, salt + 19 + index) * crown * 0.52f
        val angleY = stableNoise(dateSeed, salt + 29 + index) * crown * 0.36f
        drawPixelBlock(
            center = Offset(top.x + angleX, top.y + angleY),
            width = block * 0.8f,
            height = block * 0.8f,
            color = fruitColor
        )
    }
}

private fun DrawScope.drawPixelBlock(
    center: Offset,
    width: Float,
    height: Float,
    color: Color
) {
    drawRect(
        color = color,
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height)
    )
}

private fun DrawScope.drawFarmIssueSign(layout: FarmLayout) {
    val tileWidth = layout.tileWidth
    val tileHeight = layout.tileHeight
    val right = layout.tiles.maxOf { it.center.x } + tileWidth * 0.26f
    val top = layout.tiles.minOf { it.center.y } - tileHeight * 0.06f
    val signWidth = tileWidth * 0.44f
    val signHeight = tileHeight * 0.56f
    drawRect(
        color = Color(0xFF8A6243),
        topLeft = Offset(right - signWidth * 0.08f, top + signHeight * 0.72f),
        size = Size(signWidth * 0.16f, signHeight * 0.8f)
    )
    drawRect(
        color = Color(0xFF70482F),
        topLeft = Offset(right - signWidth * 0.58f, top - signHeight * 0.08f),
        size = Size(signWidth * 1.16f, signHeight * 1.16f)
    )
    drawRect(
        color = Color(0xFFF7D6C7),
        topLeft = Offset(right - signWidth * 0.46f, top + signHeight * 0.04f),
        size = Size(signWidth * 0.92f, signHeight * 0.92f)
    )
    drawRect(
        color = Color(0xFFB65A37),
        topLeft = Offset(right - signWidth * 0.46f, top + signHeight * 0.04f),
        size = Size(signWidth * 0.92f, signHeight * 0.16f)
    )
    drawRect(
        color = Color(0xFFB65A37),
        topLeft = Offset(right - signWidth * 0.24f, top + signHeight * 0.42f),
        size = Size(signWidth * 0.48f, signHeight * 0.14f)
    )
    drawRect(
        color = Color(0xFFF7D6C7),
        topLeft = Offset(right - signWidth * 0.58f, top - signHeight * 0.08f),
        size = Size(signWidth * 0.18f, signHeight * 0.18f)
    )
    drawRect(
        color = Color(0xFFF7D6C7),
        topLeft = Offset(right + signWidth * 0.40f, top - signHeight * 0.08f),
        size = Size(signWidth * 0.18f, signHeight * 0.18f)
    )
}

private fun stableNoise(seed: String, salt: Int): Float {
    var hash = 0x811C9DC5.toInt()
    val text = "$seed:$salt"
    text.forEach { char ->
        hash = hash xor char.code
        hash *= 16777619
    }
    val positive = hash ushr 1
    return (positive % 2001) / 1000f - 1f
}

private fun hitTestFarmPlot(
    tapOffset: Offset,
    canvasSize: IntSize,
    scale: Float,
    pan: Offset,
    plots: List<FarmPlotUiModel>
): FarmPlotUiModel? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || plots.isEmpty()) return null
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val contentOffset = Offset(
        x = center.x + (tapOffset.x - pan.x - center.x) / scale,
        y = center.y + (tapOffset.y - pan.y - center.y) / scale
    )
    val layout = buildFarmLayout(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    val plotByKey = plots.associateBy { PlotKey(it.row, it.col) }
    return layout.tiles
        .asReversed()
        .firstNotNullOfOrNull { tile ->
            val plot = plotByKey[tile.key] ?: return@firstNotNullOfOrNull null
            if (isInsidePlotHitArea(contentOffset, tile.center, layout.tileWidth, layout.tileHeight)) {
                plot
            } else {
                null
            }
        }
}

private fun isInsidePlotHitArea(
    offset: Offset,
    center: Offset,
    tileWidth: Float,
    tileHeight: Float
): Boolean {
    val diamondX = abs(offset.x - center.x) / (tileWidth / 2f)
    val diamondY = abs(offset.y - center.y) / (tileHeight / 2f)
    val insideTile = diamondX + diamondY <= 1f
    val insideCrop = abs(offset.x - center.x) <= tileWidth * 0.30f &&
        offset.y >= center.y - tileHeight * 1.85f &&
        offset.y <= center.y + tileHeight * 0.42f
    return insideTile || insideCrop
}

private fun clampPan(
    pan: Offset,
    canvasSize: IntSize,
    scale: Float
): Offset {
    val maxPanX = canvasSize.width * (scale - 1f) / 2f
    val maxPanY = canvasSize.height * (scale - 1f) / 2f
    return Offset(
        x = pan.x.coerceIn(-maxPanX, maxPanX),
        y = pan.y.coerceIn(-maxPanY, maxPanY)
    )
}

private fun allocateSlots(
    draftCount: Int,
    noteCount: Int,
    matureCount: Int
): FarmSlotAllocation {
    var remaining = MAX_FARM_PLOTS
    var draftSlots = min(draftCount, min(PRIORITY_STAGE_SOFT_LIMIT, remaining))
    remaining -= draftSlots
    var noteSlots = min(noteCount, min(PRIORITY_STAGE_SOFT_LIMIT, remaining))
    remaining -= noteSlots
    var matureSlots = min(matureCount, remaining)
    remaining -= matureSlots

    if (remaining > 0) {
        val extraDraftSlots = min(draftCount - draftSlots, remaining)
        draftSlots += extraDraftSlots
        remaining -= extraDraftSlots
    }
    if (remaining > 0) {
        val extraNoteSlots = min(noteCount - noteSlots, remaining)
        noteSlots += extraNoteSlots
        remaining -= extraNoteSlots
    }
    if (remaining > 0) {
        val extraMatureSlots = min(matureCount - matureSlots, remaining)
        matureSlots += extraMatureSlots
    }

    return FarmSlotAllocation(
        draftSlots = draftSlots,
        noteSlots = noteSlots,
        matureSlots = matureSlots
    )
}

private fun distributeItems(
    items: List<FarmContentItem>,
    slotCount: Int
): List<List<FarmContentItem>> {
    if (items.isEmpty() || slotCount <= 0) return emptyList()
    val buckets = List(min(slotCount, items.size)) { mutableListOf<FarmContentItem>() }
    items.forEachIndexed { index, item ->
        buckets[index % buckets.size] += item
    }
    return buckets
}

private fun farmPlotLevel(items: List<FarmContentItem>): Int {
    val charCount = items.sumOf { it.charCount }
    return when {
        items.size >= 5 || charCount >= 2400 -> 3
        items.size >= 2 || charCount >= 800 -> 2
        else -> 1
    }
}

private fun Note.toFarmContentItem(): FarmContentItem {
    return FarmContentItem(
        ownerType = FarmOwnerType.NOTE,
        ownerId = id,
        stage = FarmStage.SAPLING,
        title = title?.takeIf { it.isNotBlank() } ?: content.toFarmTitle("未命名收纳"),
        preview = content.toFarmPreview(),
        charCount = content.length,
        createdAt = createdAt
    )
}

private fun KnowledgeEntryDraft.toFarmContentItem(): FarmContentItem {
    val body = summary.ifBlank { content }
    return FarmContentItem(
        ownerType = FarmOwnerType.DRAFT,
        ownerId = id,
        stage = FarmStage.PLANT,
        title = title.ifBlank { body.toFarmTitle("未命名草稿") },
        preview = body.toFarmPreview(),
        charCount = content.length,
        createdAt = updatedAt
    )
}

private fun KnowledgeEntry.toFarmContentItem(): FarmContentItem {
    val body = summary.ifBlank { content }
    return FarmContentItem(
        ownerType = FarmOwnerType.KNOWLEDGE_ENTRY,
        ownerId = id,
        stage = FarmStage.MATURE,
        title = userTitle?.takeIf { it.isNotBlank() } ?: body.toFarmTitle("未命名知识"),
        preview = body.toFarmPreview(),
        charCount = content.length,
        createdAt = confirmedAt
    )
}

private fun String.toFarmTitle(fallback: String): String {
    return lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(32)
        ?: fallback
}

private fun String.toFarmPreview(): String {
    return lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(120)
}

private fun PlotKey.distanceFromCenter(): Int {
    return abs(row - FARM_CENTER) + abs(col - FARM_CENTER)
}

private data class PlotKey(
    val row: Int,
    val col: Int
)

private data class FarmLayout(
    val tileWidth: Float,
    val tileHeight: Float,
    val tiles: List<FarmTile>
)

private data class FarmTile(
    val key: PlotKey,
    val center: Offset
)

private data class FarmPlot(
    val key: PlotKey,
    val model: FarmPlotUiModel
)

private data class FarmSlotAllocation(
    val draftSlots: Int,
    val noteSlots: Int,
    val matureSlots: Int
)
