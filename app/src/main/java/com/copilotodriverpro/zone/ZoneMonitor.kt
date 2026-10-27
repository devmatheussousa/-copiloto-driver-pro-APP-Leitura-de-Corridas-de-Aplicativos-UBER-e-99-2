package com.copilotodriverpro.zone

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.copilotodriverpro.CopilotApp
import com.copilotodriverpro.models.LatLang
import com.copilotodriverpro.models.Region
import com.copilotodriverpro.regions.ZoneChange
import com.copilotodriverpro.regions.ZoneStatus
import com.copilotodriverpro.regions.ZoneTracker
import com.copilotodriverpro.settings.DriverSettings
import com.copilotodriverpro.utils.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** O que a UI mostra sobre a posição do motorista em relação às regiões de atuação. */
data class ZoneUiState(
    val status: ZoneStatus = ZoneStatus.UNKNOWN,
    val regionName: String? = null,
    /** true = o GPS está sendo lido agora (permissão + recurso ligado + app/serviço ativo). */
    val monitoring: Boolean = false,
    val hasPermission: Boolean = false,
    val gpsEnabled: Boolean = true,
    val hasGpsHardware: Boolean = true,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Float? = null
)

/** Mudança confirmada para mostrar o aviso grande (DENTRO ou FORA). */
data class ZoneEvent(val to: ZoneStatus, val regionName: String?, val initial: Boolean)

/**
 * Acompanha a posição do motorista (LocationManager — funciona até em aparelho sem Google Play
 * Services) e diz se ele está DENTRO ou FORA das regiões ativas.
 *
 * Quem precisa da posição "pega" o monitor com [acquire] e devolve com [release]; o GPS só fica
 * ligado enquanto houver alguém usando (tela aberta ou serviço em primeiro plano) — economiza bateria.
 * Tudo local: nenhuma posição sai do aparelho.
 */
class ZoneMonitor(private val app: CopilotApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val tracker = ZoneTracker(confirmations = 2, bufferM = 30.0)
    private val alerter = ZoneAlerter(app)

    private val _state = MutableStateFlow(ZoneUiState(hasGpsHardware = Permissions.hasGpsHardware(app)))
    val state: StateFlow<ZoneUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ZoneEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ZoneEvent> = _events.asSharedFlow()

    private val owners = LinkedHashSet<String>()
    private var regions: List<Region> = emptyList()
    private var settings: DriverSettings = DriverSettings()
    private var lastLoc: Location? = null
    private var lastFixAt = 0L
    private var listening = false
    private var staleJob: Job? = null

    init {
        scope.launch {
            app.regionRepository.regions.collect {
                regions = it
                evaluate()
            }
        }
        scope.launch {
            app.settingsRepository.settings.collect {
                settings = it
                refresh()
            }
        }
    }

    /** Chamado pelo card quando a corrida atual sai da região (vibra/fala conforme as opções). */
    fun alertRideOutside(s: DriverSettings) {
        alerter.alertRideOutside(s)
    }

    fun acquire(owner: String) {
        owners.add(owner)
        refresh()
    }

    fun release(owner: String) {
        owners.remove(owner)
        refresh()
    }

    /** Reavalia permissão/configuração (chamar depois que o motorista conceder a permissão). */
    fun refresh() {
        val permission = Permissions.hasFineLocation(app)
        val want = owners.isNotEmpty() && settings.zoneMonitorEnabled && permission
        if (want && !listening) startListening()
        if (!want && listening) stopListening()
        publish()
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        listening = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(provider, UPDATE_MS, UPDATE_MIN_M, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
                // aparelho sem esse provedor (ex.: tablet sem GPS)
            }
        }
        // semente: última posição conhecida, se for recente
        try {
            val seed = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < SEED_MAX_AGE_MS }
                .maxByOrNull { it.time }
            if (seed != null) onFix(seed)
        } catch (e: SecurityException) {
        }
        staleJob?.cancel()
        staleJob = scope.launch {
            while (isActive) {
                delay(5_000)
                checkStale()
            }
        }
    }

    private fun stopListening() {
        listening = false
        runCatching { lm.removeUpdates(listener) }
        staleJob?.cancel()
        staleJob = null
        lastLoc = null
        tracker.reset()
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location)
        }

        override fun onProviderEnabled(provider: String) {
            publish()
        }

        override fun onProviderDisabled(provider: String) {
            publish()
        }

        // Obrigatório em Android < 11 (senão AbstractMethodError ao receber atualização).
        @Deprecated("Usado só em versões antigas do Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val now = System.currentTimeMillis()
        // GPS recente é mais confiável que posição por rede: não deixa a rede "puxar" o ponto.
        val prev = lastLoc
        if (loc.provider == LocationManager.NETWORK_PROVIDER &&
            prev?.provider == LocationManager.GPS_PROVIDER &&
            now - lastFixAt < GPS_PREFERRED_MS
        ) return
        lastLoc = loc
        lastFixAt = now
        evaluate()
    }

    private fun checkStale() {
        if (lastLoc != null && System.currentTimeMillis() - lastFixAt > STALE_MS) {
            lastLoc = null
            tracker.noFix()
            publish()
        }
    }

    private fun evaluate() {
        val loc = if (listening) lastLoc else null
        val point = loc?.let { LatLang(it.latitude, it.longitude) }
        val accuracy = loc?.takeIf { it.hasAccuracy() }?.accuracy
        val change = tracker.update(regions, point, accuracy)
        publish()
        if (change != null) onChange(change)
    }

    private fun onChange(change: ZoneChange) {
        if (change.to != ZoneStatus.INSIDE && change.to != ZoneStatus.OUTSIDE) return
        if (!listening) return
        _events.tryEmit(ZoneEvent(change.to, change.regionName, change.initial))
        alerter.alert(change, settings)
    }

    private fun publish() {
        val loc = if (listening) lastLoc else null
        val gpsOn = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
        _state.value = ZoneUiState(
            status = tracker.status,
            regionName = tracker.regionName,
            monitoring = listening,
            hasPermission = Permissions.hasFineLocation(app),
            gpsEnabled = gpsOn,
            hasGpsHardware = Permissions.hasGpsHardware(app),
            lat = loc?.latitude,
            lng = loc?.longitude,
            accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy
        )
    }

    companion object {
        private const val UPDATE_MS = 3_000L
        private const val UPDATE_MIN_M = 5f
        private const val MAX_ACCURACY_M = 150f
        private const val STALE_MS = 90_000L
        private const val GPS_PREFERRED_MS = 15_000L
        private const val SEED_MAX_AGE_MS = 120_000L
    }
}
