package com.edgeledger.app

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class EdgeLedgerApp : Application(), ImageLoaderFactory {
    lateinit var database: AppDatabase
        private set
    lateinit var prefsRepo: ThemeAndPrefsRepository
        private set
    lateinit var syncRepo: SyncRepository
        private set

    private val _recentDiagnostics = MutableStateFlow<List<String>>(emptyList())
    val recentDiagnostics: StateFlow<List<String>> = _recentDiagnostics.asStateFlow()

    private val diagTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

    fun recordDiagnostic(tag: String, message: String) {
        val stamp = diagTimeFormatter.format(Instant.now())
        val entry = "[$stamp] $tag: ${message.take(180)}"
        _recentDiagnostics.update { current -> (listOf(entry) + current).take(20) }
    }

    fun clearDiagnostics() {
        _recentDiagnostics.value = emptyList()
    }

    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e("EdgeLedgerApp", "Uncaught background coroutine error", throwable)
            recordDiagnostic(
                "AppScope",
                "${throwable::class.simpleName ?: "Error"}: ${throwable.message ?: "no details"}",
            )
        },
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
                replaceExisting = false,
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
