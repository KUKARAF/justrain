package page.osmosis.nativeplayer

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val DEFAULT_FADE_MS = 400L

/**
 * A passive androidx.media3 Player adapter whose sole purpose is to satisfy
 * MediaSession's Player interface so the OS can show the notification/
 * lock-screen media controls. It never drives playback on its own — real
 * playback state changes call into AudioEngine directly (see
 * PlaybackService), and this class is kept in sync via notifyPlaying()/
 * notifyPausedExternally()/notifyStopped() so the notification's play/pause
 * icon reflects reality. The one path where THIS class drives the engine is
 * handleSetPlayWhenReady/handleStop, invoked when the OS itself issues a
 * play/pause (notification tap, Bluetooth button, Android Auto, etc).
 * Ported from metiq-xyz/android-app's EnginePlayer.kt, trimmed to drop
 * per-track artwork/color tinting (justrain has exactly one sound).
 */
@OptIn(markerClass = [UnstableApi::class])
class EnginePlayer(
    private val engine: AudioEngine,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private var playing = false
    private var stopped = true
    private var currentVolume = 1f

    fun notifyPlaying() {
        playing = true
        stopped = false
        invalidateState()
    }

    fun notifyPaused() {
        playing = false
        invalidateState()
    }

    fun notifyPausedExternally() {
        if (!playing) return
        playing = false
        // Instant pause: this fires on audio route loss (e.g. headphones
        // unplugged), where a fade would leak sound out of the speaker.
        engine.pause(fadeMs = 0)
        invalidateState()
    }

    fun notifyStopped() {
        playing = false
        stopped = true
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle("justrain")
            .setArtist("rain")
            .build()
        val item = MediaItemData.Builder("justrain")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("justrain")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setDurationUs(C.TIME_UNSET)
            .setIsPlaceholder(false)
            .build()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (stopped) Player.STATE_IDLE else Player.STATE_READY)
            .setVolume(currentVolume)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        if (playWhenReady) {
            stopped = false
            engine.play(fadeMs = DEFAULT_FADE_MS)
        } else {
            engine.pause(fadeMs = DEFAULT_FADE_MS)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeFlags: Int): ListenableFuture<*> {
        currentVolume = volume.coerceIn(0f, 1f)
        engine.setVolume(currentVolume)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        stopped = true
        engine.pause(fadeMs = DEFAULT_FADE_MS)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        engine.release()
        return Futures.immediateVoidFuture()
    }
}
