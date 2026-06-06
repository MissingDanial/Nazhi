package com.nazhi.app.feature.farm

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nazhi.app.R
import com.nazhi.app.core.model.DailyFarmSnapshot
import com.nazhi.app.core.model.KnowledgeEntry
import com.nazhi.app.core.model.KnowledgeEntryDraft
import com.nazhi.app.core.model.Note
import com.nazhi.app.core.ui.NazhiStatusKind
import com.nazhi.app.core.ui.PixelStatusIcon
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val FARM_SIZE = 5
private const val FARM_CENTER = 2
private const val MAX_FARM_PLOTS = FARM_SIZE * FARM_SIZE
private const val PRIORITY_STAGE_SOFT_LIMIT = 8
private const val FARM_MIN_SCALE = 1f
private const val FARM_MAX_SCALE = 2.2f
private const val CROP_VARIANT_COUNT = 3
private const val CROP_SPRITE_ANCHOR_X = 64f / 128f
private const val CROP_SPRITE_ANCHOR_Y = 104f / 128f
private const val FIELD_ASSET_WIDTH = 832f
private const val FIELD_ASSET_HEIGHT = 470f
private const val FIELD_GRID_X = 206f
private const val FIELD_GRID_Y = 25f
private const val FIELD_GRID_SIZE = 420f
private const val PLOT_PLANT_ANCHOR_Y = 76f / 128f

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

private data class CropSpriteSet(
    val sapling: List<ImageBitmap>,
    val plant: List<ImageBitmap>,
    val mature: List<ImageBitmap>
) {
    fun imageFor(stage: FarmStage, variantIndex: Int): ImageBitmap {
        val variants = when (stage) {
            FarmStage.SAPLING -> sapling
            FarmStage.PLANT -> plant
            FarmStage.MATURE -> mature
        }
        return variants[Math.floorMod(variantIndex, variants.size)]
    }
}

private data class FarmSurfaceSprites(
    val fieldBackground: ImageBitmap,
    val soilTile: ImageBitmap,
    val selectedOverlay: ImageBitmap
)

@Composable
fun DailyFarmPreview(
    snapshot: DailyFarmSnapshot,
    modifier: Modifier = Modifier,
    plots: List<FarmPlotUiModel> = emptyList(),
    selectedPlotId: String? = null,
    onPlotClick: (FarmPlotUiModel) -> Unit = {}
) {
    val cropSprites = rememberCropSprites()
    val surfaceSprites = rememberFarmSurfaceSprites()
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
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 36.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "知识农场",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FarmMetric(
                        label = "待整理",
                        kind = NazhiStatusKind.PENDING,
                        modifier = Modifier.weight(1f)
                    )
                    FarmMetric(
                        label = "待确认",
                        kind = NazhiStatusKind.DRAFT,
                        modifier = Modifier.weight(1f)
                    )
                    FarmMetric(
                        label = "已经沉淀",
                        kind = NazhiStatusKind.SETTLED,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            DailyFarmCanvas(
                snapshot = snapshot,
                plots = plots,
                surfaceSprites = surfaceSprites,
                cropSprites = cropSprites,
                selectedPlotId = selectedPlotId,
                onPlotClick = onPlotClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
            )
        }
    }
}

@Composable
private fun rememberFarmSurfaceSprites(): FarmSurfaceSprites {
    val resources = LocalContext.current.resources
    return remember(resources) {
        FarmSurfaceSprites(
            fieldBackground = loadFarmImage(resources, R.drawable.farm_field_bg),
            soilTile = loadFarmImage(resources, R.drawable.plot_soil_empty),
            selectedOverlay = loadFarmImage(resources, R.drawable.plot_soil_selected)
        )
    }
}

@Composable
private fun rememberCropSprites(): CropSpriteSet {
    val resources = LocalContext.current.resources
    return remember(resources) {
        CropSpriteSet(
            sapling = listOf(
                loadFarmImage(resources, R.drawable.crop_leaf_sapling),
                loadFarmImage(resources, R.drawable.crop_wheat_sapling),
                loadFarmImage(resources, R.drawable.crop_berry_sapling)
            ),
            plant = listOf(
                loadFarmImage(resources, R.drawable.crop_leaf_plant),
                loadFarmImage(resources, R.drawable.crop_wheat_plant),
                loadFarmImage(resources, R.drawable.crop_berry_plant)
            ),
            mature = listOf(
                loadFarmImage(resources, R.drawable.crop_leaf_mature),
                loadFarmImage(resources, R.drawable.crop_wheat_mature),
                loadFarmImage(resources, R.drawable.crop_berry_mature)
            )
        )
    }
}

private fun loadFarmImage(
    resources: android.content.res.Resources,
    drawableId: Int
): ImageBitmap {
    return BitmapFactory.decodeResource(resources, drawableId).asImageBitmap()
}

