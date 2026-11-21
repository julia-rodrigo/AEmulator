package app.aemu.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream

/**
 * Звук гостя. Гостевой HAL (audio.primary.default.so из набора движка) пишет PCM
 * 48 кГц/стерео/16 бит в FIFO /dev/eac, а мы отдаём его в AudioTrack телефона.
 */
class AudioOut(
    private val paths: VmPaths,
    private val log: (String) -> Unit,
    private val rate: Int = RATE,
    /** guest PCM channel; MediaTek keeps /dev/eac for its own driver, so ours is /dev/aemu_pcm there */
    private val dev: String = "dev/eac",
) {
    @Volatile private var thread: Thread? = null
    @Volatile private var stop = false
    @Volatile var played = 0L
        private set
    @Volatile var muted = false
    @Volatile var audioCaptureHook: ((ByteArray, Int, Int) -> Unit)? = null

    val fifo: File get() = File(paths.root, dev)

    fun makeFifo() {
        val f = fifo
        val mode = runCatching { Os.stat(f.absolutePath).st_mode and OsConstants.S_IFMT }.getOrDefault(0)
        if (mode == OsConstants.S_IFIFO) return
        f.parentFile?.mkdirs()
        f.delete()
        runCatching { Os.mkfifo(f.absolutePath, "666".toInt(8)) }
            .onFailure { log("audio: channel not created: ${it.message}") }
    }

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        if (!fifo.exists()) { log("audio: no channel"); return }
        stop = false
        thread = Thread({ pump() }, "aemu-audio").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        stop = true
        thread?.interrupt()
        // разбудить read() на FIFO: откроем его на запись и сразу закроем
        runCatching {
            Thread { runCatching { java.io.FileOutputStream(fifo).close() } }.apply { isDaemon = true; start() }
        }
        thread = null
    }

    private fun pump() {
        // The guest only keeps time through this pipe: a stalled reader means the guest's mixer drops sound.
        // Run at audio priority so taps and screen work on the phone do not starve it.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val min = runCatching { AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) }.getOrDefault(0)
        val bufSize = maxOf(min * 4, 64 * 1024)
        val buf = ByteArray(4096 + FRAME)
        // bytes of an incomplete stereo frame left from the last read; AudioTrack only takes whole frames,
        // and losing them would shift the stream by a sample (loud static until the next restart)
        var carry = 0
        var track: AudioTrack? = null
        while (!stop) {
            val ins = runCatching { FileInputStream(fifo) }.getOrNull()
            if (ins == null) { Thread.sleep(500); continue }
            if (track == null) {
                track = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build().also { it.play() }
                }.onFailure { log("audio: output failed to open: ${it.message}") }.getOrNull()
            }
            carry = 0
            ins.use { s ->
                while (!stop) {
                    val got = try { s.read(buf, carry, 4096) } catch (e: Exception) { -1 }
                    if (got < 0) break
                    if (got == 0) continue
                    val n = carry + got
                    val whole = n - n % FRAME
                    if (played == 0L) log("audio started")
                    if (whole > 0) {
                        audioCaptureHook?.invoke(buf, 0, whole)
                        val t = track
                        if (t != null && !muted) {
                            // blocking write: the phone's mixer paces us smoothly, without the 4 ms polling
                            // steps that let the track run dry while the system was busy
                            var off = 0
                            while (off < whole && !stop) {
                                val w = t.write(buf, off, whole - off, AudioTrack.WRITE_BLOCKING)
                                if (w > 0) off += w
                                else { if (w < 0) { runCatching { t.release() }; track = null }; break }
                            }
                        } else {
                            // nothing drains the pipe at play speed while muted: keep the guest's time ourselves,
                            // or its mixer spins and eats the CPU the game needs
                            runCatching { Thread.sleep(whole / FRAME * 1000L / rate) }
                        }
                        played += whole
                    }
                    carry = n - whole
                    if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry)
                }
            }
        }
        runCatching { track?.stop(); track?.release() }
    }

    companion object {
        const val RATE = 48000
        private const val FRAME = 4 // 16-bit stereo
    }
}
