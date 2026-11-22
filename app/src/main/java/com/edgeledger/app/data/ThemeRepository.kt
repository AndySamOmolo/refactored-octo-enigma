package com.edgeledger.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.edgeledger.app.model.DevigMethod
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.model.RiskStyle
import com.edgeledger.app.model.StakingMode
import com.edgeledger.app.theme.ThemeCodec
import com.edgeledger.app.theme.ThemeConfigV1
import com.edgeledger.app.theme.ThemePresets
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(
    name = "edgeledger_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher for encrypting [UserApiKeys] at rest.
 * Falls back cleanly to null if Keystore is unavailable (e.g., robolectric/unit test environments).
 */
private object KeystoreAesGcm {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "edgeledger_api_keys_aes256_gcm_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PREFIX = "ENC_GCM_V1:"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val iv = raw.copyOfRange(0, IV_BYTES)
        val ciphertext = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)
}

data class AppUserPrefs(
    val bankroll: Double = 1000.0,
    val paperBankroll: Double = 1000.0,
    val isPaperTrading: Boolean = false,
    val stakingMode: StakingMode = StakingMode.FRACTIONAL_KELLY,
    val riskStyle: RiskStyle = RiskStyle.BALANCED,
    val oddsFormat: OddsDisplayFormat = OddsDisplayFormat.DECIMAL,
    val devigMethod: DevigMethod = DevigMethod.SHIN,
    val minEvPct: Double = 2.0,
    val minProbFloorPct: Int = 20,
    val autoSyncWeekly: Boolean = true,
    val wifiOnlySync: Boolean = false,
    val marketBlendWeight: Double = 0.0,
    val alertsEnabled: Boolean = false,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 7,
    val pinnedTeams: Set<String> = emptySet(),
) {
    fun sanitized(): AppUserPrefs = copy(
        bankroll = bankroll.takeIf { it.isFinite() }?.coerceIn(1.0, 10_000_000.0) ?: 1000.0,
        paperBankroll = paperBankroll.takeIf { it.isFinite() }?.coerceIn(1.0, 10_000_000.0) ?: 1000.0,
        minEvPct = minEvPct.takeIf { it.isFinite() }?.coerceIn(-10.0, 50.0) ?: 2.0,
        minProbFloorPct = minProbFloorPct.coerceIn(1, 95),
        marketBlendWeight = marketBlendWeight.takeIf { it.isFinite() }?.coerceIn(0.0, 0.5) ?: 0.0,
        quietHoursStart = quietHoursStart.coerceIn(0, 23),
        quietHoursEnd = quietHoursEnd.coerceIn(0, 23),
        pinnedTeams = pinnedTeams.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
    )
}

