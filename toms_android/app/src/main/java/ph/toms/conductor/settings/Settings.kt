package ph.toms.conductor.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which hand the conductor uses. Every control already spans the full width, so this only moves the few
 * things that sit to one side (the keypad corners and the tab order). Right is the default.
 */
enum class Handedness { Right, Left }

interface SettingsStore {
    val handedness: StateFlow<Handedness>
    fun setHandedness(value: Handedness)

    /** Keep the screen on while a shift is open. Off lets the screen dim normally. Default: on. */
    val keepScreenOn: StateFlow<Boolean>
    fun setKeepScreenOn(value: Boolean)
}

/** Keeps the value in memory. Used by tests. */
class MemorySettings(initial: Handedness = Handedness.Right) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val handedness: StateFlow<Handedness> = state.asStateFlow()
    override fun setHandedness(value: Handedness) {
        state.value = value
    }

    private val screen = MutableStateFlow(true)
    override val keepScreenOn: StateFlow<Boolean> = screen.asStateFlow()
    override fun setKeepScreenOn(value: Boolean) {
        screen.value = value
    }
}

@Singleton
class PrefsSettings @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsStore {
    private val prefs = context.getSharedPreferences("toms_settings", Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())
    override val handedness: StateFlow<Handedness> = state.asStateFlow()

    override fun setHandedness(value: Handedness) {
        prefs.edit().putString(KEY_HAND, value.name).apply()
        state.value = value
    }

    private val screen = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_ON, true))
    override val keepScreenOn: StateFlow<Boolean> = screen.asStateFlow()

    override fun setKeepScreenOn(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()
        screen.value = value
    }

    private fun read(): Handedness =
        // An unknown or missing value (an older version, a corrupted file) falls back to the default.
        runCatching { Handedness.valueOf(prefs.getString(KEY_HAND, null) ?: "") }.getOrDefault(Handedness.Right)

    private companion object {
        const val KEY_HAND = "handedness"
        const val KEY_SCREEN_ON = "keep_screen_on"
    }
}
