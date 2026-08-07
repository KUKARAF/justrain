package page.osmosis.nativeplayer

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * A Media3 MediaSessionService: hosts an ExoPlayer and a MediaSession. The
 * service automatically builds the media notification and runs as a foreground
 * service while playing, which is what gives us lock-screen/notification
 * controls and background (screen-off) playback.
 *
 * Playback control does NOT go through the MediaSession/MediaController IPC
 * path — that async handshake is fragile. Instead the plugin binds directly to
 * this service (see LocalBinder below) and calls ExoPlayer synchronously. The
 * MediaSession exists purely to drive the OS notification surface.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    lateinit var player: ExoPlayer
        private set

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }
    private val localBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE   // seamless single-track loop
            playWhenReady = false
            setMediaItem(MediaItem.fromUri("asset:///rain-loop-long.ogg"))
            prepare()
        }
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == CONTROL_ACTION) return localBinder
        return super.onBind(intent)
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val CONTROL_ACTION = "page.osmosis.nativeplayer.CONTROL"
    }
}
