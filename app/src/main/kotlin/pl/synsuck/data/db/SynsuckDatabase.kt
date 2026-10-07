package pl.synsuck.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant

@Database(
    entities = [AccountEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class SynsuckDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(context: Context): SynsuckDatabase =
            Room.databaseBuilder(context, SynsuckDatabase::class.java, "synsuck.db").build()
    }
}

class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
