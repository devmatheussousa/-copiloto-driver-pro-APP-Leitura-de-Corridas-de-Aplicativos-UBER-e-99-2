package com.copilotodriverpro.maps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.Neighborhood
import com.copilotodriverpro.regions.StreetData
import com.copilotodriverpro.regions.StreetGeo

/*
 * Mapa de regiões desenhado direto num Canvas do Compose (sem Google Maps, sem chave, sem tiles).
 *
 * Por que é leve:
 *  - cada bairro vira UM Path pronto (construído uma vez, em segundo plano). Mexer a câmera só
 *    troca a matriz de transformação; marcar um bairro só troca a cor. Antes, cada contorno era
 *    um objeto nativo do Google Maps dentro da composição (centenas de nós a recompor);
 *  - só desenha o que está na tela e só o que é grande o bastante para ser visto;
 *  - nomes são medidos uma vez (cache do TextMeasurer) e não se sobrepõem.
 */

/* ------------------------------- câmera ------------------------------- */

@Stable
class MapCamera(initialCx: Double, initialCy: Double, initialZoom: Float) {
    var cx by mutableDoubleStateOf(initialCx)
    var cy by mutableDoubleStateOf(initialCy)
    var zoom by mutableFloatStateOf(initialZoom)

    /** Arrastar + pinça numa tacada só, mantendo o ponto sob os dedos no lugar. */
    fun transformBy(centroid: Offset, pan: Offset, zoomChange: Float, size: IntSize, density: Float) {
        if (size.width == 0 || size.height == 0) return
        val oldPx = MapMath.worldPx(zoom, density)
        val wx = cx + (centroid.x - size.width / 2.0) / oldPx
        val wy = cy + (centroid.y - size.height / 2.0) / oldPx
        val newZoom = if (zoomChange > 0f) {
            (zoom + kotlin.math.log2(zoomChange)).coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM)
        } else zoom
        val newPx = MapMath.worldPx(newZoom, density)
        cx = wx - (centroid.x + pan.x - size.width / 2.0) / newPx
        cy = (wy - (centroid.y + pan.y - size.height / 2.0) / newPx).coerceIn(0.0, 1.0)
        zoom = newZoom
    }

    /** Anima até [target]; uma nova chamada cancela a anterior. */
    suspend fun animateTo(target: CameraTarget) {
        val sx = cx; val sy = cy; val sz = zoom
        anim.snapTo(0f)
        anim.animateTo(1f, tween(350)) {
            cx = sx + (target.cx - sx) * value
            cy = sy + (target.cy - sy) * value
            zoom = sz + (target.zoom - sz) * value
        }
    }

    private val anim = Animatable(0f)
}

/* -------------------------------- cena -------------------------------- */

/** Um bairro/área pronto para desenhar: caminho em "unidades" + dados para toque e nomes. */
class MapShape(
    val id: String,
    val label: String,
    val path: Path,
    val rings: List<FloatArray>,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    val labelX: Float, val labelY: Float,
    val area: Double,
    val remainder: Boolean,
    val dashed: Boolean,
    val labelable: Boolean
) {
    fun contains(x: Float, y: Float): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        for (r in rings) if (MapMath.ringContains(r, x, y)) return true
        return false
    }
}

/** Região antiga (círculo/polígono livre): só é desenhada, não é selecionável. */
class LegacyShape(
    val active: Boolean,
    val circle: Boolean,
    val path: Path?,
    val cx: Float, val cy: Float, val radius: Float
)

