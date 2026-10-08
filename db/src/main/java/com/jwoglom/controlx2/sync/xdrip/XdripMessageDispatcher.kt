package com.jwoglom.controlx2.sync.xdrip

import android.content.Context
import com.jwoglom.controlx2.shared.util.twoDecimalPlaces
import com.jwoglom.controlx2.sync.xdrip.models.XdripDeviceStatusSnapshot
import com.jwoglom.controlx2.sync.xdrip.models.XdripSgvPayload
import com.jwoglom.controlx2.sync.xdrip.models.XdripTimedValue
import com.jwoglom.controlx2.sync.xdrip.models.XdripTreatmentPayload
import com.jwoglom.pumpx2.pump.messages.Message
import com.jwoglom.pumpx2.pump.messages.models.InsulinUnit
import com.jwoglom.pumpx2.pump.messages.response.control.InitiateBolusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.ControlIQIOBResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBasalStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBatteryAbstractResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentBolusStatusResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.CurrentEGVGuiDataResponse
import com.jwoglom.pumpx2.pump.messages.response.currentStatus.InsulinStatusResponse
import timber.log.Timber
import java.time.Duration
import java.time.Instant

internal sealed class DispatchEvent {
    data class PumpBattery(val percent: Int) : DispatchEvent()
    data class PumpIob(val units: Double) : DispatchEvent()
    data class PumpReservoir(val units: Int) : DispatchEvent()
    data class PumpBasal(val unitsPerHour: Double) : DispatchEvent()
    data class BasalTreatment(val unitsPerHour: Double) : DispatchEvent()
    // readingTime = hora real de la lectura del sensor segun la bomba (null si no es fiable)
    data class CgmSgv(val mgdl: Int, val trendRate: Int, val readingTime: Instant? = null) : DispatchEvent()
    data class TreatmentInitiated(val bolusId: Int, val status: String) : DispatchEvent()
    data class TreatmentStatus(val bolusId: Int, val requestedVolumeMilli: Long, val status: String, val timestamp: Instant) : DispatchEvent()
    data object Other : DispatchEvent()
}

