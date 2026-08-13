package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The result of a successful upload to `/v1/upload/image` or
 * `/v1/upload/glasses-image`.
 *
 * [url] is a *relative* path (e.g. `/uploads/<uuid>.png`); use [absoluteUrl] to
 * get something renderable. [uploadedAt] is only returned by the general
 * endpoint — the glasses variant leaves it null.
 */
data class UploadedImage(
    val imageId: String,
    val url: String,
    val uploadedAt: String? = null
) {
    /** The renderable URL: the backend's base joined with the relative [url]. */
    val absoluteUrl: String
        get() = if (url.startsWith("http://") || url.startsWith("https://")) url
        else "${AuthApi.BASE_URL}${if (url.startsWith("/")) url else "/$url"}"
}

/**
 * Networking layer for the IMI Glass image-upload endpoints.
 *
 * Two endpoints, same storage behaviour, different shapes:
 *  - `POST /v1/upload/image` — general purpose; file part is **`image`**, takes a
 *    free-form `source`, and returns `uploaded_at`.
 *  - `POST /v1/upload/glasses-image` — the smart-glasses capture flow; file part
 *    is **`file`**, `source` is fixed to "glasses" server-side, no `uploaded_at`.
 *
 * Both require a bearer token — the backend derives the owning user from it, so
 * no user id is ever sent. The returned `/uploads/` path is public and needs no
 * token to render.
 *
 * Follows [VisionApi]'s conventions: the shared base URL, the
 * `{ "error": { "message": ... } }` envelope, and **blocking** calls that MUST be
 * invoked off the main thread ([ImageUploadSync] handles that).
 */
class ImageUploadApi(context: Context) {

    private val appContext = context.applicationContext
    private val authApi = AuthApi(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String, val auth: Boolean = false, val code: Int = 0) : Result<Nothing>()
    }

    // ---------------------------------------------------------------------
    // POST /v1/upload/image
    // ---------------------------------------------------------------------

    /**
     * General-purpose image upload. [source] is a free-form origin tag that
     * defaults to "smart_glasses" server-side.
     */
    fun uploadImage(
        imageFile: File,
        source: String = DEFAULT_SOURCE
    ): Result<UploadedImage> {
        if (!imageFile.exists()) return Result.Err("Image file does not exist: ${imageFile.absolutePath}")
        oversizeError(imageFile.length())?.let { return it }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(PART_IMAGE, imageFile.name, imageFile.asRequestBody(mimeOf(imageFile.name)))
            .addFormDataPart("source", source)
            .build()
        return post("/v1/upload/image", body)
    }

    /** Same as [uploadImage] but for in-memory bytes (e.g. straight off the BLE transfer). */
    fun uploadImageData(
        imageData: ByteArray,
        fileName: String = "glasses_photo_${System.currentTimeMillis()}.jpg",
        source: String = DEFAULT_SOURCE
    ): Result<UploadedImage> {
        oversizeError(imageData.size.toLong())?.let { return it }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(PART_IMAGE, fileName, imageData.toRequestBody(mimeOf(fileName)))
            .addFormDataPart("source", source)
            .build()
        return post("/v1/upload/image", body)
    }

    // ---------------------------------------------------------------------
    // POST /v1/upload/glasses-image
    // ---------------------------------------------------------------------

    /**
     * The smart-glasses capture variant. Note the file part is named `file` (not
     * `image`) and there is no `source` field — the server hardcodes "glasses".
     * The response carries no `uploaded_at`.
     */
    fun uploadGlassesImage(imageFile: File): Result<UploadedImage> {
        if (!imageFile.exists()) return Result.Err("Image file does not exist: ${imageFile.absolutePath}")
        oversizeError(imageFile.length())?.let { return it }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(PART_FILE, imageFile.name, imageFile.asRequestBody(mimeOf(imageFile.name)))
            .build()
        return post("/v1/upload/glasses-image", body)
    }

    /** [uploadGlassesImage] for in-memory bytes. */
    fun uploadGlassesImageData(
        imageData: ByteArray,
        fileName: String = "glasses_photo_${System.currentTimeMillis()}.jpg"
    ): Result<UploadedImage> {
        oversizeError(imageData.size.toLong())?.let { return it }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(PART_FILE, fileName, imageData.toRequestBody(mimeOf(fileName)))
            .build()
        return post("/v1/upload/glasses-image", body)
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Reject anything over the server's cap before spending the upload, so the
     * user gets a real message instead of a bare 413.
     */
    private fun oversizeError(bytes: Long): Result.Err? =
        if (bytes > MAX_FILE_SIZE_BYTES) {
            Result.Err("Image is ${bytes / 1024 / 1024} MB — the limit is ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB")
        } else null

    private fun mimeOf(fileName: String) =
        if (fileName.substringAfterLast('.', "").equals("png", true)) PNG_MEDIA else JPEG_MEDIA

    /**
     * Attaches a fresh bearer token and POSTs [body]. Deliberately does **not**
     * set Content-Type: OkHttp emits `multipart/form-data; boundary=…` from the
     * [MultipartBody] itself, and overriding it would drop the boundary and break
     * the upload.
     */
    private fun post(path: String, body: RequestBody): Result<UploadedImage> {
        val token = authApi.ensureValidAccessToken()
            ?: return Result.Err("Not signed in", auth = true)
        val req = Request.Builder()
            .url("${AuthApi.BASE_URL}$path")
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()
        return execute(req)
    }

    private fun execute(req: Request): Result<UploadedImage> {
        return try {
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = runCatching { JSONObject(raw) }.getOrNull()
                        ?: return Result.Err("Malformed response from server")
                    val url = parsed.optString("url").takeIf { it.isNotBlank() && it != "null" }
                        ?: return Result.Err("Server returned no image URL")
                    Result.Ok(
                        UploadedImage(
                            imageId = parsed.optString("image_id"),
                            url = url,
                            uploadedAt = parsed.optString("uploaded_at")
                                .takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                } else if (response.code == 401 || response.code == 403) {
                    // ensureValidAccessToken() already refreshes proactively, so a 401
                    // here means the session is genuinely dead — the user must re-login.
                    Result.Err("Session expired", auth = true, code = response.code)
                } else if (response.code == 413) {
                    Result.Err("Image too large (max ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB)", code = 413)
                } else {
                    Log.w(TAG, "${req.method} ${req.url} -> HTTP ${response.code}: ${raw.take(300)}")
                    Result.Err(parseError(raw, response.code), code = response.code)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request ${req.method} ${req.url} failed: ${e.message}")
            Result.Err("Network error: ${e.message}")
        }
    }

    private fun parseError(raw: String, code: Int): String =
        runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Upload failed (HTTP $code)"

    companion object {
        private const val TAG = "ImageUploadApi"

        /** Field name for /v1/upload/image. */
        private const val PART_IMAGE = "image"
        /** Field name for /v1/upload/glasses-image. */
        private const val PART_FILE = "file"

        const val DEFAULT_SOURCE = "smart_glasses"

        /** Server cap is 10 MB per file (12 MB per request). */
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024

        private val JPEG_MEDIA = "image/jpeg".toMediaType()
        private val PNG_MEDIA = "image/png".toMediaType()
    }
}
