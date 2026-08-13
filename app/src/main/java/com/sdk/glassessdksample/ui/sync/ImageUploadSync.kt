package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Off-main-thread front door for [ImageUploadApi].
 *
 * [ImageUploadApi]'s calls block, so everything here hops onto a single-threaded
 * executor (uploads are serialised — two glasses captures in quick succession
 * won't compete for bandwidth). Callbacks fire on that background thread; hop to
 * the UI thread yourself if you touch views.
 *
 * Mirrors [VisionSync]: no-ops when signed out, never throws at the caller.
 */
object ImageUploadSync {

    private const val TAG = "ImageUploadSync"

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    /**
     * Upload [imageFile] via `/v1/upload/image` and hand back the stored record.
     *
     * @param onSuccess receives the [UploadedImage]; use `absoluteUrl` to render it.
     * @param onError   receives a human-readable message.
     */
    fun uploadImage(
        context: Context,
        imageFile: File,
        source: String = ImageUploadApi.DEFAULT_SOURCE,
        onSuccess: (UploadedImage) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) {
            onError("Not signed in")
            return
        }
        io.execute {
            dispatch(ImageUploadApi(ctx).uploadImage(imageFile, source), onSuccess, onError)
        }
    }

    /** [uploadImage] for raw bytes straight off the BLE/WiFi transfer. */
    fun uploadImageData(
        context: Context,
        imageData: ByteArray,
        fileName: String = "glasses_photo_${System.currentTimeMillis()}.jpg",
        source: String = ImageUploadApi.DEFAULT_SOURCE,
        onSuccess: (UploadedImage) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) {
            onError("Not signed in")
            return
        }
        io.execute {
            dispatch(ImageUploadApi(ctx).uploadImageData(imageData, fileName, source), onSuccess, onError)
        }
    }

    /** Upload through the glasses-specific `/v1/upload/glasses-image` endpoint. */
    fun uploadGlassesImage(
        context: Context,
        imageFile: File,
        onSuccess: (UploadedImage) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) {
            onError("Not signed in")
            return
        }
        io.execute {
            dispatch(ImageUploadApi(ctx).uploadGlassesImage(imageFile), onSuccess, onError)
        }
    }

    /** [uploadGlassesImage] for raw bytes. */
    fun uploadGlassesImageData(
        context: Context,
        imageData: ByteArray,
        fileName: String = "glasses_photo_${System.currentTimeMillis()}.jpg",
        onSuccess: (UploadedImage) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) {
            onError("Not signed in")
            return
        }
        io.execute {
            dispatch(ImageUploadApi(ctx).uploadGlassesImageData(imageData, fileName), onSuccess, onError)
        }
    }

    private fun dispatch(
        result: ImageUploadApi.Result<UploadedImage>,
        onSuccess: (UploadedImage) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            when (result) {
                is ImageUploadApi.Result.Ok -> {
                    Log.i(TAG, "✅ Uploaded: ${result.value.absoluteUrl}")
                    onSuccess(result.value)
                }
                is ImageUploadApi.Result.Err -> {
                    Log.w(TAG, "❌ Upload failed: ${result.message}")
                    onError(result.message)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload callback failed: ${e.message}")
        }
    }
}
