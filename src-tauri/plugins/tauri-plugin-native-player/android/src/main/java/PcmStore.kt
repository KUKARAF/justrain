package page.osmosis.nativeplayer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal data class Pcm(
    val data: ShortArray,
    val sampleRate: Int,
    val channelMask: Int,
    val channelCount: Int,
)

/**
 * Decodes the bundled rain-loop asset to raw PCM exactly once, caching the
 * result to disk (with a CRC32 of the source asset so the cache
 * auto-invalidates if the bundled asset ever changes). Ported from
 * metiq-xyz/android-app's PcmStore.kt, trimmed to a single asset.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object PcmStore {
    // Raw little-endian header: magic, asset crc32, sample rate, channel count,
    // sample count — then the samples themselves.
    private const val CACHE_MAGIC = 0x4D504331 // "MPC1"
    private const val CACHE_HEADER_BYTES = 5 * 4
    private const val ASSET_PATH = "rain-loop-long.ogg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile
    private var deferred: Deferred<Pcm>? = null

    private fun pcmAsync(context: Context): Deferred<Pcm> {
        val appContext = context.applicationContext
        val existing = deferred
        if (existing != null) return existing
        synchronized(this) {
            val again = deferred
            if (again != null) return again
            val d = scope.async(decodeDispatcher) { loadPcm(appContext) }
            deferred = d
            return d
        }
    }

    private fun loadPcm(context: Context): Pcm {
        val crc = assetCrc32(context)
        val file = File(File(context.filesDir, "pcm"), "rain.pcm")
        readCachedPcm(file, crc)?.let { return it }
        val pcm = decodeAssetToPcm(context)
        runCatching { writeCachedPcm(file, crc, pcm) }
        return pcm
    }

    private fun assetCrc32(context: Context): Int {
        val crc = CRC32()
        context.assets.open(ASSET_PATH).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value.toInt()
    }

    private fun readCachedPcm(file: File, crc: Int): Pcm? = runCatching {
        if (!file.isFile) return null
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < CACHE_HEADER_BYTES) return null
        if (buf.int != CACHE_MAGIC || buf.int != crc) return null
        val sampleRate = buf.int
        val channelCount = buf.int
        val sampleCount = buf.int
        if (sampleCount < 0 || buf.remaining() != sampleCount * 2) return null
        val shorts = ShortArray(sampleCount)
        buf.asShortBuffer().get(shorts)
        Pcm(
            data = shorts,
            sampleRate = sampleRate,
            channelMask = channelMaskFor(channelCount),
            channelCount = channelCount,
        )
    }.getOrNull()

    private fun writeCachedPcm(file: File, crc: Int, pcm: Pcm) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val bytes = ByteBuffer.allocate(CACHE_HEADER_BYTES + pcm.data.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(CACHE_MAGIC).putInt(crc)
            .putInt(pcm.sampleRate).putInt(pcm.channelCount).putInt(pcm.data.size)
        bytes.asShortBuffer().put(pcm.data)
        tmp.writeBytes(bytes.array())
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not move ${tmp.name} into place")
        }
    }

    internal suspend fun awaitPcm(context: Context): Pcm {
        val d = pcmAsync(context)
        return try {
            d.await()
        } catch (e: Throwable) {
            synchronized(this) { if (deferred === d) deferred = null }
            throw e
        }
    }

    /** Kick off the decode/cache-load in the background so first play() doesn't stall on it. */
    fun preload(context: Context) {
        pcmAsync(context)
    }

    private fun decodeAssetToPcm(context: Context): Pcm {
        val afd: AssetFileDescriptor = context.assets.openFd(ASSET_PATH)
        val extractor = MediaExtractor()
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: error("No audio track in $ASSET_PATH")
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        val pcmBytes = java.io.ByteArrayOutputStream()

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIx = codec.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    val inBuf: ByteBuffer = codec.getInputBuffer(inIx)!!
                    val read = extractor.readSampleData(inBuf, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIx, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIx >= 0) {
                val outBuf: ByteBuffer = codec.getOutputBuffer(outIx)!!
                outBuf.position(info.offset)
                outBuf.limit(info.offset + info.size)
                val chunk = ByteArray(info.size)
                outBuf.get(chunk)
                pcmBytes.write(chunk)
                codec.releaseOutputBuffer(outIx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val bytes = pcmBytes.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        return Pcm(
            data = shorts,
            sampleRate = sampleRate,
            channelMask = channelMaskFor(channelCount),
            channelCount = channelCount,
        )
    }

    private fun channelMaskFor(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> error("Unsupported channel count: $channelCount")
    }
}
