package dev.octaneui.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.octaneui.container.BackendChoice
import dev.octaneui.image.MobileDataPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App font families (all SIL OFL 1.1, variable fonts with a weight axis). */
enum class AppFont(val displayName: String) {
    INTER("Inter"), GOOGLE_SANS_FLEX("Google Sans Flex"), JETBRAINS_MONO("JetBrains Mono"),
}

/** Look of the floating navigation bar. The geometry is identical for all three; only the surface differs. */
enum class NavBarStyle { FROSTED_GLASS, GLASS, SOLID }

/** How the guest environment is shown (graphics output is not connected yet, see GuestView). */
enum class DisplayMode { FULLSCREEN, WINDOW, EMBEDDED }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val mobilePolicy: MobileDataPolicy = MobileDataPolicy.ASK,
    val customSources: List<String> = emptyList(),
    val backendChoice: BackendChoice = BackendChoice.AUTO,
    val displayMode: DisplayMode = DisplayMode.EMBEDDED,
    val uiFont: AppFont = DEFAULT_UI_FONT,
    val terminalFont: AppFont = DEFAULT_TERMINAL_FONT,
    val navStyle: NavBarStyle = NavBarStyle.FROSTED_GLASS,
    /** Bar blur intensity, 0..100. */
    val navBlur: Int = DEFAULT_NAV_BLUR,
    val verboseLogging: Boolean = false,
)

val DEFAULT_UI_FONT = AppFont.INTER
val DEFAULT_TERMINAL_FONT = AppFont.JETBRAINS_MONO
const val DEFAULT_NAV_BLUR = 50

private val Context.octaneDataStore by preferencesDataStore(name = "octaneui_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC = booleanPreferencesKey("dynamic")
        val MOBILE = stringPreferencesKey("mobile")
        val SOURCES = stringSetPreferencesKey("sources")
        val BACKEND = stringPreferencesKey("backend")
        val DISPLAY = stringPreferencesKey("display")
        val UI_FONT = stringPreferencesKey("ui_font")
        val TERM_FONT = stringPreferencesKey("terminal_font")
        val NAV_STYLE = stringPreferencesKey("nav_style")
        val NAV_BLUR = intPreferencesKey("nav_blur")
        val VERBOSE = booleanPreferencesKey("verbose_logging")
    }

    val settings: Flow<AppSettings> = context.octaneDataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            mobilePolicy = p[Keys.MOBILE]?.let { runCatching { MobileDataPolicy.valueOf(it) }.getOrNull() } ?: MobileDataPolicy.ASK,
            customSources = (p[Keys.SOURCES] ?: emptySet()).sorted(),
            backendChoice = p[Keys.BACKEND]?.let { runCatching { BackendChoice.valueOf(it) }.getOrNull() } ?: BackendChoice.AUTO,
            uiFont = p[Keys.UI_FONT]?.let { runCatching { AppFont.valueOf(it) }.getOrNull() } ?: DEFAULT_UI_FONT,
            terminalFont = p[Keys.TERM_FONT]?.let { runCatching { AppFont.valueOf(it) }.getOrNull() } ?: DEFAULT_TERMINAL_FONT,
            navStyle = p[Keys.NAV_STYLE]?.let { runCatching { NavBarStyle.valueOf(it) }.getOrNull() } ?: NavBarStyle.FROSTED_GLASS,
            verboseLogging = p[Keys.VERBOSE] ?: false,
            navBlur = (p[Keys.NAV_BLUR] ?: DEFAULT_NAV_BLUR).coerceIn(0, 100),
            displayMode = p[Keys.DISPLAY]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.EMBEDDED,
        )
    }

    suspend fun setTheme(m: ThemeMode) { context.octaneDataStore.edit { it[Keys.THEME] = m.name } }
    suspend fun setDynamic(v: Boolean) { context.octaneDataStore.edit { it[Keys.DYNAMIC] = v } }
    suspend fun setMobilePolicy(p: MobileDataPolicy) { context.octaneDataStore.edit { it[Keys.MOBILE] = p.name } }
    suspend fun setBackend(c: BackendChoice) { context.octaneDataStore.edit { it[Keys.BACKEND] = c.name } }
    suspend fun setDisplay(m: DisplayMode) { context.octaneDataStore.edit { it[Keys.DISPLAY] = m.name } }
    suspend fun setUiFont(f: AppFont) { context.octaneDataStore.edit { it[Keys.UI_FONT] = f.name } }
    suspend fun setTerminalFont(f: AppFont) { context.octaneDataStore.edit { it[Keys.TERM_FONT] = f.name } }
    suspend fun resetFonts() { context.octaneDataStore.edit { it.remove(Keys.UI_FONT); it.remove(Keys.TERM_FONT) } }
    suspend fun setNavStyle(st: NavBarStyle) { context.octaneDataStore.edit { it[Keys.NAV_STYLE] = st.name } }
    suspend fun setVerbose(v: Boolean) { context.octaneDataStore.edit { it[Keys.VERBOSE] = v } }
    suspend fun setNavBlur(v: Int) { context.octaneDataStore.edit { it[Keys.NAV_BLUR] = v.coerceIn(0, 100) } }
    suspend fun addSource(ref: String) { context.octaneDataStore.edit { it[Keys.SOURCES] = (it[Keys.SOURCES] ?: emptySet()) + ref } }
    suspend fun removeSource(ref: String) { context.octaneDataStore.edit { it[Keys.SOURCES] = (it[Keys.SOURCES] ?: emptySet()) - ref } }
}
