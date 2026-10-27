package com.copilotodriverpro.maps

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.models.RegionKind
import com.copilotodriverpro.regions.CityData
import com.copilotodriverpro.regions.Neighborhood
import com.copilotodriverpro.regions.NeighborhoodRegions
import com.copilotodriverpro.regions.StreetData
import com.copilotodriverpro.regions.StreetRepository
import com.copilotodriverpro.ui.components.CopilotoCard
import com.copilotodriverpro.ui.components.SwitchRow
import com.copilotodriverpro.ui.theme.AccentYellow
import com.copilotodriverpro.ui.theme.SignalRed
import com.copilotodriverpro.ui.theme.SignalYellow
import com.copilotodriverpro.ui.theme.TextMuted
import com.copilotodriverpro.utils.Permissions
import com.copilotodriverpro.zone.rememberLocationRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * "Região de atuação": mapa interativo com os bairros/setores REAIS da cidade (OpenStreetMap).
 * Toque num bairro para marcar/desmarcar; "Salvar regiões" grava e o app passa a avisar
 * dentro/fora usando exatamente esses bairros.
 *
 * O mapa é desenhado por [RegionMapCanvas] (Canvas do Compose, sem Google Maps): leve, sem chave de
 * API e sem Google Play Services. Avisos e regiões antigas (círculo/polígono) ficam no botão de ajustes.
 */
private sealed interface MapLoad {
    data object Idle : MapLoad
    data object Loading : MapLoad
    data class Ready(val data: CityData) : MapLoad
    data class Error(val message: String) : MapLoad
}

/** Cidades de atuação com um toque: rótulo, busca no OSM e nome oficial (para marcar as já baixadas). */
private data class CityPreset(val chip: String, val query: String, val name: String)

private val PRESETS = listOf(
    CityPreset("Goiânia", "Goiânia, Goiás", "Goiânia"),
    CityPreset("Aparecida", "Aparecida de Goiânia, Goiás", "Aparecida de Goiânia"),
    CityPreset("Sen. Canedo", "Senador Canedo, Goiás", "Senador Canedo"),
    CityPreset("Anápolis", "Anápolis, Goiás", "Anápolis"),
    CityPreset("Trindade", "Trindade, Goiás", "Trindade")
)
private const val DETAIL_ZOOM = 11f

