# BLE Image Transfer Speed-Up (`BleSpeedTuner`)

**Feature:** Faster photo / vision-frame transfer from the glasses to the Android app
**Platform:** Android only (Mark 2 devices only)
**Status:** Implemented, compiled, and **verified working on hardware**
**Implemented:** 2026-08-05
**Hardware verified on:** 2026-08-05, OnePlus (OPlus/OxygenOS, model IV2201) + `SANVNET GS4 MAX_1F8A` (`AE:28:D9:61:1F:8A`)
**New file:** `app/src/main/java/com/sdk/glassessdksample/ui/BleSpeedTuner.kt`

---

## 1. Summary

Photos and vision frames travel from the glasses to the phone over a **Bluetooth Low Energy (BLE) GATT connection**. That connection was running at Android's default settings, which are tuned for battery life, not throughput. This feature raises two per-connection parameters — **MTU** and **connection interval** — on the connection the vendor SDK actually streams over.

Measured effect on the BLE link itself:

| Parameter | Before | After | Change |
|---|---|---|---|
| MTU (bytes/packet) | 23 | **517** | ~22× larger packets |
| Connection interval | ~30–50 ms | **15 ms** | ~2–3× more frequent |

Both are confirmed by on-device logs (§7).

> **Scope note.** This speeds up the *transport*. It does not change how images are requested, assembled, or decoded. See §9 for what this does **not** fix and why Android remains slower than iOS.

---

## 2. Background: how an image gets from the glasses to the app

```
┌─────────────┐   BLE GATT (data: photos, commands)    ┌─────────────┐
│   GLASSES   │ ────────────────────────────────────►  │  ANDROID    │
│  SANVNET    │                                        │    APP      │
│  GS4 MAX    │ ◄────────────────────────────────────  │             │
└─────────────┘   Bluetooth HFP/SCO (voice audio)      └─────────────┘
```

    
    
Two independent Bluetooth links exist at once:

- **BLE / GATT** — carries data: photo bytes, vision frames, control commands. This is what the feature tunes.
- **Classic Bluetooth HFP/SCO** — carries voice audio. **Untouched by this feature.**

### The image path, step by step

1. App sends a capture command over BLE (`glassesControl`, `LargeDataHandler`).
2. Glasses capture a JPEG and push it back in **many small BLE notification packets**.
3. App reassembles those packets into a complete JPEG by scanning for JPEG markers
   (`FF D8` = start of image, `FF D9` = end) in `ContinuousVisionStreamManager`.
4. Assembled JPEG is decoded and displayed / sent for AI analysis.

**Step 2 is the bottleneck.** The number of round trips is what costs the time.

---

## 3. The problem

### 3.1 Default MTU made every image hundreds of round trips

BLE's default MTU is **23 bytes**, of which only ~20 carry payload. A ~15 KB thumbnail therefore needs:

```
15,000 bytes ÷ 20 bytes/packet ≈ 750 round trips
750 round trips × ~30 ms interval  ≈ 22 seconds
```

That arithmetic matches the observed multi-second transfers.

### 3.2 The existing "MTU boost" code never worked

`VisionChatActivity` already contained ~155 lines intended to fix this. It did not work, for three independent reasons:

**(a) It raised MTU on the wrong connection.** It opened a brand-new GATT connection purely to call `requestMtu(512)`:

```kotlin
// OLD — removed
device.connectGatt(this, false, object : BluetoothGattCallback() {
    override fun onConnectionStateChange(...) { gatt.requestMtu(512) }
})
```

MTU is a property of **one specific GATT connection**. Raising it on a new connection does nothing for the SDK's existing connection, which is the one carrying the image. The code logged `"Speed increase: 11x faster!"` while delivering none.

**(b) It did not recognise the actual hardware.** It identified the glasses by name or MAC prefix:

```kotlin
// OLD — removed
val isGlass = device.name?.contains("Glass", ignoreCase = true) == true ||
              device.name?.contains("IMI",   ignoreCase = true) == true ||
              device.name?.contains("Cyan",  ignoreCase = true) == true ||
              device.address?.startsWith("F7:36") == true ||
              device.address?.startsWith("C8:")   == true
```

