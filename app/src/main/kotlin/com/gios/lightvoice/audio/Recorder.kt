package com.gios.lightvoice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Microphone capture straight to a 16 kHz mono WAV — the format Whisper wants, so
 * nothing is transcoded on the way out.
 *
 * [AudioRecord] rather than [android.media.MediaRecorder] because the level meter
 * and the end-of-speech detection both need the samples as they arrive:
 * `MediaRecorder.getMaxAmplitude` is coarse and only polls, and an assistant that
 * waits for you to press stop does not feel like one.
 */
class Recorder(private val context: Context) {

    private val _level = MutableStateFlow(0f)

    /** Smoothed RMS, 0..1, for the level meter. */
    val level: StateFlow<Float> = _level

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var target: File? = null

    @Volatile
    private var running = false

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts capturing. [onEndOfSpeech] fires from a background thread once the
     * speaker has clearly stopped, or at [MAX_MILLIS]; the caller is expected to
     * call [stop] in response. Returns false if the mic is unavailable.
     */
    fun start(onEndOfSpeech: () -> Unit): Boolean {
        if (running || !hasPermission()) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE) // ~0.5s of headroom

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        }.getOrNull() ?: return false

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        val wav = File(context.cacheDir, "utterance.wav")
        val out = RandomAccessFile(wav, "rw")
        out.setLength(0)
        out.write(ByteArray(HEADER_BYTES)) // patched with real sizes in stop()

        record = rec
        target = wav
        running = true
        rec.startRecording()

        worker = Thread {
            val buffer = ShortArray(2048)
            val bytes = ByteArray(buffer.size * 2)
            var floor = -1f
            var elapsedMillis = 0L
            var loudSoFar = 0L
            var quietSinceMillis = 0L
            var ended = false

            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                runCatching { out.write(bytes, 0, read * 2) }

                val rms = (sqrt(sum / read) / Short.MAX_VALUE).toFloat()
                _level.value = _level.value * 0.6f + rms * 0.4f

                val chunkMillis = read * 1000L / SAMPLE_RATE
                elapsedMillis += chunkMillis

                // Calibrate the noise floor from the first moments, so a quiet room and
                // a subway platform both get a sensible speech threshold.
                if (elapsedMillis < CALIBRATE_MILLIS) {
                    floor = if (floor < 0f) rms else maxOf(floor, rms)
                    continue
                }
                val threshold = maxOf(MIN_THRESHOLD, floor * 2.5f)

                if (rms > threshold) {
                    loudSoFar += chunkMillis
                    quietSinceMillis = 0
                } else if (loudSoFar > MIN_SPEECH_MILLIS) {
                    quietSinceMillis += chunkMillis
                }

                val trailedOff = quietSinceMillis >= TRAILING_SILENCE_MILLIS
                if (!ended && (trailedOff || elapsedMillis >= MAX_MILLIS)) {
                    ended = true
                    onEndOfSpeech()
                }
            }
            runCatching { out.close() }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    /** Stops capture and returns the finished WAV, or null if nothing usable. */
    fun stop(): File? {
        if (!running) return null
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { worker?.join(500) }
        worker = null
        _level.value = 0f

        val wav = target ?: return null
        target = null
        val pcmBytes = wav.length() - HEADER_BYTES
        if (pcmBytes < SAMPLE_RATE / 4) return null // under ~0.25s: a slip, not a sentence
        return runCatching { writeHeader(wav, pcmBytes); wav }.getOrNull()
    }

    fun cancel() {
        stop()
        target?.delete()
    }

    /** Fills in the 44-byte RIFF header now that the payload length is known. */
    private fun writeHeader(wav: File, pcmBytes: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        RandomAccessFile(wav, "rw").use { f ->
            f.seek(0)
            f.write("RIFF".toByteArray())
            f.write(le32((36 + pcmBytes).toInt()))
            f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray())
            f.write(le32(16))
            f.write(le16(1)) // PCM
            f.write(le16(CHANNELS))
            f.write(le32(SAMPLE_RATE))
            f.write(le32(byteRate))
            f.write(le16(CHANNELS * BITS / 8))
            f.write(le16(BITS))
            f.write("data".toByteArray())
            f.write(le32(pcmBytes.toInt()))
        }
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS = 16
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val HEADER_BYTES = 44
        const val CALIBRATE_MILLIS = 250L
        const val MIN_THRESHOLD = 0.02f
        const val MIN_SPEECH_MILLIS = 300L
        const val TRAILING_SILENCE_MILLIS = 1300L
        const val MAX_MILLIS = 20_000L
    }
}
