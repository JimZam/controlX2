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
import java.time.ZoneId
import java.time.ZoneOffset

internal sealed class DispatchEvent {
    data class PumpBattery(val percent: Int) : DispatchEvent()
    data class PumpIob(val units: Double) : DispatchEvent()
    data class PumpReservoir(val units: Int) : DispatchEvent()
    data class PumpBasal(val unitsPerHour: Double) : DispatchEvent()
    data class BasalTreatment(val unitsPerHour: Double) : DispatchEvent()

    data class CgmSgv(
        val mgdl: Int,
        val trendRate: Int,
        val readingTime: Instant? = null
    ) : DispatchEvent()

    data class TreatmentInitiated(
        val bolusId: Int,
        val status: String
    ) : DispatchEvent()

    data class TreatmentStatus(
        val bolusId: Int,
        val requestedVolumeMilli: Long,
        val status: String,
        val timestamp: Instant
    ) : DispatchEvent()

    data object Other : DispatchEvent()
}

class XdripMessageDispatcher(
    private val broadcaster: XdripBroadcaster,
    private val configProvider: () -> XdripSyncConfig,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    constructor(context: Context) : this(
        broadcaster = XdripBroadcastSender(context),
        configProvider = {
            XdripSyncConfig.load(
                context.getSharedPreferences(
                    "WearX2",
                    Context.MODE_PRIVATE
                )
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
            val readingTime = event.readingTime

            if (readingTime == null) {
                Timber.w(
                    "CGM no enviado: la fecha de lectura no es válida. mgdl=%d",
                    event.mgdl
                )
                return
            }

            if (readingTime.isAfter(receivedAt.plusSeconds(30))) {
                Timber.w(
                    "CGM no enviado: fecha futura. readingTime=%s receivedAt=%s",
                    readingTime,
                    receivedAt
                )
                return
            }

            val direction = directionFromReadings(
                event.mgdl,
                readingTime
            )

            val sgvPayload = XdripSgvPayload(
                mgdl = event.mgdl,
                mills = readingTime.toEpochMilli(),
                direction = direction
            ).toJsonArrayString()

            broadcaster.sendSgv(
                sgvPayload,
                config.cgmSgvMinimumIntervalSeconds
            )
        }

        if (
            config.sendPumpDeviceStatus &&
            StatusCategory.PUMP_STATUS in categories
        ) {
            val deviceStatusPayload = latestPumpSnapshot
                .toPayload(createdAt = receivedAt)
                .toJsonString()

            broadcaster.sendDeviceStatus(
                deviceStatusPayload,
                config.pumpDeviceStatusMinimumIntervalSeconds
            )
        }

        if (
            config.sendTreatments &&
            StatusCategory.TREATMENT in categories
        ) {
            val treatmentPayload = when (event) {
                is DispatchEvent.TreatmentInitiated -> null

                is DispatchEvent.TreatmentStatus ->
                    if (
                        event.requestedVolumeMilli > 0 &&
                        shouldSendBolusStatus(event.bolusId)
                    ) {
                        XdripTreatmentPayload
                            .fromStatus(
                                bolusId = event.bolusId,
                                requestedVolumeMilli = event.requestedVolumeMilli,
                                status = event.status,
                                timestamp = event.timestamp
                            )
                            .toJsonArrayString()
                    } else {
                        null
                    }

                is DispatchEvent.BasalTreatment ->
                    if (shouldSendBasal(event.unitsPerHour, receivedAt)) {
                        XdripTreatmentPayload
                            .forBasalRate(
                                unitsPerHour = event.unitsPerHour,
                                durationMinutes = BASAL_TREATMENT_DURATION_MINUTES,
                                timestamp = receivedAt
                            )
                            .toJsonArrayString()
                    } else {
                        null
                    }

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

        if (
            config.sendStatusLine &&
            StatusCategory.PUMP_STATUS in categories
        ) {
            val statusline = buildString {
                append("Pump")
                latestPumpSnapshot.sgvMgdl?.value?.let {
                    append(" SGV:$it")
                }
                latestPumpSnapshot.iobUnits?.value?.let {
                    append(" IOB:${twoDecimalPlaces(it)}U")
                }
                latestPumpSnapshot.cartridgeUnits?.value?.let {
                    append(" Cart:${it}U")
                }
                latestPumpSnapshot.basalUnitsPerHour?.value?.let {
                    append(" ${twoDecimalPlaces(it)}U/h")
                }
                latestPumpSnapshot.batteryPercent?.value?.let {
                    append(" Batt:$it")
                }
            }

            broadcaster.sendExternalStatusline(
                statusline,
                config.statusLineMinimumIntervalSeconds
            )
        }
    }

    private fun updateSnapshot(
        event: DispatchEvent,
        receivedAt: Instant
    ): Set<StatusCategory> {
        return when (event) {
            is DispatchEvent.PumpBattery -> {
                latestPumpSnapshot.batteryPercent =
                    XdripTimedValue(event.percent, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }

            is DispatchEvent.PumpIob -> {
                latestPumpSnapshot.iobUnits =
                    XdripTimedValue(event.units, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }

            is DispatchEvent.PumpReservoir -> {
                latestPumpSnapshot.cartridgeUnits =
                    XdripTimedValue(event.units, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }

            is DispatchEvent.PumpBasal -> {
                latestPumpSnapshot.basalUnitsPerHour =
                    XdripTimedValue(event.unitsPerHour, receivedAt)
                setOf(StatusCategory.PUMP_STATUS)
            }

            is DispatchEvent.BasalTreatment -> {
                latestPumpSnapshot.basalUnitsPerHour =
                    XdripTimedValue(event.unitsPerHour, receivedAt)
                setOf(
                    StatusCategory.PUMP_STATUS,
                    StatusCategory.TREATMENT
                )
            }

            is DispatchEvent.CgmSgv -> {
                latestPumpSnapshot.applySgvValue(
                    event.mgdl,
                    receivedAt
                )
                setOf(StatusCategory.PUMP_STATUS)
            }

            is DispatchEvent.TreatmentInitiated,
            is DispatchEvent.TreatmentStatus ->
                setOf(StatusCategory.TREATMENT)

            is DispatchEvent.Other ->
                setOf(StatusCategory.OTHER)
        }
    }

    private fun Message.toDispatchEvent(): DispatchEvent {
        return when (this) {
            is CurrentBatteryAbstractResponse ->
                DispatchEvent.PumpBattery(batteryPercent)

            is ControlIQIOBResponse ->
                DispatchEvent.PumpIob(
                    InsulinUnit.from1000To1(pumpDisplayedIOB)
                )

            is InsulinStatusResponse ->
                DispatchEvent.PumpReservoir(currentInsulinAmount)

            is CurrentBasalStatusResponse ->
                DispatchEvent.BasalTreatment(
                    InsulinUnit.from1000To1(currentBasalRate)
                )

            is CurrentEGVGuiDataResponse ->
                DispatchEvent.CgmSgv(
                    mgdl = cgmReading,
                    trendRate = trendRate,
                    readingTime = pumpSecondsToInstantOrNull(
                        bgReadingTimestampSeconds.toLong()
                    )
                )

            is InitiateBolusResponse ->
                DispatchEvent.TreatmentInitiated(
                    bolusId,
                    statusType.toString()
                )

            is CurrentBolusStatusResponse ->
                DispatchEvent.TreatmentStatus(
                    bolusId = bolusId,
                    requestedVolumeMilli = requestedVolume,
                    status = status.toString(),
                    timestamp = pumpLocalTimeToInstant(timestampInstant)
                )

            else -> DispatchEvent.Other
        }
    }

    private var lastBolusStatusSentId: Int? = null

    @Synchronized
    private fun shouldSendBolusStatus(bolusId: Int): Boolean {
        if (bolusId == lastBolusStatusSentId) return false

        lastBolusStatusSentId = bolusId
        return true
    }

    private var lastBasalRate: Double? = null
    private var lastBasalSentAt: Instant? = null

    @Synchronized
    private fun shouldSendBasal(
        rate: Double,
        now: Instant
    ): Boolean {
        val lastAt = lastBasalSentAt

        val expired = lastAt == null ||
            Duration.between(lastAt, now) >=
            Duration.ofMinutes(BASAL_TREATMENT_DURATION_MINUTES.toLong())

        if (rate == lastBasalRate && !expired) return false

        lastBasalRate = rate
        lastBasalSentAt = now

        return true
    }

    private val recentReadings = ArrayDeque<Pair<Instant, Int>>()

    /**
     * Calcula la flecha a partir de las lecturas de los últimos 15 minutos.
     * Sin hora fiable o con menos de 4 minutos de historial, devuelve NONE.
     */
    @Synchronized
    private fun directionFromReadings(
        mgdl: Int,
        readingTime: Instant?
    ): String {
        if (readingTime == null) return "NONE"

        val last = recentReadings.lastOrNull()

        if (last == null || readingTime.isAfter(last.first)) {
            recentReadings.addLast(readingTime to mgdl)
        } else if (readingTime != last.first) {
            return "NONE"
        }

        while (
            recentReadings.isNotEmpty() &&
            Duration.between(
                recentReadings.first().first,
                readingTime
            ) > SLOPE_WINDOW
        ) {
            recentReadings.removeFirst()
        }

        val oldest = recentReadings.first()

        val minutes = Duration.between(
            oldest.first,
            readingTime
        ).seconds / 60.0

        if (minutes < MIN_SLOPE_MINUTES) return "NONE"

        val slope = (mgdl - oldest.second) / minutes

        return when {
            slope <= -3 -> "DoubleDown"
            slope <= -2 -> "SingleDown"
            slope <= -1 -> "FortyFiveDown"
            slope < 1 -> "Flat"
            slope < 2 -> "FortyFiveUp"
            slope < 3 -> "SingleUp"
            else -> "DoubleUp"
        }
    }

    /**
     * Convierte los segundos desde el 1 de enero de 2008 en un Instant.
     * El desfase se estima comparando la marca de tiempo con el reloj actual.
     */
    private fun pumpSecondsToInstantOrNull(
        pumpSeconds: Long
    ): Instant? {
        if (pumpSeconds <= 0L) return null

        val now = nowProvider()
        val raw = Instant.ofEpochSecond(
            PUMP_EPOCH_UNIX_SECONDS + pumpSeconds
        )

        val diffSeconds = Duration.between(raw, now).seconds

        val roundedSeconds = Math.round(
            diffSeconds / OFFSET_STEP_SECONDS.toDouble()
        ) * OFFSET_STEP_SECONDS

        val residual = Duration.ofSeconds(
            diffSeconds - roundedSeconds
        ).abs()

        if (
            Duration.ofSeconds(roundedSeconds).abs() > MAX_PUMP_OFFSET ||
            residual > MAX_READING_CLOCK_SKEW
        ) {
            Timber.w(
                "CGM no enviado: marca de tiempo incoherente."
            )
            return null
        }

        return raw.plusSeconds(roundedSeconds)
    }

    /**
     * Convierte la hora de un evento de bolus a Instant.
     * Interpreta la hora recibida como hora local de la bomba.
     */
    private fun pumpLocalTimeToInstant(
        pumpInstant: Instant
    ): Instant {
        val now = nowProvider()

        val corrected = pumpInstant
            .atZone(ZoneOffset.UTC)
            .toLocalDateTime()
            .atZone(ZoneId.systemDefault())
            .toInstant()

        return if (corrected.isAfter(now.plus(Duration.ofMinutes(5)))) {
            Timber.w(
                "Treatment timestamp en el futuro; se usa la hora actual."
            )
            now
        } else {
            corrected
        }
    }

    private enum class StatusCategory {
        PUMP_STATUS,
        TREATMENT,
        OTHER
    }

    companion object {
        internal const val BASAL_TREATMENT_DURATION_MINUTES = 5

        private const val PUMP_EPOCH_UNIX_SECONDS = 1199145600L

        private val MAX_READING_CLOCK_SKEW: Duration =
            Duration.ofMinutes(10)

        private val SLOPE_WINDOW: Duration =
            Duration.ofMinutes(15)

        private const val MIN_SLOPE_MINUTES = 4.0

        private const val OFFSET_STEP_SECONDS = 15 * 60L

        private val MAX_PUMP_OFFSET: Duration =
            Duration.ofHours(14)
    }
}
