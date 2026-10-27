package com.copilotodriverpro.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "copiloto_settings")

/**
 * Repositório de persistência das configurações.
 *
 * BUG CORRIGIDO: a versão anterior não salvava nem lia os campos de veículo,
 * metas e limites de embarque — eles voltavam ao padrão a cada reinício.
 * Agora TODOS os campos de DriverSettings têm chave e são gravados em save().
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<DriverSettings> = context.dataStore.data.map { p -> read(p) }.distinctUntilChanged()

    /** Lê o estado atual uma única vez (para salvos atômicos). */
    suspend fun load(): DriverSettings = settings.first()

    private fun read(p: androidx.datastore.preferences.core.Preferences): DriverSettings =
        DriverSettings(
            copilotEnabled = p[KEY_COPILOT] ?: true,
            readOnlyUber99 = p[KEY_ONLY_UBER] ?: true,
            rideCardEnabled = p[KEY_RIDE_CARD] ?: true,
            cardOpacity = p[KEY_CARD_OPACITY] ?: 1.0f,
            cardScale = p[KEY_CARD_SCALE] ?: 1.0f,
            cardX = p[KEY_CARD_X] ?: -1,
            cardY = p[KEY_CARD_Y] ?: -1,
            printEnabled = p[KEY_PRINT] ?: false,
            autoShiftEnabled = p[KEY_AUTO_SHIFT] ?: false,
            cameraMaxDurationMin = p[KEY_CAM_MAX_MIN] ?: 30,
            fuelType = p[KEY_FUEL_TYPE]?.let {
                runCatching { FuelType.valueOf(it) }.getOrNull()
            } ?: FuelType.GASOLINE,
            avgConsumptionKmPerUnit = p[KEY_CONSUMPTION] ?: 10.0f,
            fuelPricePerUnit = p[KEY_FUEL_PRICE] ?: 5.80f,
            maintenancePerKm = p[KEY_MAINT_PER_KM] ?: 0.15f,
            tireCostPerKm = p[KEY_TIRE_PER_KM] ?: 0.05f,
            fixedCostPerDay = p[KEY_FIXED_COST_DAY] ?: 0f,
            dailyGoal = p[KEY_DAILY_GOAL] ?: 0f,
            depreciationPerKm = p[KEY_DEPREC_PER_KM] ?: 0.20f,
            minNetHourly = p[KEY_MIN_NET_HOUR] ?: 30.00f,
            minNetPerKm = p[KEY_MIN_NET_KM] ?: 1.00f,
            minNetPerRide = p[KEY_MIN_NET_RIDE] ?: 0.00f,
            maxDistanceToPickupKm = p[KEY_MAX_PICKUP_KM] ?: 5.0f,
            maxTimeToPickupMin = p[KEY_MAX_PICKUP_MIN] ?: 15.0f,
            perKmMin = p[KEY_KM_MIN] ?: 1.50f,
            perKmMax = p[KEY_KM_MAX] ?: 2.00f,
            perHourMin = p[KEY_HOUR_MIN] ?: 35.00f,
            perHourMax = p[KEY_HOUR_MAX] ?: 40.00f,
            includePickupInKm = p[KEY_INCLUDE_PICKUP] ?: true,
            floatingEnabled = p[KEY_FAB_ENABLED] ?: false,
            panelPosition = p[KEY_POSITION]?.let { runCatching { PanelPosition.valueOf(it) }.getOrNull() }
                ?: PanelPosition.BOTTOM,
            fontSize = p[KEY_FONT]?.let { runCatching { FontSize.valueOf(it) }.getOrNull() }
                ?: FontSize.MEDIUM,
            compactMode = p[KEY_COMPACT] ?: true,
            showPerKm = p[KEY_SHOW_KM] ?: true,
            showPerHour = p[KEY_SHOW_HOUR] ?: true,
            showPassengerRating = p[KEY_SHOW_RATING] ?: true,
            minRating = p[KEY_MIN_RATING] ?: 4.80f,
            stopAlertEnabled = p[KEY_STOP_ALERT] ?: true,
            checkPickupRegion = p[KEY_CHECK_PICKUP] ?: true,
            checkDestinationRegion = p[KEY_CHECK_DEST] ?: true,
            alertPickupOutside = p[KEY_ALERT_PICKUP] ?: true,
            alertDestinationOutside = p[KEY_ALERT_DEST] ?: true,
            zoneMonitorEnabled = p[KEY_ZONE_MONITOR] ?: true,
            zonePillEnabled = p[KEY_ZONE_PILL] ?: true,
            zoneNotify = p[KEY_ZONE_NOTIFY] ?: true,
            zoneVibrate = p[KEY_ZONE_VIBRATE] ?: true,
            zoneVoice = p[KEY_ZONE_VOICE] ?: false,
            rideRegionAlertEnabled = p[KEY_RIDE_REGION_ALERT] ?: true,
            rideRegionVibrate = p[KEY_RIDE_REGION_VIBRATE] ?: true,
            rideRegionVoice = p[KEY_RIDE_REGION_VOICE] ?: false,
            fabX = p[KEY_FAB_X] ?: -1,
            fabY = p[KEY_FAB_Y] ?: -1
        )

    suspend fun save(settings: DriverSettings) {
        context.dataStore.edit { p ->
            p[KEY_COPILOT] = settings.copilotEnabled
            p[KEY_ONLY_UBER] = settings.readOnlyUber99
            p[KEY_RIDE_CARD] = settings.rideCardEnabled
            p[KEY_CARD_OPACITY] = settings.cardOpacity
            p[KEY_CARD_SCALE] = settings.cardScale
            p[KEY_CARD_X] = settings.cardX
            p[KEY_CARD_Y] = settings.cardY
            p[KEY_PRINT] = settings.printEnabled
            p[KEY_AUTO_SHIFT] = settings.autoShiftEnabled
            p[KEY_CAM_MAX_MIN] = settings.cameraMaxDurationMin
            p[KEY_FUEL_TYPE] = settings.fuelType.name
            p[KEY_CONSUMPTION] = settings.avgConsumptionKmPerUnit
            p[KEY_FUEL_PRICE] = settings.fuelPricePerUnit
            p[KEY_MAINT_PER_KM] = settings.maintenancePerKm
            p[KEY_TIRE_PER_KM] = settings.tireCostPerKm
            p[KEY_FIXED_COST_DAY] = settings.fixedCostPerDay
            p[KEY_DAILY_GOAL] = settings.dailyGoal
            p[KEY_DEPREC_PER_KM] = settings.depreciationPerKm
            p[KEY_MIN_NET_HOUR] = settings.minNetHourly
            p[KEY_MIN_NET_KM] = settings.minNetPerKm
            p[KEY_MIN_NET_RIDE] = settings.minNetPerRide
            p[KEY_MAX_PICKUP_KM] = settings.maxDistanceToPickupKm
            p[KEY_MAX_PICKUP_MIN] = settings.maxTimeToPickupMin
            p[KEY_KM_MIN] = settings.perKmMin
            p[KEY_KM_MAX] = settings.perKmMax
            p[KEY_HOUR_MIN] = settings.perHourMin
            p[KEY_HOUR_MAX] = settings.perHourMax
            p[KEY_INCLUDE_PICKUP] = settings.includePickupInKm
            p[KEY_FAB_ENABLED] = settings.floatingEnabled
            p[KEY_POSITION] = settings.panelPosition.name
            p[KEY_FONT] = settings.fontSize.name
            p[KEY_COMPACT] = settings.compactMode
            p[KEY_SHOW_KM] = settings.showPerKm
            p[KEY_SHOW_HOUR] = settings.showPerHour
            p[KEY_SHOW_RATING] = settings.showPassengerRating
            p[KEY_MIN_RATING] = settings.minRating
            p[KEY_STOP_ALERT] = settings.stopAlertEnabled
            p[KEY_CHECK_PICKUP] = settings.checkPickupRegion
            p[KEY_CHECK_DEST] = settings.checkDestinationRegion
            p[KEY_ALERT_PICKUP] = settings.alertPickupOutside
            p[KEY_ALERT_DEST] = settings.alertDestinationOutside
            p[KEY_ZONE_MONITOR] = settings.zoneMonitorEnabled
            p[KEY_ZONE_PILL] = settings.zonePillEnabled
            p[KEY_ZONE_NOTIFY] = settings.zoneNotify
            p[KEY_ZONE_VIBRATE] = settings.zoneVibrate
            p[KEY_ZONE_VOICE] = settings.zoneVoice
            p[KEY_RIDE_REGION_ALERT] = settings.rideRegionAlertEnabled
            p[KEY_RIDE_REGION_VIBRATE] = settings.rideRegionVibrate
            p[KEY_RIDE_REGION_VOICE] = settings.rideRegionVoice
            p[KEY_FAB_X] = settings.fabX
            p[KEY_FAB_Y] = settings.fabY
        }
    }

    suspend fun saveFabPosition(x: Int, y: Int) {
        context.dataStore.edit { p ->
            p[KEY_FAB_X] = x
            p[KEY_FAB_Y] = y
        }
    }

    companion object {
        private val KEY_COPILOT = booleanPreferencesKey("copilot_enabled")
        private val KEY_ONLY_UBER = booleanPreferencesKey("only_uber99")
        private val KEY_RIDE_CARD = booleanPreferencesKey("ride_card_enabled")
        private val KEY_CARD_OPACITY = floatPreferencesKey("card_opacity")
        private val KEY_CARD_SCALE = floatPreferencesKey("card_scale")
        private val KEY_CARD_X = intPreferencesKey("card_x")
        private val KEY_CARD_Y = intPreferencesKey("card_y")
        private val KEY_PRINT = booleanPreferencesKey("print_enabled")
        private val KEY_AUTO_SHIFT = booleanPreferencesKey("auto_shift_enabled")
        private val KEY_CAM_MAX_MIN = intPreferencesKey("camera_max_duration_min")
        private val KEY_FUEL_TYPE = stringPreferencesKey("fuel_type")
        private val KEY_CONSUMPTION = floatPreferencesKey("avg_consumption")
        private val KEY_FUEL_PRICE = floatPreferencesKey("fuel_price")
        private val KEY_MAINT_PER_KM = floatPreferencesKey("maintenance_per_km")
        private val KEY_TIRE_PER_KM = floatPreferencesKey("tire_per_km")
        private val KEY_FIXED_COST_DAY = floatPreferencesKey("fixed_cost_per_day")
        private val KEY_DAILY_GOAL = floatPreferencesKey("daily_goal")
        private val KEY_DEPREC_PER_KM = floatPreferencesKey("depreciation_per_km")
        private val KEY_MIN_NET_HOUR = floatPreferencesKey("min_net_hourly")
        private val KEY_MIN_NET_KM = floatPreferencesKey("min_net_per_km")
        private val KEY_MIN_NET_RIDE = floatPreferencesKey("min_net_per_ride")
        private val KEY_MAX_PICKUP_KM = floatPreferencesKey("max_pickup_km")
        private val KEY_MAX_PICKUP_MIN = floatPreferencesKey("max_pickup_min")
        private val KEY_KM_MIN = floatPreferencesKey("km_min")
        private val KEY_KM_MAX = floatPreferencesKey("km_max")
        private val KEY_HOUR_MIN = floatPreferencesKey("hour_min")
        private val KEY_HOUR_MAX = floatPreferencesKey("hour_max")
        private val KEY_INCLUDE_PICKUP = booleanPreferencesKey("include_pickup_km")
        private val KEY_FAB_ENABLED = booleanPreferencesKey("fab_enabled")
        private val KEY_POSITION = stringPreferencesKey("panel_position")
        private val KEY_FONT = stringPreferencesKey("font_size")
        private val KEY_COMPACT = booleanPreferencesKey("compact_mode")
        private val KEY_SHOW_KM = booleanPreferencesKey("show_per_km")
        private val KEY_SHOW_HOUR = booleanPreferencesKey("show_per_hour")
        private val KEY_SHOW_RATING = booleanPreferencesKey("show_rating")
        private val KEY_MIN_RATING = floatPreferencesKey("min_rating")
        private val KEY_STOP_ALERT = booleanPreferencesKey("stop_alert")
        private val KEY_CHECK_PICKUP = booleanPreferencesKey("check_pickup_region")
        private val KEY_CHECK_DEST = booleanPreferencesKey("check_dest_region")
        private val KEY_ALERT_PICKUP = booleanPreferencesKey("alert_pickup_outside")
        private val KEY_ALERT_DEST = booleanPreferencesKey("alert_dest_outside")
        private val KEY_ZONE_MONITOR = booleanPreferencesKey("zone_monitor")
        private val KEY_ZONE_PILL = booleanPreferencesKey("zone_pill")
        private val KEY_ZONE_NOTIFY = booleanPreferencesKey("zone_notify")
        private val KEY_ZONE_VIBRATE = booleanPreferencesKey("zone_vibrate")
        private val KEY_ZONE_VOICE = booleanPreferencesKey("zone_voice")
        private val KEY_RIDE_REGION_ALERT = booleanPreferencesKey("ride_region_alert")
        private val KEY_RIDE_REGION_VIBRATE = booleanPreferencesKey("ride_region_vibrate")
        private val KEY_RIDE_REGION_VOICE = booleanPreferencesKey("ride_region_voice")
        private val KEY_FAB_X = intPreferencesKey("fab_x")
        private val KEY_FAB_Y = intPreferencesKey("fab_y")
    }
}
