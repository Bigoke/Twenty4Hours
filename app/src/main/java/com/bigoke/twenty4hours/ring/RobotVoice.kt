package com.bigoke.twenty4hours.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.bigoke.twenty4hours.data.VoiceStyle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * "Robot voice": the phone's best offline text-to-speech voice records the words, then a small
 * effects chain turns them into a robot while keeping them easy to understand.
 *
 * Styles:
 *  - DROID   — small and chirpy: raised pitch, light ring modulation, metallic body, gentle crunch,
 *              chirps between words.
 *  - CLASSIC — sci-fi robot: lowered pitch, strong ring modulation, metallic resonance.
 *  - VOCODER — the smooth "synth voice": a 20-band channel vocoder playing a buzzy synth with the
 *              shape of your words, plus a breath of noise so consonants stay clear.
 *
 * [intensity] (0–1) blends between the clean voice and the full effect.
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
                if (ready) tts?.let { pickBestVoice(it) }
                waiting.forEach { it() }
                waiting.clear()
            }
        }
    }

    /** Highest-quality installed voice for the phone's language that works offline. */
    private fun pickBestVoice(t: TextToSpeech) {
        val loc = Locale.getDefault()
        runCatching {
            val voices = t.voices ?: emptySet()
            fun usable(v: Voice) = !v.isNetworkConnectionRequired &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            val best = voices.filter { usable(it) && it.locale.language == loc.language }
                .sortedWith(compareByDescending<Voice> { it.locale.country == loc.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency })
                .firstOrNull()
            if (best != null) t.voice = best else t.language = loc
        }.onFailure { t.language = loc }
    }

    /** Turn [text] into robot audio. [done] runs on the main thread (null on failure). */
    fun render(text: String, style: VoiceStyle, intensity: Float, done: (Clip?) -> Unit) {
        val go = go@{
            val engine = tts
            if (!ready || engine == null) { done(null); return@go }
            val id = "robot${counter++}"
            val file = File(ctx.cacheDir, "$id.wav")
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != id) return
                    Thread {
                        val clip = runCatching {
                            readWav(file)?.let { (samples, rate) -> process(samples, rate, style, intensity.coerceIn(0f, 1f)) }
                        }.getOrNull()
                        file.delete()
                        main.post { done(clip) }
                    }.start()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id) main.post { done(null) } }
            })
            // Pitch is set on the speech engine itself: no speed change, no graininess
            engine.setPitch(
                when (style) {
                    VoiceStyle.DROID -> 1.0f + 0.45f * intensity
                    VoiceStyle.CLASSIC -> 1.0f - 0.2f * intensity
                    VoiceStyle.VOCODER -> 1.0f
                }
            )
            engine.setSpeechRate(if (style == VoiceStyle.CLASSIC) 0.88f else 0.95f)
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
        val t = runCatching {
            AudioTrack(attrs, fmt, clip.samples.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
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

    // ================================================================== DSP

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
                channels = bb.getShort(pos + 10).toInt().coerceAtLeast(1)
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                val start = pos + 8
                val len = minOf(if (size <= 0) b.size - start else size, b.size - start)
                if (bits != 16) return null
                val frames = len / 2 / channels
                return FloatArray(frames) { i -> bb.getShort(start + i * 2 * channels) / 32768f } to rate
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }

    private fun process(dry: FloatArray, rate: Int, style: VoiceStyle, k: Float): Clip {
        normalize(dry, 0.9f)
        val wet = when (style) {
            VoiceStyle.DROID -> droid(dry, rate, k)
            VoiceStyle.CLASSIC -> classic(dry, rate, k)
            VoiceStyle.VOCODER -> vocoder(dry, rate)
        }
        // Blend: even at full strength keep a trace of the clean voice for clarity
        val wetMix = 0.25f + 0.7f * k
        val v = FloatArray(dry.size) { i -> dry[i] * (1 - wetMix) + wet[i] * wetMix }
        highPass(v, rate, 90f) // remove rumble from ring modulation
        val body = if (style == VoiceStyle.DROID) withChirps(v, rate, k) else v
        // Gentle soft-limit + level, and 10 ms fades so nothing clicks
        for (i in body.indices) body[i] = tanh(body[i] * 1.4f) / tanh(1.4f)
        normalize(body, 0.92f)
        fade(body, rate * 10 / 1000)
        val pcm = ShortArray(body.size) { i -> (body[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        return Clip(pcm, rate)
    }

    // ---------------------------------------------------------------- styles

    private fun droid(x: FloatArray, rate: Int, k: Float): FloatArray {
        val v = x.copyOf()
        ringMod(v, rate, 48f + 30f * k, 0.35f + 0.25f * k)
        comb(v, rate, delayMs = 3.2f, feedback = 0.45f + 0.2f * k, mix = 0.35f)
        // Smooth first, then a light crunch — gritty, not fizzy
        lowPass(v, rate, 5200f)
        crush(v, rate, targetRate = 12000, bits = 7, mix = 0.25f + 0.25f * k)
        return v
    }

    private fun classic(x: FloatArray, rate: Int, k: Float): FloatArray {
        val v = x.copyOf()
        ringMod(v, rate, 32f + 18f * k, 0.6f + 0.35f * k)
        comb(v, rate, delayMs = 6.5f, feedback = 0.55f + 0.2f * k, mix = 0.4f)
        comb(v, rate, delayMs = 9.1f, feedback = 0.4f, mix = 0.2f)
        lowPass(v, rate, 6000f)
        return v
    }

    /**
     * Channel vocoder: a buzzy synth (saw + a little noise) shaped by the voice in 24 bands.
     * Tuned by measuring how well each band's loudness follows the original speech over time
     * (pre-emphasis + a fast 6 ms envelope kept the words clearest).
     */
    private fun vocoder(x: FloatArray, rate: Int): FloatArray {
        val n = x.size
        val bands = 24
        val fLo = 110.0
        val fHi = minOf(7500.0, rate * 0.45)
        val ratio = (fHi / fLo).pow(1.0 / bands)
        val q = sqrt(ratio) / (ratio - 1) * 1.3

        // Pre-emphasis on the analysed voice so consonants drive the high bands
        val mod = FloatArray(n) { i -> x[i] - 0.9f * (if (i > 0) x[i - 1] else 0f) }

        // Carrier: a 104 Hz saw, plus noise so "s", "t" and "f" sounds stay audible
        val carrier = FloatArray(n)
        var p = 0.0
        val rnd = Random(7)
        val noise = 0.18f
        for (i in 0 until n) {
            p += 104.0 / rate; if (p >= 1) p -= 1
            carrier[i] = ((2 * p - 1) * (1 - noise) + (rnd.nextFloat() * 2 - 1) * noise).toFloat()
        }

        val out = FloatArray(n)
        val smooth = exp(-1.0 / (0.006 * rate)).toFloat()
        for (b in 0 until bands) {
            val fc = fLo * ratio.pow(b + 0.5)
            val mf = Biquad.bandPass(fc, q, rate)
            val cf = Biquad.bandPass(fc, q, rate)
            var env = 0f
            for (i in 0 until n) {
                env = smooth * env + (1 - smooth) * abs(mf.step(mod[i]))
                out[i] += cf.step(carrier[i]) * env
            }
        }
        normalize(out, 0.9f)
        return out
    }

    // ---------------------------------------------------------------- building blocks

    private fun ringMod(v: FloatArray, rate: Int, hz: Float, mix: Float) {
        for (i in v.indices) {
            val c = sin(2 * PI * hz * i / rate).toFloat()
            v[i] = v[i] * (1 - mix) + v[i] * c * mix * 1.5f
        }
    }

    /** Short feedback comb filter: the "metal tube" resonance. */
    private fun comb(v: FloatArray, rate: Int, delayMs: Float, feedback: Float, mix: Float) {
        val d = (rate * delayMs / 1000f).toInt().coerceAtLeast(1)
        val buf = FloatArray(d)
        var idx = 0
        for (i in v.indices) {
            val y = buf[idx]
            val inp = v[i] + y * feedback
            buf[idx] = inp
            idx = (idx + 1) % d
            v[i] = v[i] * (1 - mix) + y * mix * 1.3f
        }
    }

    private fun crush(v: FloatArray, rate: Int, targetRate: Int, bits: Int, mix: Float) {
        val hold = (rate.toFloat() / targetRate).coerceAtLeast(1f)
        val steps = 2f.pow(bits - 1)
        var acc = 0f
        var held = 0f
        for (i in v.indices) {
            acc += 1f
            if (acc >= hold) { acc -= hold; held = floor(v[i] * steps) / steps }
            v[i] = v[i] * (1 - mix) + held * mix
        }
    }

    private fun lowPass(v: FloatArray, rate: Int, hz: Float) {
        val f = Biquad.lowPass(hz.toDouble(), 0.707, rate)
        for (i in v.indices) v[i] = f.step(v[i])
    }

    private fun highPass(v: FloatArray, rate: Int, hz: Float) {
        val f = Biquad.highPass(hz.toDouble(), 0.707, rate)
        for (i in v.indices) v[i] = f.step(v[i])
    }

    private fun normalize(v: FloatArray, peak: Float) {
        var m = 0.0001f
        for (s in v) m = maxOf(m, abs(s))
        val g = peak / m
        for (i in v.indices) v[i] *= g
    }

    private fun fade(v: FloatArray, n: Int) {
        val len = minOf(n, v.size / 2)
        for (i in 0 until len) {
            val g = i.toFloat() / len
            v[i] *= g
            v[v.size - 1 - i] *= g
        }
    }

    /** Droid chirps: two at the start, one in each pause between words, a falling one at the end. */
    private fun withChirps(v: FloatArray, rate: Int, k: Float): FloatArray {
        val level = 0.18f + 0.22f * k
        val gaps = findGaps(v, rate)
        val out = ArrayList<Float>(v.size + rate)
        fun add(from: Float, to: Float, ms: Int) = chirp(rate, from, to, ms, level).forEach { out.add(it) }
        fun silence(ms: Int) = repeat(rate * ms / 1000) { out.add(0f) }
        add(900f, 2600f, 70); silence(25); add(2300f, 1400f, 55); silence(70)
        var gi = 0
        var up = true
        for (i in v.indices) {
            out.add(v[i])
            if (gi < gaps.size && i == gaps[gi]) {
                if (up) add(1200f, 2800f, 50) else add(2500f, 1500f, 50)
                up = !up
                gi++
            }
        }
        silence(60)
        add(2600f, 700f, 130)
        return FloatArray(out.size) { out[it] }
    }

    private fun findGaps(v: FloatArray, rate: Int): List<Int> {
        val win = rate / 100
        if (win <= 0 || v.size < win * 4) return emptyList()
        val rms = FloatArray(v.size / win) { w ->
            var s = 0f
            for (j in 0 until win) { val x = v[w * win + j]; s += x * x }
            sqrt(s / win)
        }
        val quiet = (rms.maxOrNull() ?: 0f) * 0.06f
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

    /** A soft sine sweep with a little harmonic, enveloped so it doesn't click. */
    private fun chirp(rate: Int, from: Float, to: Float, ms: Int, level: Float): FloatArray {
        val n = rate * ms / 1000
        var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toFloat() / n
            val f = from * (to / from).toDouble().pow(t.toDouble())
            phase += 2 * PI * f / rate
            val env = sin(PI * t)
            ((sin(phase) + 0.25 * sin(2 * phase)) * level * env).toFloat()
        }
    }
}

/** Standard RBJ biquad filter. */
private class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun step(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun bandPass(f: Double, q: Double, rate: Int): Biquad {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q)
            return make(alpha, 0.0, -alpha, 1 + alpha, -2 * cos(w), 1 - alpha)
        }

        fun lowPass(f: Double, q: Double, rate: Int): Biquad {
            val w = 2 * PI * minOf(f, rate * 0.45) / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(f: Double, q: Double, rate: Int): Biquad {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}
