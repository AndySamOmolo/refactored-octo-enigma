package com.edgeledger.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.dataStore by preferencesDataStore(name = "edgeledger_prefs")

data class AppUserPrefs(
    val bankroll: Double = 1000.0,
    val riskStyle: RiskStyle = RiskStyle.BALANCED,
    val oddsFormat: OddsDisplayFormat = OddsDisplayFormat.DECIMAL,
    val autoSyncWeekly: Boolean = true,
    val marketBlendWeight: Double = 0.0, // PR-10: 0..0.5 blend with sharp market
)

class ThemeAndPrefsRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val KEY_THEME = stringPreferencesKey("theme_json_v1")
    private val KEY_BANKROLL = doublePreferencesKey("bankroll")
    private val KEY_RISK = stringPreferencesKey("risk_style")
    private val KEY_ODDS_FMT = stringPreferencesKey("odds_format")
    private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync_weekly")
    private val KEY_BLEND = doublePreferencesKey("market_blend")

    private val _theme = MutableStateFlow(ThemePresets.ALL.first())
    val theme: StateFlow<ThemeConfigV1> = _theme.asStateFlow()

    private val _prefs = MutableStateFlow(AppUserPrefs())
    val prefs: StateFlow<AppUserPrefs> = _prefs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // Multi-step Undo/Redo stack (ED-04)
    private val undoStack = ArrayDeque<ThemeConfigV1>()
    private val redoStack = ArrayDeque<ThemeConfigV1>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    init {
        scope.launch {
            val p = context.dataStore.data.first()
            p[KEY_THEME]?.let { ThemeCodec.decodeOrNull(it) }?.let { _theme.value = it }
            _prefs.value = AppUserPrefs(
                bankroll = p[KEY_BANKROLL] ?: 1000.0,
                riskStyle = runCatching { RiskStyle.valueOf(p[KEY_RISK] ?: "BALANCED") }.getOrDefault(RiskStyle.BALANCED),
                oddsFormat = runCatching { OddsDisplayFormat.valueOf(p[KEY_ODDS_FMT] ?: "DECIMAL") }.getOrDefault(OddsDisplayFormat.DECIMAL),
                autoSyncWeekly = p[KEY_AUTO_SYNC] ?: true,
                marketBlendWeight = (p[KEY_BLEND] ?: 0.0).coerceIn(0.0, 0.5),
            )
            _loaded.value = true
        }
    }

    fun updateTheme(transform: (ThemeConfigV1) -> ThemeConfigV1) {
        val current = _theme.value
        val next = transform(current)
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > 20) undoStack.removeFirst()
        redoStack.clear()
        _theme.value = next
        persistTheme(next)
    }

    fun undoTheme() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeLast()
        redoStack.addLast(_theme.value)
        _theme.value = prev
        persistTheme(prev)
    }

    fun redoTheme() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeLast()
        undoStack.addLast(_theme.value)
        _theme.value = next
        persistTheme(next)
    }

    fun resetTheme() {
        updateTheme { ThemePresets.ALL.first() }
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

    fun updatePrefs(transform: (AppUserPrefs) -> AppUserPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        scope.launch {
            context.dataStore.edit {
                it[KEY_BANKROLL] = next.bankroll
                it[KEY_RISK] = next.riskStyle.name
                it[KEY_ODDS_FMT] = next.oddsFormat.name
                it[KEY_AUTO_SYNC] = next.autoSyncWeekly
                it[KEY_BLEND] = next.marketBlendWeight
            }
        }
    }
}
