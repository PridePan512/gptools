package com.example.gptest.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WatchlistTabEntity::class,
        WatchlistStock::class,
        AlertRuleEntity::class,
        AlertConditionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS alert_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        matchMode TEXT NOT NULL,
                        notifyMode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lastTriggeredMs INTEGER,
                        onceConsumed INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS alert_conditions (
                        id TEXT NOT NULL PRIMARY KEY,
                        ruleId TEXT NOT NULL,
                        stockCode TEXT NOT NULL,
                        stockName TEXT NOT NULL,
                        metric TEXT NOT NULL,
                        operator TEXT NOT NULL,
                        numberValue TEXT NOT NULL,
                        compareCode TEXT,
                        compareName TEXT,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(ruleId) REFERENCES alert_rules(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_alert_conditions_ruleId ON alert_conditions(ruleId)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchlist_tabs (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        locked INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO watchlist_tabs (id, name, sortOrder, locked)
                    VALUES ('default', '自选股', 0, 1)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchlist_new (
                        tabId TEXT NOT NULL,
                        code TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        PRIMARY KEY(tabId, code),
                        FOREIGN KEY(tabId) REFERENCES watchlist_tabs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO watchlist_new (tabId, code, sortOrder)
                    SELECT 'default', code, sortOrder FROM watchlist
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE watchlist")
                db.execSQL("ALTER TABLE watchlist_new RENAME TO watchlist")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watchlist_tabId ON watchlist(tabId)")
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gptest.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
