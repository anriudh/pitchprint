package io.github.anriudh.pitchprint.record

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Receives volume button presses while recording, including with the screen off.
 *
 * A media session with remote playback and its own volume provider gets the presses as callbacks,
 * and no stream volume changes. It reports itself as playing because Android only routes volume
 * keys to a session that is playing.
 *
 * [onAdjust] gets every callback with its direction (0 is a release) and whether it starts a new press.
 */
class VolumeMarkers(context: Context, private val onAdjust: (direction: Int, newPress: Boolean) -> Unit) {
    private val session = MediaSession(context, "pitchprint-markers")
    private var held = false
    private var lastNs = 0L

    fun start() {
        // MediaSession drops volume adjustments silently unless a callback is set, even an empty one.
        session.setCallback(object : MediaSession.Callback() {})
        session.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                adjust(direction)
            }
        })
        session.setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build(),
        )
        session.isActive = true
    }

    fun stop() {
        session.isActive = false
        session.release()
    }

    // The G73 sends a nonzero direction on press, sometimes repeats it every 50 ms while held, and
    // sends 0 on release, so the first nonzero after a release is a new press. On a phone that
    // never reports the release, activity within a second of the last counts as the same press.
    private fun adjust(direction: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        val newPress = direction != 0 && (!held || now - lastNs > HOLD_GAP_NS)
        if (direction != 0) {
            held = true
            lastNs = now
        } else {
            held = false
        }
        onAdjust(direction, newPress)
    }

    private companion object {
        const val HOLD_GAP_NS = 1_000_000_000L
    }
}
