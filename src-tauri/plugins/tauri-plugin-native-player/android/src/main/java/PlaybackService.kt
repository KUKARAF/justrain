package page.osmosis.nativeplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
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

enum class PlayResult { STARTED, FOCUS_DENIED, FAILED }

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
 * calls AudioEngine synchronously.
 *
 * Audio focus is opt-out via the "pause other audio" setting ([exclusive],
 * default on). When on, every play path (in-app, notification/headset play,
 * resume after a transient loss) takes AUDIOFOCUS_GAIN, so Spotify & co.
 * pause, we and they don't fight over headset buttons, and a phone call
 * pauses the rain (resuming when it ends). When off we never request focus,
 * so rain layers over whatever else plays and nothing can pause it. Either
 * way ACTION_AUDIO_BECOMING_NOISY (headphones unplugged) pauses instantly to
 * avoid rain suddenly blasting out of the speaker.
 *
 * Every isPlaying change — whoever caused it — is reported to the plugin via
 * the binder's state listener so the in-app play button stays in sync.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var engine: AudioEngine
    private lateinit var player: EnginePlayer
    private var session: MediaSession? = null
    private var stateListener: ((Boolean) -> Unit)? = null

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null   // API 26+ only
    private var exclusive = true
    private var hasFocus = false
    // Set on AUDIOFOCUS_LOSS_TRANSIENT while playing: resume on AUDIOFOCUS_GAIN.
    private var resumeOnFocusGain = false

    // All methods must be called on the main thread (the player's looper).
    inner class LocalBinder : Binder() {
        fun play(soft: Boolean, exclusive: Boolean, onResult: (PlayResult) -> Unit) {
            this@PlaybackService.exclusive = exclusive
            if (!acquireFocus()) { onResult(PlayResult.FOCUS_DENIED); return }
            val fadeMs = if (soft && !engine.hasStarted()) SOFT_START_FADE_MS else NORMAL_FADE_MS
            engine.play(fadeMs) { success ->
                if (success) player.notifyPlaying() else releaseFocus()
                onResult(if (success) PlayResult.STARTED else PlayResult.FAILED)
            }
        }
        fun pause() {
            releaseFocus()
            engine.pause(NORMAL_FADE_MS)
            player.notifyPaused()
        }
        fun setVolume(v: Float) {
            engine.setVolume(v)
        }
        /** Re-applies the "pause other audio" setting immediately if we're playing. */
        fun setExclusive(v: Boolean) {
            exclusive = v
            if (!v) releaseFocus()
            else if (player.isPlaying && !acquireFocus()) Log.i(TAG, "focus denied on setExclusive — keep playing")
        }
        fun isPlaying(): Boolean = player.isPlaying
        /** Called with the new isPlaying on every change. Pass null to drop the reference. */
        fun setStateListener(l: ((Boolean) -> Unit)?) {
            stateListener = l
        }
    }
    private val localBinder = LocalBinder()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "onIsPlayingChanged isPlaying=$isPlaying")
            if (isPlaying) promoteForeground() else demoteForeground()
            stateListener?.invoke(isPlaying)
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "ACTION_AUDIO_BECOMING_NOISY — pausing instantly")
                releaseFocus()
                player.notifyPausedExternally()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "onAudioFocusChange $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took over for good (e.g. Spotify play): stop and
                // let go — the user resumes us explicitly.
                releaseFocus()
                player.notifyPausedExternally(FOCUS_FADE_MS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Call, navigation prompt, voice assistant: pause, keep our
                // focus request on the stack, and resume on GAIN.
                hasFocus = false
                resumeOnFocusGain = player.isPlaying
                player.notifyPausedExternally(FOCUS_FADE_MS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // API 26+ ducks us automatically (willPauseWhenDucked=false).
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    engine.play(NORMAL_FADE_MS) { success -> if (success) player.notifyPlaying() }
                }
            }
        }
    }

    /** Takes audio focus if [exclusive]; returns false if the system denied it (e.g. during a call). */
    private fun acquireFocus(): Boolean {
        resumeOnFocusGain = false
        if (!exclusive) { abandonFocus(); return true }
        if (hasFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) Log.i(TAG, "audio focus denied ($result)")
        return hasFocus
    }

    /** Gives focus back (other apps may resume) and forgets any pending transient resume. */
    private fun releaseFocus() {
        resumeOnFocusGain = false
        abandonFocus()
    }

    private fun abandonFocus() {
        hasFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioEngine.AUDIO_ATTRIBUTES)
                .setOnAudioFocusChangeListener(focusListener, Handler(mainLooper))
                .build()
        }
        engine = AudioEngine(this)
        player = EnginePlayer(
            engine, mainLooper,
            onPlayRequested = { acquireFocus() },
            onPauseRequested = { releaseFocus() },
        )
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
        releaseFocus()
        stateListener = null
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
        const val FOCUS_FADE_MS = 250L
    }
}
