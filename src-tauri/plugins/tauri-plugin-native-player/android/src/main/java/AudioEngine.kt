package page.osmosis.nativeplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

private const val FADE_STEP_MS = 10L
private const val BLOCK_FRAMES = 1024

/**
 * Raw AudioTrack playback of the single bundled rain loop, streamed from
 * pre-decoded PCM with a coroutine feeder that wraps the read position back
 * to 0 for sample-accurate, gapless looping. Ported from
 * metiq-xyz/android-app's AudioEngine.kt, trimmed to one always-on layer
 * (no multi-layer mixing, no warmth EQ, no binaural tones) — and, just like
 * metiq, this never requests audio focus, so nothing can involuntarily pause
 * it (see PlaybackService for the one exception: AUDIO_BECOMING_NOISY).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioEngine(private val context: Context) {
    private var track: AudioTrack? = null
    private var feeder: Job? = null

    @Volatile
    private var fadeFactor: Float = 0f

    @Volatile
    private var baseVolume: Float = 1f
    private var fadeJob: Job? = null

    private val feederScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var startJob: Job? = null

    private fun fadeSteps(ms: Long): Int = (ms / FADE_STEP_MS).toInt().coerceAtLeast(1)
    private fun fadeInGain(progress: Float): Float = sqrt(progress.coerceIn(0f, 1f))
    private fun fadeOutGain(progress: Float): Float =
        if (progress >= 1f) 0f else 10f.pow(-3f * progress.coerceAtLeast(0f))
    private fun appliedVolume(): Float = (baseVolume * fadeFactor).coerceIn(0f, 1f)

    fun hasStarted(): Boolean = track != null

    /** Starts playback if not already started, then fades in over [fadeMs]. */
    fun play(fadeMs: Long) {
        val existing = track
        if (existing != null) {
            fadeJob?.cancel()
            fadeJob = fadeScope.launch {
                runCatching { existing.play() }
                rampVolume(fadeMs, toward = 1f)
            }
            return
        }
        if (startJob != null) return
        val job = feederScope.launch {
            val pcm = try {
                PcmStore.awaitPcm(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@launch
            }
            val newTrack = withContext(Dispatchers.IO) { buildTrack(pcm.sampleRate, pcm.channelMask) }
            if (!isActive) {
                runCatching { newTrack.release() }
                return@launch
            }
            newTrack.setVolume(0f)
            track = newTrack
            fadeFactor = 0f
            feeder = launchFeeder(newTrack, pcm)
            fadeJob?.cancel()
            fadeJob = fadeScope.launch { rampVolume(fadeMs, toward = 1f) }
        }
        job.invokeOnCompletion { startJob = null }
        startJob = job
    }

    /** Fades out over [fadeMs] (0 = instant) then pauses, keeping the buffer/feeder alive. */
    fun pause(fadeMs: Long) {
        val t = track ?: return
        fadeJob?.cancel()
        if (fadeMs <= 0) {
            fadeFactor = 0f
            runCatching { t.setVolume(0f) }
            runCatching { t.pause() }
            return
        }
        fadeJob = fadeScope.launch {
            rampVolume(fadeMs, toward = 0f)
            runCatching { t.pause() }
        }
    }

    private suspend fun rampVolume(fadeMs: Long, toward: Float) {
        val t = track ?: return
        val from = fadeFactor
        val steps = fadeSteps(fadeMs)
        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            fadeFactor = if (toward >= 1f) {
                from + (1f - from) * fadeInGain(progress)
            } else {
                from * fadeOutGain(progress)
            }
            runCatching { t.setVolume(appliedVolume()) }
            delay(FADE_STEP_MS)
        }
        fadeFactor = toward
        runCatching { t.setVolume(appliedVolume()) }
    }

    /** Instant, immediate volume set — no fade. Used for live volume-slider drags. */
    fun setVolume(v: Float) {
        baseVolume = v.coerceIn(0f, 1f)
        track?.let { runCatching { it.setVolume(appliedVolume()) } }
    }

    fun release() {
        fadeJob?.cancel()
        startJob?.cancel()
        startJob = null
        val f = feeder
        if (f != null) {
            f.cancel()
        } else {
            track?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
        track = null
        feeder = null
    }

    private fun launchFeeder(track: AudioTrack, pcm: Pcm): Job =
        feederScope.launch {
            val src = pcm.data
            val ch = pcm.channelCount
            val frames = src.size / ch
            if (frames == 0) {
                runCatching { track.release() }
                return@launch
            }
            val total = BLOCK_FRAMES * ch
            val buf = ShortArray(total)
            var pos = 0
            var started = false
            try {
                while (isActive) {
                    var f = 0
                    while (f < BLOCK_FRAMES) {
                        val n = minOf(BLOCK_FRAMES - f, frames - pos)
                        System.arraycopy(src, pos * ch, buf, f * ch, n * ch)
                        pos += n
                        f += n
                        if (pos >= frames) pos = 0
                    }
                    var off = 0
                    while (off < total && isActive) {
                        val n = track.write(buf, off, total - off, AudioTrack.WRITE_NON_BLOCKING)
                        if (n < 0) return@launch
                        off += n
                        if (!started && off > 0) {
                            track.play()
                            started = true
                        }
                        if (off < total) delay(5)
                    }
                }
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }

    private fun buildTrack(sampleRate: Int, channelMask: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val channelCount = if (channelMask == AudioFormat.CHANNEL_OUT_STEREO) 2 else 1
        val desired = BLOCK_FRAMES * channelCount * 2 * 4
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, desired))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }
}
