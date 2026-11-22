package com.edgeledger.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.edgeledger.app.data.AppDatabase
import com.edgeledger.app.data.SyncRepository
import com.edgeledger.app.data.ThemeAndPrefsRepository
import com.edgeledger.app.data.WeeklySyncWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EdgeLedgerApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
        private set
    lateinit var prefsRepo: ThemeAndPrefsRepository
        private set
    lateinit var syncRepo: SyncRepository
        private set

    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> },
    )

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        prefsRepo = ThemeAndPrefsRepository(this)
        syncRepo = SyncRepository(this, database, prefsRepo)

        appScope.launch {
            prefsRepo.awaitLoaded()
            syncRepo.hydrateFromRoom()
            val p = prefsRepo.prefs.value
            WeeklySyncWorker.scheduleWeekly(
                context = this@EdgeLedgerApp,
                enabled = p.autoSyncWeekly,
                wifiOnly = p.wifiOnlySync,
            )
        }
    }

    /**
     * Single app-wide Coil ImageLoader with SVG support and bounded caches.
     * Prevents per-composable ImageLoader/OkHttpClient leaks in LazyColumn rows.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_logo_cache"))
                    .maxSizeBytes(32L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
}
