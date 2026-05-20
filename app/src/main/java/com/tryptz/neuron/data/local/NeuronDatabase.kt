package com.tryptz.neuron.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tryptz.neuron.data.local.dao.*
import com.tryptz.neuron.data.local.entity.*

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        InstalledModelEntity::class,
        LocalModelEntity::class,
        CodeSnippetEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class NeuronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun installedModelDao(): InstalledModelDao
    abstract fun localModelDao(): LocalModelDao
    abstract fun codeSnippetDao(): CodeSnippetDao

    companion object {
        /**
         * v2 -> v3: adds the index backing `InstalledModelDao.getByDescriptorId`.
         * The index name and shape must match exactly what Room generates for
         * `@Index("descriptorId")` on `installed_models`, or Room's post-migration
         * identity check will fail.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_installed_models_descriptorId` " +
                        "ON `installed_models` (`descriptorId`)"
                )
            }
        }

        /**
         * Ordered schema migrations, wired into the builder via
         * `.addMigrations(*NeuronDatabase.MIGRATIONS)`.
         *
         * To change the schema: bump `version` above, build once so the new
         * `app/schemas/<version>.json` is exported and commit it, then append a
         * `Migration(previous, new)` to this array. There is no `1 -> 2` migration:
         * the app is pre-release (versionCode 1), so no shipped install had schema v1.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_2_3)
    }
}