/** Um quadradinho de ruas (~1,2 km): um Path só, com caixa para só desenhar quando aparece na tela. */
class StreetCell(val path: Path, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/** Todas as ruas de um tipo ([cls] 0 = rodovia … 4 = ruas), divididas em quadradinhos. */
class StreetLayer(val cls: Int, val cells: List<StreetCell>)

class MapScene(
    val originX: Double,
    val originY: Double,
    val shapes: List<MapShape>,
    /** Quem pode ganhar nome, do maior para o menor (os maiores têm prioridade). */
    val labelOrder: List<MapShape>,
    val legacy: List<LegacyShape>,
    /** Ruas, da menor para a maior (a maior fica desenhada por cima). */
    val streets: List<StreetLayer> = emptyList()
) {
    /** Qual bairro está sob o toque (o MENOR se houver sobreposição); "Demais áreas" por último. */
    fun hit(ux: Float, uy: Float, detail: Boolean, selected: Set<String>): String? {
        var best: MapShape? = null
        for (sh in shapes) {
            if (sh.remainder) continue
            if (!detail && sh.id !in selected) continue
            if (sh.contains(ux, uy) && (best == null || sh.area < best.area)) best = sh
        }
        if (best != null) return best.id
        for (sh in shapes) if (sh.remainder && sh.contains(ux, uy)) return sh.id
        return null
    }

    companion object {
        val EMPTY = MapScene(0.5, 0.5, emptyList(), emptyList(), emptyList())

        /** Pesado (projeta e monta os caminhos): chamar fora da thread principal. */
        fun build(items: List<Neighborhood>, legacyRegions: List<Region>, streetData: List<StreetData> = emptyList()): MapScene {
            if (items.isEmpty() && legacyRegions.isEmpty()) return EMPTY

            var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
            fun extend(lat: Double, lng: Double) {
                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                if (lng < minLng) minLng = lng; if (lng > maxLng) maxLng = lng
            }
            items.forEach { extend(it.bounds.south, it.bounds.west); extend(it.bounds.north, it.bounds.east) }
            legacyRegions.forEach { r ->
                when (r.kind) {
                    RegionKind.CIRCLE -> r.center?.let { extend(it.lat, it.lng) }
                    RegionKind.POLYGON -> r.points.forEach { extend(it.lat, it.lng) }
                }
            }
            val ox = MapMath.worldX((minLng + maxLng) / 2.0)
            val oy = MapMath.worldY((minLat + maxLat) / 2.0)

            fun ux(lng: Double) = ((MapMath.worldX(lng) - ox) * MapMath.UNIT_SCALE).toFloat()
            fun uy(lat: Double) = ((MapMath.worldY(lat) - oy) * MapMath.UNIT_SCALE).toFloat()
            fun ringOf(pts: List<LatLang>): FloatArray {
                val a = FloatArray(pts.size * 2)
                for (i in pts.indices) { a[2 * i] = ux(pts[i].lng); a[2 * i + 1] = uy(pts[i].lat) }
                return a
            }
            fun pathOf(rings: List<FloatArray>): Path {
                val p = Path()
                for (r in rings) {
                    if (r.size < 6) continue
                    p.moveTo(r[0], r[1])
                    var i = 2
                    while (i < r.size) { p.lineTo(r[i], r[i + 1]); i += 2 }
                    p.close()
                }
                return p
            }

            val shapes = ArrayList<MapShape>(items.size)
            for (n in items) {
                val rings = n.rings.map { ringOf(it) }.filter { it.size >= 6 }
                if (rings.isEmpty()) continue
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (r in rings) {
                    var i = 0
                    while (i < r.size) {
                        if (r[i] < x0) x0 = r[i]; if (r[i] > x1) x1 = r[i]
                        if (r[i + 1] < y0) y0 = r[i + 1]; if (r[i + 1] > y1) y1 = r[i + 1]
                        i += 2
                    }
                }
                shapes += MapShape(
                    id = n.id,
                    label = if (n.name.length > 22) n.name.take(21) + "…" else n.name,
                    path = pathOf(rings),
                    rings = rings,
                    minX = x0, minY = y0, maxX = x1, maxY = y1,
                    labelX = ux(n.center.lng), labelY = uy(n.center.lat),
                    area = rings.sumOf { MapMath.ringArea(it) },
                    remainder = n.remainder,
                    dashed = n.remainder || n.approx || n.gap,
                    labelable = !n.remainder && !n.gap
                )
            }

            val legacy = legacyRegions.mapNotNull { r ->
                when (r.kind) {
                    RegionKind.CIRCLE -> r.center?.let { c ->
                        LegacyShape(
                            r.active, true, null, ux(c.lng), uy(c.lat),
                            MapMath.metersToUnits(r.radiusM, c.lat).toFloat()
                        )
                    }
                    RegionKind.POLYGON ->
                        if (r.points.size >= 3) LegacyShape(r.active, false, pathOf(listOf(ringOf(r.points))), 0f, 0f, 0f)
                        else null
                }
            }

            return MapScene(
                ox, oy, shapes, shapes.filter { it.labelable }.sortedByDescending { it.area }, legacy,
                streetsFor(streetData, ox, oy, ::ux, ::uy)
            )
        }

        /** Ruas já montadas para esta origem e estes dados: salvar regiões não pode refazer todas as ruas. */
        private class StreetCache(
            val ox: Double, val oy: Double,
            val key: List<StreetData>, val layers: List<StreetLayer>
        )

        // SoftReference: o Android pode soltar esse cache se faltar memória.
        @Volatile private var streetCache: java.lang.ref.SoftReference<StreetCache>? = null

        private fun streetsFor(
            data: List<StreetData>, ox: Double, oy: Double,
            ux: (Double) -> Float, uy: (Double) -> Float
        ): List<StreetLayer> {
            if (data.isEmpty()) return emptyList()
            val c = streetCache?.get()
            if (c != null && c.ox == ox && c.oy == oy && c.key.size == data.size) {
                var same = true
                for (i in data.indices) if (c.key[i] !== data[i]) { same = false; break }
                if (same) return c.layers
            }
            val layers = buildStreets(data, ux, uy)
            streetCache = java.lang.ref.SoftReference(StreetCache(ox, oy, ArrayList(data), layers))
            return layers
        }

        private const val STREET_CELL = 8192f // unidades ≈ quadrado de ~1,2 km

        /**
         * Junta as ruas por tipo e por quadradinho. Cada quadradinho é UM Path (nada de um Path por rua):
         * desenhar é só pular os que estão fora da tela. Pesado — chamar fora da thread principal.
         */
        private fun buildStreets(
            data: List<StreetData>,
            ux: (Double) -> Float,
            uy: (Double) -> Float
        ): List<StreetLayer> {
            if (data.isEmpty()) return emptyList()
            class Cell {
                val path = Path()
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            }
            val byCls = Array(StreetGeo.CLASSES) { HashMap<Long, Cell>() }
            for (sd in data) for (ln in sd.lines) {
                val p = ln.pts
                val n = p.size / 2
                if (n < 2) continue
                val fx = ux(p[1].toDouble())
                val fy = uy(p[0].toDouble())
                val key = (Math.floor((fx / STREET_CELL).toDouble()).toLong() shl 32) or
                    (Math.floor((fy / STREET_CELL).toDouble()).toLong() and 0xFFFFFFFFL)
                val cell = byCls[ln.cls].getOrPut(key) { Cell() }
                cell.path.moveTo(fx, fy)
                if (fx < cell.x0) cell.x0 = fx; if (fx > cell.x1) cell.x1 = fx
                if (fy < cell.y0) cell.y0 = fy; if (fy > cell.y1) cell.y1 = fy
                for (i in 1 until n) {
                    val x = ux(p[2 * i + 1].toDouble())
                    val y = uy(p[2 * i].toDouble())
                    cell.path.lineTo(x, y)
                    if (x < cell.x0) cell.x0 = x; if (x > cell.x1) cell.x1 = x
                    if (y < cell.y0) cell.y0 = y; if (y > cell.y1) cell.y1 = y
                }
            }
            // da menor (4) para a maior (0): a maior é desenhada por último, por cima
            return (StreetGeo.CLASSES - 1 downTo 0).map { c ->
                StreetLayer(c, byCls[c].values.map { StreetCell(it.path, it.x0, it.y0, it.x1, it.y1) })
            }
        }
    }
}

/* ------------------------------- desenho ------------------------------- */

private val SelFill = Color(0x66FECA07)
private val SelStroke = Color(0xFFFECA07)
private val IdleFill = Color(0x2690A4AE)
private val IdleStroke = Color(0xAA90A4AE)
private val RemainderFill = Color(0x0F90A4AE)
private val RemainderSelFill = Color(0x26FECA07)
private val LabelIdle = Color(0xFFFFFFFF)
private val LabelSel = Color(0xFFFFFFFF)
private val LabelHalo = Color(0xFF000000)
private val MeFill = Color(0xFF2979FF)

const val MAP_BACKGROUND = 0xFF14181C
private const val LABEL_ZOOM = 11.5f

// Ruas: a partir de qual zoom cada tipo aparece (as pequenas só de bem perto), espessura em dp e cor.
private val STREET_MIN_ZOOM = floatArrayOf(9f, 10f, 11.5f, 12.5f, 14f)
private val STREET_WIDTH_DP = floatArrayOf(3f, 2.6f, 2f, 1.5f, 1f)
private val STREET_COLOR = arrayOf(
    Color(0xF0FFFFFF), Color(0xE6FFFFFF), Color(0xCCE0E0E0), Color(0xAACFD8DC), Color(0x80CFD8DC)
)
private const val MAX_LABELS = 60

/**
 * Desenha a cena e trata os gestos (arrastar, pinça, toque). [detailZoom]: abaixo dele só a cidade
 * inteira e o que já está marcado aparecem (como antes); a partir dele, todos os bairros visíveis.
 */
@Composable
fun RegionMapCanvas(
    scene: MapScene,
    selected: Set<String>,
    camera: MapCamera,
    myPosition: LatLang?,
    showStreets: Boolean,
    detailZoom: Float,
    onToggle: (String) -> Unit,
    onSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val sceneNow by rememberUpdatedState(scene)
    val selectedNow by rememberUpdatedState(selected)
    val toggleNow by rememberUpdatedState(onToggle)
    val textMeasurer = rememberTextMeasurer(cacheSize = 128)
    val idleStyle = remember { TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LabelIdle) }
    val selStyle = remember { TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LabelSel) }

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it; onSize(it) }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val s = MapMath.unitToPx(camera.zoom, density)
                    val cuX = ((camera.cx - sceneNow.originX) * MapMath.UNIT_SCALE).toFloat()
                    val cuY = ((camera.cy - sceneNow.originY) * MapMath.UNIT_SCALE).toFloat()
                    val ux = cuX + (tap.x - canvasSize.width / 2f) / s
                    val uy = cuY + (tap.y - canvasSize.height / 2f) / s
                    sceneNow.hit(ux, uy, camera.zoom >= detailZoom, selectedNow)?.let { toggleNow(it) }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.transformBy(centroid, pan, zoom, canvasSize, density)
                }
            }
    ) {
        val w = this.size.width
        val h = this.size.height
        val s = MapMath.unitToPx(camera.zoom, density)
        val cuX = ((camera.cx - scene.originX) * MapMath.UNIT_SCALE).toFloat()
        val cuY = ((camera.cy - scene.originY) * MapMath.UNIT_SCALE).toFloat()
        val vMinX = cuX - w / 2f / s; val vMaxX = cuX + w / 2f / s
        val vMinY = cuY - h / 2f / s; val vMaxY = cuY + h / 2f / s
        val detail = camera.zoom >= detailZoom

        val dash = PathEffect.dashPathEffect(floatArrayOf(14f * density / s, 8f * density / s))
        val thin = Stroke(width = 1.5f * density / s)
        val thinDash = Stroke(width = 1.5f * density / s, pathEffect = dash)
        val thick = Stroke(width = 3f * density / s)
        val thickDash = Stroke(width = 3f * density / s, pathEffect = dash)
        val minPx = 1.5f

        fun visible(sh: MapShape) =
            sh.maxX >= vMinX && sh.minX <= vMaxX && sh.maxY >= vMinY && sh.minY <= vMaxY &&
                ((sh.maxX - sh.minX) * s >= minPx || (sh.maxY - sh.minY) * s >= minPx)

        withTransform({
            translate(w / 2f, h / 2f)
            scale(s, s, Offset.Zero)
            translate(-cuX, -cuY)
        }) {
            // 1) Município inteiro ("Demais áreas") por baixo
            for (sh in scene.shapes) {
                if (!sh.remainder || !visible(sh)) continue
                val sel = sh.id in selected
                drawPath(sh.path, if (sel) RemainderSelFill else RemainderFill)
                drawPath(sh.path, if (sel) SelStroke else IdleStroke, style = if (sel) thickDash else thinDash)
            }
            // 2) Regiões antigas (círculo/polígono livre)
            for (lg in scene.legacy) {
                val fill = if (lg.active) Color(0x22FECA07) else Color(0x11888888)
                val line = if (lg.active) Color(0xAAFECA07) else Color(0x88888888)
                if (lg.circle) {
                    drawCircle(fill, lg.radius, Offset(lg.cx, lg.cy))
                    drawCircle(line, lg.radius, Offset(lg.cx, lg.cy), style = thin)
                } else lg.path?.let { drawPath(it, fill); drawPath(it, line, style = thin) }
            }
            // 3) Bairros parados (só de perto)
            if (detail) {
                for (sh in scene.shapes) {
                    if (sh.remainder || sh.id in selected || !visible(sh)) continue
                    drawPath(sh.path, IdleFill)
                    drawPath(sh.path, IdleStroke, style = if (sh.dashed) thinDash else thin)
                }
            }
            // 4) Marcados por cima (aparecem de qualquer distância)
            for (sh in scene.shapes) {
                if (sh.remainder || sh.id !in selected || !visible(sh)) continue
                drawPath(sh.path, SelFill)
                drawPath(sh.path, SelStroke, style = if (sh.dashed) thickDash else thick)
            }
            // 5) Ruas por cima (só os quadradinhos visíveis e só os tipos que cabem neste zoom)
            if (showStreets) {
                for (layer in scene.streets) {
                    if (camera.zoom < STREET_MIN_ZOOM[layer.cls]) continue
                    val st = Stroke(
                        width = STREET_WIDTH_DP[layer.cls] * density / s,
                        cap = if (layer.cls >= 3) StrokeCap.Butt else StrokeCap.Round,
                        join = if (layer.cls >= 3) StrokeJoin.Bevel else StrokeJoin.Round
                    )
                    val color = STREET_COLOR[layer.cls]
                    for (cell in layer.cells) {
                        if (cell.maxX < vMinX || cell.minX > vMaxX || cell.maxY < vMinY || cell.minY > vMaxY) continue
                        drawPath(cell.path, color, style = st)
                    }
                }
            }
        }

        // Nomes (em pixels de tela, sem escalar), sem sobreposição
        if (camera.zoom >= LABEL_ZOOM) {
            val placed = ArrayList<Rect>(MAX_LABELS)
            for (sh in scene.labelOrder) {
                if (placed.size >= MAX_LABELS) break
                if (sh.labelX < vMinX || sh.labelX > vMaxX || sh.labelY < vMinY || sh.labelY > vMaxY) continue
                val need = (kotlin.math.min(sh.label.length, 22) * 6.8f + 10f) * density
                if ((sh.maxX - sh.minX) * s < need) continue
                val sel = sh.id in selected
                val layout = textMeasurer.measure(sh.label, if (sel) selStyle else idleStyle)
                val sx = (sh.labelX - cuX) * s + w / 2f
                val sy = (sh.labelY - cuY) * s + h / 2f
                val rect = Rect(
                    sx - layout.size.width / 2f, sy - layout.size.height / 2f,
                    sx + layout.size.width / 2f, sy + layout.size.height / 2f
                )
                var clash = false
                for (p in placed) if (p.overlaps(rect)) { clash = true; break }
                if (clash) continue
                placed += rect
                drawText(
                    layout, color = LabelHalo, topLeft = rect.topLeft,
                    drawStyle = Stroke(width = 2.5f * density, join = StrokeJoin.Round)
                )
                drawText(layout, topLeft = rect.topLeft)
            }
        }

        // Você
        myPosition?.let { p ->
            val ux = ((MapMath.worldX(p.lng) - scene.originX) * MapMath.UNIT_SCALE).toFloat()
            val uy = ((MapMath.worldY(p.lat) - scene.originY) * MapMath.UNIT_SCALE).toFloat()
            val c = Offset((ux - cuX) * s + w / 2f, (uy - cuY) * s + h / 2f)
            drawCircle(Color.White, 9f * density, c)
            drawCircle(MeFill, 6.5f * density, c)
        }
    }
}
