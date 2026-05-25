package com.nazhi.app.feature.farm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nazhi.app.core.model.DailyFarmSnapshot
import kotlin.math.abs
import kotlin.math.min

private const val FARM_SIZE = 5
private const val FARM_CENTER = 2

@Composable
fun DailyFarmPreview(
    snapshot: DailyFarmSnapshot,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
                Text(
                    text = "${snapshot.maturityScore}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            DailyFarmCanvas(
                snapshot = snapshot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FarmMetric(label = "小苗", value = snapshot.saplingCount)
                FarmMetric(label = "植物", value = snapshot.plantCount)
                FarmMetric(label = "成熟", value = snapshot.matureCount)
            }

            Text(
                text = snapshot.summaryText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FarmMetric(label: String, value: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DailyFarmCanvas(
    snapshot: DailyFarmSnapshot,
    modifier: Modifier = Modifier
) {
    val plots = buildFarmPlots(snapshot)
    Canvas(modifier = modifier) {
        val tileWidth = min(size.width / 5.7f, size.height / 3.75f)
        val tileHeight = tileWidth * 0.52f
        val gridHeight = tileHeight * FARM_SIZE
        val originY = ((size.height - gridHeight) / 2f + tileHeight * 0.35f).coerceAtLeast(22f)
        val originX = size.width / 2f
        val tiles = buildFarmTiles(originX, originY, tileWidth, tileHeight)
        val plotByKey = plots.associateBy { it.key }

        drawRoundRect(
            color = Color(0xFFEAF3F0),
            cornerRadius = CornerRadius(tileWidth * 0.22f, tileWidth * 0.22f)
        )

        tiles.forEach { tile ->
            val baseColor = if ((tile.key.row + tile.key.col) % 2 == 0) {
                Color(0xFFDDEDDC)
            } else {
                Color(0xFFD2E4D2)
            }
            drawIsoTile(
                center = tile.center,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                fill = baseColor,
                border = Color(0xFFB4CBB4)
            )
        }

        tiles.forEach { tile ->
            plotByKey[tile.key]?.let { plot ->
                drawFarmCrop(
                    plot = plot,
                    center = tile.center,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    dateSeed = snapshot.dateId
                )
            }
        }
    }
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

private fun buildFarmPlots(snapshot: DailyFarmSnapshot): List<FarmPlot> {
    val centerOut = allPlotKeys().sortedWith(
        compareBy<PlotKey> { it.distanceFromCenter() }
            .thenBy { it.row + it.col }
            .thenBy { it.col }
    )
    val result = mutableListOf<FarmPlot>()
    val available = centerOut.toMutableList()

    fun takePlots(units: Int, stage: FarmStage, order: List<PlotKey>) {
        if (units <= 0 || available.isEmpty()) return
        val keys = order.filter { it in available }
        val cellCount = min(units, keys.size)
        if (cellCount <= 0) return
        val baseLevel = units / cellCount
        val bonusCount = units % cellCount

        keys.take(cellCount).forEachIndexed { index, key ->
            val level = (baseLevel + if (index < bonusCount) 1 else 0).coerceIn(1, 3)
            result += FarmPlot(key = key, stage = stage, level = level)
            available.remove(key)
        }
    }

    takePlots(snapshot.matureCount, FarmStage.MATURE, centerOut)
    takePlots(snapshot.plantCount, FarmStage.PLANT, centerOut)
    val saplingOrder = if (result.isEmpty()) {
        centerOut
    } else {
        centerOut.sortedByDescending { it.distanceFromCenter() }
    }
    takePlots(snapshot.saplingCount, FarmStage.SAPLING, saplingOrder)

    return result
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
    val path = Path().apply {
        moveTo(center.x, center.y - tileHeight / 2f)
        lineTo(center.x + tileWidth / 2f, center.y)
        lineTo(center.x, center.y + tileHeight / 2f)
        lineTo(center.x - tileWidth / 2f, center.y)
        close()
    }
    drawPath(path = path, color = Color(0x22000000))
    drawPath(path = path, color = fill)
    drawPath(
        path = path,
        color = border.copy(alpha = 0.62f),
        style = Stroke(width = (tileWidth * 0.012f).coerceAtLeast(1f))
    )
}

private fun DrawScope.drawFarmCrop(
    plot: FarmPlot,
    center: Offset,
    tileWidth: Float,
    tileHeight: Float,
    dateSeed: String
) {
    val salt = plot.key.row * 31 + plot.key.col * 17 + plot.stage.ordinal * 13
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
    drawLine(
        color = stemColor,
        start = base,
        end = top,
        strokeWidth = (tileWidth * 0.045f).coerceAtLeast(2f),
        cap = StrokeCap.Round
    )
    val leafRadius = tileWidth * (0.065f + level * 0.008f)
    drawCircle(color = leafColor, radius = leafRadius, center = Offset(top.x - leafRadius * 0.72f, top.y + leafRadius * 0.18f))
    drawCircle(color = Color(0xFF83C477), radius = leafRadius, center = Offset(top.x + leafRadius * 0.72f, top.y - leafRadius * 0.08f))
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
    drawLine(
        color = stemColor,
        start = base,
        end = top,
        strokeWidth = (tileWidth * 0.052f).coerceAtLeast(2.5f),
        cap = StrokeCap.Round
    )
    val leafRadius = tileWidth * (0.105f + level * 0.012f)
    drawCircle(color = leafColor, radius = leafRadius, center = Offset(top.x - leafRadius * 0.92f, top.y + leafRadius * 0.86f))
    drawCircle(color = lightLeaf, radius = leafRadius * 0.92f, center = Offset(top.x + leafRadius * 0.90f, top.y + leafRadius * 0.48f))
    drawCircle(color = Color(0xFF2F7D57), radius = leafRadius * 0.88f, center = Offset(top.x, top.y))
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
    drawLine(
        color = trunkColor,
        start = base,
        end = top,
        strokeWidth = (tileWidth * 0.070f).coerceAtLeast(3f),
        cap = StrokeCap.Round
    )
    val radius = tileWidth * (0.145f + level * 0.016f)
    drawCircle(color = darkLeaf, radius = radius, center = Offset(top.x, top.y))
    drawCircle(color = midLeaf, radius = radius * 0.82f, center = Offset(top.x - radius * 0.76f, top.y + radius * 0.25f))
    drawCircle(color = lightLeaf, radius = radius * 0.78f, center = Offset(top.x + radius * 0.74f, top.y + radius * 0.18f))
    drawCircle(color = Color(0xFF2F7D57), radius = radius * 0.72f, center = Offset(top.x, top.y - radius * 0.62f))

    val fruitColor = Color(0xFFE16B6F)
    repeat(level) { index ->
        val angleX = stableNoise(dateSeed, salt + 19 + index) * radius * 0.82f
        val angleY = stableNoise(dateSeed, salt + 29 + index) * radius * 0.50f
        drawCircle(
            color = fruitColor,
            radius = (tileWidth * 0.028f).coerceAtLeast(2f),
            center = Offset(top.x + angleX, top.y + angleY)
        )
    }
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

private fun PlotKey.distanceFromCenter(): Int {
    return abs(row - FARM_CENTER) + abs(col - FARM_CENTER)
}

private enum class FarmStage {
    SAPLING,
    PLANT,
    MATURE
}

private data class PlotKey(
    val row: Int,
    val col: Int
)

private data class FarmTile(
    val key: PlotKey,
    val center: Offset
)

private data class FarmPlot(
    val key: PlotKey,
    val stage: FarmStage,
    val level: Int
)

private fun DailyFarmSnapshot.summaryText(): String {
    return when {
        saplingCount == 0 && plantCount == 0 && matureCount == 0 -> "这一天还没有作物。"
        matureCount > 0 -> "已有 $matureCount 株成熟作物。"
        plantCount > 0 -> "有 $plantCount 株植物等待确认沉淀。"
        saplingCount > 0 -> "有 $saplingCount 株小苗等待 AI 整理。"
        else -> "农场状态已更新。"
    }
}
