package com.thinkoff.clawwatch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Typed phone-side health summary read from Health Connect (where Oura's Android
 * app writes its data) and synced to the watch over the Wear Data Layer. v1
 * carries sleep / last HR / steps; restingHr + hrv are nullable for the next
 * pass. The watch reads `summary()` for agent context; the raw fields ride along
 * for later use.
 */
data class HealthSnapshot(
    val sleepMinutes: Long? = null,
    val lastSleepEndEpochMs: Long? = null,
    val lastHr: Long? = null,
    val restingHr: Long? = null,
    val hrvMs: Double? = null,
    val steps24h: Long = 0,
    val source: String = "health_connect",
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun summary(): String = buildString {
        if (sleepMinutes != null) append("sleep ${sleepMinutes / 60}h${sleepMinutes % 60}m, ")
        if (lastHr != null) append("last HR $lastHr, ")
        if (restingHr != null) append("resting HR $restingHr, ")
        if (hrvMs != null) append("HRV ${hrvMs.toInt()}ms, ")
        append("steps $steps24h")
    }.trim().trimEnd(',', ' ')

    fun toJson(): String = JSONObject().apply {
        put("summary", summary())
        sleepMinutes?.let { put("sleep_minutes", it) }
        lastSleepEndEpochMs?.let { put("last_sleep_end", it) }
        lastHr?.let { put("last_hr", it) }
        restingHr?.let { put("resting_hr", it) }
        hrvMs?.let { put("hrv_ms", it) }
        put("steps_24h", steps24h)
        put("source", source)
        put("generated_at", generatedAtEpochMs)
    }.toString()
}

class HealthConnectManager(private val context: Context) {
    
    fun isAvailable(): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return try {
            healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readRecentHealthData(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "Health Connect SDK not available."
        }
        if (!hasAllPermissions()) {
            return@withContext "Health Connect missing permissions."
        }

        val endTime = Instant.now()
        val startTime = endTime.minus(24, ChronoUnit.HOURS)
        val timeRangeFilter = TimeRangeFilter.between(startTime, endTime)

        try {
            val stepsRequest = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val stepsResponse = healthConnectClient.readRecords(stepsRequest)
            val totalSteps = stepsResponse.records.sumOf { it.count }

            val hrRequest = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val hrResponse = healthConnectClient.readRecords(hrRequest)
            val lastHr = hrResponse.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute

            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val sleepResponse = healthConnectClient.readRecords(sleepRequest)
            val lastSleep = sleepResponse.records.lastOrNull()
            val sleepDurationHr = lastSleep?.let {
                java.time.Duration.between(it.startTime, it.endTime).toHours()
            }

            buildString {
                append("Steps last 24h: $totalSteps. ")
                if (lastHr != null) append("Last HR: $lastHr bpm. ")
                if (sleepDurationHr != null) append("Last sleep: $sleepDurationHr hrs.")
            }
        } catch (e: Exception) {
            "Health Connect data error: ${e.message}"
        }
    }

    /** Typed version of the recent-health read. Null when HC is unavailable or
     *  unpermissioned, so callers can cleanly skip the sync. */
    suspend fun readHealthSnapshot(): HealthSnapshot? = withContext(Dispatchers.IO) {
        if (!isAvailable() || !hasAllPermissions()) return@withContext null
        val endTime = Instant.now()
        val startTime = endTime.minus(24, ChronoUnit.HOURS)
        val range = TimeRangeFilter.between(startTime, endTime)
        try {
            val steps = healthConnectClient
                .readRecords(ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = range))
                .records.sumOf { it.count }
            val lastHr = healthConnectClient
                .readRecords(ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = range))
                .records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute
            val lastSleep = healthConnectClient
                .readRecords(ReadRecordsRequest(recordType = SleepSessionRecord::class, timeRangeFilter = range))
                .records.lastOrNull()
            HealthSnapshot(
                sleepMinutes = lastSleep?.let { java.time.Duration.between(it.startTime, it.endTime).toMinutes() },
                lastSleepEndEpochMs = lastSleep?.endTime?.toEpochMilli(),
                lastHr = lastHr,
                steps24h = steps,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Read the latest snapshot and push it to the watch over the Wear Data
     *  Layer at /clawwatch/health-state. Best-effort; returns true if pushed. */
    suspend fun syncHealthToWatch(): Boolean = withContext(Dispatchers.IO) {
        val snap = readHealthSnapshot() ?: return@withContext false
        try {
            val req = PutDataMapRequest.create("/clawwatch/health-state").apply {
                dataMap.putString("health_json", snap.toJson())
                dataMap.putLong("ts", snap.generatedAtEpochMs)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(req).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}