private val Pill = Color(0xE6070707)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as CopilotApp
    val repo = app.neighborhoodRepository
    val scope = rememberCoroutineScope()
    val saved by app.regionRepository.regions.collectAsState(initial = null)
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val zone by app.zoneMonitor.state.collectAsState()
    val requestLocation = rememberLocationRequester()
    val density = context.resources.displayMetrics.density

    var load by remember { mutableStateOf<MapLoad>(MapLoad.Idle) }
    var loadedCities by remember { mutableStateOf<List<CityData>>(emptyList()) }
    var progress by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Set<String>?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Region?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Ruas (OpenStreetMap): baixam uma vez por cidade, ficam no aparelho e só aparecem se o botão "Ruas" estiver ligado
    val streetRepo = remember { StreetRepository(context.applicationContext) }
    var streetsOn by remember { mutableStateOf(false) }
    var streets by remember { mutableStateOf<Map<Long, StreetData>>(emptyMap()) }
    var streetsDone by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var streetMsg by remember { mutableStateOf<String?>(null) }
    var streetBusy by remember { mutableStateOf(false) }
    var streetJob by remember { mutableStateOf<Job?>(null) }
    var streetsAutoShown by remember { mutableStateOf(false) }

    // Brasil inteiro até uma cidade ser carregada/enquadrada
    val camera = remember { MapCamera(MapMath.worldX(-47.93), MapMath.worldY(-15.78), 4f) }
    val zoomedIn by remember { derivedStateOf { camera.zoom >= DETAIL_ZOOM } }

    // Seleção inicial = o que já está salvo (restaura ao abrir a tela)
    LaunchedEffect(saved != null) {
        val s = saved
        if (s != null && draft == null) draft = NeighborhoodRegions.selectedIds(s)
    }

    // Mantém o GPS/serviço coerentes com as regiões ativas (como antes)
    LaunchedEffect(saved?.count { it.active }, settings?.zoneMonitorEnabled, zone.hasPermission) {
        app.zoneMonitor.refresh()
        app.syncHostService()
    }

    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun errorText(t: Throwable): String =
        if (t is SocketTimeoutException) "O servidor de mapas demorou demais. Toque em Buscar cidade para tentar de novo."
        else if (t is IOException) "Sem conexão para baixar os bairros. Confira a internet e tente de novo."
        else t.message ?: "Não foi possível carregar os bairros."

    fun addCity(d: CityData) {
        loadedCities = loadedCities.filter { it.city.osmId != d.city.osmId } + d
    }

    /** Baixa várias cidades em sequência; se uma falhar, as outras continuam. */
    fun loadCities(presets: List<CityPreset>) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val errors = mutableListOf<String>()
            var last: CityData? = null
            presets.forEachIndexed { i, pr ->
                load = MapLoad.Loading
                progress = "${i + 1}/${presets.size} · ${pr.name}"
                repo.findCity(pr.query)
                    .mapCatching { repo.load(it).getOrThrow() }
                    .onSuccess { addCity(it); last = it }
                    .onFailure { errors += "${pr.name}: ${errorText(it)}" }
            }
            progress = null
            load = if (errors.isEmpty() && last != null) MapLoad.Ready(last!!)
            else MapLoad.Error(errors.joinToString(" "))
        }
    }

    // Reabre as cidades já baixadas (cache do aparelho, sem internet). Usa o cache em memória do
    // repositório: se o app já leu os bairros para analisar uma corrida, não lê/parseia de novo.
    LaunchedEffect(Unit) {
        val cached = repo.cachedCities().filter { c -> PRESETS.any { it.name.equals(c.city.name, true) } }
        if (cached.isNotEmpty()) {
            loadedCities = cached
            load = MapLoad.Ready(cached.last())
        }
    }

    // Bairros exibidos = cidades carregadas + seleção salva de outras cidades
    val loadedItems = remember(loadedCities) { loadedCities.flatMap { it.items } }
    val all: List<Neighborhood> = remember(loadedItems, saved) {
        val ids = loadedItems.map { it.id }.toSet()
        loadedItems + NeighborhoodRegions.fromSaved(saved.orEmpty()).filter { it.id !in ids }
    }
    val selected = draft.orEmpty()
    val savedIds = remember(saved) { NeighborhoodRegions.selectedIds(saved.orEmpty()) }
    val dirty = draft != null && selected != savedIds
    val legacy = remember(saved) { saved.orEmpty().filter { NeighborhoodRegions.neighborhoodIdOf(it.id) == null } }
    val myPosition: LatLang? = if (zone.lat != null && zone.lng != null) LatLang(zone.lat!!, zone.lng!!) else null

    // Projeta e monta os caminhos FORA da thread principal; enquanto isso a cena anterior continua na tela
    val scene by produceState(MapScene.EMPTY, all, legacy, streets) {
        value = withContext(Dispatchers.Default) { MapScene.build(all, legacy, streets.values.toList()) }
    }

    fun toggle(id: String) {
        val cur = draft.orEmpty()
        draft = if (id in cur) cur - id else cur + id
    }

    // Ruas que já estão no aparelho entram sozinhas (sem internet); se houver, o botão "Ruas" já abre ligado
    val cityKeyForStreets = loadedCities.map { it.city.osmId }
    LaunchedEffect(cityKeyForStreets) {
        val add = mutableMapOf<Long, StreetData>()
        val done = mutableSetOf<Long>()
        for (c in loadedCities) {
            if (c.city.osmId in streets) continue
            val r = streetRepo.load(c.city, download = false) ?: continue
            add[c.city.osmId] = r.data
            if (r.missingParts == 0) done += c.city.osmId
        }
        if (add.isNotEmpty()) {
            streets = streets + add
            streetsDone = streetsDone + done
            if (!streetsAutoShown) { streetsOn = true; streetsAutoShown = true }
        }
    }

    /** Liga/desliga as ruas; ao ligar baixa o que ainda falta (só as cidades carregadas). */
    fun toggleStreets() {
        if (streetsOn) { streetsOn = false; return }
        if (loadedCities.isEmpty()) {
            Toast.makeText(context, "Carregue uma cidade primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        streetsOn = true
        val todo = loadedCities.filter { it.city.osmId !in streetsDone }
        if (todo.isEmpty()) return
        streetJob?.cancel()
        streetBusy = true
        streetJob = scope.launch {
            try {
                var incomplete = 0
                todo.forEachIndexed { i, c ->
                    streetMsg = "Baixando ruas (${i + 1}/${todo.size} · ${c.city.name})…"
                    val r = streetRepo.load(c.city, download = true) { done, total ->
                        streetMsg = "Baixando ruas (${i + 1}/${todo.size} · ${c.city.name} · $done/$total)…"
                    }
                    if (r != null) {
                        streets = streets + (c.city.osmId to r.data)
                        if (r.missingParts == 0) streetsDone = streetsDone + c.city.osmId else incomplete++
                    } else incomplete++
                }
                streetMsg = if (incomplete == 0) null
                else "Algumas partes das ruas não baixaram. Desligue e ligue \"Ruas\" para completar."
            } finally {
                streetBusy = false
            }
        }
    }

    // Enquadra a cidade (ou a seleção salva) quando o mapa tem tamanho e há o que mostrar
    val cityKey = loadedCities.map { it.city.osmId }
    LaunchedEffect(canvasSize, cityKey, saved == null) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val src = if (loadedItems.isNotEmpty()) loadedItems else all
        if (src.isEmpty()) return@LaunchedEffect
        camera.animateTo(
            MapMath.fit(
                south = src.minOf { it.bounds.south }, west = src.minOf { it.bounds.west },
                north = src.maxOf { it.bounds.north }, east = src.maxOf { it.bounds.east },
                widthPx = canvasSize.width, heightPx = canvasSize.height,
                density = density, padPx = 48 * density
            )
        )
    }

    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(2_200)
            savedFlash = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color(MAP_BACKGROUND))) {
        RegionMapCanvas(
            scene = scene,
            selected = selected,
            camera = camera,
            myPosition = myPosition,
            showStreets = streetsOn,
            detailZoom = DETAIL_ZOOM,
            onToggle = ::toggle,
            onSize = { canvasSize = it },
            modifier = Modifier.fillMaxSize()
        )

        /* ---- topo: as 5 cidades ---- */
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.78f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val missing = PRESETS.filter { p -> loadedCities.none { it.city.name.equals(p.name, true) } }
                val busy = load == MapLoad.Loading
                Text(
                    if (streetsOn) "✓ Ruas" else "Ruas",
                    color = if (streetsOn) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (streetsOn) AccentYellow else Pill)
                        .clickable(enabled = !streetBusy) { toggleStreets() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    if (missing.isEmpty()) "✓ As 5 cidades" else "Carregar as 5 cidades",
                    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(AccentYellow)
                        .clickable(enabled = !busy && missing.isNotEmpty()) { loadCities(missing) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                PRESETS.forEach { p ->
                    val done = loadedCities.any { it.city.name.equals(p.name, true) }
                    Text(
                        (if (done) "✓ " else "") + p.chip,
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Pill)
                            .clickable(enabled = !busy) { loadCities(listOf(p)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            val hint: Pair<String, Color>? = when (val l = load) {
                MapLoad.Loading -> ("Baixando bairros" + (progress?.let { " ($it)" } ?: "") + " — a 1ª vez leva alguns minutos…") to Color.White
                is MapLoad.Error -> l.message to SignalYellow
                MapLoad.Idle -> if (all.isEmpty()) "Toque em \"Carregar as 5 cidades\" para ver os bairros" to Color.White else null
                is MapLoad.Ready -> if (!zoomedIn && all.isNotEmpty()) "Aproxime o mapa para ver e marcar os bairros (tracejado = cidade inteira)" to Color.White else if (all.any { it.approx || it.gap }) "Tracejado = área sem bairro mapeado (comércio, terminal, escola…); toque para marcar" to TextMuted else null
            }
            if (streetMsg != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Pill).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (streetBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentYellow)
                    Text(streetMsg ?: "", color = if (streetBusy) Color.White else SignalYellow, fontSize = 12.sp)
                    if (streetBusy) {
                        Text(
                            "Cancelar", color = AccentYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                streetJob?.cancel(); streetBusy = false; streetMsg = null; streetsOn = streets.isNotEmpty()
                            }
                        )
                    } else {
                        Text(
                            "OK", color = AccentYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { streetMsg = null }
                        )
                    }
                }
            }
            if (hint != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Pill).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (load == MapLoad.Loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentYellow)
                    Text(hint.first, color = hint.second, fontSize = 12.sp)
                    if (load == MapLoad.Loading) {
                        Text(
                            "Cancelar", color = AccentYellow, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { loadJob?.cancel(); load = MapLoad.Idle }
                        )
                    }
                }
            }
        }

        /* ---- lateral: ajustes e minha localização ---- */
        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(onClick = { sheetOpen = true }) {
                Icon(Icons.Filled.Settings, "Avisos e ajustes")
            }
            SmallFloatingActionButton(onClick = {
                val p = myPosition
                when {
                    !zone.hasPermission -> requestLocation()
                    p == null -> Toast.makeText(context, "Aguardando o GPS…", Toast.LENGTH_SHORT).show()
                    else -> scope.launch {
                        camera.animateTo(CameraTarget(MapMath.worldX(p.lng), MapMath.worldY(p.lat), 14f))
                    }
                }
            }) { Icon(Icons.Filled.MyLocation, "Minha localização") }
        }

        /* ---- base: contador + ações ---- */
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).navigationBarsPadding()
                .clip(RoundedCornerShape(16.dp)).background(Pill).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when {
                    savedFlash -> "✓ Regiões salvas"
                    dirty -> "${selected.size} selecionada(s) · não salvo"
                    else -> "${selected.size} selecionada(s)"
                },
                color = if (savedFlash) AccentYellow else TextMuted,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pad = PaddingValues(horizontal = 4.dp)
                OutlinedButton(
                    onClick = { draft = all.map { it.id }.toSet() },
                    enabled = all.isNotEmpty(),
                    contentPadding = pad,
                    modifier = Modifier.weight(1f)
                ) { Text("Selecionar todas", fontSize = 12.sp, maxLines = 1) }
                OutlinedButton(
                    onClick = { draft = emptySet() },
                    enabled = selected.isNotEmpty(),
                    contentPadding = pad,
                    modifier = Modifier.weight(1f)
                ) { Text("Limpar seleção", fontSize = 12.sp, maxLines = 1) }
                Button(
                    onClick = {
                        val pick = selected
                        scope.launch {
                            val current = app.regionRepository.regions.first()
                            app.regionRepository.saveAll(NeighborhoodRegions.applySelection(current, pick, all))
                            app.zoneMonitor.refresh()
                            app.syncHostService()
                            savedFlash = true
                        }
                    },
                    enabled = dirty,
                    contentPadding = pad,
                    modifier = Modifier.weight(1f)
                ) { Text("Salvar regiões", fontSize = 12.sp, maxLines = 1) }
            }
        }
    }

    /* ------------------- avisos e regiões antigas (fora do mapa) ------------------- */
    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CopilotoCard("AVISO DE REGIÃO DE ATUAÇÃO") {
                    Text(
                        "Só as regiões ATIVAS valem. Quando você sair delas o app avisa " +
                            "\"Fora da região de atuação\"; ao voltar, \"Dentro da região de atuação\".",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!zone.hasPermission) {
                        Text(
                            "Falta a permissão de localização precisa. A posição fica só no aparelho.",
                            color = SignalYellow, style = MaterialTheme.typography.labelSmall
                        )
                        Button(onClick = requestLocation, modifier = Modifier.fillMaxWidth()) {
                            Text("Permitir localização")
                        }
                    } else if (!zone.gpsEnabled) {
                        Text(
                            "A localização do aparelho está desligada.",
                            color = SignalRed, style = MaterialTheme.typography.labelSmall
                        )
                        OutlinedButton(onClick = { Permissions.openLocationSettings(context) }) {
                            Text("Ligar a localização")
                        }
                    }
                    if (!zone.hasGpsHardware) {
                        Text(
                            "Este aparelho não tem GPS (comum em tablet só Wi-Fi): a posição vem de redes " +
                                "e pode errar centenas de metros perto da borda da região.",
                            color = SignalYellow, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (zone.hasPermission && zone.monitoring) {
                        zone.accuracyM?.let {
                            Text(
                                "Precisão do GPS agora: ±%.0f m".format(it),
                                style = MaterialTheme.typography.labelSmall, color = TextMuted
                            )
                        }
                    }
                    SwitchRow(
                        label = "Avisar quando entrar/sair",
                        checked = settings?.zoneMonitorEnabled ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(zoneMonitorEnabled = v) } },
                        description = "Acompanha sua posição pelo GPS enquanto o app/serviço estiver ativo"
                    )
                    SwitchRow(
                        label = "Selo sobre o Uber/99",
                        checked = settings?.zonePillEnabled ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(zonePillEnabled = v) } },
                        description = "Etiqueta verde/vermelha no topo da tela (exige o leitor ativado)"
                    )
                    SwitchRow(
                        label = "Notificação",
                        checked = settings?.zoneNotify ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(zoneNotify = v) } }
                    )
                    SwitchRow(
                        label = "Vibrar",
                        checked = settings?.zoneVibrate ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(zoneVibrate = v) } },
                        description = "Fora = 3 vibrações longas; dentro = 1 curta"
                    )
                    SwitchRow(
                        label = "Falar em voz alta",
                        checked = settings?.zoneVoice ?: false,
                        onCheckedChange = { v -> app.updateSetting { it.copy(zoneVoice = v) } }
                    )
                }

                CopilotoCard("QUANDO UMA CORRIDA APARECER") {
                    Text(
                        "Compara o EMBARQUE e o DESTINO da oferta com as regiões ativas e avisa no card " +
                            "se a corrida te leva para fora.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SwitchRow(
                        label = "Avisar se a corrida sai da região",
                        checked = settings?.rideRegionAlertEnabled ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(rideRegionAlertEnabled = v) } },
                        description = "Faixa vermelha/verde no card da corrida (exige o leitor ativado)"
                    )
                    SwitchRow(
                        label = "Vibrar",
                        checked = settings?.rideRegionVibrate ?: true,
                        onCheckedChange = { v -> app.updateSetting { it.copy(rideRegionVibrate = v) } },
                        description = "2 vibrações quando a corrida sai da região"
                    )
                    SwitchRow(
                        label = "Falar em voz alta",
                        checked = settings?.rideRegionVoice ?: false,
                        onCheckedChange = { v -> app.updateSetting { it.copy(rideRegionVoice = v) } }
                    )
                }

                if (legacy.isNotEmpty()) {
                    Text("Regiões antigas (círculo/polígono)", style = MaterialTheme.typography.titleMedium)
                    legacy.forEach { region ->
                        CopilotoCard(region.name) {
                            Text(
                                if (region.kind == RegionKind.CIRCLE) "Circular · raio %.0f m".format(region.radiusM)
                                else "Polígono · ${region.points.size} vértices",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (region.active) "Ativa" else "Desativada",
                                    color = if (region.active) AccentYellow else TextMuted
                                )
                                Switch(checked = region.active, onCheckedChange = { v ->
                                    scope.launch { app.regionRepository.toggle(region.id, v) }
                                })
                                TextButton(onClick = { deleteTarget = region }) { Text("Excluir") }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { region ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir região?") },
            text = { Text("\"${region.name}\" deixa de valer para o aviso de região.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.regionRepository.delete(region.id) }
                    deleteTarget = null
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
}
