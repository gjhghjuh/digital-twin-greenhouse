package cn.greenhouse.twin.data.monitoring

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cn.greenhouse.twin.domain.ObservationSource
import cn.greenhouse.twin.domain.ActuatorKey
import cn.greenhouse.twin.domain.monitoring.AlarmEndReason
import cn.greenhouse.twin.domain.monitoring.AlarmEpisode
import cn.greenhouse.twin.domain.monitoring.AlarmKind
import cn.greenhouse.twin.domain.monitoring.AlarmSeverity
import kotlinx.coroutines.flow.Flow

// 报警记录是手机观察结果，独立于设备原始历史，不改写归档与实时记录。
@Entity(tableName = "alarm_episodes", indices = [Index(value = ["deviceId", "kind", "endedAtUtcMs"])])
data class AlarmEpisodeEntity(
    @androidx.room.PrimaryKey val id: String,
    val deviceId: String,
    val kind: String,
    val severity: String,
    val source: String?,
    val triggerValue: Double?,
    val latestValue: Double?,
    val thresholdValue: Double?,
    val unit: String,
    val startedAtUtcMs: Long,
    val lastObservedAtUtcMs: Long,
    val triggerSampleUtcMs: Long?,
    val lastSampleUtcMs: Long?,
    val triggerBootId: String?,
    val triggerSeq: Long?,
    val lastBootId: String?,
    val lastSeq: Long?,
    val observedDurationMs: Long,
    val acknowledgedAtUtcMs: Long?,
    val recoveredAtUtcMs: Long?,
    val endedAtUtcMs: Long?,
    val endedReason: String?,
    val verificationPending: Boolean,
    val commandId: String?,
    val detail: String,
    val ruleSignature: String,
    val commandTarget: String? = null,
) {
    fun episode() = AlarmEpisode(id, deviceId, AlarmKind.valueOf(kind), AlarmSeverity.valueOf(severity),
        source?.let(ObservationSource::valueOf), triggerValue, latestValue, thresholdValue, unit,
        startedAtUtcMs, lastObservedAtUtcMs, triggerSampleUtcMs, lastSampleUtcMs, triggerBootId,
        triggerSeq, lastBootId, lastSeq, observedDurationMs, acknowledgedAtUtcMs, recoveredAtUtcMs,
        endedAtUtcMs, endedReason?.let(AlarmEndReason::valueOf), verificationPending, commandId, detail,
        commandTarget?.let(ActuatorKey::valueOf))
}

@Dao
interface AlarmEpisodeDao {
    @Query("SELECT * FROM alarm_episodes ORDER BY startedAtUtcMs DESC, id")
    fun observeAll(): Flow<List<AlarmEpisodeEntity>>

    @Query("SELECT * FROM alarm_episodes ORDER BY startedAtUtcMs DESC, id")
    suspend fun all(): List<AlarmEpisodeEntity>

    @Insert
    suspend fun insert(episode: AlarmEpisodeEntity)

    @Update
    suspend fun update(episode: AlarmEpisodeEntity)
}
