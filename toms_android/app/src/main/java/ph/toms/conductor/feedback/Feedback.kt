package ph.toms.conductor.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the conductor should feel and hear. Colour on screen is never the only signal. */
enum class Cue {
    /** Tap accepted: one short buzz, one high beep. */
    Done,

    /** Tap refused: two long buzzes, one low tone. */
    Refused,

    /** An existing trip was opened: one buzz, a neutral double beep. */
    Opened,
}

interface Feedback {
    fun play(cue: Cue)
}

/** A pattern as alternating off/on times in milliseconds, starting with an initial delay. */
object Patterns {
    val Done = longArrayOf(0, 60)
    val Refused = longArrayOf(0, 250, 120, 250)
    val Opened = longArrayOf(0, 60, 80, 60)
}

@Singleton
class DeviceFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
) : Feedback {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // Created lazily and released on failure: a ToneGenerator can fail to construct when audio is busy.
    private var tones: ToneGenerator? = null

    override fun play(cue: Cue) {
        buzz(
            when (cue) {
                Cue.Done -> Patterns.Done
                Cue.Refused -> Patterns.Refused
                Cue.Opened -> Patterns.Opened
            },
        )
        beep(
            when (cue) {
                Cue.Done -> ToneGenerator.TONE_PROP_ACK
                Cue.Refused -> ToneGenerator.TONE_PROP_NACK
                Cue.Opened -> ToneGenerator.TONE_PROP_BEEP2
            },
        )
    }

    private fun buzz(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun beep(tone: Int) {
        runCatching {
            val generator = tones ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tones = it }
            generator.startTone(tone, 180)
        }.onFailure {
            tones?.release()
            tones = null
        }
    }
}

/** For tests and previews: remembers what was asked for. */
class RecordingFeedback : Feedback {
    val cues = mutableListOf<Cue>()
    override fun play(cue: Cue) {
        cues += cue
    }
}
