package com.smsgatewayagent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Process-wide SQLite helper. A single instance is shared so the BroadcastReceiver,
 * WorkManager worker and the React Native bridge all read/write the same connection pool.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, Schema.DB_NAME, null, Schema.DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            Schema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Forward-only migrations (SQLiteOpenHelper already runs this inside a transaction). Every
        // statement in CREATE_STATEMENTS is `IF NOT EXISTS`, so re-running the full set is
        // idempotent for existing tables and simply creates the newly-added ones (v2: sim_config,
        // services). Column-adding ALTERs for future versions go behind an `if (oldVersion < N)`.
        if (oldVersion < 2) {
            Schema.CREATE_STATEMENTS.forEach { db.execSQL(it) }
        }
    }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
    }
}
