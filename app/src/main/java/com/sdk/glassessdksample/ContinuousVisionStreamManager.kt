package com.sdk.glassessdksample

import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Continuous Vision Stream Manager
 * 
 * Manages BLE-based continuous camera streaming from Glass to Gemini Live
 * Uses CoV (Continuous Vision) Mode for smooth video-like streaming
 * 
 * Key Features:
 * - CoV Mode keeps camera always-on (no sleep between captures)
 * - Small thumbnail size for fast transfer (30x30 = ~5KB vs 5MB full image)
 * - BLE notifications for frame delivery
 * - Direct streaming to Gemini Live for real-time AI commentary
 */
class ContinuousVisionStreamManager(
    private val scope: CoroutineScope
) {
    private val TAG = "ContinuousVisionStream"
    
    // Streaming state
    private var isStreaming = false
    private var streamJob: Job? = null
    // Manual loop control (single-shot request loop)
    private var isLoopActive = false
    
    // Thumbnail size for fast transfer (smaller = faster)
    // 30x30 = ~5KB, 50x50 = ~15KB, 100x100 = ~50KB
    private val THUMBNAIL_WIDTH = 50
    private val THUMBNAIL_HEIGHT = 50
    
    // Frame delivery callback
    private var onFrameReceived: ((ByteArray) -> Unit)? = null
    // Reassembly buffer for chunked JPEG frames
    private val imageBuffer = ByteArrayOutputStream()
    private var isCollectingFrame = false
    private val FRAME_START_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) // JPEG SOI
    private val FRAME_END_MARKER = byteArrayOf(0xFF.toByte(), 0xD9.toByte())   // JPEG EOI
    
    // BLE notification listener for continuous frames
    private val visionFrameListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                if (rsp == null) {
                    Log.e(TAG, "[BLE] GlassesDeviceNotifyRsp is null!")
                    return
                }

                val loadData = rsp.loadData
                if (loadData == null) {
                    Log.e(TAG, "[BLE] loadData is null in GlassesDeviceNotifyRsp!")
                    return
                }
                Log.d(TAG, "[BLE] loadData received: size=${loadData.size}, bytes=${loadData.joinToString(",", limit=16)}")

                if (loadData.size < 7) {
                    Log.e(TAG, "[BLE] loadData too short: size=${loadData.size}, bytes=${loadData.joinToString(",", limit=16)}")
                    return
                }

                // Type 6 = Vision AI frame
                val notifyType = loadData[6].toInt() and 0xFF
                Log.d(TAG, "[BLE] Notification type: $notifyType (raw=${loadData[6]})")

                if (notifyType == 6 || notifyType == 89) {
                    Log.d(TAG, "📸 Received vision frame chunk (or stream): ${loadData.size} bytes")

                    // Route through acceptBigData, not processCleanData directly: it
                    // strips the BC59 header/footer when present and passes raw JPEG
                    // through untouched. Feeding headers into the reassembler leaves
                    // protocol bytes embedded in the middle of the image.
                    try {
                        acceptBigData(loadData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error feeding data to reassembler: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing vision frame: ${e.message}")
            }
        }
    }
    
    /**
     * Start continuous vision streaming
     * 
     * Flow:
     * 1. Enable CoV Mode (camera always-on)
     * 2. Start Vision AI with small thumbnail size
     * 3. Register BLE listener for frames
     * 4. Frames automatically delivered via BLE notifications
     */
    fun startStreaming(context: android.content.Context, onFrame: (ByteArray) -> Unit) {
        if (isStreaming) {
            Log.w(TAG, "⚠️ Streaming already active")
            return
        }

        isStreaming = true
        onFrameReceived = onFrame

        Log.i(TAG, "🎥 Starting manual-loop vision streaming...")

        // Widen the BLE pipe before frames start flowing. This path is the most
        // sensitive to link speed in the whole app: it pulls frame after frame, so
        // every round trip saved compounds across the session.
        com.sdk.glassessdksample.ui.BleSpeedTuner.tune(context, "VisionStream")

        // Register BLE listener for vision frames (type 6)
        LargeDataHandler.getInstance().addOutDeviceListener(6, visionFrameListener)

        streamJob = scope.launch {
            try {
                // Safety: Ensure any previous streaming is stopped on device
                Log.d(TAG, "🧼 Sending stop commands to reset any stuck sessions")
                sendBleCommand(byteArrayOf(2, 1, 7)) // Stop Vision
                sendBleCommand(byteArrayOf(2, 1, 26)) // Stop CoV

                delay(500) // small pause for hardware to settle

                // Enable CoV Mode (keep device responsive)
                Log.d(TAG, "📹 Enabling CoV Mode (keep device responsive)")
                sendBleCommand(byteArrayOf(2, 1, 25)) {
                    Log.d(TAG, "✅ CoV Mode ON")
                }

                delay(500)

                // Start manual single-shot loop
                isLoopActive = true
                requestNextFrame()

                Log.i(TAG, "🔁 Manual-frame loop active")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Streaming start error: ${e.message}", e)
                stopStreaming()
            }
        }
    }
    
    /**
     * Stop continuous vision streaming
     */
    fun stopStreaming() {
        if (!isStreaming) return

        Log.i(TAG, "🛑 Stopping manual-loop vision streaming...")
        isStreaming = false
        isLoopActive = false

        streamJob?.cancel()
        streamJob = null

        // Send stop commands
        sendBleCommand(byteArrayOf(2, 1, 7)) { Log.d(TAG, "✅ Vision AI stopped") }
        sendBleCommand(byteArrayOf(2, 1, 26)) { Log.d(TAG, "✅ CoV Mode OFF") }

        // Unregister BLE listener
        LargeDataHandler.getInstance().removeOutDeviceListener(6)

        onFrameReceived = null

        Log.i(TAG, "🛑 Streaming stopped")
    }

    /**
     * Request a single-shot frame (Mode 1) using Fire & Forget approach.
     * 
     * 🚀 FAST WAY: No waiting for callback - data comes via streamListener (registerGptUploadListener)
     * This eliminates the 10-second SafeBleCommandHelper timeout completely!
     */
    private fun requestNextFrame() {
        if (!isLoopActive) return
        val cmd = byteArrayOf(0x02, 0x01, 0x06, THUMBNAIL_WIDTH.toByte(), THUMBNAIL_HEIGHT.toByte(), 0x01)
        
        // 🚀 FIRE & FORGET: Send command without waiting for callback
        // The actual image data arrives via bc59 packets -> registerGptUploadListener -> acceptBigData
        try {
            LargeDataHandler.getInstance().glassesControl(cmd, null)
            Log.d(TAG, "⚡ Frame Requested (Fire & Forget - No Wait)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send glassesControl: ${e.message}")
        }
    }
    
    /**
     * [DEPRECATED - kept for reference] Old blocking request method
     * Request a single-shot frame (Mode 1) and return when the command is sent.
     */
    private fun requestNextFrameBlocking() {
        if (!isLoopActive) return
        val cmd = byteArrayOf(0x02, 0x01, 0x06, THUMBNAIL_WIDTH.toByte(), THUMBNAIL_HEIGHT.toByte(), 0x01)
        Log.d(TAG, "🔄 Requesting next single-shot frame (Mode 1) - BLOCKING")
        // Use SDK's glassesControl so the SDK will route the big-data response into our callback
        try {
            LargeDataHandler.getInstance().glassesControl(cmd, object : ILargeDataResponse<GlassModelControlResponse> {
                override fun parseData(cmdType: Int, resp: GlassModelControlResponse?) {
                    try {
                        if (resp == null) {
                            Log.e(TAG, "💬 glassesControl response is null")
                            return
                        }

                        // First try the targeted extractor
                        var payload = extractBytesFromResponse(resp)
                        if (payload != null) {
                            Log.d(TAG, "📨 RAW glassesControl payload (direct): size=${payload.size}")
                            onDataReceived(payload)
                            return
                        }

                        // If direct methods failed, perform deep reflection: list fields and recurse
                        try {
                            val cls = resp.javaClass
                            Log.w(TAG, "📡 glassesControl response class: ${cls.name}")
                            for (field in cls.declaredFields) {
                                try {
                                    field.isAccessible = true
                                    val value = field.get(resp)
                                    if (value == null) {
                                        Log.d(TAG, "👉 Field: ${field.name} = null")
                                        continue
                                    }

                                    when (value) {
                                        is ByteArray -> {
                                            Log.w(TAG, "👉 Field: ${field.name} -> ByteArray (size=${value.size})")
                                            onDataReceived(value)
                                            return
                                        }
                                        else -> {
                                            Log.d(TAG, "� Field: ${field.name} -> ${value.javaClass.name}")
                                            // Try recursive extraction (depth-limited)
                                            val nested = deepExtractBytes(value, 0)
                                            if (nested != null) {
                                                Log.w(TAG, "👉 Found nested ByteArray in field ${field.name} (size=${nested.size})")
                                                onDataReceived(nested)
                                                return
                                            }
                                        }
                                    }
                                } catch (inner: Exception) {
                                    Log.e(TAG, "Field access error: ${inner.message}")
                                }
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "Deep reflection error: ${e2.message}")
                        }

                        Log.e(TAG, "💬 Could not extract payload from GlassModelControlResponse via deep reflection")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in glassesControl callback: ${e.message}")
                    }
                }
            })
            Log.d(TAG, "🔁 RequestNextFrame (glassesControl) sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send glassesControl command: ${e.message}")
            // Fallback to previous safe helper if SDK path fails
            sendBleCommand(cmd) { Log.d(TAG, "🔁 RequestNextFrame fallback sent") }
        }
    }

    /**
     * Try to extract a ByteArray payload from an SDK response object using common getters/fields.
     */
    private fun extractBytesFromResponse(resp: Any): ByteArray? {
        try {
            // Common direct field / getter names observed: loadData, data, a, getLoadData(), getData()
            val cls = resp.javaClass
            try {
                val m = cls.getMethod("getLoadData")
                val res = m.invoke(resp) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

            try {
                val f = cls.getDeclaredField("loadData")
                f.isAccessible = true
                val res = f.get(resp) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

            try {
                val m = cls.getMethod("getData")
                val res = m.invoke(resp) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

            try {
                val f = cls.getDeclaredField("data")
                f.isAccessible = true
                val res = f.get(resp) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

            try {
                val f = cls.getDeclaredField("a")
                f.isAccessible = true
                val res = f.get(resp) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "extractBytesFromResponse error: ${e.message}")
        }
        return null
    }

    /**
     * Recursively inspect an object to find any ByteArray payloads (depth-limited).
     */
    private fun deepExtractBytes(obj: Any?, depth: Int): ByteArray? {
        if (obj == null) return null
        if (depth > 3) return null
        try {
            if (obj is ByteArray) return obj

            val cls = obj.javaClass
            // Try common getters first
            try {
                val m = cls.getMethod("getLoadData")
                val res = m.invoke(obj) as? ByteArray
                if (res != null) return res
            } catch (_: Exception) {}

            try {
                val m2 = cls.getMethod("getData")
                val res2 = m2.invoke(obj) as? ByteArray
                if (res2 != null) return res2
            } catch (_: Exception) {}

            // Inspect declared fields
            for (field in cls.declaredFields) {
                try {
                    field.isAccessible = true
                    val v = field.get(obj) ?: continue
                    if (v is ByteArray) return v
                    val nested = deepExtractBytes(v, depth + 1)
                    if (nested != null) return nested
                } catch (_: Exception) {
                    // ignore field access errors
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deepExtractBytes error: ${e.message}")
        }
        return null
    }
    
    /**
     * Send BLE command to Glass
     */
    private fun sendBleCommand(cmd: ByteArray, onComplete: (() -> Unit)? = null) {
        SafeBleCommandHelper.executeCommandSafely(cmd, { code, error ->
            if (error == null) {
                Log.d(TAG, "✅ BLE command success: code=$code")
            } else {
                Log.e(TAG, "❌ BLE command error: code=$code, msg=$error")
            }
            onComplete?.invoke()
        })
    }
    
    /**
     * Check if streaming is active
     */
    fun isActive(): Boolean = isStreaming
    
    /**
     * Cleanup
     */
    fun cleanup() {
        stopStreaming()
    }

    /** Public entry for external listeners (BigData) to deliver raw payloads */
    fun acceptBigData(data: ByteArray) {
        try {
            // 🔬 X-RAY: Raw packet dekhein (sirf pehle kuch packets)
            if (packetCount < 20 || packetCount % 100 == 0L) {
                val rawHex = data.take(16.coerceAtMost(data.size)).joinToString(" ") { 
                    String.format("%02X", it) 
                }
                Log.d(TAG, "🔬 RAW [${data.size}b] #$packetCount: $rawHex")
            }
            packetCount++
            
            // 🌍 UNIVERSAL STRIPPER - Accept ALL packets, no type filtering!
            // Protocol: BC 59 [len_lo] [len_hi] [seq_lo] [seq_hi] [type1] [type2] [DATA...] [crc1] [crc2]
            // Header = 8 bytes, Footer = 2 bytes (usually)
            
            // 1. Basic Size Check
            if (data.size < 8) {
                return
            }
            
            // 2. Check for BC59 Header
            val hasBC59Header = (data[0] == 0xBC.toByte() || data[0] == (-68).toByte()) && 
                                (data[1] == 0x59.toByte() || data[1] == 89.toByte())
            
            if (!hasBC59Header) {
                // No BC59 header - might be direct JPEG data, pass through
                Log.d(TAG, "📦 No BC59, raw data pass-through")
                processCleanData(data)
                return
            }
            
            // 3. ✂️ Strip the 8-byte BC59 header.
            //
            // The trailing 2 bytes are NOT stripped. A CRC exists only on the final
            // packet of a transfer, so removing 2 bytes from every chunk deletes real
            // image data mid-stream and corrupts the JPEG. Any trailing CRC lands
            // after the EOI marker and is discarded by the reassembler anyway.
            val headerSize = 8
            val payloadSize = data.size - headerSize

            if (payloadSize > 0) {
                val cleanData = ByteArray(payloadSize)
                System.arraycopy(data, headerSize, cleanData, 0, payloadSize)

                // Log first few bytes of clean data to hunt for FF D8
                if (packetCount < 30) {
                    val cleanHex = cleanData.take(8.coerceAtMost(cleanData.size)).joinToString(" ") {
                        String.format("%02X", it)
                    }
                    Log.d(TAG, "✂️ CLEAN [${cleanData.size}b]: $cleanHex")
                }

                processCleanData(cleanData)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "acceptBigData error: ${e.message}")
        }
    }
    
    // Packet counter for debug logging
    private var packetCount = 0L
    
    /**
     * Process cleaned image data - HUNT for FF D8 (JPEG Start) and FF D9 (JPEG End)
     * Universal approach: Accept everything, let JPEG markers guide us
     */
    private fun processCleanData(data: ByteArray) {
        try {
            if (!isCollectingFrame) {
                // Not collecting yet: look for the start of an image. Anything before
                // the SOI is inter-frame padding and is discarded.
                val soiPos = findMarker(data, 0xFF.toByte(), 0xD8.toByte())
                if (soiPos < 0) return

                imageBuffer.reset()
                imageBuffer.write(data, soiPos, data.size - soiPos)
                isCollectingFrame = true
                Log.i(TAG, "📸 SOI found at $soiPos — collecting frame")
            } else {
                // Already collecting: append blindly.
                //
                // Do NOT scan for SOI here. FF D8 occurs freely inside JPEG
                // entropy-coded data, so treating every occurrence as a new frame
                // resets the buffer mid-image and no frame ever completes. Only the
                // EOI scan below may end a frame.
                imageBuffer.write(data)
            }

            // Scan for EOI, but only past the SOI we already have. Searching from 0
            // could match an FF D9 byte pair inside the JPEG header segments.
            val buffer = imageBuffer.toByteArray()
            val eoiPos = findMarker(buffer, 0xFF.toByte(), 0xD9.toByte(), fromIndex = 2)
            if (eoiPos >= 2) {
                val jpegEnd = eoiPos + 2
                val jpegData = buffer.copyOfRange(0, jpegEnd)
                Log.i(TAG, "✅ EOI at $eoiPos — complete JPEG: ${jpegData.size} bytes")

                imageBuffer.reset()
                isCollectingFrame = false

                // Carry over anything after this image; the next frame may already
                // have started inside the same chunk.
                if (buffer.size > jpegEnd) {
                    processCleanData(buffer.copyOfRange(jpegEnd, buffer.size))
                }

                deliverFrame(jpegData)
                return
            }

            // Overflow protection
            if (imageBuffer.size() > 500_000) {
                Log.w(TAG, "⚠️ Buffer overflow (>500KB) with no EOI — clearing")
                imageBuffer.reset()
                isCollectingFrame = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "processCleanData error: ${e.message}")
            imageBuffer.reset()
            isCollectingFrame = false
        }
    }
    
    /**
     * Find 2-byte marker in data at or after [fromIndex], return position or -1.
     */
    private fun findMarker(data: ByteArray, b1: Byte, b2: Byte, fromIndex: Int = 0): Int {
        for (i in maxOf(0, fromIndex) until data.size - 1) {
            if (data[i] == b1 && data[i + 1] == b2) {
                return i
            }
        }
        return -1
    }
    
    /**
     * Deliver completed JPEG frame
     */
    private fun deliverFrame(jpegData: ByteArray) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            
            if (bitmap != null) {
                Log.i(TAG, "🎉 FRAME DECODED! ${bitmap.width}x${bitmap.height}")
                onFrameReceived?.invoke(jpegData)
                
                // Request next frame if streaming
                if (isLoopActive) {
                    requestNextFrame()
                }
            } else {
                Log.e(TAG, "❌ BitmapFactory returned NULL (corrupt JPEG?)")
                // Debug: Log first and last few bytes
                val head = jpegData.take(10).joinToString(" ") { String.format("%02X", it) }
                val tail = jpegData.takeLast(10).joinToString(" ") { String.format("%02X", it) }
                Log.e(TAG, "   Head: $head | Tail: $tail")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deliverFrame error: ${e.message}")
        }
    }
    
    /**
     * OLD - Now handled in acceptBigData with surgical precision
     * Keeping for compatibility
     */
    @Deprecated("Use acceptBigData with surgical stripping")
    private fun stripBleHeader(data: ByteArray): ByteArray {
        return data // No-op now, stripping done in acceptBigData
    }

    /**
     * OLD onDataReceived - replaced by processCleanData
     * Keeping as fallback
     */
    @Deprecated("Use processCleanData instead")
    private fun onDataReceived(data: ByteArray) {
        processCleanData(data)
    }

    /**
     * Find the first occurrence of target inside array, return index or -1
     */
    private fun indexOf(array: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || array.size < target.size) return -1
        for (i in 0..array.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (array[i + j] != target[j]) { found = false; break }
            }
            if (found) return i
        }
        return -1
    }

    /**
     * Find last occurrence of target inside array, return index or -1
     */
    private fun lastIndexOf(array: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || array.size < target.size) return -1
        for (i in array.size - target.size downTo 0) {
            var found = true
            for (j in target.indices) {
                if (array[i + j] != target[j]) { found = false; break }
            }
            if (found) return i
        }
        return -1
    }
}