The test hardware is **`SANVNET GS4 MAX_1F8A`** at **`AE:28:D9:61:1F:8A`** — it matches none of these. The function logged `⚠️ No Glass device found` and returned without acting.

**(c) The reflection fallback could never succeed.** A helper recursively searched SDK objects for a `BluetoothGatt` field. Decompiling `LIB_GLASSES_SDK-release_3.aar` shows **no SDK class declares a `BluetoothGatt` field** — the GATT lives inside a `Map<String, BluetoothGatt>` in `BleBaseControl`, which the field-type search would never match.

### 3.3 Connection priority was never requested

`requestConnectionPriority()` appeared nowhere in the codebase. Android defaults to a power-saving interval (~30–50 ms); `CONNECTION_PRIORITY_HIGH` requests ~7.5–15 ms.

### 3.4 The visible symptom of the slow pipe

Because transfers were slow, images had been shrunk to **50×50 pixels**:

```kotlin
// ContinuousVisionStreamManager.kt — unchanged by this feature
private val THUMBNAIL_WIDTH  = 50
private val THUMBNAIL_HEIGHT = 50
```

Transfers were still slow at that size. iOS moves a full-resolution JPEG faster than Android moved a 50×50 thumbnail — evidence the bottleneck was the transport, not the image.

---

## 4. The solution

### 4.1 Reaching the SDK's real GATT connection

Decompiling the SDK revealed a **public** accessor:

```java
// com.oudmon.ble.base.bluetooth.BleBaseControl
public class BleBaseControl {
    public static BleBaseControl getInstance();
    protected Map<String, BluetoothGatt> mBluetoothGatt;
    public BluetoothGatt getGatt(String address);   // ← public API
}
```

No reflection and no second connection are needed:

```kotlin
private fun resolveSdkGatts(context: Context): List<BluetoothGatt> {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        ?: return emptyList()
    val control = BleBaseControl.getInstance()

    return manager.getConnectedDevices(BluetoothProfile.GATT).mapNotNull { device ->
        control.getGatt(device.address)
    }
}
```

**Device identification is solved by construction.** Addresses come from the system's connected-GATT list, and `getGatt()` returns `null` for any address the SDK does not manage. No names, no MAC prefixes — works with any branding, including future hardware.

### 4.2 Applying the tuning

```kotlin
val fast  = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
val asked = gatt.requestMtu(MAX_BLE_MTU)   // 517 = ATT maximum
```

`517` is the ATT protocol maximum; the peripheral negotiates down to whatever it supports (this hardware accepted the full 517).

---

## 5. Where it runs

`BleSpeedTuner.tune(context, reason)` is called from **six** sites. The `reason` string is logged so it is always clear which screen triggered a tune.

| # | File | Line | Reason tag | Trigger |
|---|---|---|---|---|
| 1 | `MyBluetoothReceiver.kt` | 88 | `ServicesDiscovered` | Glasses connect — **primary hook** |
| 2 | `VisionChatActivity.kt` | 1074 | `VisionChat` | Vision Chat screen opens |
| 3 | `ChatActivity.kt` | 434 | `Chat/capture` | "Capture from Glasses" tapped |
| 4 | `ContinuousVisionStreamManager.kt` | 118 | `VisionStream` | Frame streaming starts |
| 5 | `GlassMediaGalleryActivity.kt` | 235 | `MediaGallery` | Media Gallery opens |
| 6 | `LiveGalleryActivity.kt` | 312 | `LiveGallery` | Live Gallery opens |

**Site 1 is the important one.** Tuning at connect time means the link is already fast before any screen requests an image — the first capture of a session does not pay for the negotiation.

**Sites 5 and 6 are lower value.** Both galleries transfer bulk media over **WiFi**, not BLE. Tuning still shortens the BLE control/thumbnail phase before WiFi takes over, but should not be expected to speed up bulk downloads.

---

## 6. Safety mechanisms

