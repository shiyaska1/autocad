package com.sketchdxf.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(
    entities = [SketchWork::class, SketchSource::class, SketchShape::class, SketchBlock::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sketchDao(): SketchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Real migrations for schema changes that have already shipped, so upgrading the app
        // doesn't silently delete every sketch someone's already saved. fallbackToDestructiveMigration
        // stays on as a safety net only for a future version jump that isn't covered here.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN path TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_works ADD COLUMN unit TEXT NOT NULL DEFAULT 'mm'")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN color INTEGER")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN major INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sketch_blocks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`shapesData` TEXT NOT NULL, " +
                        "`pxPerMm` REAL NOT NULL)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN fontSize REAL NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN strokeWidth REAL NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_shapes ADD COLUMN dimOffset REAL NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sketch_works ADD COLUMN baseImagePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sketch_works ADD COLUMN backgroundCleared INTEGER NOT NULL DEFAULT 0")
            }
        }

        const val DB_FILE_NAME = "sketch_dxf.db"

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_FILE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }

        /** Closes the open connection (checkpointing its WAL file) and drops the cached instance,
         *  so the underlying .db file on disk is safe to read or overwrite — used by
         *  [com.sketchdxf.app.data.BackupManager] around an export/import. [get] transparently
         *  reopens a fresh connection the next time anything touches the database. */
        fun closeAndReset() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
