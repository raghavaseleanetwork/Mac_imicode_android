package com.sdk.glassessdksample.wakeword

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Snowboy-based wake-word detector.
 *
 * This class uses reflection so the app can compile even when Snowboy SDK
 * artifacts are not bundled yet. If the SDK/classes are absent, initialize()
 * throws with setup guidance and HotHelper can fall back to custom ONNX.
 */
class SnowboyWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "SnowboyWakeWord"
        private const val DEFAULT_THRESHOLD = 0.5f
        private const val DETECTION_COOLDOWN_MS = 2_000L
        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val DEFAULT_FRAME_LENGTH = 512

        private val MODEL_ASSET_CANDIDATES = listOf(
            "snowboy/hey_imi.pmdl",
            "custom_wakeword/hey_imi.pmdl",
            "hey_imi.pmdl"
        )

        private val RESOURCE_ASSET_CANDIDATES = listOf(
            "snowboy/common.res",
            "common.res"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var snowboyDetect: Any? = null
    private var runDetectionMethod: Method? = null
    private var runDetectionWithLengthMethod: Method? = null
    private var numSamplesMethod: Method? = null
    private var sampleRateMethod: Method? = null
    private var setSensitivityMethod: Method? = null
    private var setAudioGainMethod: Method? = null
    private var applyFrontendMethod: Method? = null

    private var audioRecorder: AudioRecord? = null
    private var listeningThread: Thread? = null
    @Volatile
    private var isListening = false

    private var threshold = DEFAULT_THRESHOLD
    private var lastDetectionTs = 0L
    private var chimePlayer: MediaPlayer? = null

    @Throws(Exception::class)
    fun initialize() {
        val detectorClass = try {
            Class.forName("ai.kitt.snowboy.SnowboyDetect")
        } catch (e: Exception) {
            throw IllegalStateException(
                "Snowboy SDK not found. Add Snowboy AAR/JNI to app/libs before selecting Snowboy.",
                e
            )
        }

        val resourceAsset = firstExistingAsset(RESOURCE_ASSET_CANDIDATES)
            ?: throw IllegalStateException("Snowboy resource file missing. Add snowboy/common.res to assets.")
        val modelAsset = firstExistingAsset(MODEL_ASSET_CANDIDATES)
            ?: throw IllegalStateException("Snowboy model missing. Add snowboy/hey_imi.pmdl to assets.")

        val resourceFile = copyAssetToFilesDir(resourceAsset)
        val modelFile = copyAssetToFilesDir(modelAsset)

        val detector = detectorClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance(resourceFile.absolutePath, modelFile.absolutePath)

        runDetectionMethod = detectorClass.methods.firstOrNull {
            it.name == "RunDetection" && it.parameterTypes.contentEquals(arrayOf(ShortArray::class.java))
        }
        runDetectionWithLengthMethod = detectorClass.methods.firstOrNull {
            it.name == "RunDetection" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == ShortArray::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }

        numSamplesMethod = detectorClass.methods.firstOrNull {
            it.name == "NumSamples" && it.parameterTypes.isEmpty()
        }
        sampleRateMethod = detectorClass.methods.firstOrNull {
            it.name == "SampleRate" && it.parameterTypes.isEmpty()
        }
        setSensitivityMethod = detectorClass.methods.firstOrNull {
            it.name == "SetSensitivity" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        setAudioGainMethod = detectorClass.methods.firstOrNull {
            it.name == "SetAudioGain" &&
                it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Float::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Float::class.java)
        }
        applyFrontendMethod = detectorClass.methods.firstOrNull {
            it.name == "ApplyFrontend" &&
                it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Boolean::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Boolean::class.java)
        }

        snowboyDetect = detector

        applyFrontendMethod?.invoke(detector, true)
        setAudioGainMethod?.invoke(detector, 1.0f)
        applyThresholdToDetector()
        preloadChimeSound()

        Log.i(TAG, "Snowboy initialized. model=$modelAsset resource=$resourceAsset")
    }

    fun setThreshold(value: Float) {
        threshold = value.coerceIn(0.05f, 1.0f)
        applyThresholdToDetector()
    }

    fun getThreshold(): Float = threshold

    fun start() {
        if (isListening) return
        if (snowboyDetect == null) {
            Log.e(TAG, "Call initialize() before start()")
            return
        }

        val sampleRate = getSampleRate().coerceAtLeast(DEFAULT_SAMPLE_RATE)
        val frameLength = getFrameLength().coerceAtLeast(160)

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferSize, frameLength * 4)
            )

            if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed for Snowboy")
                return
            }

            isListening = true
            audioRecorder?.startRecording()

            listeningThread = Thread({
                val frame = ShortArray(frameLength)
                while (isListening && !Thread.currentThread().isInterrupted) {
                    val read = audioRecorder?.read(frame, 0, frame.size) ?: 0
                    if (read > 0) {
                        if (read == frame.size) {
                            evaluateFrame(frame)
                        } else {
                            evaluateFrame(frame.copyOf(read))
                        }
                    }
                }
            }, "SnowboyWakeWord-Loop").also { it.start() }

            Log.i(TAG, "Snowboy listening started")
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO missing for Snowboy", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Snowboy: ${e.message}", e)
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
        Log.i(TAG, "Snowboy listening stopped")
    }

    fun cleanup() {
        stop()
        snowboyDetect = null
        runDetectionMethod = null
        runDetectionWithLengthMethod = null
        numSamplesMethod = null
        sampleRateMethod = null
        setSensitivityMethod = null
        setAudioGainMethod = null
        applyFrontendMethod = null
        try {
            chimePlayer?.release()
        } catch (_: Exception) {
        }
        chimePlayer = null
    }

    fun isListening(): Boolean = isListening

    fun playChimeSound() {
        WakeChime.play(chimePlayer, TAG)
    }

    fun processExternalAudio(pcmData: ByteArray) {
        if (!isListening) return

        try {
            val shortBuffer = ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            val samples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(samples)

            val frameLength = getFrameLength().coerceAtLeast(160)
            var index = 0
            while (index < samples.size) {
                val end = minOf(index + frameLength, samples.size)
                evaluateFrame(samples.copyOfRange(index, end))
                index = end
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snowboy processExternalAudio failed: ${e.message}")
        }
    }

    private fun evaluateFrame(frame: ShortArray) {
        val result = runDetection(frame)
        if (result > 0) {
            val now = System.currentTimeMillis()
            if (now - lastDetectionTs < DETECTION_COOLDOWN_MS) return
            lastDetectionTs = now

            isListening = false
            mainHandler.post {
                playChimeSound()
                onWakeWordDetected(1.0f)
            }
            Log.i(TAG, "Snowboy detected wake word")
        }
    }

    private fun runDetection(frame: ShortArray): Int {
        val detector = snowboyDetect ?: return -1
        return try {
            when {
                runDetectionWithLengthMethod != null -> {
                    (runDetectionWithLengthMethod?.invoke(detector, frame, frame.size) as? Int) ?: -1
                }
                runDetectionMethod != null -> {
                    (runDetectionMethod?.invoke(detector, frame) as? Int) ?: -1
                }
                else -> -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snowboy RunDetection failed: ${e.message}")
            -1
        }
    }

    private fun getFrameLength(): Int {
        val detector = snowboyDetect ?: return DEFAULT_FRAME_LENGTH
        return try {
            (numSamplesMethod?.invoke(detector) as? Int) ?: DEFAULT_FRAME_LENGTH
        } catch (_: Exception) {
            DEFAULT_FRAME_LENGTH
        }
    }

    private fun getSampleRate(): Int {
        val detector = snowboyDetect ?: return DEFAULT_SAMPLE_RATE
        return try {
            (sampleRateMethod?.invoke(detector) as? Int) ?: DEFAULT_SAMPLE_RATE
        } catch (_: Exception) {
            DEFAULT_SAMPLE_RATE
        }
    }

    private fun applyThresholdToDetector() {
        val detector = snowboyDetect ?: return
        try {
            // Snowboy expects a comma-separated sensitivity string for model(s).
            val sensitivity = threshold.coerceIn(0.05f, 1.0f)
            setSensitivityMethod?.invoke(detector, String.format("%.2f", sensitivity))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Snowboy sensitivity: ${e.message}")
        }
    }

    private fun preloadChimeSound() {
        chimePlayer = WakeChime.createPlayer(context)
    }

    private fun firstExistingAsset(candidates: List<String>): String? {
        for (asset in candidates) {
            try {
                context.assets.open(asset).close()
                return asset
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun copyAssetToFilesDir(assetName: String): File {
        val outFile = File(context.filesDir, assetName.replace('/', '_'))
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }
}