@Composable
private fun FarmMetric(
    label: String,
    kind: NazhiStatusKind,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PixelStatusIcon(kind = kind, size = 20.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun DailyFarmCanvas(
    snapshot: DailyFarmSnapshot,
    plots: List<FarmPlotUiModel>,
    surfaceSprites: FarmSurfaceSprites,
    cropSprites: CropSpriteSet,
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
            // Keep the viewport fixed; scale and pan the whole farm scene inside it.
            clipFarmInteraction(layout) {
                withFarmSceneTransform(
                    layout = layout,
                    pan = pan,
                    zoomScale = zoomScale
                ) {
                    drawFarmFieldBackground(
                        image = surfaceSprites.fieldBackground,
                        layout = layout
                    )

                    layout.tiles.forEach { tile ->
                        drawFarmImage(
                            image = surfaceSprites.soilTile,
                            topLeft = tile.topLeft,
                            size = Size(layout.tileWidth, layout.tileHeight)
                        )
                    }

                    layout.tiles.forEach { tile ->
                        plotByKey[tile.key]?.let { plot ->
                            drawFarmCrop(
                                plot = plot,
                                center = tile.center,
                                tileWidth = layout.tileWidth,
                                tileHeight = layout.tileHeight,
                                dateSeed = snapshot.dateId,
                                cropSprites = cropSprites
                            )
                        }
                    }

                    if (selectedPlotKey != null) {
                        layout.tiles.firstOrNull { it.key == selectedPlotKey }?.let { tile ->
                            drawFarmImage(
                                image = surfaceSprites.selectedOverlay,
                                topLeft = tile.topLeft,
                                size = Size(layout.tileWidth, layout.tileHeight)
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
    val fieldScale = min(width / FIELD_ASSET_WIDTH, height / FIELD_ASSET_HEIGHT)
    val fieldWidth = FIELD_ASSET_WIDTH * fieldScale
    val fieldHeight = FIELD_ASSET_HEIGHT * fieldScale
    val fieldTopLeft = Offset(
        x = (width - fieldWidth) / 2f,
        y = 0f
    )
    val gridTopLeft = Offset(
        x = fieldTopLeft.x + FIELD_GRID_X * fieldScale,
        y = fieldTopLeft.y + FIELD_GRID_Y * fieldScale
    )
    val gridSize = FIELD_GRID_SIZE * fieldScale
    val tileSize = gridSize / FARM_SIZE
    return FarmLayout(
        tileWidth = tileSize,
        tileHeight = tileSize,
        fieldTopLeft = fieldTopLeft,
        fieldSize = Size(fieldWidth, fieldHeight),
        interactionTopLeft = fieldTopLeft,
        interactionSize = Size(fieldWidth, fieldHeight),
        interactionCenter = Offset(
            x = fieldTopLeft.x + fieldWidth / 2f,
            y = fieldTopLeft.y + fieldHeight / 2f
        ),
        tiles = buildFarmTiles(gridTopLeft, tileSize)
    )
}

private fun buildFarmTiles(
    gridTopLeft: Offset,
    tileSize: Float
): List<FarmTile> {
    return buildList {
        for (row in 0 until FARM_SIZE) {
            for (col in 0 until FARM_SIZE) {
                val topLeft = Offset(
                    x = gridTopLeft.x + col * tileSize,
                    y = gridTopLeft.y + row * tileSize
                )
                add(
                    FarmTile(
                        key = PlotKey(row, col),
                        topLeft = topLeft,
                        center = Offset(topLeft.x + tileSize / 2f, topLeft.y + tileSize / 2f)
                    )
                )
            }
        }
    }.sortedWith(compareBy<FarmTile> { it.key.row }.thenBy { it.key.col })
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

private fun DrawScope.drawFarmFieldBackground(
    image: ImageBitmap,
    layout: FarmLayout
) {
    drawFarmImage(
        image = image,
        topLeft = layout.fieldTopLeft,
        size = layout.fieldSize
    )
}

private fun DrawScope.clipFarmInteraction(
    layout: FarmLayout,
    block: DrawScope.() -> Unit
) {
    clipRect(
        left = layout.interactionTopLeft.x,
        top = layout.interactionTopLeft.y,
        right = layout.interactionTopLeft.x + layout.interactionSize.width,
        bottom = layout.interactionTopLeft.y + layout.interactionSize.height
    ) {
        block()
    }
}

private fun DrawScope.withFarmSceneTransform(
    layout: FarmLayout,
    pan: Offset,
    zoomScale: Float,
    block: DrawScope.() -> Unit
) {
    withTransform({
        translate(left = pan.x, top = pan.y)
        scale(
            scaleX = zoomScale,
            scaleY = zoomScale,
            pivot = layout.interactionCenter
        )
    }) {
        block()
    }
}

private fun DrawScope.drawFarmImage(
    image: ImageBitmap,
    topLeft: Offset,
    size: Size
) {
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1)
        ),
        filterQuality = FilterQuality.None
    )
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
    dateSeed: String,
    cropSprites: CropSpriteSet
) {
    val salt = plot.row * 31 + plot.col * 17 + plot.stage.ordinal * 13
    val jitterX = stableNoise(dateSeed, salt) * tileWidth * 0.04f
    val jitterY = stableNoise(dateSeed, salt + 7) * tileHeight * 0.04f
    val tileTop = center.y - tileHeight / 2f
    val base = Offset(
        x = center.x + jitterX,
        y = tileTop + tileHeight * PLOT_PLANT_ANCHOR_Y + jitterY
    )

    drawOval(
        color = Color(0x22000000),
        topLeft = Offset(base.x - tileWidth * 0.16f, base.y - tileHeight * 0.10f),
        size = Size(tileWidth * 0.32f, tileHeight * 0.18f)
    )

    val variantIndex = stableIndex(plot.plotId, CROP_VARIANT_COUNT)
    val sprite = cropSprites.imageFor(plot.stage, variantIndex)
    drawCropSprite(
        image = sprite,
        stage = plot.stage,
        level = plot.level,
        base = base,
        tileWidth = tileWidth
    )
}

private fun DrawScope.drawCropSprite(
    image: ImageBitmap,
    stage: FarmStage,
    level: Int,
    base: Offset,
    tileWidth: Float
) {
    val stageScale = when (stage) {
        FarmStage.SAPLING -> 0.76f
        FarmStage.PLANT -> 0.94f
        FarmStage.MATURE -> 1.08f
    }
    val levelScale = 1f + (level - 1).coerceAtLeast(0) * 0.06f
    val spriteSize = (tileWidth * stageScale * levelScale).roundToInt().coerceAtLeast(18)
    val left = (base.x - spriteSize * CROP_SPRITE_ANCHOR_X).roundToInt()
    val top = (base.y - spriteSize * CROP_SPRITE_ANCHOR_Y).roundToInt()
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(spriteSize, spriteSize),
        filterQuality = FilterQuality.None
    )
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

private fun stableIndex(seed: String, modulo: Int): Int {
    if (modulo <= 1) return 0
    var hash = 0x811C9DC5.toInt()
    seed.forEach { char ->
        hash = hash xor char.code
        hash *= 16777619
    }
    return Math.floorMod(hash, modulo)
}

private fun hitTestFarmPlot(
    tapOffset: Offset,
    canvasSize: IntSize,
    scale: Float,
    pan: Offset,
    plots: List<FarmPlotUiModel>
): FarmPlotUiModel? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || plots.isEmpty()) return null
    val layout = buildFarmLayout(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    if (!layout.isInsideInteraction(tapOffset)) return null
    val contentOffset = screenToFarmContent(
        offset = tapOffset,
        layout = layout,
        scale = scale,
        pan = pan
    )
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
    val insideTile = abs(offset.x - center.x) <= tileWidth / 2f &&
        abs(offset.y - center.y) <= tileHeight / 2f
    val insideCrop = abs(offset.x - center.x) <= tileWidth * 0.38f &&
        offset.y >= center.y - tileHeight * 1.05f &&
        offset.y <= center.y + tileHeight * 0.12f
    return insideTile || insideCrop
}

private fun screenToFarmContent(
    offset: Offset,
    layout: FarmLayout,
    scale: Float,
    pan: Offset
): Offset {
    val pivot = layout.interactionCenter
    return Offset(
        x = pivot.x + (offset.x - pan.x - pivot.x) / scale,
        y = pivot.y + (offset.y - pan.y - pivot.y) / scale
    )
}

private fun FarmLayout.isInsideInteraction(offset: Offset): Boolean {
    return offset.x >= interactionTopLeft.x &&
        offset.x <= interactionTopLeft.x + interactionSize.width &&
        offset.y >= interactionTopLeft.y &&
        offset.y <= interactionTopLeft.y + interactionSize.height
}

private fun clampPan(
    pan: Offset,
    canvasSize: IntSize,
    scale: Float
): Offset {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || scale <= FARM_MIN_SCALE) {
        return Offset.Zero
    }
    val layout = buildFarmLayout(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    val maxPanX = layout.interactionSize.width * (scale - 1f) / 2f
    val maxPanY = layout.interactionSize.height * (scale - 1f) / 2f
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
    val fieldTopLeft: Offset,
    val fieldSize: Size,
    val interactionTopLeft: Offset,
    val interactionSize: Size,
    val interactionCenter: Offset,
    val tiles: List<FarmTile>
)

private data class FarmTile(
    val key: PlotKey,
    val topLeft: Offset,
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
