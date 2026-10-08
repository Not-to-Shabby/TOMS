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
 * things that sit to one side (the Undo and Clear pair and the tab order). Right is the default.
 */
enum class Handedness { Right, Left }

interface SettingsStore {
    val handedness: StateFlow<Handedness>
    fun setHandedness(value: Handedness)
}

/** Keeps the value in memory. Used by tests. */
class MemorySettings(initial: Handedness = Handedness.Right) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val handedness: StateFlow<Handedness> = state.asStateFlow()
    override fun setHandedness(value: Handedness) {
        state.value = value
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

    private fun read(): Handedness =
        // An unknown or missing value (an older version, a corrupted file) falls back to the default.
        runCatching { Handedness.valueOf(prefs.getString(KEY_HAND, null) ?: "") }.getOrDefault(Handedness.Right)

    private companion object {
        const val KEY_HAND = "handedness"
    }
}
