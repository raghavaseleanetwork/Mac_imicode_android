package com.sdk.glassessdksample.ui

import android.util.Log
import com.sdk.glassessdksample.RemoteConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shazam-style song identification.
 *
 * Audio source: we do NOT open a second AudioRecord. GeminiLiveService already
 * owns the mic during a live session, so a second recorder would either fail to
 * acquire it or fight with the first one on OEM builds. Instead GeminiLiveService
 * tees the PCM frames it is already reading into [PcmRingBuffer] below, and this
 * object encodes the most recent few seconds to WAV and posts it to AudD.
 *
 * A useful side effect of the tee: by the time the user finishes saying "what
 * song is this?", the buffer already holds the audio from BEFORE they spoke, so
 * there is no "listening..." pause — the clip is ready immediately.
 */
object SongIdentifier {

    private const val TAG = "SongIdentifier"

    /** How much audio AudD gets. Short clips trigger their error #300 (audio too small). */
    const val CLIP_SECONDS = 8

    /**
     * Keep a little more than we send, so a clip is available even if the tee
     * started slightly late or the session was interrupted mid-turn.
     */
    private const val BUFFER_SECONDS = 12

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Fingerprinting on AudD's side is usually well under 2s, but ambient
        // clips are the slow case; keep this generous so we don't kill a
        // request that was about to succeed.
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Rolling window of raw PCM16 mono audio.
     *
     * Fixed-size byte array written circularly — no allocation per audio frame,
     * which matters because the capture loop calls [write] roughly every 30ms
     * for the whole session.
     */
    class PcmRingBuffer(val sampleRate: Int) {
        private val capacity = sampleRate * 2 * BUFFER_SECONDS // 2 bytes per sample, mono
        private val buf = ByteArray(capacity)
        private var writePos = 0
        private var filled = 0

        @Synchronized
        fun write(data: ByteArray, length: Int) {
            var offset = 0
            var remaining = length
            while (remaining > 0) {
                val chunk = minOf(remaining, capacity - writePos)
                System.arraycopy(data, offset, buf, writePos, chunk)
                writePos = (writePos + chunk) % capacity
                offset += chunk
                remaining -= chunk
            }
            filled = minOf(filled + length, capacity)
        }

        /** Most recent [seconds] of audio in chronological order, or null if we don't have that much yet. */
        @Synchronized
        fun lastSeconds(seconds: Int): ByteArray? {
            val wanted = minOf(sampleRate * 2 * seconds, capacity)
            if (filled < wanted) {
                Log.w(TAG, "Ring buffer has ${filled / (sampleRate * 2)}s, need ${seconds}s")
                return null
            }
            val out = ByteArray(wanted)
            val start = ((writePos - wanted) % capacity + capacity) % capacity
            val firstChunk = minOf(wanted, capacity - start)
            System.arraycopy(buf, start, out, 0, firstChunk)
            if (firstChunk < wanted) {
                System.arraycopy(buf, 0, out, firstChunk, wanted - firstChunk)
            }
            return out
        }

        @Synchronized
        fun clear() {
            writePos = 0
            filled = 0
        }

        @Synchronized
        fun secondsBuffered(): Int = filled / (sampleRate * 2)
    }

    /**
     * Wrap raw PCM16LE mono in a WAV container. AudD needs a real audio file —
     * bare PCM is rejected as an invalid file (their error #500).
     */
    fun wavEncode(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2 // mono, 16-bit
        val totalDataLen = 36 + pcm.size

        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) = out.write(
            byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte()
            )
        )
        fun writeShortLE(v: Int) = out.write(
            byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
        )

        writeString("RIFF")
        writeIntLE(totalDataLen)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)        // PCM header size
        writeShortLE(1)       // format = PCM
        writeShortLE(1)       // channels = mono
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(2)       // block align
        writeShortLE(16)      // bits per sample
        writeString("data")
        writeIntLE(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * Post a WAV clip to AudD and return a short spoken-word friendly result.
     *
     * The return value goes straight back to Gemini/GPT as a tool result, so it
     * is phrased as plain text the model can read aloud rather than raw JSON.
     */
    suspend fun identify(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val token = RemoteConfigManager.auddApiKey
        if (token.isBlank()) {
            Log.e(TAG, "No AudD API token configured in Remote Config")
            return@withContext "Song recognition isn't set up yet."
        }

        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("api_token", token)
                .addFormDataPart(
                    "file", "clip.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()

            // Must be https:// — AudD drops the body if a http:// request gets redirected.
            val req = Request.Builder()
                .url("https://api.audd.io/")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful || raw == null) {
                    Log.e(TAG, "AudD HTTP ${resp.code}")
                    return@withContext "I couldn't reach the music service just now."
                }

                val json = JSONObject(raw)
                val status = json.optString("status")
                if (status != "success") {
                    val err = json.optJSONObject("error")
                    val code = err?.optInt("error_code") ?: -1
                    val msg = err?.optString("error_message") ?: "unknown error"
                    Log.e(TAG, "AudD error #$code: $msg")
                    // #900/#901 are auth/quota problems — worth distinguishing so the
                    // user isn't told "no match" when the real issue is the API key.
                    return@withContext when (code) {
                        900, 901 -> "Song recognition isn't set up correctly."
                        300, 500 -> "I couldn't get a clear enough recording of that."
                        else -> "I couldn't identify that one."
                    }
                }

                // A successful request with no match returns result: null.
                // That's not an error — the audio just didn't match anything.
                if (json.isNull("result")) {
                    Log.d(TAG, "AudD: no match")
                    return@withContext "I couldn't identify that song."
                }

                val result = json.getJSONObject("result")
                val title = result.optString("title").takeIf { it.isNotBlank() }
                val artist = result.optString("artist").takeIf { it.isNotBlank() }

                if (title == null) {
                    return@withContext "I couldn't identify that song."
                }

                Log.d(TAG, "AudD match: $title by $artist")
                return@withContext if (artist != null) "$title by $artist" else title
            }
        } catch (e: Exception) {
            Log.e(TAG, "Song identification failed: ${e.message}", e)
            return@withContext "I couldn't identify that song right now."
        }
    }

    /**
     * Full flow: pull the last [CLIP_SECONDS] from the live session's mic tee,
     * encode, and identify. Returns a spoken-word friendly string either way.
     */
    suspend fun identifyFromLiveSession(): String {
        val service = GeminiLiveService.getInstance()
            ?: return "Ask me again while I'm listening."

        val clip = service.getSongIdClip(CLIP_SECONDS)
            ?: return "I need a few more seconds of the song — ask me again in a moment."

        Log.d(TAG, "Identifying from ${clip.size} bytes of PCM")
        return identify(wavEncode(clip, service.songIdSampleRate()))
    }
}
