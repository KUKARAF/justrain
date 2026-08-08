package page.osmosis.nativeplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper

private const val TAG = "PlaybackService"
private const val CHANNEL_ID = "rain_playback"
private const val NOTIFICATION_ID = 1

/**
 * Hosts the AudioEngine (raw AudioTrack playback, see AudioEngine.kt) and a
 * MediaSession that exists purely to drive the OS notification/lock-screen
 * surface via EnginePlayer, a passive Player adapter.
 *
 * MediaSessionService is documented to auto-promote itself to a foreground
 * service (and post the notification) whenever its player reports
 * isPlaying — but that auto-detection did not fire reliably for our custom
 * SimpleBasePlayer-based EnginePlayer (observed: startForegroundCount stayed
 * 0 and the OS's background-audio hardening silently muted us). So instead
 * we drive startForeground()/stopForeground() explicitly off a Player.Listener,
 * which is fully within our control and doesn't depend on that auto-detection.
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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "onIsPlayingChanged isPlaying=$isPlaying")
            if (isPlaying) promoteForeground() else demoteForeground()
        }
    }

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
        player.addListener(playerListener)
        session = MediaSession.Builder(this, player).build()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this, becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        PcmStore.preload(this)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Rain playback", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun promoteForeground() {
        val s = session ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("justrain")
            .setContentText("raining")
            .setOngoing(true)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(s))
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun demoteForeground() {
        stopForeground(Service.STOP_FOREGROUND_DETACH)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == CONTROL_ACTION) return localBinder
        return super.onBind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        session?.run {
            player.removeListener(playerListener)
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
