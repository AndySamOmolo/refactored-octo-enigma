package com.edgeledger.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.edgeledger.app.engine.DevigMethod
import com.edgeledger.app.engine.StakingMode
import com.edgeledger.app.model.OddsDisplayFormat
import com.edgeledger.app.model.RiskStyle
import com.edgeledger.app.theme.ThemeCodec
import com.edgeledger.app.theme.ThemeConfigV1
import com.edgeledger.app.theme.ThemePresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "edgeledger_prefs")

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
    val alertsEnabled: Boolean = true,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 7,
    val pinnedTeams: Set<String> = emptySet(),
)

class ThemeAndPrefsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    init {
        scope.launch {
            val p = context.dataStore.data.first()
            p[KEY_THEME]?.let { ThemeCodec.decodeOrNull(it) }?.let { _theme.value = it }
            p[KEY_SAVED_THEMES]?.let { raw ->
                runCatching { json.decodeFromString(ListSerializer(ThemeConfigV1.serializer()), raw) }
                    .onSuccess { _savedThemes.value = it }
            }
            p[KEY_API_KEYS]?.let { raw ->
                runCatching { json.decodeFromString(UserApiKeys.serializer(), raw) }
                    .onSuccess { _apiKeys.value = it }
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
                marketBlendWeight = (p[KEY_BLEND] ?: 0.0).coerceIn(0.0, 0.5),
                alertsEnabled = p[KEY_ALERTS] ?: true,
                quietHoursStart = p[KEY_QUIET_START] ?: 22,
                quietHoursEnd = p[KEY_QUIET_END] ?: 7,
                pinnedTeams = pinned,
            )
            _loaded.value = true
        }
    }

    private fun refreshUndoFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun updateTheme(transform: (ThemeConfigV1) -> ThemeConfigV1) {
        val current = _theme.value
        val next = transform(current).sanitized()
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > 20) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoFlags()
        _theme.value = next
        persistTheme(next)
    }

    fun undoTheme() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.addLast(_theme.value)
        refreshUndoFlags()
        _theme.value = prev
        persistTheme(prev)
    }

    fun redoTheme() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(_theme.value)
        refreshUndoFlags()
        _theme.value = next
        persistTheme(next)
    }

    fun resetTheme() {
        updateTheme { ThemePresets.default }
    }

    fun saveCustomTheme(customName: String) {
        val cleanName = customName.trim().ifBlank { "My Custom Theme ${_savedThemes.value.size + 1}" }
        val custom = _theme.value.copy(
            id = "custom-${System.currentTimeMillis()}",
            name = cleanName,
        ).sanitized()
        val updated = (listOf(custom) + _savedThemes.value).take(16)
        _savedThemes.value = updated
        persistSavedThemes(updated)
    }

    fun duplicateCustomTheme(id: String) {
        val src = _savedThemes.value.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = "custom-${System.currentTimeMillis()}",
            name = "${src.name} Copy",
        )
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
        val parsed = ThemeCodec.decodeOrNull(raw) ?: return false
        updateTheme { parsed }
        return true
    }

    fun exportThemeJson(): String = ThemeCodec.encode(_theme.value)

    private fun persistTheme(config: ThemeConfigV1) {
        scope.launch {
            context.dataStore.edit { it[KEY_THEME] = ThemeCodec.encode(config) }
        }
    }

    private fun persistSavedThemes(list: List<ThemeConfigV1>) {
        scope.launch {
            val encoded = json.encodeToString(ListSerializer(ThemeConfigV1.serializer()), list)
            context.dataStore.edit { it[KEY_SAVED_THEMES] = encoded }
        }
    }

    fun updateApiKeys(transform: (UserApiKeys) -> UserApiKeys) {
        val next = transform(_apiKeys.value)
        _apiKeys.value = next
        scope.launch {
            val encoded = json.encodeToString(UserApiKeys.serializer(), next)
            context.dataStore.edit { it[KEY_API_KEYS] = encoded }
        }
    }

    fun togglePinTeam(teamName: String) {
        val current = _prefs.value.pinnedTeams
        val updated = if (teamName in current) current - teamName else current + teamName
        updatePrefs { it.copy(pinnedTeams = updated) }
    }

    fun updatePrefs(transform: (AppUserPrefs) -> AppUserPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        scope.launch {
            val pinnedJson = json.encodeToString(SetSerializer(String.serializer()), next.pinnedTeams)
            context.dataStore.edit {
                it[KEY_BANKROLL] = next.bankroll
                it[KEY_PAPER_BANKROLL] = next.paperBankroll
                it[KEY_IS_PAPER] = next.isPaperTrading
                it[KEY_STAKING_MODE] = next.stakingMode.name
                it[KEY_RISK] = next.riskStyle.name
                it[KEY_ODDS_FMT] = next.oddsFormat.name
                it[KEY_DEVIG] = next.devigMethod.name
                it[KEY_MIN_EV] = next.minEvPct
                it[KEY_MIN_PROB] = next.minProbFloorPct
                it[KEY_AUTO_SYNC] = next.autoSyncWeekly
                it[KEY_WIFI_ONLY] = next.wifiOnlySync
                it[KEY_BLEND] = next.marketBlendWeight
                it[KEY_ALERTS] = next.alertsEnabled
                it[KEY_QUIET_START] = next.quietHoursStart
                it[KEY_QUIET_END] = next.quietHoursEnd
                it[KEY_PINNED] = pinnedJson
            }
        }
    }
}
