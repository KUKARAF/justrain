package page.osmosis.nativeplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin

@InvokeArg
class LoadArgs {
    lateinit var path: String
}

@InvokeArg
class VolumeArgs {
    var volume: Float = 1.0f
}

@TauriPlugin
class NativePlayerPlugin(private val activity: Activity) : Plugin(activity) {
    private var controller: MediaController? = null

    // Connect (once) to the PlaybackService's MediaSession, then run `cb` on the
    // main thread with the controller (all Player calls must be on main).
    private fun withController(cb: (MediaController?) -> Unit) {
        val existing = controller
        if (existing != null) {
            ContextCompat.getMainExecutor(activity).execute { cb(existing) }
            return
        }
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        future.addListener({
            controller = try { future.get() } catch (e: Exception) { null }
            cb(controller)
        }, ContextCompat.getMainExecutor(activity))
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
    fun load(invoke: Invoke) {
        val args = invoke.parseArgs(LoadArgs::class.java)
        withController { c ->
            if (c == null) { invoke.reject("player unavailable"); return@withController }
            c.setMediaItem(MediaItem.fromUri(Uri.parse(args.path)))
            c.repeatMode = Player.REPEAT_MODE_ONE
            c.prepare()
            invoke.resolve()
        }
    }

    @Command
    fun play(invoke: Invoke) {
        ensureNotificationPermission()
        withController { c ->
            if (c == null) { invoke.reject("player unavailable"); return@withController }
            c.play()
            invoke.resolve()
        }
    }

    @Command
    fun pause(invoke: Invoke) {
        withController { c -> c?.pause(); invoke.resolve() }
    }

    @Command
    fun stop(invoke: Invoke) {
        withController { c -> c?.stop(); invoke.resolve() }
    }

    @Command
    fun setVolume(invoke: Invoke) {
        val args = invoke.parseArgs(VolumeArgs::class.java)
        withController { c ->
            if (c != null) c.volume = args.volume.coerceIn(0f, 1f)
            invoke.resolve()
        }
    }
}