class XdripMessageDispatcher(
    private val broadcaster: XdripBroadcaster,
    private val configProvider: () -> XdripSyncConfig,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    constructor(context: Context) : this(
        broadcaster = XdripBroadcastSender(context),
        // The host apps store xDrip config in the legacy "WearX2" SharedPreferences
        // file (mobile Prefs.prefs() and wear WearPrefs.prefs() both use this file).
        configProvider = {
            XdripSyncConfig.load(
                context.getSharedPreferences("WearX2", Context.MODE_PRIVATE)
            )
        }
    )

    private val latestPumpSnapshot = XdripDeviceStatusSnapshot()

    fun onReceiveMessage(message: Message) {
        onEvent(message.toDispatchEvent())
    }

    internal fun onEvent(event: DispatchEvent) {
        val config = configProvider()
        if (!config.enabled) return

        val receivedAt = nowProvider()
        val categories = updateSnapshot(event, receivedAt)

        if (config.sendCgmSgv && event is DispatchEvent.CgmSgv) {
            // Se usa la hora real de la lectura: asi la misma lectura genera siempre el mismo
            // payload (se filtra como duplicada) y xDrip+ calcula bien la tendencia.
            val readingTime = event.readingTime ?: receivedAt
            Timber.i(
                "CGM mgdl=%d trendRate=%d readingTime=%s receivedAt=%s",
                event.mgdl, event.trendRate, event.readingTime, receivedAt
            )
            val sgvPayload = XdripSgvPayload
                .fromValue(event.mgdl, event.trendRate, readingTime)
                .toJsonArrayString()
            broadcaster.sendSgv(sgvPayload, config.cgmSgvMinimumIntervalSeconds)
        }

        if (config.sendPumpDeviceStatus && StatusCategory.PUMP_STATUS in categories) {
            val deviceStatusPayload = latestPumpSnapshot
                .toPayload(createdAt = receivedAt)
                .toJsonString()
            broadcaster.sendDeviceStatus(deviceStatusPayload, config.pumpDeviceStatusMinimumIntervalSeconds)
        }

        if (config.sendTreatments && StatusCategory.TREATMENT in categories) {
            val treatmentPayload = when (event) {
                is DispatchEvent.TreatmentInitiated -> XdripTreatmentPayload(
                    eventType = "Bolus",
                    createdAt = receivedAt.toString(),
                    mills = receivedAt.toEpochMilli(),
                    notes = "ControlX2 bolus initiated bolusId=${event.bolusId} status=${event.status}"
                ).toJsonArrayString()

                is DispatchEvent.TreatmentStatus -> XdripTreatmentPayload
                    .fromStatus(
                        bolusId = event.bolusId,
                        requestedVolumeMilli = event.requestedVolumeMilli,
                        status = event.status,
                        timestamp = event.timestamp
                    )
                    .toJsonArrayString()

                is DispatchEvent.BasalTreatment -> XdripTreatmentPayload
                    .forBasalRate(
                        unitsPerHour = event.unitsPerHour,
                        durationMinutes = BASAL_TREATMENT_DURATION_MINUTES,
                        timestamp = receivedAt
                    )
                    .toJsonArrayString()

                else -> null
            }
            if (treatmentPayload != null) {
                broadcaster.sendTreatments(
                    treatmentsJsonString = treatmentPayload,
                    minimumIntervalSeconds = config.treatmentsMinimumIntervalSeconds,
                    alsoSendNewFood = true
                )
            }
        }

        if (config.sendStatusLine && StatusCategory.PUMP_STATUS in categories) {
            val statusline = buildString {
                append("Pump")
                latestPumpSnapshot.sgvMgdl?.value?.let { append(" SGV:$it") }
                latestPumpSnapshot.iobUnits?.value?.let { append(" IOB:${twoDecimalPlaces(it)}U") }
                latestPumpSnapshot.cartridgeUnits?.value?.let { append(" Cart:${it}U") }
                latestPumpSnapshot.basalUnitsPerHour?.value?.let { append(" ${twoDecimalPlaces(it)}U/h") }
                latestPumpSnapshot.batteryPercent?.value?.let { append(" Batt:$it") }
            }
            broadcaster.sendExternalStatusline(statusline, config.statusLineMinimumIntervalSeconds)
        }
    }

    private fun updateSnapshot(event: DispatchEvent, receivedAt: Instant): Set<StatusCategory> {
        return when (event) {
            is DispatchEvent.PumpBattery -> {
                latestPumpSnapshot.batteryPercent = XdripTimedValue(event.percent, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }
            is DispatchEvent.PumpIob -> {
                latestPumpSnapshot.iobUnits = XdripTimedValue(event.units, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }
            is DispatchEvent.PumpReservoir -> {
                latestPumpSnapshot.cartridgeUnits = XdripTimedValue(event.units, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }
            is DispatchEvent.PumpBasal -> {
                latestPumpSnapshot.basalUnitsPerHour = XdripTimedValue(event.unitsPerHour, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }
            is DispatchEvent.BasalTreatment -> {
                latestPumpSnapshot.basalUnitsPerHour = XdripTimedValue(event.unitsPerHour, receivedAt)
                setOf(StatusCategory.PUMP_STATUS, StatusCategory.TREATMENT)
            }
            is DispatchEvent.CgmSgv -> {
                latestPumpSnapshot.applySgvValue(event.mgdl, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }
            is DispatchEvent.TreatmentInitiated,
            is DispatchEvent.TreatmentStatus -> setOf(StatusCategory.TREATMENT)
            is DispatchEvent.Other -> setOf(StatusCategory.OTHER)
        }
    }

    private fun Message.toDispatchEvent(): DispatchEvent {
        return when (this) {
            is CurrentBatteryAbstractResponse -> DispatchEvent.PumpBattery(batteryPercent)
            is ControlIQIOBResponse -> DispatchEvent.PumpIob(InsulinUnit.from1000To1(pumpDisplayedIOB))
            is InsulinStatusResponse -> DispatchEvent.PumpReservoir(currentInsulinAmount)
            is CurrentBasalStatusResponse -> DispatchEvent.BasalTreatment(InsulinUnit.from1000To1(currentBasalRate))
            is CurrentEGVGuiDataResponse -> DispatchEvent.CgmSgv(
                mgdl = cgmReading,
                trendRate = trendRate,
                readingTime = pumpSecondsToInstantOrNull(bgReadingTimestampSeconds.toLong())
            )
            is InitiateBolusResponse -> DispatchEvent.TreatmentInitiated(bolusId, statusType.toString())
            is CurrentBolusStatusResponse -> DispatchEvent.TreatmentStatus(
                bolusId = bolusId,
                requestedVolumeMilli = requestedVolume,
                status = status.toString(),
                timestamp = timestampInstant
            )
            else -> DispatchEvent.Other
        }
    }

    /**
     * La bomba cuenta segundos desde el 2008-01-01 00:00:00. Segun el modelo, esa hora puede ser
     * UTC o la hora local de la bomba, asi que se prueban las dos interpretaciones y se acepta la
     * que cae a menos de 30 minutos de ahora. Si ninguna cuadra se devuelve null y se usa la hora
     * de recepcion como antes.
     */
    private fun pumpSecondsToInstantOrNull(pumpSeconds: Long): Instant? {
        if (pumpSeconds <= 0L) return null
        val now = nowProvider()
        val unixSeconds = PUMP_EPOCH_UNIX_SECONDS + pumpSeconds

        // 1) Interpretada como UTC
        val asUtc = Instant.ofEpochSecond(unixSeconds)
        if (Duration.between(asUtc, now).abs() <= MAX_READING_CLOCK_SKEW) return asUtc

        // 2) Interpretada como hora local del movil
        val asLocal = java.time.LocalDateTime
            .ofEpochSecond(unixSeconds, 0, java.time.ZoneOffset.UTC)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
        if (Duration.between(asLocal, now).abs() <= MAX_READING_CLOCK_SKEW) return asLocal

        Timber.w(
            "CGM readingTime descartada: pumpSeconds=%d utc=%s local=%s now=%s",
            pumpSeconds, asUtc, asLocal, now
        )
        return null
    }

    private enum class StatusCategory {
        PUMP_STATUS,
        TREATMENT,
        OTHER
    }

    companion object {
        /** Duration for basal treatment segments, matching the pump status polling interval. */
        internal const val BASAL_TREATMENT_DURATION_MINUTES = 5

        /** 2008-01-01T00:00:00Z en segundos Unix. */
        private const val PUMP_EPOCH_UNIX_SECONDS = 1199145600L
        private val MAX_READING_CLOCK_SKEW: Duration = Duration.ofMinutes(30)
    }
}
