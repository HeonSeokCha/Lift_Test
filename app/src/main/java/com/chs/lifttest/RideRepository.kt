package com.chs.lifttest

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class RideRepository(context: Context) {

    data class RideRecord(
        /** 기록 시각 (System.currentTimeMillis) — 센서 timestamp는 부팅 기준이라 별도 저장 */
        val wallTimeMs: Long,
        val ride: ElevatorDetector.ElevatorRide,
    )

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    fun append(ride: ElevatorDetector.ElevatorRide) = synchronized(lock) {
        try {
            val all = readAll() + RideRecord(System.currentTimeMillis(), ride)
            writeAll(all.takeLast(MAX_RECORDS))
        } catch (e: Exception) {
            Log.e(TAG, "append failed", e)
        }
    }

    /** 최신순 */
    fun load(): List<RideRecord> = synchronized(lock) { readAll().asReversed() }

    fun clear() = synchronized(lock) { file.delete(); Unit }

    private fun readAll(): List<RideRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            try {
                val o = JSONObject(line)
                RideRecord(
                    wallTimeMs = o.getLong("t"),
                    ride = ElevatorDetector.ElevatorRide(
                        direction = ElevatorDetector.Direction.valueOf(o.getString("dir")),
                        startNs = o.getLong("s"),
                        endNs = o.getLong("e"),
                        altitudeChangeM = o.getDouble("dh").toFloat(),
                        estimatedFloors = o.getInt("fl"),
                        peakVerticalSpeedMps = o.getDouble("v").toFloat(),
                        confidence = o.getDouble("c").toFloat(),
                    )
                )
            } catch (e: Exception) {
                null // 손상된 줄은 건너뜀
            }
        }
    }

    private fun writeAll(records: List<RideRecord>) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(records.joinToString("\n") { r ->
            JSONObject()
                .put("t", r.wallTimeMs)
                .put("dir", r.ride.direction.name)
                .put("s", r.ride.startNs)
                .put("e", r.ride.endNs)
                .put("dh", r.ride.altitudeChangeM.toDouble())
                .put("fl", r.ride.estimatedFloors)
                .put("v", r.ride.peakVerticalSpeedMps.toDouble())
                .put("c", r.ride.confidence.toDouble())
                .toString()
        })
        if (!tmp.renameTo(file)) { // 원자적 교체 실패 시 폴백
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val TAG = "RideRepository"
        const val FILE_NAME = "rides.jsonl"
        const val MAX_RECORDS = 200
    }
}
