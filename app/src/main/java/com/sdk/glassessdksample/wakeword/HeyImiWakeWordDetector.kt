package com.sdk.glassessdksample.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Single-model "Hey IMI" detector using custom_wakeword/imi_cnn_mobile.onnx.
 *
 * This is a 1:1 port of the iOS wake-word pipeline (WakeWordDetector.swift +
 * MelSpectrogramExtractor.swift). The SAME model file and the SAME preprocessing
 * and detection constants are used so Android behaves identically to iOS.
 *
 * Pipeline:
 * - 16 kHz mono input
 * - 1.5s rolling window (24,000 samples)
 * - 100ms step
 * - peak-normalize audio -> STFT (periodic 400-Hann in 512 FFT, hop 160)
 * - Slaney mel (40 bands, 80..7600 Hz) -> power_to_db clip[-80,0] -> normalize [-1,+1]
 * - ONNX input shape: [1,1,40,150]
 */
class HeyImiWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "HeyImiWakeWord"

        // Single embedded model, identical to the iOS app (imi_cnn_mobile.onnx).
        // Weights are embedded (no external .data sidecar).
        private const val MODEL_FP32 = "custom_wakeword/imi_cnn_mobile.onnx"

        const val SAMPLE_RATE = 16_000
        private const val N_MELS = 40
        private const val N_FFT = 512
        private const val WIN_LEN = 400
        private const val HOP_LEN = 160
        private const val FMIN = 80.0
        private const val FMAX = 7600.0
        private const val N_TIME = 150
        private const val TOP_DB = 80.0f

        const val BUFFER_SIZE = N_TIME * HOP_LEN // 24,000 samples = 1.5s
        const val CHUNK_SIZE = 1_600 // 100ms

        // Detection constants — EXACTLY matching iOS WakeWordDetector.swift
        // (phone/headset-mic settings). Do not diverge from these without also
        // changing iOS, or the two apps will behave differently.
        const val DEFAULT_THRESHOLD = 0.55f        // sustained EMA fire level
        const val DEFAULT_PEAK_TRIGGER = 0.85f     // single raw frame -> fire now
        private const val DEFAULT_SMOOTHING = 2     // 2/(2+1) => emaAlpha 0.667
        private const val DEFAULT_CONSEC = 2        // frames above threshold to fire
        private const val DEFAULT_COOLDOWN_MS = 2_000L
        private const val DEFAULT_ENERGY_GATE = 0.005f // RMS below this = silence, skip model
        private const val EXTERNAL_AUDIO_PRIORITY_MS = 1500L
    }

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName = "mel_spectrogram"

    private var audioRecorder: AudioRecord? = null
    private var listeningThread: Thread? = null
    @Volatile
    private var isListening = false
    private var activeAudioSource: Int = MediaRecorder.AudioSource.MIC
    private var lastMonitorLogTs = 0L
    @Volatile
    private var lastExternalAudioTs = 0L
    private var lastExternalPriorityLogTs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    // Context handle for the shared WakeChimePlayer, not a per-detector player.
    // Each detector used to own a MediaPlayer here; playback now goes through the
    // one shared SoundPool so the chime survives the SCO route the detector runs
    // under, and there is nothing per-detector left to release.
    private var chimePlayer: Context? = null

    private val rollingBuffer = ShortArray(BUFFER_SIZE)
    private val bufferLock = Any()

    private var threshold = DEFAULT_THRESHOLD
    private var peakTrigger = DEFAULT_PEAK_TRIGGER
    private val smoothing = DEFAULT_SMOOTHING
    private val consec = DEFAULT_CONSEC
    private val cooldownMs = DEFAULT_COOLDOWN_MS
    private val energyGate = DEFAULT_ENERGY_GATE

    // iOS emaAlpha = 0.667 (SMOOTHING=2 => 2/(2+1)).
    private val emaAlpha = 2.0f / (smoothing + 1.0f)

    private var ema = 0.0f
    private var streak = 0
    private var lastFireTs = 0L

    private val fft = FloatFFT_1D(N_FFT.toLong())
    private val hannWindow = buildHannWindow()
    private val melFilterBank = buildMelFilterBank()

    @Throws(Exception::class)
    fun initialize() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }

            // Load the same single-file model the iOS app ships (weights embedded).
            val modelBytes = loadModelFromAssets(MODEL_FP32)
            session = ortEnv?.createSession(modelBytes, options)
            val modelUsed = MODEL_FP32

            inputName = session?.inputNames?.firstOrNull() ?: "mel_spectrogram"
            validateModelSignature()

            preloadChimeSound()
            Log.i(TAG, "Initialized wake-word model: $modelUsed input=$inputName")
        } catch (e: Exception) {
            cleanup()
            throw e
        }
    }

    fun setThreshold(value: Float) {
        threshold = value.coerceIn(0.005f, 0.99f)
        Log.d(TAG, "Threshold set: threshold=$threshold")
    }

    fun getThreshold(): Float = threshold

    /**
     * Raise/lower the single-frame instant-fire level.
     *
     * Mark 2's microphone is markedly more sensitive than Mark 1's, and on it the
     * model scores 0.93-0.97 on ordinary conversation that does not contain the
     * wake phrase — comfortably over the 0.85 default, so every one of those fires
     * immediately via the peak path without the EMA/consec gate ever being
     * consulted. Note this cannot be fixed by [setThreshold]: that governs a
     * different gate which these detections never reach.
     */
    fun setPeakTrigger(value: Float) {
        peakTrigger = value.coerceIn(0.05f, 1.0f)
        Log.d(TAG, "Peak trigger set: peakTrigger=$peakTrigger")
    }

    fun getPeakTrigger(): Float = peakTrigger

    fun start() {
        if (isListening) return
        if (session == null || ortEnv == null) {
            Log.e(TAG, "Call initialize() before start()")
            return
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecorder = createInitializedRecorder(max(minBufferSize, CHUNK_SIZE * 4))
            if (audioRecorder == null) {
                Log.e(TAG, "AudioRecord init failed for all sources")
                return
            }

            resetState()
            synchronized(bufferLock) {
                rollingBuffer.fill(0)
            }
            lastMonitorLogTs = 0L

            isListening = true
            audioRecorder?.startRecording()

            listeningThread = Thread({ processAudioLoop() }, "HeyImiWakeWord-Loop").also { it.start() }
            Log.i(
                TAG,
                "Wake config: threshold=$threshold peakTrigger=$peakTrigger smoothing=$smoothing consec=$consec cooldownMs=$cooldownMs energyGate=$energyGate emaAlpha=$emaAlpha"
            )
            Log.i(TAG, "Wake-word listening started (source=${audioSourceName(activeAudioSource)})")
            logRoutedInputDevice()
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "start() failed: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        isListening = false
        try {
            listeningThread?.interrupt()
            listeningThread = null

            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null
        } catch (_: Exception) {
        }
        Log.i(TAG, "Wake-word listening stopped")
    }

    fun cleanup() {
        stop()
        try {
            session?.close()
            ortEnv?.close()
        } catch (_: Exception) {
        }
        session = null
        ortEnv = null
        chimePlayer = null
    }

    fun isListening(): Boolean = isListening

    fun playChimeSound() {
        WakeChime.play(chimePlayer, TAG)
    }

    fun processExternalAudio(pcmData: ByteArray) {
        if (!isListening) return

        try {
            lastExternalAudioTs = System.currentTimeMillis()
            val shortBuffer = ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val samples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(samples)

            synchronized(bufferLock) {
                val copySize = minOf(samples.size, BUFFER_SIZE)
                System.arraycopy(rollingBuffer, copySize, rollingBuffer, 0, BUFFER_SIZE - copySize)
                System.arraycopy(samples, samples.size - copySize, rollingBuffer, BUFFER_SIZE - copySize, copySize)
            }

            evaluateCurrentWindow()
        } catch (e: Exception) {
            Log.e(TAG, "processExternalAudio failed: ${e.message}")
        }
    }

    private fun processAudioLoop() {
        val chunk = ShortArray(CHUNK_SIZE)
        var emptyReadCount = 0

        while (isListening && !Thread.currentThread().isInterrupted) {
            try {
                val read = audioRecorder?.read(chunk, 0, chunk.size) ?: 0
                if (read <= 0) {
                    emptyReadCount += 1
                    if (emptyReadCount % 30 == 0) {
                        Log.w(
                            TAG,
                            "Wake audio read returned $read repeatedly (count=$emptyReadCount source=${audioSourceName(activeAudioSource)})"
                        )
                    }
                    continue
                }

                if (emptyReadCount >= 30) {
                    Log.i(TAG, "Wake audio stream recovered after $emptyReadCount empty reads")
                }
                emptyReadCount = 0

                // External PCM (e.g., glasses mic) takes temporary priority.
                // Avoid mixing asynchronous internal mic frames with external frames.
                if ((System.currentTimeMillis() - lastExternalAudioTs) < EXTERNAL_AUDIO_PRIORITY_MS) {
                    val now = System.currentTimeMillis()
                    if (now - lastExternalPriorityLogTs >= 3000L) {
                        Log.d(TAG, "External PCM priority active; skipping internal mic chunk processing")
                        lastExternalPriorityLogTs = now
                    }
                    continue
                }

                // Append the new chunk to the tail of the rolling window, exactly
                // like the iOS RingBuffer (no speech-floor gating / silence-wipe;
                // the energy gate on the full window handles silence — see iOS
                // WakeWordDetector.checkForWakeWord).
                synchronized(bufferLock) {
                    System.arraycopy(rollingBuffer, read, rollingBuffer, 0, BUFFER_SIZE - read)
                    System.arraycopy(chunk, 0, rollingBuffer, BUFFER_SIZE - read, read)
                }

                evaluateCurrentWindow()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Audio loop error: ${e.message}")
            }
        }
    }

    /**
     * 1:1 port of iOS WakeWordDetector.checkForWakeWord + processScore + fireDetection.
     * Order: energy gate (on full window) -> infer -> EMA smooth -> peak trigger
     * (raw >= 0.85) -> threshold + consec gate (ema >= 0.55, 2 frames) -> cooldown.
     */
    private fun evaluateCurrentWindow() {
        val now = System.currentTimeMillis()

        val windowCopy = synchronized(bufferLock) { rollingBuffer.copyOf() }

        // Energy gate (iOS step 1): RMS of the FULL 1.5s window. On silence, skip
        // the model and decay the EMA toward zero so a stale streak can't survive.
        val rms = computeRms(windowCopy, windowCopy.size)
        if (rms < energyGate) {
            ema *= (1.0f - emaAlpha)
            streak = 0
            if (now - lastMonitorLogTs >= 3000L) {
                Log.d(
                    TAG,
                    "Wake monitor (gated): rms=${"%.5f".format(rms)} raw=0.000 ema=${"%.5f".format(ema)} threshold=${"%.3f".format(threshold)} source=${audioSourceName(activeAudioSource)}"
                )
                lastMonitorLogTs = now
            }
            return
        }

        val rawScore = infer(windowCopy)

        // EMA smooth (iOS step 2).
        ema = emaAlpha * rawScore + (1.0f - emaAlpha) * ema

        if (now - lastMonitorLogTs >= 3000L) {
            Log.d(
                TAG,
                "Wake monitor: rms=${"%.5f".format(rms)} raw=${"%.5f".format(rawScore)} ema=${"%.5f".format(ema)} streak=$streak threshold=${"%.3f".format(threshold)} peak=${"%.3f".format(peakTrigger)} source=${audioSourceName(activeAudioSource)}"
            )
            lastMonitorLogTs = now
        }

        // Peak trigger (iOS step 5): a single confident raw frame fires immediately.
        if (rawScore >= peakTrigger) {
            Log.i(TAG, "Peak trigger! raw=$rawScore")
            fireDetection(rawScore)
            return
        }

        // Threshold + consec gate (iOS steps 3-4).
        if (ema >= threshold) {
            streak += 1
            if (streak >= consec) {
                Log.i(TAG, "EMA trigger! ema=$ema hits=$streak")
                fireDetection(ema)
            }
        } else {
            streak = 0
        }
    }

    /** iOS fireDetection: cooldown debounce, reset EMA/streak, notify. */
    private fun fireDetection(confidence: Float) {
        val now = System.currentTimeMillis()
        if ((now - lastFireTs) < cooldownMs) {
            return
        }

        lastFireTs = now
        ema = 0.0f
        streak = 0
        Log.i(TAG, "Hey IMI detected: confidence=$confidence")
        isListening = false
        mainHandler.post {
            playChimeSound()
            onWakeWordDetected(confidence)
        }
    }

    private fun infer(audioShort: ShortArray): Float {
        return try {
            val audioFloat = FloatArray(audioShort.size)
            for (i in audioShort.indices) {
                audioFloat[i] = (audioShort[i].toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
            }

            val inputData = audioToMelInput(audioFloat)
            val tensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(inputData),
                longArrayOf(1, 1, N_MELS.toLong(), N_TIME.toLong())
            )
            val output = session?.run(mapOf(inputName to tensor))
            val score = extractScore(output)

            tensor.close()
            output?.close()
            score
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            0.0f
        }
    }

    private fun audioToMelInput(audio: FloatArray): FloatArray {
        val clipped = if (audio.size >= BUFFER_SIZE) {
            audio.copyOfRange(audio.size - BUFFER_SIZE, audio.size)
        } else {
            FloatArray(BUFFER_SIZE).also { dst ->
                System.arraycopy(audio, 0, dst, 0, audio.size)
            }
        }

        // Peak-normalize the audio (REQUIRED by the model contract, matching iOS
        // MelSpectrogramExtractor step 2: audio /= max(abs(audio)), skip if peak
        // < 1e-6). The model was trained on peak-normalized clips; without this,
        // scores collapse toward ~0. THIS WAS MISSING ON ANDROID.
        var peak = 0.0f
        for (v in clipped) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 1e-6f) {
            val inv = 1.0f / peak
            for (i in clipped.indices) clipped[i] *= inv
        }

        val spec = computePowerSpectrogram(clipped) // [nFrames][nFftBins]
        val mel = Array(N_MELS) { FloatArray(spec.size) }

        for (t in spec.indices) {
            val frame = spec[t]
            for (m in 0 until N_MELS) {
                var acc = 0.0f
                val filter = melFilterBank[m]
                for (k in filter.indices) {
                    val w = filter[k]
                    if (w > 0.0f) {
                        acc += frame[k] * w
                    }
                }
                val db = powerToDb(acc)
                val clippedDb = db.coerceIn(-TOP_DB, 0.0f)
                mel[m][t] = (clippedDb / TOP_DB) * 2.0f + 1.0f
            }
        }

        val out = FloatArray(N_MELS * N_TIME)
        for (m in 0 until N_MELS) {
            for (t in 0 until N_TIME) {
                val v = if (t < mel[m].size) mel[m][t] else -1.0f
                out[m * N_TIME + t] = v
            }
        }
        return out
    }

    private fun computePowerSpectrogram(audio: FloatArray): Array<FloatArray> {
        val pad = N_FFT / 2
        val padded = FloatArray(audio.size + pad * 2)
        System.arraycopy(audio, 0, padded, pad, audio.size)

        val frames = 1 + (padded.size - N_FFT) / HOP_LEN
        val nBins = N_FFT / 2 + 1
        val out = Array(frames) { FloatArray(nBins) }

        val fftInput = FloatArray(N_FFT * 2)

        for (frameIdx in 0 until frames) {
            val start = frameIdx * HOP_LEN
            fftInput.fill(0.0f)

            for (i in 0 until N_FFT) {
                fftInput[i] = padded[start + i] * hannWindow[i]
            }

            fft.realForwardFull(fftInput)

            for (k in 0 until nBins) {
                val re = fftInput[2 * k]
                val im = fftInput[2 * k + 1]
                out[frameIdx][k] = re * re + im * im
            }
        }

        return out
    }

    private fun buildHannWindow(): FloatArray {
        val window = FloatArray(N_FFT)
        val offset = (N_FFT - WIN_LEN) / 2
        // PERIODIC Hann (librosa fftbins=True / torch periodic=True), matching iOS
        // MelSpectrogramExtractor.buildHannWindow: denominator is WIN_LEN, NOT
        // WIN_LEN-1. The model was trained with the periodic window; the symmetric
        // form shifts every coefficient and shows up as a ~0.006 mel error.
        val denom = WIN_LEN.toFloat()
        for (i in 0 until WIN_LEN) {
            val v = 0.5f - 0.5f * cos((2.0 * PI * i / denom).toFloat())
            window[offset + i] = v
        }
        return window
    }

    private fun buildMelFilterBank(): Array<FloatArray> {
        val nBins = N_FFT / 2 + 1
        val binsHz = FloatArray(nBins) { i ->
            i * SAMPLE_RATE.toFloat() / N_FFT.toFloat()
        }

        // Match librosa.feature.melspectrogram defaults used by the terminal script:
        // - htk = false (Slaney mel scale)
        // - norm = "slaney"
        val hzPoints = librosaMelFrequencies(N_MELS + 2, FMIN.toFloat(), FMAX.toFloat())
        val fdiff = FloatArray(hzPoints.size - 1) { i -> hzPoints[i + 1] - hzPoints[i] }

        val filterbank = Array(N_MELS) { FloatArray(nBins) }
        for (m in 0 until N_MELS) {
            val lowerDen = max(fdiff[m], 1e-8f)
            val upperDen = max(fdiff[m + 1], 1e-8f)

            for (k in 0 until nBins) {
                val hz = binsHz[k]
                val lower = (hz - hzPoints[m]) / lowerDen
                val upper = (hzPoints[m + 2] - hz) / upperDen
                val w = kotlin.math.min(lower, upper).coerceAtLeast(0.0f)
                filterbank[m][k] = w
            }

            val slaneyDen = hzPoints[m + 2] - hzPoints[m]
            val slaneyNorm = if (slaneyDen > 1e-8f) 2.0f / slaneyDen else 1.0f
            for (k in 0 until nBins) {
                filterbank[m][k] *= slaneyNorm
            }
        }

        return filterbank
    }

    private fun hzToMelSlaney(hz: Float): Float {
        val fSp = 200.0f / 3.0f
        val minLogHz = 1000.0f
        val minLogMel = minLogHz / fSp
        val logStep = (ln(6.4) / 27.0).toFloat()

        return if (hz >= minLogHz) {
            minLogMel + (ln((hz / minLogHz).toDouble()).toFloat() / logStep)
        } else {
            hz / fSp
        }
    }

    private fun melToHzSlaney(mel: Float): Float {
        val fSp = 200.0f / 3.0f
        val minLogHz = 1000.0f
        val minLogMel = minLogHz / fSp
        val logStep = (ln(6.4) / 27.0).toFloat()

        return if (mel >= minLogMel) {
            minLogHz * exp((mel - minLogMel) * logStep)
        } else {
            mel * fSp
        }
    }

    private fun librosaMelFrequencies(nMels: Int, fMinHz: Float, fMaxHz: Float): FloatArray {
        val minMel = hzToMelSlaney(fMinHz)
        val maxMel = hzToMelSlaney(fMaxHz)
        val frequencies = FloatArray(nMels)

        if (nMels <= 1) {
            if (nMels == 1) frequencies[0] = melToHzSlaney(minMel)
            return frequencies
        }

        val step = (maxMel - minMel) / (nMels - 1).toFloat()
        for (i in 0 until nMels) {
            frequencies[i] = melToHzSlaney(minMel + step * i)
        }
        return frequencies
    }

    private fun powerToDb(power: Float): Float {
        val p = max(power.toDouble(), 1e-10)
        return (10.0 * ln(p) / ln(10.0)).toFloat()
    }

    private fun extractScore(result: OrtSession.Result?): Float {
        if (result == null) return 0.0f
        return try {
            val value = result[0].value
            extractFirstNumeric(value) ?: 0.0f
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read score: ${e.message}")
            0.0f
        }
    }

    private fun extractFirstNumeric(value: Any?): Float? {
        return when (value) {
            is Number -> value.toFloat()
            is FloatArray -> value.firstOrNull()
            is DoubleArray -> value.firstOrNull()?.toFloat()
            is IntArray -> value.firstOrNull()?.toFloat()
            is Array<*> -> value.asSequence().mapNotNull { extractFirstNumeric(it) }.firstOrNull()
            else -> null
        }
    }

    private fun computeRms(chunk: ShortArray, size: Int): Float {
        if (size <= 0) return 0.0f
        var sum = 0.0
        for (i in 0 until size) {
            val v = chunk[i].toDouble() / 32768.0
            sum += v * v
        }
        return sqrt(sum / size).toFloat()
    }

    private fun resetState() {
        ema = 0.0f
        streak = 0
        lastFireTs = 0L
        lastExternalAudioTs = 0L
        lastExternalPriorityLogTs = 0L
    }

    private fun createInitializedRecorder(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        for (source in sources) {
            try {
                val recorder = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    activeAudioSource = source
                    Log.i(TAG, "AudioRecord initialized with source=${audioSourceName(source)}")
                    return recorder
                }

                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=${audioSourceName(source)} (${e.message})")
            }
        }

        return null
    }

    /**
     * Log the microphone the system ACTUALLY gave us, not the one we asked for.
     *
     * [activeAudioSource] is only the requested AudioSource constant — it always
     * prints "MIC" even when the frames are really arriving from the glasses over
     * Bluetooth SCO, which happens whenever the device is left in
     * MODE_IN_COMMUNICATION with SCO on by a finished conversation. iOS runs its
     * wake detector on the phone mic (AudioSessionManager.activateForVoiceLoop,
     * which pins input to the glasses, is called only for conversations), so the
     * routed device is the one thing that has to match for parity.
     */
    private fun logRoutedInputDevice() {
        try {
            val device = audioRecorder?.routedDevice
            if (device == null) {
                Log.i(TAG, "🎙️ Wake mic route: UNKNOWN (routedDevice null)")
                return
            }
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "PHONE MIC (matches iOS)"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH SCO / GLASSES (diverges from iOS)"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED HEADSET"
                else -> "type=${device.type}"
            }
            Log.i(TAG, "🎙️ Wake mic route: $kind — ${device.productName}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read routed input device: ${e.message}")
        }
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> source.toString()
        }
    }

    private fun preloadChimeSound() {
        chimePlayer = WakeChime.createPlayer(context)
    }

    private fun loadModelFromAssets(assetPath: String): ByteArray {
        return context.assets.open(assetPath).use { it.readBytes() }
    }

    private fun validateModelSignature() {
        val sessionRef = session ?: return
        val info = sessionRef.inputInfo[inputName]?.info
        if (info !is TensorInfo) {
            Log.w(TAG, "Unable to validate model signature; input info is not TensorInfo")
            return
        }

        val shape = info.shape
        val expected = longArrayOf(1, 1, N_MELS.toLong(), N_TIME.toLong())
        if (shape.size != expected.size) {
            throw IllegalStateException(
                "Unexpected wake model input rank ${shape.size}; expected ${expected.size}. " +
                    "Actual shape=${shape.joinToString(prefix = "[", postfix = "]")}, expected=${expected.joinToString(prefix = "[", postfix = "]")}."
            )
        }

        val mismatches = mutableListOf<String>()
        for (i in expected.indices) {
            val actualDim = shape[i]
            val expectedDim = expected[i]

            // ONNX dynamic dimensions are commonly represented as -1.
            // Treat any non-positive value as dynamic and therefore compatible.
            val dynamicDim = actualDim <= 0L
            if (!dynamicDim && actualDim != expectedDim) {
                mismatches.add("dim[$i]=$actualDim (expected $expectedDim)")
            }
        }

        if (mismatches.isNotEmpty()) {
            throw IllegalStateException(
                "Unexpected wake model input shape ${shape.joinToString(prefix = "[", postfix = "]")}; " +
                    "expected ${expected.joinToString(prefix = "[", postfix = "]")}. Mismatches: ${mismatches.joinToString()}"
            )
        }

        val dynamicDims = shape.indices.mapNotNull { index -> if (shape[index] <= 0L) index else null }
        val dynamicSuffix = if (dynamicDims.isNotEmpty()) {
            " (dynamic dims=${dynamicDims.joinToString(prefix = "[", postfix = "]")})"
        } else {
            ""
        }

        Log.i(TAG, "Wake model signature validated: input=$inputName shape=${shape.joinToString(prefix = "[", postfix = "]")}$dynamicSuffix")
    }
}
