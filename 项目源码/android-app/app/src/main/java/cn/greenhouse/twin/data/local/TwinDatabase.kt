package cn.greenhouse.twin.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.greenhouse.twin.data.history.HistoryRecordCodec
import cn.greenhouse.twin.data.monitoring.AlarmEpisodeEntity
import cn.greenhouse.twin.data.monitoring.AlarmEpisodeDao
import cn.greenhouse.twin.domain.StateSource
import kotlinx.coroutines.flow.Flow

// 设备原始时间、云端接收时间和 APP 获取时间分别保存，不互相替代。
@Entity(
    tableName = "twin_records",
    primaryKeys = ["deviceId", "bootId", "kind", "seq"],
    indices = [Index(value = ["deviceId", "kind", "cloudReceivedAtUtcMs"]),
        Index(value = ["deviceId", "kind", "recordUtcMs"])],
)
data class TwinRecordEntity(
    val deviceId: String,
    val bootId: String,
    val kind: String,
    val seq: Long,
    val stateVersion: Long,
    val configVersion: Int,
    val modelVersion: Int,
    val recordUtcMs: Long?,
    val uptimeMs: Long,
    val cloudReceivedAtUtcMs: Long?,
    val appReceivedAtUtcMs: Long?,
    val payloadJson: String,
    val stateSource: String = StateSource.LIVE.name,
    val payloadEncoding: String = HistoryRecordCodec.COMPACT,
    val normalizedCoreJson: String = "",
    val timeQuality: String = if (recordUtcMs == null) "UNSYNCED" else "SYNCED",
    val archiveReceivedAtUtcMs: Long? = null,
    val archiveName: String? = null,
    val importedAtUtcMs: Long? = null,
) {
    init {
        require(stateSource in setOf(StateSource.LIVE.name, "ARCHIVE")) { "正式历史不接受设计样例或回放状态" }
        require(deviceId.isNotBlank() && bootId.isNotBlank() && kind.isNotBlank()) {
            "记录复合标识不能为空"
        }
        require(seq >= 0 && stateVersion >= 0 && configVersion >= 0 && modelVersion >= 0 && uptimeMs >= 0) {
            "记录序号、版本或开机时间不正确"
        }
        require(recordUtcMs == null || recordUtcMs >= 0) { "设备 UTC 时间必须有效或为空" }
        require(cloudReceivedAtUtcMs == null || cloudReceivedAtUtcMs > 0) { "云接收时间必须有效或为空" }
        require(appReceivedAtUtcMs == null || appReceivedAtUtcMs > 0) { "APP接收时间必须有效或为空" }
        require(stateSource != StateSource.LIVE.name || (cloudReceivedAtUtcMs != null && appReceivedAtUtcMs != null)) {
            "LIVE记录必须保留云端和APP实际接收时间"
        }
        require(stateSource != "ARCHIVE" || (cloudReceivedAtUtcMs == null && appReceivedAtUtcMs == null &&
            archiveReceivedAtUtcMs != null && archiveReceivedAtUtcMs > 0 && archiveName?.isNotBlank() == true &&
            importedAtUtcMs != null && importedAtUtcMs > 0)) { "串口档案须保留真实归档来源和接收/导入时间，不冒充云接收" }
        require(payloadJson.isNotBlank()) { "记录原始内容不能为空" }
    }
}

// 同身份的档案表示单独留存；主记录去重不等于删除已核实的串口原内容与来源。
@Entity(tableName = "twin_archive_representations",
    primaryKeys = ["deviceId", "bootId", "kind", "seq", "fileSha256", "rowNumber"])
data class TwinArchiveRepresentationEntity(
    val deviceId: String, val bootId: String, val kind: String, val seq: Long,
    val fileSha256: String, val rowNumber: Int, val payloadJson: String,
    val archiveReceivedAtUtcMs: Long, val archiveName: String, val importedAtUtcMs: Long,
)