class ThemeAndPrefsRepository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val writeMutex = Mutex()
    private val undoLock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        isLenient = true
    }

    private val KEY_THEME = stringPreferencesKey("theme_json_v1")
    private val KEY_SAVED_THEMES = stringPreferencesKey("saved_custom_themes_v1")
    private val KEY_API_KEYS = stringPreferencesKey("user_api_keys_v1")
    private val KEY_BANKROLL = doublePreferencesKey("bankroll")
    private val KEY_PAPER_BANKROLL = doublePreferencesKey("paper_bankroll")
    private val KEY_IS_PAPER = booleanPreferencesKey("is_paper_trading")
    private val KEY_STAKING_MODE = stringPreferencesKey("staking_mode")
    private val KEY_RISK = stringPreferencesKey("risk_style")
    private val KEY_ODDS_FMT = stringPreferencesKey("odds_format")
    private val KEY_DEVIG = stringPreferencesKey("devig_method")
    private val KEY_MIN_EV = doublePreferencesKey("min_ev_pct")
    private val KEY_MIN_PROB = intPreferencesKey("min_prob_floor")
    private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync_weekly")
    private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_sync")
    private val KEY_BLEND = doublePreferencesKey("market_blend")
    private val KEY_ALERTS = booleanPreferencesKey("alerts_enabled")
    private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
    private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    private val KEY_PINNED = stringPreferencesKey("pinned_teams_json")

    private val _theme = MutableStateFlow(ThemePresets.default)
    val theme: StateFlow<ThemeConfigV1> = _theme.asStateFlow()

    private val _savedThemes = MutableStateFlow<List<ThemeConfigV1>>(emptyList())
    val savedThemes: StateFlow<List<ThemeConfigV1>> = _savedThemes.asStateFlow()

    private val _apiKeys = MutableStateFlow(UserApiKeys())
    val apiKeys: StateFlow<UserApiKeys> = _apiKeys.asStateFlow()

    private val _prefs = MutableStateFlow(AppUserPrefs())
    val prefs: StateFlow<AppUserPrefs> = _prefs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val undoStack = ArrayDeque<ThemeConfigV1>()
    private val redoStack = ArrayDeque<ThemeConfigV1>()

    private val _canUndo = MutableStateFlow(false)
    val canUndoFlow: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedoFlow: StateFlow<Boolean> = _canRedo.asStateFlow()

    init {
        scope.launch {
            try {
                val p = appContext.dataStore.data.first()
                p[KEY_THEME]?.let { ThemeCodec.decodeOrNull(it) }?.let { _theme.value = it }

                // Decode each saved theme individually via ThemeCodec so one corrupt entry never wipes all
                p[KEY_SAVED_THEMES]?.let { raw ->
                    runCatching {
                        val arr = json.parseToJsonElement(raw) as? JsonArray
                        arr?.mapNotNull { elem -> ThemeCodec.decodeOrNull(elem.toString()) } ?: emptyList()
                    }.onSuccess { _savedThemes.value = it }
                }

                // Decrypt Keystore AES-256-GCM API keys (with one-time migration from legacy plaintext)
                p[KEY_API_KEYS]?.let { raw ->
                    val decrypted = if (KeystoreAesGcm.isEncrypted(raw)) {
                        runCatching { KeystoreAesGcm.decrypt(raw) }.getOrNull()
                    } else {
                        raw
                    }
                    if (!decrypted.isNullOrBlank()) {
                        runCatching { json.decodeFromString(UserApiKeys.serializer(), decrypted) }
                            .onSuccess { keys ->
                                _apiKeys.value = keys
                                if (!KeystoreAesGcm.isEncrypted(raw)) {
                                    // Migrate legacy plaintext keys into Keystore AES-256-GCM immediately
                                    persistApiKeysLocked(keys)
                                }
                            }
                    }
                }

                val pinned = p[KEY_PINNED]?.let { raw ->
                    runCatching { json.decodeFromString(SetSerializer(String.serializer()), raw) }.getOrNull()
                } ?: emptySet()

                _prefs.value = AppUserPrefs(
                    bankroll = p[KEY_BANKROLL] ?: 1000.0,
                    paperBankroll = p[KEY_PAPER_BANKROLL] ?: 1000.0,
                    isPaperTrading = p[KEY_IS_PAPER] ?: false,
                    stakingMode = runCatching { StakingMode.valueOf(p[KEY_STAKING_MODE] ?: "FRACTIONAL_KELLY") }.getOrDefault(StakingMode.FRACTIONAL_KELLY),
                    riskStyle = runCatching { RiskStyle.valueOf(p[KEY_RISK] ?: "BALANCED") }.getOrDefault(RiskStyle.BALANCED),
                    oddsFormat = runCatching { OddsDisplayFormat.valueOf(p[KEY_ODDS_FMT] ?: "DECIMAL") }.getOrDefault(OddsDisplayFormat.DECIMAL),
                    devigMethod = runCatching { DevigMethod.valueOf(p[KEY_DEVIG] ?: "SHIN") }.getOrDefault(DevigMethod.SHIN),
                    minEvPct = p[KEY_MIN_EV] ?: 2.0,
                    minProbFloorPct = p[KEY_MIN_PROB] ?: 20,
                    autoSyncWeekly = p[KEY_AUTO_SYNC] ?: true,
                    wifiOnlySync = p[KEY_WIFI_ONLY] ?: false,
                    marketBlendWeight = p[KEY_BLEND] ?: 0.0,
                    alertsEnabled = p[KEY_ALERTS] ?: false,
                    quietHoursStart = p[KEY_QUIET_START] ?: 22,
                    quietHoursEnd = p[KEY_QUIET_END] ?: 7,
                    pinnedTeams = pinned,
                ).sanitized()
            } catch (_: Exception) {
                // Keep safe defaults; never freeze splash screen
            } finally {
                _loaded.value = true
            }
        }
    }

    /** Suspends until initial DataStore hydration has completed. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private fun refreshUndoFlagsLocked() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun updateTheme(transform: (ThemeConfigV1) -> ThemeConfigV1) {
        val next = synchronized(undoLock) {
            val current = _theme.value
            val candidate = transform(current).sanitized()
            if (candidate == current) return
            undoStack.addLast(current)
            if (undoStack.size > 20) undoStack.removeFirst()
            redoStack.clear()
            refreshUndoFlagsLocked()
            _theme.value = candidate
            candidate
        }
        persistTheme(next)
    }

    fun undoTheme() {
        val prev = synchronized(undoLock) {
            if (undoStack.isEmpty()) return
            val p = undoStack.removeLast()
            redoStack.addLast(_theme.value)
            refreshUndoFlagsLocked()
            _theme.value = p
            p
        }
        persistTheme(prev)
    }

    fun redoTheme() {
        val next = synchronized(undoLock) {
            if (redoStack.isEmpty()) return
            val n = redoStack.removeLast()
            undoStack.addLast(_theme.value)
            refreshUndoFlagsLocked()
            _theme.value = n
            n
        }
        persistTheme(next)
    }

    fun resetTheme() {
        updateTheme { ThemePresets.default }
    }

    fun saveCustomTheme(customName: String) {
        val cleanName = customName.trim().ifBlank { "My Custom Theme ${_savedThemes.value.size + 1}" }
        val custom = _theme.value.copy(
            id = "custom-${UUID.randomUUID()}",
            name = cleanName,
        ).sanitized()
        val updated = (listOf(custom) + _savedThemes.value).take(16)
        _savedThemes.value = updated
        persistSavedThemes(updated)
    }

    fun duplicateCustomTheme(id: String) {
        val src = _savedThemes.value.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = "custom-${UUID.randomUUID()}",
            name = "${src.name} Copy",
        ).sanitized()
        val updated = (listOf(copy) + _savedThemes.value).take(16)
        _savedThemes.value = updated
        persistSavedThemes(updated)
    }

    fun deleteCustomTheme(id: String) {
        val updated = _savedThemes.value.filterNot { it.id == id }
        _savedThemes.value = updated
        persistSavedThemes(updated)
    }

    fun importThemeJson(raw: String): Boolean {
        if (raw.length > 131_072) return false
        val parsed = ThemeCodec.decodeOrNull(raw) ?: return false
        updateTheme { parsed }
        return true
    }

    fun exportThemeJson(): String = ThemeCodec.encode(_theme.value)

    private fun persistTheme(config: ThemeConfigV1) {
        scope.launch {
            awaitLoaded()
            writeMutex.withLock {
                appContext.dataStore.edit { it[KEY_THEME] = ThemeCodec.encode(config) }
            }
        }
    }

    private fun persistSavedThemes(list: List<ThemeConfigV1>) {
        scope.launch {
            awaitLoaded()
            writeMutex.withLock {
                val encoded = json.encodeToString(ListSerializer(ThemeConfigV1.serializer()), list)
                appContext.dataStore.edit { it[KEY_SAVED_THEMES] = encoded }
            }
        }
    }

    private suspend fun persistApiKeysLocked(keys: UserApiKeys) {
        writeMutex.withLock {
            val encodedJson = json.encodeToString(UserApiKeys.serializer(), keys)
            val encrypted = KeystoreAesGcm.encrypt(encodedJson)
            appContext.dataStore.edit { it[KEY_API_KEYS] = encrypted }
        }
    }

    fun updateApiKeys(transform: (UserApiKeys) -> UserApiKeys) {
        var nextKeys = UserApiKeys()
        _apiKeys.update { current ->
            transform(current).also { nextKeys = it }
        }
        scope.launch {
            awaitLoaded()
            persistApiKeysLocked(nextKeys)
        }
    }

    fun togglePinTeam(teamName: String) {
        val clean = teamName.trim()
        if (clean.isBlank()) return
        updatePrefs { current ->
            val updated = if (clean in current.pinnedTeams) current.pinnedTeams - clean else current.pinnedTeams + clean
            current.copy(pinnedTeams = updated)
        }
    }

    fun updatePrefs(transform: (AppUserPrefs) -> AppUserPrefs) {
        var nextPrefs = AppUserPrefs()
        _prefs.update { current ->
            transform(current).sanitized().also { nextPrefs = it }
        }
        scope.launch {
            awaitLoaded()
            writeMutex.withLock {
                val pinnedJson = json.encodeToString(SetSerializer(String.serializer()), nextPrefs.pinnedTeams)
                appContext.dataStore.edit {
                    it[KEY_BANKROLL] = nextPrefs.bankroll
                    it[KEY_PAPER_BANKROLL] = nextPrefs.paperBankroll
                    it[KEY_IS_PAPER] = nextPrefs.isPaperTrading
                    it[KEY_STAKING_MODE] = nextPrefs.stakingMode.name
                    it[KEY_RISK] = nextPrefs.riskStyle.name
                    it[KEY_ODDS_FMT] = nextPrefs.oddsFormat.name
                    it[KEY_DEVIG] = nextPrefs.devigMethod.name
                    it[KEY_MIN_EV] = nextPrefs.minEvPct
                    it[KEY_MIN_PROB] = nextPrefs.minProbFloorPct
                    it[KEY_AUTO_SYNC] = nextPrefs.autoSyncWeekly
                    it[KEY_WIFI_ONLY] = nextPrefs.wifiOnlySync
                    it[KEY_BLEND] = nextPrefs.marketBlendWeight
                    it[KEY_ALERTS] = nextPrefs.alertsEnabled
                    it[KEY_QUIET_START] = nextPrefs.quietHoursStart
                    it[KEY_QUIET_END] = nextPrefs.quietHoursEnd
                    it[KEY_PINNED] = pinnedJson
                }
            }
            // Keep WorkManager constraints synchronized with autoSyncWeekly / wifiOnlySync
            WeeklySyncWorker.scheduleWeekly(
                context = appContext,
                enabled = nextPrefs.autoSyncWeekly,
                wifiOnly = nextPrefs.wifiOnlySync,
            )
        }
    }
}
