# Image Upload Feature

## Overview

Photos captured by the glasses are uploaded to the **IMI Glass backend** and stored
against the signed-in user's account. The backend derives the owning user from the
access token, so no user ID is ever sent.

Base URL: `http://136.243.196.163:8080` (shared via `AuthApi.BASE_URL`).

## Components

### 1. `ui/sync/ImageUploadApi.kt`
Networking layer for the two upload endpoints. **Blocking — must be called off the
main thread.** Follows the same conventions as `VisionApi`: bearer auth via
`AuthApi.ensureValidAccessToken()` (which refreshes an expired token first), the
shared base URL, and the `{ "error": { "message": … } }` error envelope.

| Method | Endpoint | File part | Notes |
|---|---|---|---|
| `uploadImage(file, source)` | `POST /v1/upload/image` | `image` | `source` defaults to `smart_glasses`; returns `uploaded_at` |
| `uploadImageData(bytes, …)` | `POST /v1/upload/image` | `image` | For raw bytes off the BLE/WiFi transfer |
| `uploadGlassesImage(file)` | `POST /v1/upload/glasses-image` | `file` | `source` fixed to `glasses` server-side; no `uploaded_at` |
| `uploadGlassesImageData(bytes, …)` | `POST /v1/upload/glasses-image` | `file` | As above, for raw bytes |

Returns `Result.Ok(UploadedImage)` or `Result.Err(message, auth, code)`.

### 2. `ui/sync/ImageUploadSync.kt`
Fire-and-forget front door that moves the blocking calls onto a single-threaded
executor (uploads are serialised, so back-to-back captures don't compete for
bandwidth). No-ops when signed out.

> ⚠️ Callbacks arrive on a **background thread** — wrap UI work in `runOnUiThread`.

### 3. `UploadSettingsDialog.kt`
Toggles auto-upload (`auto_upload_enabled` in the `imi_prefs` file) and shows the
destination plus the signed-in account. The upload URL is **no longer
configurable** — the old `image_upload_url` setting is retired.

## `UploadedImage`

```kotlin
data class UploadedImage(
    val imageId: String,     // UUID of the DB record
    val url: String,         // relative, e.g. "/uploads/<uuid>.png"
    val uploadedAt: String?  // ISO-8601 UTC; null on the glasses endpoint
) {
    val absoluteUrl: String  // BASE_URL + url — use this to render
}
```

`/uploads/…` is served publicly, so `absoluteUrl` can go straight into Glide /
an `ImageView` **without** an `Authorization` header.

## Usage

```kotlin
ImageUploadSync.uploadImage(
    context = this,
    imageFile = file,
    source = "smart_glasses",
    onSuccess = { uploaded ->
        runOnUiThread { Glide.with(this).load(uploaded.absoluteUrl).into(imageView) }
    },
    onError = { error -> Log.e(TAG, "Upload failed: $error") }
)
```

## Integration Points

### Photo Capture Flow
1. Glasses capture photo → BLE transfer
2. `MyDeviceNotifyListener.parseData()` receives photo data
3. `savePhoto()` writes the file and saves it to the Live Gallery
4. If `auto_upload_enabled` → `uploadPhotoToServer()` → `ImageUploadSync.uploadImage()`
5. On success the public URL is cached in `lastUploadedImageUrl`; a toast reports the outcome

### Related: `VisionSync` / `VisionApi`
`LiveGalleryManager.savePhoto()` **separately** mirrors every capture to
`/v1/gallery/photos` (a vision *record*, which also carries the AI description).
That is a different endpoint with a different purpose — the upload API here just
stores an image and hands back a URL. Both can run for the same photo.

## Errors & Limits

| Status | Meaning | Handling |
|---|---|---|
| `401` / `403` | Missing / invalid / expired token | Token is refreshed proactively; a 401 here means re-login (`Result.Err(auth = true)`) |
| `413` | File over the size limit | Mapped to a readable message |

Max file size is **10 MB** (12 MB per request); `ImageUploadApi` rejects oversized
images client-side before spending the upload.

## Requirements

- User must be **signed in** — uploads no-op otherwise.
- `android:usesCleartextTraffic="true"` is set (the backend is plain HTTP).

## Logs

Look for these tags:
- `ImageUploadApi` — HTTP failures
- `ImageUploadSync` — per-upload success (`✅ Uploaded: <url>`) / failure
- `SmartGlassAI` — photo capture and save operations

## Troubleshooting

### Nothing uploads
- Confirm **Enable Auto Upload** is on and the user is signed in (the dialog warns when not).

### "Session expired"
- The refresh token has lapsed; sign in again.

### `NetworkOnMainThreadException`
- `ImageUploadApi` is blocking by design — call it through `ImageUploadSync`.