@Dao
interface TwinArchiveRepresentationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(value: TwinArchiveRepresentationEntity): Long

    @Query("SELECT * FROM twin_archive_representations WHERE deviceId=:deviceId AND bootId=:bootId AND kind=:kind AND seq=:seq")
    suspend fun identity(deviceId: String, bootId: String, kind: String, seq: Long): List<TwinArchiveRepresentationEntity>

    @Query("SELECT COUNT(*) FROM twin_archive_representations")
    suspend fun count(): Int
}

@Dao
abstract class TwinRecordDao {
    // 同键忽略只用于去重，异内容冲突由 Repository 在读取已存内容后明确处理。
    suspend fun insertOrIgnore(record: TwinRecordEntity): Long {
        return insertLiveRecord(HistoryRecordCodec.normalize(record))
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLiveRecord(record: TwinRecordEntity): Long

    @Query(
        "SELECT * FROM twin_records WHERE deviceId = :deviceId AND bootId = :bootId " +
            "AND kind = :kind AND seq = :seq LIMIT 1",
    )
    abstract suspend fun findIdentity(deviceId: String, bootId: String, kind: String, seq: Long): TwinRecordEntity?

    @Query("SELECT COUNT(*) FROM twin_records")
    abstract fun count(): Flow<Int>

    @Query(
        "SELECT * FROM twin_records WHERE deviceId = :deviceId AND kind = 'snapshot' " +
            "ORDER BY cloudReceivedAtUtcMs DESC, appReceivedAtUtcMs DESC LIMIT 1",
    )
    abstract suspend fun latestSnapshot(deviceId: String): TwinRecordEntity?

    @Query("SELECT DISTINCT deviceId FROM twin_records ORDER BY deviceId")
    abstract fun devices(): Flow<List<String>>

    @Query("SELECT * FROM twin_records WHERE deviceId = :deviceId AND kind = :kind " +
        "AND recordUtcMs >= :startUtcMs AND recordUtcMs < :endUtcMs ORDER BY recordUtcMs, bootId, seq")
    abstract suspend fun range(deviceId: String, kind: String, startUtcMs: Long, endUtcMs: Long): List<TwinRecordEntity>

    @Query("SELECT * FROM twin_records WHERE deviceId = :deviceId AND kind = 'snapshot' " +
        "AND recordUtcMs < :utcMs ORDER BY recordUtcMs DESC LIMIT 1")
    abstract suspend fun snapshotBefore(deviceId: String, utcMs: Long): TwinRecordEntity?

    @Query("SELECT * FROM twin_records WHERE deviceId = :deviceId AND kind = 'snapshot' " +
        "AND recordUtcMs >= :utcMs ORDER BY recordUtcMs LIMIT 1")
    abstract suspend fun snapshotAfter(deviceId: String, utcMs: Long): TwinRecordEntity?

    @Query("SELECT COUNT(*) FROM twin_records WHERE deviceId = :deviceId AND recordUtcMs IS NULL")
    abstract suspend fun unknownUtcCount(deviceId: String): Int

    @Query("SELECT MIN(recordUtcMs) FROM twin_records WHERE deviceId = :deviceId")
    abstract suspend fun firstUtc(deviceId: String): Long?

    @Query("SELECT MAX(recordUtcMs) FROM twin_records WHERE deviceId = :deviceId")
    abstract suspend fun lastUtc(deviceId: String): Long?
}

@Database(entities = [TwinRecordEntity::class, TwinArchiveRepresentationEntity::class, AlarmEpisodeEntity::class], version = 3, exportSchema = false)
abstract class TwinDatabase : RoomDatabase() {
    abstract fun records(): TwinRecordDao
    abstract fun representations(): TwinArchiveRepresentationDao
    abstract fun alarms(): AlarmEpisodeDao

