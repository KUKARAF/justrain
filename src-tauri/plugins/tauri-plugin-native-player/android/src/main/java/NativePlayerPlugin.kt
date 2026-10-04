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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

private const val TAG = "NativePlayerPlugin"

@InvokeArg
class PlayArgs {
    var soft: Boolean = false
    var exclusive: Boolean = true
}

@InvokeArg
class ExclusiveArgs {
    var exclusive: Boolean = true
}

@InvokeArg
class VolumeArgs {
    var volume: Float = 1.0f
}

/**
 * Controls PlaybackService via a direct bindService()/Binder connection — a
 * synchronous, in-process reference to its AudioEngine — instead of the
 * async MediaController/SessionToken IPC handshake (which was the actual
 * source of "player unavailable" failures).
 *
 * Native → JS: every playback state change (notification/headset/Bluetooth
 * play-pause, headphones unplugged, audio focus loss/gain) is pushed as a
 * "state" plugin event `{playing}`; `getState` lets the webview pull it after
 * it may have missed events (e.g. while hidden).
 */
@TauriPlugin
class NativePlayerPlugin(private val activity: Activity) : Plugin(activity) {
    private var binder: PlaybackService.LocalBinder? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? PlaybackService.LocalBinder
            binder = b
            Log.i(TAG, "onServiceConnected, binder=$b")
            b ?: return
            b.setStateListener { playing -> emitState(playing) }
            emitState(b.isPlaying())
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            binder?.setStateListener(null)
            binder = null
        }
    }

    private fun emitState(playing: Boolean) {
        trigger("state", JSObject().put("playing", playing))
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

    // The service outlives the Activity; drop its reference to us (and thus
    // the Activity/WebView) so a recreated Activity doesn't leak this one.
    override fun onDestroy(activity: AppCompatActivity) {
        binder?.setStateListener(null)
        binder = null
        runCatching { this.activity.unbindService(connection) }
        super.onDestroy(activity)
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
        val args = invoke.parseArgs(PlayArgs::class.java)
        val b = binder
        if (b == null) { invoke.reject("player unavailable"); return }
        mainHandler.post {
            b.play(args.soft, args.exclusive) { result ->
                when (result) {
                    PlayResult.STARTED -> invoke.resolve(JSObject().put("playing", true))
                    // Not an error: another app (usually a call) holds audio focus.
                    PlayResult.FOCUS_DENIED -> invoke.resolve(JSObject().put("playing", false))
                    PlayResult.FAILED -> invoke.reject("failed to start playback — see logcat tag AudioEngine")
                }
            }
        }
    }

    @Command
    fun pause(invoke: Invoke) {
        val b = binder
        if (b == null) { invoke.reject("player unavailable"); return }
        mainHandler.post { b.pause() }
        invoke.resolve()
    }

    @Command
    fun setVolume(invoke: Invoke) {
        val args = invoke.parseArgs(VolumeArgs::class.java)
        val b = binder
        if (b == null) { invoke.reject("player unavailable"); return }
        val v = args.volume.coerceIn(0f, 1f)
        mainHandler.post { b.setVolume(v) }
        invoke.resolve()
    }

    @Command
    fun setExclusive(invoke: Invoke) {
        val args = invoke.parseArgs(ExclusiveArgs::class.java)
        val b = binder
        if (b == null) { invoke.reject("player unavailable"); return }
        mainHandler.post { b.setExclusive(args.exclusive) }
        invoke.resolve()
    }

    @Command
    fun getState(invoke: Invoke) {
        val b = binder
        if (b == null) { invoke.reject("player unavailable"); return }
        mainHandler.post { invoke.resolve(JSObject().put("playing", b.isPlaying())) }
    }
}
