package app.solarma.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Statistics entity for tracking user streaks and achievements.
 */
@Entity(tableName = "stats")
data class StatsEntity(
    // Single row for stats
    @PrimaryKey val id: Int = 1,
    /** Current wake streak (consecutive successful wakes) */
    val currentStreak: Int = 0,
    /** Longest wake streak ever */
    val longestStreak: Int = 0,
    /** Total successful wakes */
    val totalWakes: Int = 0,
    /** Total alarms set */
    val totalAlarms: Int = 0,
    /** Total SOL deposited (in lamports) */
    val totalDeposited: Long = 0,
    /** Total SOL saved (claimed back) */
    val totalSaved: Long = 0,
    /** Total SOL slashed (lost) */
    val totalSlashed: Long = 0,
    /** Total snooze count */
    val totalSnoozes: Int = 0,
    /** Last successful wake timestamp */
    val lastWakeAt: Long? = null,
    /** Profile created timestamp */
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * DAO for stats operations.
 */
@Dao
interface StatsDao {
    @Query("SELECT * FROM stats WHERE id = 1")
    fun getStats(): Flow<StatsEntity?>

    @Query("SELECT * FROM stats WHERE id = 1")
    suspend fun getStatsOnce(): StatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: StatsEntity)

    @Query(
        "UPDATE stats SET currentStreak = currentStreak + 1, " +
            "longestStreak = MAX(longestStreak, currentStreak + 1), " +
            "totalWakes = totalWakes + 1, lastWakeAt = :timestamp WHERE id = 1",
    )
    suspend fun recordSuccessfulWake(timestamp: Long)

    @Query("UPDATE stats SET currentStreak = 0 WHERE id = 1")
    suspend fun resetStreak()

    @Query("UPDATE stats SET totalSnoozes = totalSnoozes + 1 WHERE id = 1")
    suspend fun incrementSnoozes()

    @Query("UPDATE stats SET totalAlarms = totalAlarms + 1 WHERE id = 1")
    suspend fun incrementTotalAlarms()

    @Query("UPDATE stats SET totalDeposited = totalDeposited + :amount WHERE id = 1")
    suspend fun addDeposit(amount: Long)

    @Query("UPDATE stats SET totalSaved = totalSaved + :amount WHERE id = 1")
    suspend fun addSaved(amount: Long)

    @Query("UPDATE stats SET totalSlashed = totalSlashed + :amount WHERE id = 1")
    suspend fun addSlashed(amount: Long)
}