| Mechanism | Purpose |
|---|---|
| **Mark 2 gate** | `tune()` returns immediately unless the stored device type is `MARK2`. Mark 1's BLE link is never modified. |
| **10-second rate limit** | `MIN_RETUNE_INTERVAL_MS = 10_000`. Six call sites can fire in quick succession; renegotiating MTU mid-transfer is wasteful and can disrupt an in-flight image. |
| **`resetThrottle()`** | Called on fresh connection (`MyBluetoothReceiver:87`). A new link resets MTU and interval to defaults, so stale tuning must not suppress re-tuning. |
| **Permission check** | Verifies `BLUETOOTH_CONNECT` on Android 12+ (API 31+) before touching the GATT. |
| **Total exception safety** | Every path is wrapped; `tune()` never throws into a caller. A failure degrades to default BLE speed, never a crash. |
| **Graceful absence** | If no SDK GATT exists, logs a warning and returns. |

---

## 7. Hardware verification — 2026-08-05

Captured from the device during a real connection:

```
15:48:38.103  BluetoothGatt  requestConnectionPriority() - params: 1
15:48:38.104  BleSpeedTuner  ⚡ connectionPriority(HIGH) on SANVNET GS4 MAX_1F8A → true (ServicesDiscovered)
15:48:38.104  BluetoothGatt  configureMTU() - device: AE:28:D9:61:1F:8A mtu: 517
15:48:38.106  BleSpeedTuner  🚀 requestMtu(517) on SANVNET GS4 MAX_1F8A → true (ServicesDiscovered)
...
15:48:38.376  BluetoothGatt  onConnectionUpdated() - Device=AE:28:D9:61:1F:8A interval=12 latency=0 timeout=500 status=0
...
15:48:40.625  BluetoothGatt  onConfigureMTU() - Device=AE:28:D9:61:1F:8A mtu=517 status=0
15:48:40.626  GLASSES_LOG    517
```

What each line proves:

- `onConfigureMTU ... mtu=517 status=0` — **MTU genuinely negotiated to 517.** `status=0` is `GATT_SUCCESS`. This is the OS callback, not merely the app's request.
- `GLASSES_LOG 517` — the **vendor SDK independently confirms** 517.
- `interval=12` — 12 × 1.25 ms = **15 ms**, down from the ~30–50 ms default.
- `on SANVNET GS4 MAX_1F8A` — device identification works on hardware the old code could not recognise.
- `(ServicesDiscovered)` — the connect-time hook fires, so the link is fast before any capture.

### Known behaviour: interval relaxes when idle

```
15:48:47.062  onConnectionUpdated() - interval=30 latency=10
```

~9 seconds after tuning, the OS relaxed the interval to save power. This is normal Android behaviour, not a defect. The per-screen `tune()` calls re-request HIGH priority when a capture screen opens. If captures feel inconsistent, lower `MIN_RETUNE_INTERVAL_MS`.

---

## 8. Files changed

| File | Change | Lines |
|---|---|---|
| `ui/BleSpeedTuner.kt` | **New** — the whole feature | +150 |
| `VisionChatActivity.kt` | Deleted 4 broken MTU functions; delegate to `BleSpeedTuner` | −177 |
| `MyBluetoothReceiver.kt` | Tune + reset throttle on service discovery | +8 |
| `ChatActivity.kt` | Tune before glasses capture | +4 |
| `ContinuousVisionStreamManager.kt` | Tune before streaming; `startStreaming` gained a `Context` parameter | +7 |
| `GlassMediaGalleryActivity.kt` | Tune on screen entry | +5 |
| `LiveGalleryActivity.kt` | Tune on screen entry | +3 |

Functions deleted from `VisionChatActivity` (all non-functional):
`maximizeBluetoothSpeed()`, `tryRequestMtuViaInternalGatt()`, `findAndRequestMtu()`, `tryRequestMtuViaSdk()`.

**Breaking change:** `ContinuousVisionStreamManager.startStreaming()` now takes a `Context` as its first parameter. The one caller (`VisionChatActivity:1507`) is updated.

Build status: `:app:assembleDebug` **SUCCESSFUL**.

---

## 9. Limitations — what this does NOT fix

### 9.1 Android is still slower than iOS, by design of the SDKs

iOS receives a **complete JPEG in a single SDK callback**:

```swift
func didReceiveAIChatImageData(_ imageData: Data)   // whole image, assembled by the SDK
```

The Android SDK provides no equivalent. Decompilation of `LIB_GLASSES_SDK-release_3.aar` confirms:

- `AiChatResponse.acceptData()` returns `false` and stores raw bytes — **no reassembly**.
- `getPictureThumbnails(ILargeDataImageResponse)` delivers **chunks**, not whole images. Its `boolean` parameter means "this is the final chunk", not "here is your image". It auto-paginates but never concatenates.

So the Android app **must** reassemble JPEGs by hand, and this feature does not change that. Closing the remaining gap requires the vendor to add whole-image assembly to the Android SDK.

> **Correction of an earlier claim.** During investigation it was initially reported that `getPictureThumbnails` was an unused "whole image" API. Reading the bytecode disproved this. The Android SDK genuinely lacks the capability iOS has.

### 9.2 Not changed by this feature

- **50×50 thumbnail size** — still hardcoded. Now that the pipe is ~22× wider, raising it is worth testing separately.
- **Manual JPEG reassembly** — unchanged and still necessary.
- **WiFi bulk transfer path** — untouched.
- **All audio behaviour** — SCO/HFP voice is a separate Bluetooth link. This feature does not modify audio modes, volume, or routing.

### 9.3 Not yet measured

MTU and interval improvements are **confirmed**. End-to-end capture time is **not** — logs captured so far end before a transfer completes. To measure:

```
adb logcat -c
adb logcat -v time -s BleSpeedTuner:* ContinuousVisionStream:* GLASSES_LOG:*
```

Time from `⚡ Frame Requested` to frame-complete, averaged over ~5 captures, against the previous baseline.

### 9.4 Interaction with audio is unverified

BLE and Bluetooth audio share one radio. A more aggressive BLE link (larger packets, 2–3× more frequent) **could in principle** compete with SCO voice audio. This has not been tested in isolation.

If audio problems appear, disabling `BleSpeedTuner` is a clean, one-line A/B test:

```kotlin
fun tune(context: Context, reason: String) {
    if (true) return   // TEMPORARY: A/B test BLE tuning vs audio quality
    ...
}
```

---

## 10. Troubleshooting

| Log line | Meaning | Action |
|---|---|---|
| `⚡ connectionPriority(HIGH) → true` + `🚀 requestMtu(517) → true` | Requests sent | Confirm with `onConfigureMTU` |
| `onConfigureMTU ... mtu=517 status=0` | **Success** | None |
| `onConfigureMTU ... status≠0` | Peripheral refused | Firmware limit — raise with vendor |
| `⚠️ No SDK GATT connection found` | SDK holds no GATT for any connected device | Check glasses are connected via the app, not just system Bluetooth |
| `BLUETOOTH_CONNECT not granted` | Missing runtime permission | Grant Nearby Devices |
| `↩️ Skipping BLE tune — tuned Nms ago` | Rate limit active | Normal; lower `MIN_RETUNE_INTERVAL_MS` if it blocks a needed re-tune |
| Nothing from `BleSpeedTuner` at all | Device type is not MARK2, or no call site reached | Verify Mark 2 is selected |

---

## 11. Key constants

`BleSpeedTuner.kt`:

```kotlin
private const val MAX_BLE_MTU           = 517      // ATT maximum
private const val MIN_RETUNE_INTERVAL_MS = 10_000L // rate limit
```

`ContinuousVisionStreamManager.kt` (unchanged, related):

```kotlin
private val THUMBNAIL_WIDTH  = 50
private val THUMBNAIL_HEIGHT = 50
```

---

## 12. Suggested next steps

1. **Measure end-to-end capture time** (§9.3) — the improvement is currently inferred, not quantified.
2. **Test a larger thumbnail** — 50×50 was a workaround for the slow pipe; try 100×100 or 160×160.
3. **A/B test against audio** (§9.4) — confirm the BLE tuning does not disturb SCO voice.
4. **Raise SDK parity with the vendor** — Android needs the whole-image callback iOS already has (§9.1). This is the only route to true iOS-speed transfers.
