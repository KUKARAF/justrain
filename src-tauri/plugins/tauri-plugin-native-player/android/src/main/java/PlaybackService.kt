package page.osmosis.nativeplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

private const val TAG = "PlaybackService"

/**
 * Hosts the AudioEngine (raw AudioTrack playback, see AudioEngine.kt) and a
 * MediaSession that exists purely to drive the OS notification/lock-screen
 * surface via EnginePlayer, a passive Player adapter. The service runs as a
 * foreground service while playing, which is what gives us background
 * (screen-off) playback.
 *
 * Playback control does NOT go through the MediaSession/MediaController IPC
 * path — the plugin binds directly to this service (see LocalBinder) and
 * calls AudioEngine synchronously. Unlike our previous ExoPlayer-based
 * implementation, this deliberately never requests audio focus (matching
 * metiq-xyz/android-app's proven approach), so nothing can involuntarily
 * pause it. The one exception is ACTION_AUDIO_BECOMING_NOISY (headphones
 * unplugged) — standard Android practice, unrelated to audio focus — which
 * pauses instantly to avoid rain suddenly blasting out of the speaker.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var engine: AudioEngine
    private lateinit var player: EnginePlayer
    private var session: MediaSession? = null

    inner class LocalBinder : Binder() {
        fun play(soft: Boolean) {
            val fadeMs = if (soft && !engine.hasStarted()) SOFT_START_FADE_MS else NORMAL_FADE_MS
            engine.play(fadeMs)
            player.notifyPlaying()
        }
        fun pause() {
            engine.pause(NORMAL_FADE_MS)
            player.notifyPaused()
        }
        fun setVolume(v: Float) {
            engine.setVolume(v)
        }
    }
    private val localBinder = LocalBinder()

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "ACTION_AUDIO_BECOMING_NOISY — pausing instantly")
                player.notifyPausedExternally()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this)
        player = EnginePlayer(engine, mainLooper)
        session = MediaSession.Builder(this, player).build()
        ContextCompat.registerReceiver(
            this, becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PcmStore.preload(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == CONTROL_ACTION) return localBinder
        return super.onBind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        session?.run {
            player.release()   // triggers handleRelease() -> engine.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val CONTROL_ACTION = "page.osmosis.nativeplayer.CONTROL"
        const val SOFT_START_FADE_MS = 18_000L
        const val NORMAL_FADE_MS = 400L
    }
}