    companion object {
        @Volatile
        private var instance: TwinDatabase? = null

        fun open(context: Context): TwinDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TwinDatabase::class.java,
                "greenhouse-live.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        // 仅新增本地报警表，已有真实设备记录与档案表示原样保留。
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS alarm_episodes (id TEXT NOT NULL, deviceId TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, severity TEXT NOT NULL, source TEXT, triggerValue REAL, latestValue REAL, " +
                    "thresholdValue REAL, unit TEXT NOT NULL, startedAtUtcMs INTEGER NOT NULL, lastObservedAtUtcMs INTEGER NOT NULL, " +
                    "triggerSampleUtcMs INTEGER, lastSampleUtcMs INTEGER, triggerBootId TEXT, triggerSeq INTEGER, lastBootId TEXT, " +
                    "lastSeq INTEGER, observedDurationMs INTEGER NOT NULL, acknowledgedAtUtcMs INTEGER, recoveredAtUtcMs INTEGER, " +
                    "endedAtUtcMs INTEGER, endedReason TEXT, verificationPending INTEGER NOT NULL, commandId TEXT, " +
                    "detail TEXT NOT NULL, ruleSignature TEXT NOT NULL, commandTarget TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alarm_episodes_deviceId_kind_endedAtUtcMs ON alarm_episodes(deviceId,kind,endedAtUtcMs)")
            }
        }

        // 真实历史逐行保留并规范化；迁移失败直接报告，不删除数据库重新建库。
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE twin_records_v2 (deviceId TEXT NOT NULL, bootId TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, seq INTEGER NOT NULL, stateVersion INTEGER NOT NULL, configVersion INTEGER NOT NULL, " +
                    "modelVersion INTEGER NOT NULL, recordUtcMs INTEGER, uptimeMs INTEGER NOT NULL, cloudReceivedAtUtcMs INTEGER, " +
                    "appReceivedAtUtcMs INTEGER, payloadJson TEXT NOT NULL, stateSource TEXT NOT NULL, payloadEncoding TEXT NOT NULL, " +
                    "normalizedCoreJson TEXT NOT NULL, timeQuality TEXT NOT NULL, archiveReceivedAtUtcMs INTEGER, archiveName TEXT, " +
                    "importedAtUtcMs INTEGER, PRIMARY KEY(deviceId, bootId, kind, seq))")
                db.query("SELECT * FROM twin_records").use { cursor ->
                    fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                    fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                    while (cursor.moveToNext()) {
                        val utcColumn = cursor.getColumnIndexOrThrow("recordUtcMs")
                        val original = TwinRecordEntity(text("deviceId"), text("bootId"), text("kind"), number("seq"),
                            number("stateVersion"), number("configVersion").toInt(), number("modelVersion").toInt(),
                            if (cursor.isNull(utcColumn)) null else cursor.getLong(utcColumn), number("uptimeMs"),
                            number("cloudReceivedAtUtcMs"), number("appReceivedAtUtcMs"), text("payloadJson"), text("stateSource"))
                        val value = HistoryRecordCodec.normalize(original)
                        db.execSQL("INSERT INTO twin_records_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(
                            value.deviceId, value.bootId, value.kind, value.seq, value.stateVersion, value.configVersion,
                            value.modelVersion, value.recordUtcMs, value.uptimeMs, value.cloudReceivedAtUtcMs,
                            value.appReceivedAtUtcMs, value.payloadJson, value.stateSource, value.payloadEncoding,
                            value.normalizedCoreJson, value.timeQuality, null, null, null))
                    }
                }
                db.execSQL("DROP TABLE twin_records")
                db.execSQL("ALTER TABLE twin_records_v2 RENAME TO twin_records")
                db.execSQL("CREATE INDEX index_twin_records_deviceId_kind_cloudReceivedAtUtcMs ON twin_records(deviceId,kind,cloudReceivedAtUtcMs)")
                db.execSQL("CREATE INDEX index_twin_records_deviceId_kind_recordUtcMs ON twin_records(deviceId,kind,recordUtcMs)")
                db.execSQL("CREATE TABLE twin_archive_representations (deviceId TEXT NOT NULL, bootId TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, seq INTEGER NOT NULL, fileSha256 TEXT NOT NULL, rowNumber INTEGER NOT NULL, " +
                    "payloadJson TEXT NOT NULL, archiveReceivedAtUtcMs INTEGER NOT NULL, archiveName TEXT NOT NULL, " +
                    "importedAtUtcMs INTEGER NOT NULL, PRIMARY KEY(deviceId,bootId,kind,seq,fileSha256,rowNumber))")
            }
        }
    }
}
