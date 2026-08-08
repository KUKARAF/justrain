package page.osmosis.nativeplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin

private const val TAG = "NativePlayerPlugin"

@InvokeArg
class VolumeArgs {
    var volume: Float = 1.0f
}

/**
 * Controls PlaybackService via a direct bindService()/Binder connection — a
 * synchronous, in-process reference to its ExoPlayer — instead of the async
 * MediaController/SessionToken IPC handshake (which was the actual source of
 * "player unavailable" failures).
 */
@TauriPlugin
class NativePlayerPlugin(private val activity: Activity) : Plugin(activity) {
    private var playback: PlaybackService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playback = (binder as? PlaybackService.LocalBinder)?.service
            Log.i(TAG, "onServiceConnected, playback=$playback")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            playback = null
        }
    }

    // Bind here (not in the constructor/init block): if binding threw during
    // construction, Tauri's plugin loader would silently drop the whole
    // plugin, and every future invoke would permanently reject with
    // "not initialized" no matter how many times we retry.
    override fun load(webView: WebView) {
        super.load(webView)
        try {
            val intent = Intent(activity, PlaybackService::class.java).setAction(PlaybackService.CONTROL_ACTION)
            val bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService() returned $bound")
        } catch (e: Throwable) {
            Log.e(TAG, "bindService() failed", e)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3317
            )
        }
    }

    @Command
    fun play(invoke: Invoke) {
        ensureNotificationPermission()
        val service = playback
        if (service == null) { invoke.reject("player unavailable"); return }
        mainHandler.post { service.player.play() }
        invoke.resolve()
    }

    @Command
    fun pause(invoke: Invoke) {
        val service = playback
        if (service == null) { invoke.reject("player unavailable"); return }
        mainHandler.post { service.player.pause() }
        invoke.resolve()
    }

    @Command
    fun setVolume(invoke: Invoke) {
        val args = invoke.parseArgs(VolumeArgs::class.java)
        val service = playback
        if (service == null) { invoke.reject("player unavailable"); return }
        val v = args.volume.coerceIn(0f, 1f)
        mainHandler.post { service.player.volume = v }
        invoke.resolve()
    }
}
