package com.betterclouddrive.mobile.core.database

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transfer_tasks")
data class TransferTaskEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val status: String,
    val serverBaseUrl: String,
    val teamId: Long?,
    val remoteParentId: Long?,
    val remoteFileId: Long?,
    val remoteResultFileId: Long?,
    val remoteVersionNumber: Int? = null,
    val fileName: String,
    val mimeType: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
    val sourceHandle: String?,
    val destinationHandle: String?,
    val sessionId: String?,
    val chunkSize: Long,
    val totalChunks: Int,
    val md5Hash: String?,
    val retryCount: Int,
    val errorKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface TransferTaskDao {
    @Query("SELECT COUNT(*) FROM transfer_tasks")
    suspend fun count(): Int

    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE id = :id")
    suspend fun find(id: String): TransferTaskEntity?

    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt ASC")
    suspend fun all(): List<TransferTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: TransferTaskEntity)

    @Transaction
    suspend fun upsertAll(values: List<TransferTaskEntity>) {
        values.forEach { upsert(it) }
    }

    @Query("DELETE FROM transfer_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun deleteAllById(ids: List<String>) {
        ids.forEach { delete(it) }
    }

    @Query("DELETE FROM transfer_tasks")
    suspend fun deleteAll()
}

@Database(entities = [TransferTaskEntity::class], version = 2, exportSchema = true)
@ConstructedBy(MobileDatabaseConstructor::class)
abstract class MobileDatabase : RoomDatabase() {
    abstract fun transferTaskDao(): TransferTaskDao

    suspend fun purgeDeletedTransferContent() {
        useWriterConnection { connection ->
            connection.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.execSQL("VACUUM")
        }
    }
}

object MobileDatabaseMigration1To2 : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare("ALTER TABLE transfer_tasks ADD COLUMN remoteVersionNumber INTEGER").use { statement ->
            statement.step()
        }
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object MobileDatabaseConstructor : RoomDatabaseConstructor<MobileDatabase> {
    override fun initialize(): MobileDatabase
}
