package io.github.anriudh.pitchprint.record

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Receives volume button presses while recording, including with the screen off.
 *
 * A media session with remote playback and its own volume provider gets the presses as callbacks,
 * and no stream volume changes. It reports itself as playing because Android only routes volume
 * keys to a session that is playing.
 */
class VolumeMarkers(context: Context, private val onPress: (String) -> Unit) {
    private val session = MediaSession(context, "pitchprint-markers")

    fun start() {
        // MediaSession drops volume adjustments silently unless a callback is set, even an empty one.
        session.setCallback(object : MediaSession.Callback() {})
        session.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                when {
                    direction > 0 -> onPress("up")
                    direction < 0 -> onPress("down")
                }
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
}
