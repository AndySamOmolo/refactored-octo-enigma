package com.edgeledger.app

import android.app.Application
import com.edgeledger.app.data.AppDatabase
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.data.ThemeAndPrefsRepository
import com.edgeledger.app.data.WeeklySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EdgeLedgerApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var prefsRepo: ThemeAndPrefsRepository
        private set
    lateinit var syncRepo: SyncRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        prefsRepo = ThemeAndPrefsRepository(this)
        syncRepo = SyncRepository(this, database, prefsRepo)

        appScope.launch {
            syncRepo.hydrateFromRoom()
        }
        WeeklySyncWorker.scheduleWeekly(this)
    }
}
