package com.bigoke.twenty4hours.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Robot voice": speaks text with the phone's own text-to-speech, then reshapes it to sound like a
 * small droid — ring modulation, a slight pitch-up, lo-fi crunch and chirps between words —
 * while keeping the words understandable.
 *
 * Use [render] to get processed audio, then [play] it. Call [release] when done.
 */
class RobotVoice(private val ctx: Context) {

    class Clip(val samples: ShortArray, val sampleRate: Int) {
        val durationMs: Long get() = samples.size * 1000L / sampleRate
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = mutableListOf<() -> Unit>()
    private var track: AudioTrack? = null
    private var counter = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) tts?.language = Locale.getDefault()
                waiting.forEach { it() }
                waiting.clear()
            }
        }
    }

    /** Turn [text] into processed droid audio. [done] runs on the main thread (null on failure). */
    fun render(text: String, done: (Clip?) -> Unit) {
        val go = go@{
            val engine = tts
            if (!ready || engine == null) { done(null); return@go }
            val id = "robot${counter++}"
            val file = File(ctx.cacheDir, "$id.wav")
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != id) return
                    // DSP off the main thread
                    Thread {
                        val clip = runCatching { readWav(file)?.let { process(it.first, it.second) } }.getOrNull()
                        file.delete()
                        main.post { done(clip) }
                    }.start()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) main.post { done(null) } }
            })
            engine.setPitch(1.1f)
            engine.setSpeechRate(0.92f)
            val r = engine.synthesizeToFile(text, Bundle(), file, id)
            if (r != TextToSpeech.SUCCESS) done(null)
        }
        if (ready || tts == null) go() else waiting += go
    }

    /** Play a rendered clip. [alarm] routes it through the alarm volume. */
    fun play(clip: Clip, alarm: Boolean, volume: Float = 1f) {
        stop()
        val attrs = AudioAttributes.Builder()
            .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(clip.sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bytes = clip.samples.size * 2
        val t = runCatching {
            AudioTrack(attrs, fmt, bytes, AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        }.getOrNull() ?: return
        t.write(clip.samples, 0, clip.samples.size)
        t.setVolume(volume)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    fun release() {
        stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ------------------------------------------------------------------ audio

    /** 16-bit PCM WAV → floats (mono) + sample rate. */
    private fun readWav(f: File): Pair<FloatArray, Int>? {
        val b = f.readBytes()
        if (b.size < 44) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 22050
        var channels = 1
        var bits = 16
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                val start = pos + 8
                val len = minOf(if (size <= 0) b.size - start else size, b.size - start)
                if (bits != 16) return null
                val frames = len / 2 / channels
                val out = FloatArray(frames) { i -> bb.getShort(start + i * 2 * channels) / 32768f }
                return out to rate
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun process(input: FloatArray, rate: Int): Clip {
        // 1. Pitch up a little (small, chirpy droid) by resampling
        val pitch = 1.16f
        val n = (input.size / pitch).toInt()
        val v = FloatArray(n) { i ->
            val x = i * pitch
            val k = floor(x).toInt()
            val f = x - k
            val a = input.getOrElse(k) { 0f }
            val c = input.getOrElse(k + 1) { 0f }
            a + (c - a) * f
        }

        // 2. Ring modulation — the classic robot timbre — blended so words stay clear
        val ringHz = 62.0
        val mix = 0.55f
        for (i in v.indices) {
            val carrier = sin(2 * PI * ringHz * i / rate).toFloat()
            v[i] = v[i] * (1 - mix) + v[i] * carrier * mix * 1.6f
        }

        // 3. Light bitcrush: hold every other sample and quantise for lo-fi grit
        val steps = 40f
        var held = 0f
        for (i in v.indices) {
            if (i % 2 == 0) held = v[i]
            v[i] = (Math.round(held * steps) / steps) * 0.35f + v[i] * 0.65f
        }

        // 4. Chirps: one to start, one in each pause between words, a falling one at the end
        val gaps = findGaps(v, rate)
        val out = ArrayList<Float>(v.size + rate)
        fun addChirp(from: Float, to: Float, ms: Int) = chirp(rate, from, to, ms).forEach { out.add(it) }
        addChirp(900f, 2600f, 70); repeat(rate * 30 / 1000) { out.add(0f) }
        addChirp(2200f, 1300f, 60); repeat(rate * 60 / 1000) { out.add(0f) }
        var gi = 0
        var nextChirp = 0
        for (i in v.indices) {
            out.add(v[i])
            if (gi < gaps.size && i == gaps[gi]) {
                val up = nextChirp % 2 == 0
                nextChirp++
                addChirp(if (up) 1100f else 2400f, if (up) 2700f else 1400f, 55)
                gi++
            }
        }
        repeat(rate * 50 / 1000) { out.add(0f) }
        addChirp(2600f, 700f, 120)

        // 5. Normalise and convert to 16-bit
        var peak = 0.0001f
        out.forEach { peak = maxOf(peak, abs(it)) }
        val g = 0.92f / peak
        val pcm = ShortArray(out.size) { i -> (out[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        return Clip(pcm, rate)
    }

    /** Sample positions in the middle of quiet stretches (≥ 90 ms) between spoken parts. */
    private fun findGaps(v: FloatArray, rate: Int): List<Int> {
        val win = rate / 100 // 10 ms windows
        if (win <= 0 || v.size < win * 4) return emptyList()
        val rms = FloatArray(v.size / win) { w ->
            var s = 0f
            for (j in 0 until win) { val x = v[w * win + j]; s += x * x }
            sqrt(s / win)
        }
        val loud = (rms.maxOrNull() ?: 0f)
        val quiet = loud * 0.06f
        val gaps = mutableListOf<Int>()
        var run = 0
        var spoke = false
        for (w in rms.indices) {
            if (rms[w] < quiet) run++ else {
                if (spoke && run >= 9) gaps += (w - run / 2) * win
                run = 0
                spoke = true
            }
        }
        return gaps
    }

    private fun chirp(rate: Int, from: Float, to: Float, ms: Int): FloatArray {
        val n = rate * ms / 1000
        var phase = 0.0
        return FloatArray(n) { i ->
            val f = from + (to - from) * i / n
            phase += 2 * PI * f / rate
            (sin(phase) * 0.35 * sin(PI * i / n)).toFloat()
        }
    }
}
