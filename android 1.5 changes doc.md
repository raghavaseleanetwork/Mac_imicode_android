# Android 1.5 — Changes Log

This file tracks every code change made during this session, in order. Updated after each change.

---

## 1. Fix: "Search the web" voice command not working (Mark 1)

**Date:** 2026-09-08

**Problem:**
Saying "search the web for X" or "search the web" to the assistant did not trigger a web search. Instead it always fell back to general Gemini chat.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/VoiceCommandInterpreter.kt`
- The `GlassAction` enum already had a `SEARCH_WEB` value, and `MainActivity.kt` already correctly routed it:
  - `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt:3604` — `GlassAction.SEARCH_WEB -> searchWeb(data)`
- However, `VoiceCommandInterpreter.detectAction()` (the function that maps spoken text to a `GlassAction`) had **no matching branch** for phrases like "search the web", "search the web for", or "search for". Every such phrase fell through to the final `else` fallback and returned `GlassAction.GENERAL_CHAT` instead of `GlassAction.SEARCH_WEB`.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/VoiceCommandInterpreter.kt`
- Added a new detection branch inside `detectAction()`, placed after the existing "news" check and before the fallback:

```kotlin
if (text.contains("news")) {
    return GlassAction.GET_NEWS to text
}

if (text.contains("search the web for") || text.contains("search web for") || text.contains("search the web") || text.contains("search for") || text.startsWith("search")) {
    val query = text
        .replace("search the web for", "")
        .replace("search web for", "")
        .replace("search the web", "")
        .replace("search for", "")
        .replace("search", "")
        .trim()
    return GlassAction.SEARCH_WEB to query
}
```

**Behavior after fix:**
- "search the web for weather in Delhi" → `GlassAction.SEARCH_WEB`, query = `"weather in delhi"`
- "search the web" (no query) → `GlassAction.SEARCH_WEB`, query = `""`
- "search for cheap flights" → `GlassAction.SEARCH_WEB`, query = `"cheap flights"`
- "search python tutorials" → `GlassAction.SEARCH_WEB`, query = `"python tutorials"`
- These now route into `searchWeb(data)` in `MainActivity.kt` instead of `chatWithGemini(command)`.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/VoiceCommandInterpreter.kt` — added SEARCH_WEB detection branch (edit only, no other lines changed).

**Not changed (verified, already correct):**
- `MainActivity.kt` — `searchWeb()` call site and `GlassAction.SEARCH_WEB` enum case were already correct; no edits made there.
- `GlassAction` enum — `SEARCH_WEB` value already existed; no edits made there.

---

## 2. Fix: Some questions only play the loading/thinking sound, no answer ever comes

**Date:** 2026-09-08

**Problem:**
For some general "Hey Imi, ..." chat questions, the assistant plays the loading/thinking sound and then never speaks an answer — it just hangs.

**Root cause:**
- General chat in this app is answered by **Gemini Live** (`geminiLiveService`), which can use Gemini's function-calling ("tools") to fetch live data (web search, weather, stock, wiki, dictionary) before answering.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`
  - `initializeGeminiLive()` (around line 7471) registers `onToolCall(toolName, args)` (line 7732), which calls `handleGeminiToolCall(toolName, args)` (line 6381) **synchronously** — Gemini Live blocks waiting for this function's return value before it can speak a reply.
  - Several branches inside `handleGeminiToolCall` — `dictionary`/`define_word`, `wiki_summary`/`wikipedia`, `web_search`/`web_search_instant`, `get_stock`/`stock_quote`, `get_weather`/`weather`, `web_search_full` — call into `LocalToolHandlers` via `kotlinx.coroutines.runBlocking { ... }`, which blocks the calling thread until the network call finishes.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/LocalToolHandlers.kt`
  - The shared `OkHttpClient` (used for dictionary/wiki/DuckDuckGo web search/Open-Meteo weather/Yahoo Finance stock lookups) was created with **`OkHttpClient()`** — default timeouts, and critically **no overall call timeout**. On a slow, flaky, or stalled connection (common over a phone hotspot / BLE-tethered glasses network), a request could hang far longer than expected, or effectively indefinitely if the connection stalls mid-response.
  - Because `handleGeminiToolCall` blocks on this call (`runBlocking`) and Gemini Live blocks on `handleGeminiToolCall`, a hung network call meant: no tool result is ever returned → Gemini Live never receives data to answer with → it never speaks → the user is left hearing only the loading/thinking tone forever, with no error and no fallback message.
  - This only reproduces on **some** questions because it only affects questions where Gemini decides to invoke one of these tools (e.g. "what's the weather", "who is X", "search for Y", "define Z", stock lookups) and only when that particular network call stalls — plain conversational questions that don't need a tool call are unaffected, which matches the intermittent/"some questions" symptom.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/LocalToolHandlers.kt`
- Added explicit bounded timeouts to the shared `OkHttpClient` so every network call used by these tools is guaranteed to return (successfully or with a caught exception → fallback error string) well within Gemini Live's patience, instead of potentially hanging indefinitely:

```kotlin
import java.util.concurrent.TimeUnit

private val client = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .writeTimeout(6, TimeUnit.SECONDS)
    .callTimeout(8, TimeUnit.SECONDS)
    .build()
```

- No other logic changed. Every call site already wraps its `client.newCall(...).execute()` in `try/catch`, so a timeout now throws a caught `IOException`/`SocketTimeoutException` and returns the existing fallback string (e.g. `"Error fetching weather: ..."`) — which flows back through `handleGeminiToolCall` → Gemini Live → is spoken to the user, instead of the call hanging forever.

**Behavior after fix:**
- Any of the affected tool calls (dictionary, wiki summary, web search — instant/full, weather, stock quote) will now always return within ~8 seconds, either with real data or a spoken error message like "Error fetching weather: ..." — never a silent infinite hang.
- The user will always hear a spoken response after asking a question that triggers one of these tools, even on a bad network.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/LocalToolHandlers.kt` — added `import java.util.concurrent.TimeUnit` and replaced `OkHttpClient()` with a `OkHttpClient.Builder()` configured with connect/read/write/call timeouts.

**Not changed (reviewed, out of scope for this fix):**
- `MainActivity.kt` — `handleGeminiToolCall` / `onToolCall` logic itself was not changed; the `runBlocking` calls remain, but are now bounded transitively by the client timeout.
- `SongIdentifier.kt` and `GlassBrowserTools.kt` — also invoked via `runBlocking` inside `handleGeminiToolCall`, but not covered by this fix since the reported issue was specific to general chat questions (weather/search/wiki/stock/dictionary style), not song ID or browser tools. Flagged here for future attention if the same symptom is seen with those tools.

---

## 3. Fix: Calling a contact from the contact list not working (Mark 1)

**Date:** 2026-09-08

**Problem:**
Asking the assistant to call someone by name (e.g. "call John") did not actually make the call — nothing happened after the initial permission prompt.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`
- `lookupAndCall(contactName)` (line ~3916) checks for `READ_CONTACTS` permission. If it is not yet granted (e.g. first time the feature is used, or after a reinstall), it speaks an error, calls `ActivityCompat.requestPermissions(...)`, and **returns** — it does not look up or call the contact on this pass; it relies on being invoked again after the permission is granted.
- `onRequestPermissionsResult()` (line ~1089) handles the `REQUEST_READ_CONTACTS` case (line ~1102) by only showing a `Toast` ("Contacts access granted") when the permission is granted. It never re-triggers the contact lookup/call. This is inconsistent with the sibling permission flow for `CALL_PHONE` in `makePhoneCall()` (line ~4040), which *does* retry itself via a `postDelayed` check after requesting `CALL_PHONE`.
- Net effect: the very first "call X" voice command (or any time contacts permission had been revoked) would ask for permission, the user grants it, and then the call simply never happens — the user has to repeat the command a second time for it to work, which reads as "calling from the contact list is not working."

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`
- Added a new field to remember which contact call is waiting on the permission prompt (near the other `REQUEST_*` constants, ~line 277-279):

```kotlin
private val REQUEST_READ_CONTACTS = 302
private val REQUEST_CALL_PHONE = 303
private var pendingContactCallName: String? = null
```

- `lookupAndCall()` now records the contact name before requesting permission (~line 3918-3922):

```kotlin
if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
    speakOut("I need contacts permission to find $contactName", "ERROR")
    pendingContactCallName = contactName
    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_READ_CONTACTS)
    return
}
```

- `onRequestPermissionsResult()`'s `REQUEST_READ_CONTACTS` branch (~line 1102-1111) now resumes the call automatically once permission is granted, and clears the pending name either way so a denial doesn't leave stale state:

```kotlin
REQUEST_READ_CONTACTS -> {
    if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(this, "Contacts access granted", Toast.LENGTH_SHORT).show()
        // Resume the call that was waiting on this permission, if any.
        pendingContactCallName?.let { name ->
            pendingContactCallName = null
            lookupAndCall(name)
        }
    } else {
        pendingContactCallName = null
        speakOut("Contacts permission denied. Can't access phonebook.", "ERROR")
    }
}
```

**Behavior after fix:**
- First-time (or permission-revoked) "call X": permission prompt appears, and as soon as the user grants it, the contact lookup + call proceeds automatically — no need to repeat the voice command.
- If the user denies the permission, no stale pending call is left around to fire unexpectedly later.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt` — added `pendingContactCallName` field, set it in `lookupAndCall()`, and consumed/cleared it in `onRequestPermissionsResult()`'s `REQUEST_READ_CONTACTS` branch.

**Not changed (reviewed, not the cause here):**
- `findContactPhoneNumber()`, `makePhoneCall()` — contact search and dialing logic were already correct; `makePhoneCall()` already had its own retry-after-permission handling for `CALL_PHONE`, so it was left as-is.
- `speakOut()`'s Gemini-Live-mode gating (only `VISION`/`ERROR` utterance IDs are spoken locally, others rely on Gemini Live audio) — this affects the "Calling X" confirmation phrase across several action functions (`searchWeb`, `navigateTo`, `lookupAndCall`, etc.) equally by design, not specific to calling, so left unchanged.

---

## 4. Fix: "Call [contact]" hangs forever on the processing chime, session goes dead (Mark 1)

**Date:** 2026-09-08

**Problem (from user-provided logcat):**
On Mark 1, after asking "Who would you like to call?" and answering "Call Ayushi," the app just played the processing/thinking chime for ~8 seconds and then went completely silent — no answer, no call placed, no error, and the assistant stopped listening. Logcat showed:
- `🔊 PROCESSING CHIME TRIGGERED` right after the user's speech ended.
- Nothing but outbound audio-chunk logs (`📤 Sent N audio chunks`) for the next ~8 seconds — no `toolCall`, no `serverContent`, no reply of any kind from the Gemini Live websocket.
- `🔊 PROCESSING CHIME STOPPED (max duration reached, no reply)` — the chime's own safety watchdog firing after `MAX_CUE_MS` (8000ms) with nothing further happening afterward.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
- The app already has a "silent turn" recovery mechanism, `recoverFromSilentTurn()` (~line 3000), which re-prompts Gemini once to actually speak an answer when a turn ends with no audio/text. But it is only invoked from one place: inside the `turnComplete` handling block in `handleGeminiMessage()` (~line 2907-2925), i.e. only after the Gemini Live server explicitly sends a `turnComplete` message.
- In this failure, the Gemini Live server never sent **any** message back for the turn at all — no `serverContent`, no `toolCall`, no `turnComplete`. This can happen with a stuck/dropped server-side turn (e.g. the model started evaluating a `make_phone_call` tool call and the response never arrived). Because there was no `turnComplete` event, `recoverFromSilentTurn()` was never called.
- The only thing that *did* fire was the processing chime's own watchdog (~line 540-550, `MAX_CUE_MS = 8000L` at line 145), which exists purely to stop the looping sound so it doesn't play forever — it had no knowledge of, and no hook into, the silent-turn recovery logic. So the sound stopped, but the turn itself was left dangling forever: no retry, no fallback message, no session reset, no re-arming of listening.
- Net effect: any turn where the server silently drops the response entirely (not just responds-with-nothing-and-closes) left the user stuck with dead air after the chime, mirroring exactly what was seen for "call Ayushi."

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
- In the `MAX_CUE_MS` watchdog callback (~line 540-559), after stopping the chime, added a call into the existing `recoverFromSilentTurn()` recovery path if no audio/transcription was ever received for the turn — so a fully-dropped server turn now gets the same one-shot recovery nudge as a turn that closed with `turnComplete` but produced nothing:

```kotlin
mainHandler.postDelayed({
    if (isThinkingSoundPlaying.compareAndSet(true, false)) {
        synchronized(thinkingSoundLock) {
            if (thinkingStreamId != 0) {
                try { pool.stop(thinkingStreamId) } catch (_: Exception) {}
                thinkingStreamId = 0
            }
        }
        Log.d(TAG, "🔊 PROCESSING CHIME STOPPED (max duration reached, no reply)")

        // The server never sent turnComplete for this turn (dropped response,
        // stuck tool call, etc.), so recoverFromSilentTurn() in
        // handleGeminiMessage() never ran - that path only fires on
        // turnComplete. Without this, the session just goes silent forever.
        // Nudge the model for an answer now using whatever was transcribed so
        // far, same as the turnComplete-triggered recovery.
        val fullInput = currentInputTranscription.toString()
        if (!receivedAudioInCurrentTurn && !hasTranscriptionForCurrentTurn) {
            recoverFromSilentTurn(fullInput)
        }
    }
}, thinkingCueToken, MAX_CUE_MS)
```

- No changes were needed to `recoverFromSilentTurn()` itself — it already guards against retrying more than once per turn (`silentTurnRetried`), against nudging on a blank turn with no tool call, and against firing when the session isn't ready, so it's safe to call from this new site too.
- `recoverFromSilentTurn()` sends a new prompt via `speakText(prompt, speakDirectly = false)`, which pushes a fresh `client_content` turn on the same websocket (with `turn_complete: true`) asking Gemini to actually answer — e.g. to proceed with the `make_phone_call` tool call / read back the result — regardless of whether the previous turn ever formally closed.

**Behavior after fix:**
- If Gemini's server drops a turn entirely (no message at all for up to 8s), the app now automatically asks the model once more to answer/complete the action, instead of leaving the user with silence and a dead session.
- "Call Ayushi" (and any other command that hits this failure mode) gets a real chance to complete or to get a spoken explanation, rather than hanging indefinitely.
- If the retried turn also produces nothing, `recoverFromSilentTurn()`'s existing one-retry guard (`silentTurnRetried`) prevents an infinite retry loop — matching the existing behavior for the `turnComplete`-triggered case.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt` — added a `recoverFromSilentTurn()` call inside the processing-chime `MAX_CUE_MS` watchdog callback.

**Not changed (reviewed, not the cause here):**
- `recoverFromSilentTurn()`, `handleGeminiMessage()`'s existing `turnComplete` recovery block — logic was already correct for the case it handles; only needed to also be reachable from the "no message at all" case, which this fix adds.
- Contact lookup/call logic (`lookupAndCall`, `findContactPhoneNumber`, `makePhoneCall`) and the `make_phone_call` tool handler in `MainActivity.handleGeminiToolCall` — unchanged; the logcat showed the failure happening before any tool call was even received by the app, so this was a Gemini Live turn-delivery issue, not a contacts/calling logic bug (see item 3 above for the separate contacts-permission-retry fix).

---

## 5. Fix: Calling a contact by name doesn't reliably find/call the right person (Mark 1)

**Date:** 2026-09-08

**Problem:**
Saying "call [name]" (e.g. "call Ayush" for a contact saved as "Ayushi") frequently fails to call the right person — reported as calling not working across many different people's names, not an isolated case.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`
- The old `findContactPhoneNumber(name)` (~line 3952, prior version) matched contacts using a raw SQL `LIKE '%name%'` substring query against `DISPLAY_NAME`, taking whatever row the query happened to return **first** (contacts are not queried in relevance order — just whatever order the ContactsContract provider returns them in, which can be alphabetical, insertion order, or something else entirely depending on device/OEM).
- This has two failure modes that both show up as "calling isn't working":
  1. **No match at all** when the spoken name doesn't appear as an exact substring of the saved name (or vice versa) — e.g. voice transcription renders "Ayushi" as "Ayushee" or "I ushi", or the user says a nickname/short form that isn't literally contained in the saved contact name.
  2. **Wrong match** when multiple contacts loosely match the substring (e.g. several contacts contain "an" or a common syllable) — the first row from the unordered query wins, regardless of how close it actually is to what was said.
- There was no actual "closest match" logic anywhere — just first-hit substring search, and a separate weaker fallback that split the spoken name into words and repeated the same substring search per word.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`
- Rewrote `findContactPhoneNumber(name)` (~line 3952) to:
  1. Load **all** phone contacts once (name + number) via a single unfiltered `ContactsContract.CommonDataKinds.Phone` query.
  2. Return an exact case-insensitive full-name match immediately if one exists.
  3. Otherwise score every contact against the spoken name using a new `contactNameSimilarity(spoken, contactName)` helper (~line 4018) and pick the highest-scoring contact — i.e. the actual **closest** match, not just the first substring hit:
     - A substring hit in either direction (e.g. "ayush" inside "ayushi sharma") scores highly (0.85-1.0), weighted by how close the two lengths are, so a near-exact substring beats a substring buried in an unrelated longer name.
     - Otherwise falls back to normalized Levenshtein (edit-distance) similarity against the closest individual word in the contact's name, so small mis-transcriptions ("Ayushee" vs "Ayushi") and first-name/last-name-only matches ("Sharma" matching "Ayushi Sharma") both still resolve.
  4. Added a minimum similarity threshold (`0.5`) below which no contact is returned at all, rather than confidently calling a wrong person on a weak/coincidental match.
- Added two small private helpers used only by this matching logic: `contactNameSimilarity()` and `levenshteinDistance()` (standard dynamic-programming edit distance), both scoped to `MainActivity` right after `findContactPhoneNumber`.

**Behavior after fix:**
- "Call Ayush" now matches a contact saved as "Ayushi" (or "Ayushi Sharma", or with a slightly different spelling) by picking the closest-scoring contact instead of requiring an exact substring hit or relying on arbitrary query order.
- If no contact is close enough to be confident about, the app now correctly reports "couldn't find" and offers the dial-pad fallback (existing behavior in `lookupAndCall`), instead of silently calling the wrong person.
- This applies to every contact, not just a specific name — the fix is in the general matching algorithm used for all "call X" requests.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt` — replaced `findContactPhoneNumber()`'s substring-query logic with load-all + closest-match scoring; added `contactNameSimilarity()` and `levenshteinDistance()` helper functions.

**Not changed (reviewed, unaffected by this fix):**
- `lookupAndCall()`, `makePhoneCall()` — call/permission flow around the lookup was already correct (see item 3 above for the separate contacts-permission-retry fix) and did not need changes; they call `findContactPhoneNumber()` exactly as before, just now get a better answer back.
- `findContactEmail()` — the analogous email-lookup-by-name function used for messaging still uses the old substring `LIKE` query. Not touched in this fix since the reported issue was specifically about phone calls; flagged here in case the same "closest match" treatment is wanted for email/message recipient lookup later.

---

## 6. Build fix: "Invalid classfile header" / dexBuilderDebug failure

**Date:** 2026-09-08

**Problem:**
Local Gradle build (`:app:assembleDebug`) failed at the `:app:dexBuilderDebug` step with:
```
AGPBI: {"kind":"error","text":"com.android.tools.r8.internal.Oe: Invalid classfile header", ...LiveGalleryActivity$onCreate$13.class"}
AGPBI: {"kind":"error","text":"com.android.tools.r8.internal.Oe: Invalid classfile header", ...LiveGalleryActivity$syncFromGlasses$1$reachable$1.class"}
```
along with a large stack trace (`DexArchiveBuilderTaskDelegate`, `FreeListBlockStore.alloc`, etc.).

**Root cause:**
- This was **not** a source-code bug. `:app:compileDebugKotlin` reported `UP-TO-DATE`/reused incrementally, but two specific compiled `.class` files under `app/build/tmp/kotlin-classes/debug/com/sdk/glassessdksample/ui/gallery/` (`LiveGalleryActivity$onCreate$13.class` and `LiveGalleryActivity$syncFromGlasses$1$reachable$1.class`) were corrupted/truncated on disk from a prior interrupted or partially-written build.
- The D8 dexer (`dexBuilderDebug`) failed trying to read those two stale class files ("Invalid classfile header" = the file's bytecode magic number/structure is broken, not that the Kotlin source itself is invalid).
- The accompanying `FreeListBlockStore.alloc` stack trace pointed at Gradle's own local build-cache/task-history store also being in a bad state, consistent with an interrupted earlier build (e.g. IDE killed mid-build, disk full momentarily, etc.) rather than anything in the app's Kotlin/Java source.

**Fix:**
- Ran a forced rebuild that bypasses Gradle's up-to-date checks and regenerates all intermediate outputs from source, clearing the stale/corrupted class files and cache entries:
```
./gradlew.bat :app:assembleDebug --rerun-tasks
```
- Result: `BUILD SUCCESSFUL in 4m 1s` — all 41 tasks executed fresh, `LiveGalleryActivity`'s inner classes recompiled cleanly, and `dexBuilderDebug`/`mergeProjectDexDebug`/`packageDebug` all completed without error.

**Behavior after fix:**
- `:app:assembleDebug` builds successfully from a clean state.
- No source code was changed for this item — this was purely a local build-cache corruption issue.

**Files touched in this change:**
- None (no source files edited). Stale generated files under `Mac_imicode_android/app/build/tmp/kotlin-classes/debug/` were regenerated by the forced rebuild; `app/build/` is a generated directory and not part of the tracked source.

**Note for future occurrences:**
If this "Invalid classfile header" error (or similar Gradle `FreeListBlockStore`/cache errors) comes back, the fix is the same: force a clean recompile. If `--rerun-tasks` alone doesn't clear it, also try deleting `Mac_imicode_android/app/build/` (safe — it's fully regenerated) and, if needed, `Mac_imicode_android/.gradle/` before rebuilding.

---

## 7. Fix: Mic button in Quick Notes search bar is not clickable

**Date:** 2026-09-08

**Problem:**
In the Quick Notes screen, the small mic icon next to the search bar (used for voice search) did nothing when tapped.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/res/layout/activity_quick_notes.xml`
- The mic `ImageView` (`btn_search_mic`, ~line 184) exists in the layout, sits right next to `et_search`, and has `?attr/selectableItemBackgroundBorderless` for a tap ripple — so it visually looks tappable.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesActivity.kt`
- However, `setupUI()` never attached a `setOnClickListener` to `btn_search_mic` — every other button in this screen (`btn_back`, `tab_ai`, `tab_self`, `btn_compose`) had a listener wired up, but the mic button was simply skipped, so tapping it was a complete no-op (not even a ripple's worth of behavior beyond the visual press state).

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesActivity.kt`
- Wired up `btn_search_mic` to launch Android's system speech-to-text dialog (same `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` pattern already used elsewhere in the app, e.g. `ChatActivity.kt`), with the recognized text filled into the search box:

```kotlin
findViewById<ImageView>(R.id.btn_search_mic).setOnClickListener { onSearchMicClicked() }
```

- Added supporting registration + handlers (near the other `lateinit`/launcher fields and near `openEditor`):

```kotlin
private val speechLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode != RESULT_OK) return@registerForActivityResult
    val spoken = result.data
        ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()
        .orEmpty()
    if (spoken.isNotBlank()) {
        etSearch.setText(spoken)
        etSearch.setSelection(etSearch.text.length)
    }
}

private val recordAudioPermissionLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
) { granted -> if (granted) startVoiceSearch() }

private fun onSearchMicClicked() {
    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (hasPermission) startVoiceSearch()
    else recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
}

private fun startVoiceSearch() {
    val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toString())
        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search notes")
        putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }
    try {
        speechLauncher.launch(intent)
    } catch (e: Exception) {
        Toast.makeText(this, "Voice search not available on this device", Toast.LENGTH_SHORT).show()
    }
}
```

- `RECORD_AUDIO` permission was already declared in `AndroidManifest.xml` (used elsewhere for the wake word / Gemini Live mic), so no manifest change was needed — only the runtime permission prompt via `recordAudioPermissionLauncher` for devices where it hasn't been granted yet.
- Since `etSearch` already has a `TextWatcher` (`afterTextChanged` → `refreshList()`), setting the recognized text automatically re-filters the notes list — no extra wiring needed for the search itself to work once the text is filled in.

**Behavior after fix:**
- Tapping the mic icon in Quick Notes now opens the system speech-recognition dialog ("Search notes"), and whatever is spoken is placed into the search box and immediately filters the notes list, same as typing it manually.
- If `RECORD_AUDIO` permission hasn't been granted yet, tapping the mic now prompts for it first, then proceeds automatically once granted.
- Verified with `:app:compileDebugKotlin` — builds clean with no new warnings/errors introduced.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesActivity.kt` — added `speechLauncher` and `recordAudioPermissionLauncher` activity-result launchers, `onSearchMicClicked()`/`startVoiceSearch()` functions, and wired `btn_search_mic`'s click listener.

**Not changed:**
- `activity_quick_notes.xml` — the `btn_search_mic` view itself was already correctly defined (icon, sizing, ripple background); only the missing Kotlin-side click listener needed to be added.
- `NoteEditorActivity.kt` — the note editor (used when composing/editing an individual note) has no mic button of its own; this fix only covers the search-bar mic on the Quick Notes list screen, which is what was reported.

---

## 8. Removed: Bottom "Ask AI" composer bar on the History screen

**Date:** 2026-09-08

**Problem (from user-provided screenshot):**
On the History screen (past conversation transcripts), a bottom bar with a "+" button, an "Ask AI" text field, a mic icon, and a voice-mode orb icon was shown, styled like a chat composer. The user reported this bar produced garbled/nonsense output when used (e.g. a string of repeated syllables sent as if it were a message) and pointed out that History is a read-only conversation log, not a messaging/chat screen — the composer bar has no place there and should be removed entirely.

**Root cause / investigation:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationHistoryActivity.kt`
- Searched the entire activity for any reference to the composer's view IDs (`composer`, `etAskAi`, `btnComposerAdd`, `btnComposerMic`, `btnComposerVoice`) — there were **none**. The bar was pure leftover UI in the layout XML with zero Kotlin-side wiring: no click listeners, no text submission handling, nothing.
- This means the bar was already fully non-functional/dead by design — any apparent "response" to typing/tapping in it (like the garbled text seen in the screenshot) was not this screen's own logic; the screen never reads or acts on that EditText at all. It was inert leftover UI that only added visual clutter and false affordance (looks like a chat box, does nothing when used).
- Confirmed via `grep` across the whole `app/src/main/java` tree that `btnComposerAdd`, `btnComposerMic`, `btnComposerVoice`, and `etAskAi` are referenced nowhere outside the layout XML itself.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_conversation_history.xml`
- Removed the entire "Ask AI" composer `LinearLayout` block (previously ~line 116-173: the `composer` container with `btnComposerAdd`, `etAskAi`, `btnComposerMic`, `btnComposerVoice`).
- `rvThread` (the RecyclerView showing the conversation thread, ~line 106) no longer anchors above the composer (`android:layout_above="@id/composer"` removed) and now fills the full remaining height of the thread view, with its bottom padding bumped from `10dp` to `16dp` to keep the last message comfortably clear of the screen edge instead of the now-removed bar.
- Confirmed no other file references the removed view IDs (`grep` across `app/src/main/java` returned nothing), so nothing else needed updating.
- Verified with `:app:compileDebugKotlin` — builds clean.

**Behavior after fix:**
- The History screen's thread view (past conversation transcripts) now shows only the message list, filling the screen — no bottom input bar, no mic, no "+"/voice-orb icons.
- The "Recents" list screen (the other half of this activity, listing past conversation sessions) was already unaffected — it never had this composer to begin with.
- No functionality was lost, since the composer had no working logic behind it to begin with.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/res/layout/activity_conversation_history.xml` — removed the `composer` `LinearLayout` block and its children; adjusted `rvThread`'s layout constraints/padding to fill the space.

**Not changed (reviewed, not needed):**
- `ConversationHistoryActivity.kt` — no code referenced the composer or its children, so no Kotlin changes were required.
- `hist_composer_bar.xml`, `hist_voice_orb.xml`, `ic_waveform.xml` drawables — left in place (unused now) rather than deleted, since removing drawable resources is out of scope for this UI fix and they may be reused elsewhere; can be cleaned up separately if desired.

---

## 9. Fix: "Clear" on History wiped ALL conversations instead of just the open one

**Date:** 2026-09-08

**Problem:**
On the History screen, tapping "Clear" while viewing a single conversation thread deleted the entire conversation history (every past session), not just the one thread currently open. Requested behavior: keep "Clear" for wiping everything (used on the Recents/all-conversations list), and use "Delete" for removing just one specific conversation session when viewing it individually.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationHistoryActivity.kt`
- The header's `btnClearHistory` button was shared by both screens (Recents list and single-thread view, see item 8's layout) and always called the same `clearHistory()` function, which unconditionally called `ConversationSessionStore.clearAll(this)` — deleting every stored session regardless of whether the user was looking at the full list or one specific conversation.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationSessionStore.kt` — there was no function to delete a single session by id; only `clearAll()` existed.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/HistoryRecentsAdapter.kt` — `HistoryTopic` (the row/thread model shown in both screens) did not carry the underlying session's `id`, so even if a "delete one" function existed, the UI had no way to know *which* session to delete once a thread was opened.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationSessionStore.kt`
- Added a new function to remove exactly one session:
```kotlin
fun deleteSession(context: Context, sessionId: String) {
    val sessions = getSessions(context)
    if (sessions.removeAll { it.id == sessionId }) {
        save(context, sessions)
    }
}
```
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/HistoryRecentsAdapter.kt`
- `HistoryTopic` now carries the session id alongside its title/messages:
```kotlin
data class HistoryTopic(
    val sessionId: String,
    val title: String,
    val messages: List<Pair<Boolean, String>>
)
```
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationHistoryActivity.kt`
- `loadAndRenderHistory()` now passes `sessionId = session.id` when building each `HistoryTopic`.
- Added `openTopic` (tracks which thread, if any, is currently open) and a `btnClearHistory` field reference so its label can be swapped.
- `openThread(topic)` now stores `openTopic = topic` and sets the button text to **"Delete"**; `showRecents()` clears `openTopic` and resets the button text to **"Clear"`. This means the label itself now reflects and enforces the correct scope depending on which screen is showing.
- The button's click handler was split into a router plus two focused functions:
```kotlin
private fun onClearOrDeleteClicked() {
    if (showingThread) deleteOpenThread() else clearAllHistory()
}

private fun clearAllHistory() {
    ConversationSessionStore.clearAll(this)
    // ...existing "cleared" behavior (unchanged), only reachable from Recents now
}

private fun deleteOpenThread() {
    val topic = openTopic ?: return
    ConversationSessionStore.deleteSession(this, topic.sessionId)
    Toast.makeText(this, "Conversation deleted", Toast.LENGTH_SHORT).show()
    showRecents()
}
```

**Behavior after fix:**
- On the Recents list (all conversations), the button reads **"Clear"** and wipes every stored conversation session, same as before — unchanged behavior for that screen.
- When a single conversation thread is opened, the button now reads **"Delete"** and removes only that one session; every other conversation in history is left untouched. After deleting, the screen returns to the (now-updated) Recents list.
- Verified with `:app:compileDebugKotlin` — builds clean; confirmed `HistoryTopic(...)` has only the one call site (already updated) so no other code broke from the new required `sessionId` field.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationSessionStore.kt` — added `deleteSession(context, sessionId)`.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/HistoryRecentsAdapter.kt` — added `sessionId` field to `HistoryTopic`.
3. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ConversationHistoryActivity.kt` — added `openTopic`/`btnClearHistory` fields, pass `sessionId` when building topics, swap button label between "Clear"/"Delete" in `openThread()`/`showRecents()`, split the click handler into `onClearOrDeleteClicked()` → `clearAllHistory()` / `deleteOpenThread()`.

**Not changed (reviewed, not needed):**
- `activity_conversation_history.xml` — the `btnClearHistory` TextView itself needed no layout changes; only its text is now updated at runtime depending on context.
- Remote/backend sync (`ConversationSync`) — `pushClearAll()` is still called for the all-history clear path (unchanged); no equivalent "delete one session" sync call exists yet, so `deleteSession()` is local-only for now. Flagged here in case server-side per-session deletion is wanted later.

---

## 10. Removed: Edit (pencil) button on Profile's "Your Account" card

**Date:** 2026-09-08

**Problem (from user-provided screenshot):**
On the Profile screen, the "Your Account" card (showing the user avatar, name "User", and "No email on file") had a pencil/edit icon on the right. The user should not be able to edit this account info from here — the icon needed to be removed entirely.

**Investigation:**
- File: `Mac_imicode_android/app/src/main/res/layout/activity_profile.xml`
- The pencil icon was `editProfileButton` (~line 121-129), an `ImageButton` with `ic_edit` sitting to the right of the avatar/name/email block inside the "Your Account" card.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ProfileActivity.kt`
- Its click listener (~line 115) opened `UserMemoryActivity` — i.e. tapping the pencil took the user to a screen for editing their stored profile/memory data. Since the layout uses Android view binding (`binding.editProfileButton`), the view and its Kotlin reference both needed to be removed together to keep the project compiling.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_profile.xml`
- Removed the `editProfileButton` `ImageButton` entirely from the "Your Account" card's row (the avatar + name/email column now simply fills the row on its own).
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ProfileActivity.kt`
- Removed the corresponding click listener block:
```kotlin
// Edit profile button
binding.editProfileButton.setOnClickListener {
    // Open user memory activity for editing
    startActivity(Intent(this, UserMemoryActivity::class.java))
}
```
- Confirmed via `grep` that `editProfileButton` is referenced nowhere else in the codebase, so no other file needed updating.
- Verified with `:app:compileDebugKotlin` — builds clean (view-binding regenerates `ActivityProfileBinding` without an `editProfileButton` property, and no code still referenced it).

**Behavior after fix:**
- The "Your Account" card on the Profile screen now shows only the avatar, "User" name, and "No email on file" — no edit/pencil icon, and no way to reach the profile-editing screen from here.
- `UserMemoryActivity` itself (the screen the pencil used to open) was not deleted — it simply has no remaining entry point from the Profile screen. If it's reachable from elsewhere in the app and should also be locked down, that would need to be addressed separately.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/res/layout/activity_profile.xml` — removed the `editProfileButton` `ImageButton`.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ProfileActivity.kt` — removed the `editProfileButton` click listener.

**Not changed (reviewed, out of scope):**
- `UserMemoryActivity.kt` — left as-is; only its entry point from Profile was removed, not the activity/screen itself.
- Avatar image (`profileImage`), name (`userName`), and email (`userEmail`) views — left in place and still display-only (no click listeners on them either), consistent with the request to make this section non-editable.

---

## 11. Web browser: fixed permanent CAPTCHA/login lockup + added direct voice control

**Date:** 2026-09-08

Two related fixes/additions to the in-app web browser feature ("Web" section, driven by voice through the glasses and by hand/typed command on the phone).

### 11a. Fix: browser could get permanently stuck on a CAPTCHA/login wall

**Problem:**
When a voice-driven browsing task ("open my email and check") hit a CAPTCHA or a sign-in wall, the assistant would say to open the Web screen and finish it there, then say "continue." But once stuck, the user could never actually get past it — every future `browse_web` request was refused, "continue" just re-hit the same wall, and there was no way to navigate anywhere or change the site at all. This matched exactly what was reported: "once it gets stuck in any capture situation... the user cannot run any websites, and the user cannot change any website."

**Root cause (investigated in detail before changing anything):**
- The voice-driven browser (`GlassBrowserTools`/`browse_web`) runs against `GlassBrowserEngine` (`Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserEngine.kt`) — a **separate, off-screen WebView singleton**, explicitly never attached to any window (`GlassBrowserEngine.kt:69-72`, "this browser has no viewer"). It shares cookies with the visible browser via the same global `CookieManager` (`WebSessionManager.kt`), but is a completely different WebView instance with its own page/DOM.
- When a CAPTCHA or login wall is hit, `HeadlessAgentRunner.handOff()` calls `GlassBrowserEngine.requireUser(reason, goal)`, which sets `pendingReason`/`awaitingUser` and tells the user to "open the Web screen... and finish it, then say continue" (`HeadlessAgentRunner.kt`).
- `GlassBrowserTools.browse()` (`GlassBrowserTools.kt`, the `browse_web` handler) checks `GlassBrowserEngine.pendingReason` first and refuses to do anything else while it's set — by design, so a new task can't silently abandon the parked one.
- The bug: the **visible** `WebBrowserActivity` (opened from "the Web screen") shows its **own separate WebView** (`binding.webView`). Its `showGlassHandoffIfWaiting()` only *displayed a text message* about being stuck — it never loaded the actual URL the off-screen engine was stuck on, and had no "Continue"/"Cancel" control wired to `GlassBrowserEngine` at all. So the CAPTCHA/login page the user was told to "finish" was never actually shown anywhere the user could see or tap it.
- Saying "continue" called `GlassBrowserEngine.resume()`, which just re-ran the agent against the same (still-unsolved) off-screen page — the same CAPTCHA/login wall was detected again immediately, `pendingReason` was set right back, and the lock was permanent. `GlassBrowserEngine.cancel()` — the only thing that could force-clear the stuck state — existed in the code but was never called from anywhere (dead code), so there was no escape hatch at all.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- `showGlassHandoffIfWaiting()` (called from `onResume()`) now:
  1. Actually loads the URL the off-screen `GlassBrowserEngine` is stuck on into the **visible** WebView (`loadUrl(stuckUrl)`, fetched via the suspend `GlassBrowserEngine.currentUrl()` on `lifecycleScope`) — since both WebViews share the same cookie jar, logging in / solving the CAPTCHA here genuinely resolves the block for the off-screen engine too, instead of being invisible busywork.
  2. Shows a real, working **"Continue"** button (previously always hidden for this path: `showContinue` was hardcoded `false`) and repurposes the existing Stop button as **"Cancel"** while this state is showing.
- Added `continueGlassHandoff()` — calls `GlassBrowserEngine.resume()` after the user has actually solved the block on the now-visible page, and tells them to say "continue" to the glasses to resume the voice task against the now-resolved page.
- Added `cancelGlassHandoff()` — calls the previously-dead `GlassBrowserEngine.cancel()`, so a stuck state the user does *not* want to resolve can always be force-cleared instead of blocking the browser forever.
- Added an `isShowingGlassHandoff` flag so the shared Stop/Continue buttons route to the right handler (`agent?.stop()`/`onContinueTapped()` for the **on-screen** command-bar agent's own separate handoff flow, vs. `cancelGlassHandoff()`/`continueGlassHandoff()` for the **glasses'** handoff) without the two flows fighting over the same UI — `showAgentStrip()` resets the flag (and the button label back to "Stop") whenever anything else uses the status strip.

**Behavior after fix:**
- When the glasses hit a CAPTCHA/login wall, opening the Web screen now shows the *actual* stuck page, so the user can genuinely solve it (log in, tick the CAPTCHA) rather than staring at an unrelated page with just a status message.
- Tapping "Continue" there clears the block against a page that's actually been resolved, so telling the glasses to "continue" afterward now has a real chance of succeeding instead of re-hitting the same wall.
- Tapping "Cancel" (or saying "browser cancel" — see 11b below) always force-clears a stuck state, so the browser can never be permanently locked even if the user doesn't want to finish that step.

**Files touched (11a):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — rewrote `showGlassHandoffIfWaiting()`; added `isShowingGlassHandoff` flag, `continueGlassHandoff()`, `cancelGlassHandoff()`; updated `btnAgentStop`/`btnAgentContinue` click listeners to route based on which handoff is active; added the flag-reset guard in `showAgentStrip()`.

---

### 11b. Added: direct voice control for the browser (scroll, click, type, back/forward, cancel)

**Problem:**
The user asked for the browser to be "completely voice-controllable," with the whole thing operable by voice only. Investigated the existing architecture first: the only voice interface into the browser was a single coarse `browse_web(goal)` tool that hands the request to an LLM planning loop (`WebAgentPlanner`) for up to 10 steps. There was no way to issue one immediate command like "scroll down" or "click sign up" — saying that would trigger a brand-new full `browse_web` call, re-running the whole read-plan-validate-execute loop just to do one thing.

**What already existed (kept as-is):**
- `browse_web` (full goal), `browser_continue` (resume after handoff), `read_current_page` (summarise), `catch_up_on_ai` (AI-service catch-up) — all in `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserTools.kt`.
- The underlying safety boundary (`ActionValidator.kt`) — blocks typing into password/sensitive fields, blocks tapping sign-in/login controls (those must be a manual user tap), and requires confirmation for anything that spends money or is irreversible. New tools reuse this validator rather than bypassing it.

**Added — six new direct-action voice tools**, each acting immediately on whatever page is already open, without a full re-plan:
- `browser_scroll` (direction: up/down; amount: "a bit"/"a lot"/"top"/"bottom")
- `browser_click` (description of the button/link to tap, in the user's own words — matched against the page's visible buttons/links)
- `browser_type` (which field, the text, optional submit/press-enter — matched against the page's visible input labels; still refuses sensitive/password fields, same as the full agent)
- `browser_back` / `browser_forward`
- `browser_cancel` (drops the current task/handoff — "never mind", "cancel that" — using the same previously-dead `GlassBrowserEngine.cancel()` from fix 11a)

**Implementation:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserTools.kt`
- Added the six tool names to `TOOL_NAMES` and their declarations (name, description, parameters) to `declarations()`.
- Added dispatch cases in `handle()` for each new tool name.
- Added `scroll()`, `click()`, `type()`, `backOrForward()`, `cancel()` handler functions, plus two small helpers:
  - `findByLabel()` — matches a spoken description ("sign up", "the search box") against the current page's buttons/links/inputs (read via the existing `PageReader`, the same data the full planner already reasons over) by exact match, then substring match, then word-overlap — without an LLM call, so it resolves instantly.
  - `runDirectAction()` — takes one `BrowserAction`, runs it through the existing `ActionValidator` (so a direct `browser_click` on a login button still hands off to the user, a direct `browser_type` into a password field still refuses, exactly as the full agent loop already enforced), then executes it via the existing `ActionExecutor` against `GlassBrowserEngine`'s WebView.
- "Scroll to top/bottom" is approximated with a large relative `BrowserAction.Scroll` (±25 viewport-heights) rather than adding a new raw-JS escape hatch, since `BrowserAction`/`ActionExecutor` are deliberately a closed, validated action set and it wasn't worth breaking that boundary for an edge case a big scroll already covers.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
- Extended the browser section of the system prompt (~line 2161) to tell the model: once a page is open via `browse_web`/`browser_continue`, use the new direct tools for simple next steps on the *same* page ("scroll down" -> `browser_scroll`, "click sign up" -> `browser_click`, etc.) instead of calling `browse_web` again, and only fall back to a fresh `browse_web` goal for a genuinely new multi-step task. Also documented `browser_cancel` alongside the existing `browser_continue` guidance.

**Behavior after fix:**
- Simple mid-browsing voice commands ("scroll down", "click the second result", "go back", "type running shoes in the search box") now execute immediately against the already-open page, instead of re-running a multi-step LLM planning loop each time.
- The safety boundaries from the full agent loop (no passwords/OTPs typed, no tapping sign-in buttons, confirmation required for payments/irreversible actions) apply identically to these direct tools, since they share the same `ActionValidator`.
- "Cancel"/"never mind" now works by voice too (`browser_cancel`), not just via the phone screen's new Cancel button from fix 11a.

**Files touched (11b):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserTools.kt` — added 6 tool declarations, `TOOL_NAMES` entries, dispatch cases, and handler functions (`scroll`, `click`, `type`, `backOrForward`, `cancel`, `findByLabel`, `runDirectAction`).
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt` — extended the browser system-prompt block to describe when to use the new direct tools vs. `browse_web`.

**Not changed (reviewed, deliberately out of scope for this pass):**
- Tab management (opening/switching/closing multiple tabs) — no support exists anywhere in `GlassBrowserEngine`/`BrowserAction` (single WebView singleton); not added here since it's a larger structural change (would need `GlassBrowserEngine` to become tab-aware) and wasn't part of what was asked.
- Unifying the on-screen (`WebBrowserActivity`/`WebAgentSession`) and off-screen (`GlassBrowserEngine`/`HeadlessAgentRunner`) browser/agent systems into one — fix 11a bridges the specific CAPTCHA-visibility gap between them without merging the two systems, which would be a much larger refactor.
- `MAX_STEPS` caps on the full `browse_web` planner loop (10 steps in `HeadlessAgentRunner`, 15 in `WebAgentSession`) — left as-is; the new direct tools reduce how often a long voice session needs to lean on the capped loop at all, which addresses the practical concern without changing the cap itself.
- Payment/irreversible-action confirmation (`ActionValidator.NeedsConfirmation`) — still always redirects to the phone screen rather than accepting a spoken "yes," by design (see the code's own reasoning: a spoken yes is too weak a gate for something that costs money or can't be undone). Not changed, and the new `browser_click`/`browser_type` tools go through the same check.

---

## 12. Fix: browser refused to open a new site because an unrelated old page had a CAPTCHA/login wall

**Date:** 2026-09-08

**Problem (from user-provided screenshot):**
Asked the browser to "open YouTube." Instead it navigated to (or was already sitting on) Claude's sign-in page and immediately reported "This page is asking for a CAPTCHA. Please solve it, then tap Continue" — even though the actual goal (YouTube) has nothing to do with Claude or a CAPTCHA at all.

**Root cause:**
- `WebBrowserActivity` restores whatever page was last open (`lastUrl()`, persisted across launches) when the screen is reopened — in this case, a Claude sign-in page left over from earlier testing/use.
- `WebAgentSession.runLoop()` (`Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebAgentSession.kt:134-137`) always reads the **current** page first and hands that snapshot to the planner for the very first step of *any* new goal — before the planner has had a chance to even issue an `Open`/`Search` action toward the actually-requested site.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/ActionValidator.kt`
  - `validate()`'s CAPTCHA/login gate (~line 41-49) blocked **every** action against a hasCaptcha page except the terminal ones (`HandoffToUser`/`Done`/`Failed`) — this included `Open` and `Search`, i.e. actions whose entire purpose is *leaving* the blocked page. So even if the planner correctly tried to navigate to YouTube, the validator itself would reject/hand-off that navigation because the page it was leaving happened to have a CAPTCHA on it.
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebAgentPlanner.kt`
  - The planner's own system prompt (rule 3, ~line 57-58) unconditionally instructed: "If the page shows a login screen or a CAPTCHA, use handoff" — with no exception for a goal that doesn't actually require interacting with that page, so the model itself was steered toward giving up rather than just navigating away.
- Net effect: being on *any* CAPTCHA/login-walled page (even one from a completely unrelated earlier task) made the browser refuse to go anywhere else at all, exactly matching "I told it to open YouTube... there is no need to solve a CAPTCHA on YouTube."

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/ActionValidator.kt`
- Added `Open` and `Search` to the exemption list in the CAPTCHA/login gate, since navigating away needs no interaction with what's blocking the current page:
```kotlin
if (page?.hasCaptcha == true &&
    action !is BrowserAction.HandoffToUser &&
    action !is BrowserAction.Done &&
    action !is BrowserAction.Failed &&
    action !is BrowserAction.Open &&
    action !is BrowserAction.Search
) {
    return Verdict.Handoff(
        "This page is asking for a CAPTCHA. Please solve it, then tap Continue."
    )
}
```
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebAgentPlanner.kt`
- Rewrote system-prompt rule 3 to make the handoff conditional on the goal actually needing that page, with an explicit YouTube-style example so the model doesn't default to giving up:
```
3. If the CURRENT page shows a login screen or a CAPTCHA and the goal
   actually requires using THIS page (reading it, clicking something on
   it, submitting a form on it), use "handoff" and explain what the
   user should do. But if the goal is to go somewhere else entirely
   (e.g. goal is "open YouTube" but the current page happens to be some
   other site's login/CAPTCHA screen), just "open" or "search" to the
   site the goal actually asks for - leaving an unrelated blocked page
   needs no handoff, since you are not interacting with it.
```

**Behavior after fix:**
- Asking to open a site that has nothing to do with whatever page happens to already be loaded now works immediately, even if that old page has a CAPTCHA or login wall on it — the agent (and the validator, as a hard backstop even if the model still tries something else first) can navigate straight away from it.
- The CAPTCHA/login handoff (and the fix from item 11a for when it does legitimately trigger) is unchanged for cases where the goal genuinely requires interacting with the blocked page itself.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/ActionValidator.kt` — exempted `BrowserAction.Open`/`BrowserAction.Search` from the CAPTCHA/login handoff gate in `validate()`.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebAgentPlanner.kt` — rewrote system-prompt rule 3 to make the handoff instruction conditional on the goal needing the current page, with a worked example.

**Not changed (reviewed, not needed):**
- `WebAgentSession.kt` — the "always read the current page first" behavior itself is correct and unchanged; the actual bug was that a CAPTCHA on that page over-broadly blocked navigation actions too, which is what the fix addresses.
- `GlassBrowserTools.kt`'s new direct tools from item 11b (`browser_click`/`browser_type`/etc.) — these call the same shared `ActionValidator.validate()`, so they automatically inherit this fix; no separate change needed there.
- `lastUrl()` persistence in `WebBrowserActivity` — not changed; restoring the last page across launches is intentional existing behavior, not itself a bug (the bug was what the agent did when it found a blocked page there for an unrelated goal).

---

## 13. Fix: "Continue with Google" sign-in freezes; voice commands felt broken/glitchy

**Date:** 2026-09-08

Two more browser problems reported together: (1) manually tapping "Continue with Google" on a sign-in page (e.g. ChatGPT) just froze with no progress, and (2) the mic/voice command system in the browser felt unreliable — speaking a task didn't seem to make anything happen.

### 13a. Fix: OAuth "Continue with Google" (and similar popup sign-ins) froze the browser

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebSessionManager.kt`
- The WebView was configured with `setSupportMultipleWindows(false)` (~old line 38) but `javaScriptCanOpenWindowsAutomatically = true`, and neither `WebBrowserActivity` nor `GlassBrowserEngine` implemented `WebChromeClient.onCreateWindow`.
- "Continue with Google" (and most OAuth "Continue with X" buttons) opens its sign-in flow via `window.open(...)` — a **popup window**, not a normal link/navigation. With multi-window support off and no handler for the popup request, that `window.open()` call is silently dropped: the popup never appears, and the page is left waiting forever for a response from a window that was never created. That is exactly "stuck there, not going forward anywhere."

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebSessionManager.kt`
- Flipped `setSupportMultipleWindows(false)` to `true`, with a comment explaining why (needed for `onCreateWindow` to ever fire at all).
- New file: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/PopupWindowRouter.kt`
- Since there is only ever one visible WebView per screen (no real desire for a second browser window), added a small shared helper, `PopupWindowRouter.routeInto(target, resultMsg)`, used from `onCreateWindow`: it creates a throwaway WebView just to catch the popup's first navigation URL, loads that URL into the real/target WebView instead, and immediately destroys the throwaway one.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- Added `onCreateWindow` to `binding.webView`'s `WebChromeClient`, delegating to `PopupWindowRouter.routeInto(binding.webView, resultMsg)`.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserEngine.kt`
- The off-screen, voice-driven WebView had the exact same gap (a voice-driven "sign in with Google" would freeze the same way, just invisibly) — added the same `onCreateWindow` handler to its `WebChromeClient` in `ensureWebView()`.

**Behavior after fix:**
- Tapping "Continue with Google" (or Apple, or any OAuth button that opens a popup) now actually proceeds to the sign-in flow instead of freezing, in both the visible browser and the voice-driven off-screen one.

**Files touched (13a):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebSessionManager.kt` — enabled multi-window support.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/PopupWindowRouter.kt` — new shared popup-routing helper.
3. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — added `onCreateWindow` to the visible WebView's chrome client.
4. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserEngine.kt` — added `onCreateWindow` to the off-screen WebView's chrome client.

---

### 13b. Fix: browser's voice commands appeared to do nothing ("mic not working", "search is glitchy")

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- The mic itself (`VoiceInputController`) worked correctly and did transcribe speech. The actual issue was `autoRunVoice()` (~old line 857), which defaulted to **`false`** ("Hands-free mode. Off by default: a mis-heard sentence shouldn't send the agent off across live pages").
- With that default, every spoken command only **filled the command text box** and showed a small Toast — "Check the command, then tap send" — rather than doing anything. Nothing about the mic itself was broken, but from the user's side, speaking a task (including a plain search like "search Amazon for headphones") appeared to be ignored, which reads exactly like "the mic is not working properly" and "the search system is really glitchy" — especially since the confirmation was just a passing Toast, easy to miss entirely.
- This directly conflicts with what was asked for: "if I assign a proper task to him, it will do the step-by-step task for me" — i.e. speaking a goal should just run it, the same way the glasses' voice assistant works.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- Changed `autoRunVoice()`'s default from `false` to `true`:
```kotlin
private fun autoRunVoice(): Boolean = prefs().getBoolean(KEY_AUTO_RUN, true)
```
- Updated the accompanying doc comment to explain the change and note that the menu's existing "Review voice commands" toggle (`MENU_AUTO_RUN`, unchanged) still lets a user opt back into the old reviewed-before-running behavior if they want it.

**Behavior after fix:**
- Speaking a command in the browser's command bar (mic button) now runs it immediately by default — a task like "search Amazon for headphones and tell me the price" starts the step-by-step agent right away, and a CAPTCHA/login step along the way now correctly hands off for the user to complete (per fix 11a) rather than the whole thing silently sitting idle waiting for a manual "send" tap the user might not even notice is needed.
- Users who prefer to review a spoken command before it runs can still switch that on via the browser's menu ("Review voice commands"), which persists the same as before.

**Files touched (13b):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — changed `autoRunVoice()` default `SharedPreferences` value from `false` to `true`; updated its doc comment.

**Not changed (reviewed, not needed):**
- `VoiceInputController.kt` — the speech recognizer setup itself (permission handling, error recovery, fallback to the system dialog) was already correct; no changes needed there.
- `CommandRouter.kt` / `WebAgentSession.kt` — routing a spoken goal to summarise/catch-up/full-agent was already correct; the only problem was that it never got a chance to run without an extra manual tap, which the default-on hands-free change now fixes.

---

## 14. Fix: tapping the browser mic sometimes opened a second, unexplained "mic"

**Date:** 2026-09-08

**Problem (from user-provided screenshot/description):**
Tapping the mic in the Web browser's command bar sometimes started the normal in-app listening indicator, then — with no explanation — a second, different "listening" UI popped up on top of it (Android's system speech-recognition dialog). The user only wanted one mic experience, not two.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt`
- `onError()` in the inline `SpeechRecognizer`'s listener (~old line 85-101) treated several very common, everyday recognizer conditions as reasons to automatically **fall back to launching a second, completely separate system**: `SpeechRecognizer.ERROR_NO_MATCH` (didn't catch any words), `ERROR_SPEECH_TIMEOUT` (user paused too long), `ERROR_CLIENT`, and `ERROR_RECOGNIZER_BUSY` — all of which happen routinely in normal use, not just as rare failures.
- When any of those fired, `listener.onFallbackToSystemDialog(...)` was called (`WebBrowserActivity.kt`, old `onFallbackToSystemDialog` override), which launched Android's own full-screen system speech-recognition dialog via `startActivityForResult`. This is Google's own separate "Listening…" UI, distinct from and on top of the app's own inline mic indicator the user had just been looking at — exactly "it was like the normal listen part... but suddenly it will open a Google text-to-speech [dialog]."
- This was originally added as a resilience measure (the code comment said "retrying through the system dialog works far more often than reporting them"), but in practice it meant an everyday hiccup (a short pause, a moment of background noise) silently swapped the whole UI out from under the user without explanation.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt`
- `onError()` no longer calls any fallback for the recoverable error codes — it now just resets the listening state (mic goes back to idle) and does nothing further, so the user can simply tap the mic again if they want to retry. Only genuinely unrecoverable errors (audio hardware problem, missing permission, no network, server error) still surface a message via `listener.onError(...)`.
- Removed the now-dead `onFallbackToSystemDialog` from the `Listener` interface entirely, since nothing calls it anymore.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- Removed the `onFallbackToSystemDialog` override and the `onActivityResult` handler that used to receive its result, along with the now-unused `VoiceInputController.REQ_SYSTEM_SPEECH` request code (removed from `VoiceInputController.kt`'s companion object).
- Confirmed via `grep` across the codebase that nothing else referenced `onFallbackToSystemDialog` or `REQ_SYSTEM_SPEECH`, so removal was safe.

**Behavior after fix:**
- Tapping the mic now always uses just the one, single in-app listening indicator — no second system dialog ever appears on top of it.
- If the recognizer doesn't catch anything (silence, too-short pause, momentary busy state), the mic simply goes back to idle and the user can tap it again — no unexpected UI swap, no confusing "two mics."

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt` — removed the system-dialog fallback branch from `onError()`; removed `onFallbackToSystemDialog` from the `Listener` interface; removed the unused `REQ_SYSTEM_SPEECH` constant.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — removed the `onFallbackToSystemDialog` override and the `onActivityResult` override that handled its result.

**Also observed in the same report (not a bug — external limitation, noted for awareness):**
- The screenshot also showed the browser now correctly navigating all the way to `accounts.google.com` for "Continue with Google" (confirming fix 13a's popup routing worked), but Google's own sign-in page then presented a spinning/blocked state and a CAPTCHA challenge of its own. This is Google actively detecting and challenging sign-in attempts from an embedded WebView (a deliberate Google security policy, not something this app's code controls) — our CAPTCHA-handoff UI (fix 11a) is correctly reporting that real block, with a working Continue/Cancel so the user is never stuck, but the app cannot make Google itself allow WebView-based Google sign-in. If Google account sign-in specifically needs to work reliably, the durable fix is opening it in the device's default browser or a Chrome Custom Tab instead of the in-app WebView for that one flow — flagged here as a possible follow-up, not implemented in this pass since it's a different, larger change (a new external-browser-handoff path) than what was asked for today.

---

## 15. Fix: mic never auto-stopped, and the keyboard covered the command text while typing

**Date:** 2026-09-08

Follow-up logcat (`Speech error 7` = `ERROR_NO_MATCH`, confirming fix 14's "no second dialog" behavior is working) plus two new problems from the same session: (1) tapping the mic should stop listening on its own after a couple of seconds of silence instead of staying open, and (2) the keyboard covers the command text field while typing, so what's being typed can't be seen.

### 15a. Fix: mic could stay "listening" indefinitely instead of auto-stopping

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt`
- The recognizer intent already requests `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`/`EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` = 2000ms, but these are only **requests** to the OEM speech-recognition service — on this device's recognizer (and others), they can simply be ignored, leaving the mic "listening" indefinitely with nothing happening, since nothing in the app itself enforced a hard limit.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt`
- Added a hard, app-side watchdog independent of what the OEM recognizer honors: `start()` now schedules `mainHandler.postDelayed({ stop() }, watchdogToken, HARD_TIMEOUT_MS)` (`HARD_TIMEOUT_MS = 6000L`) right after `startListening()` succeeds, using the same `Handler`-with-token pattern already used elsewhere in this codebase (`GeminiLiveService.kt`'s processing-chime watchdog) so it can be cancelled cleanly.
- The watchdog is cancelled (`mainHandler.removeCallbacksAndMessages(watchdogToken)`) at the top of `stop()` (so it doesn't fire again after a normal stop) and in `release()` (so nothing leaks past the Activity's lifecycle).

**Behavior after fix:**
- The mic now always stops listening on its own — either when the recognizer legitimately finishes (results, or one of the recoverable "everyday" errors from fix 14), or, as a guaranteed backstop, after 6 seconds regardless of what the OEM recognizer does. It can no longer be left open indefinitely.

**Files touched (15a):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/VoiceInputController.kt` — added `mainHandler`/`watchdogToken` fields, the `HARD_TIMEOUT_MS` constant, the `postDelayed`/`removeCallbacksAndMessages` calls in `start()`/`stop()`/`release()`.

---

### 15b. Fix: on-screen keyboard covered the command text field while typing

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/utils/SystemBarsInsets.kt`
- `SystemBarsInsets.apply()` (called from every activity, including `WebBrowserActivity`) turns on edge-to-edge window drawing (`WindowCompat.setDecorFitsSystemWindows(activity.window, false)`), which makes the app's window responsible for consuming *every* inset itself — including the on-screen keyboard (IME).
- Its own inset listener, however, only ever accounted for `WindowInsetsCompat.Type.systemBars()` and `displayCutout()` — never `ime()`. Once edge-to-edge is on, `android:windowSoftInputMode="adjustResize"` (already set on `WebBrowserActivity` in the manifest) stops having any effect, because edge-to-edge windows bypass that legacy behavior entirely and expect the app to react to the IME inset itself.
- Net effect: nothing ever pushed the command bar (or its `EditText`) up when the keyboard appeared — the keyboard simply drew on top of it, hiding the text being typed exactly as described ("the keyboard overlaps it, so I am not able to see what I am writing").

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- Added `setupCommandBarImeInset()`, called from `onCreate()`: installs its own `ViewCompat.setOnApplyWindowInsetsListener` directly on `layoutCommandBar` (kept separate from `SystemBarsInsets`'s listener on the root view, so the two don't fight over the same view) that reads `WindowInsetsCompat.Type.ime()` and grows the command bar's bottom margin by the keyboard's height while it's shown, on top of whatever base margin the layout already specifies:
```kotlin
private fun setupCommandBarImeInset() {
    val bar = binding.layoutCommandBar
    val baseBottomMargin = (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
        val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = baseBottomMargin + imeHeight
        }
        insets
    }
}
```

**Behavior after fix:**
- The command bar (and the text being typed into it) now stays above the on-screen keyboard instead of being covered by it — visible the whole time while typing.

**Files touched (15b):**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — added `setupCommandBarImeInset()` and its call from `onCreate()`; added `ViewGroup`/`updateLayoutParams` imports.

**Not changed (reviewed, out of scope for this pass):**
- `SystemBarsInsets.kt` itself — not modified, since it's shared across every activity in the app and adding IME handling there could shift behavior on every other screen; the fix is scoped to the one screen (and one view) that actually has a text field competing with the keyboard for space. If other screens with text input show the same overlap, the same `setupCommandBarImeInset()`-style pattern could be applied to them individually.
- `activity_web_browser.xml` — no layout changes needed; the fix is purely runtime margin adjustment on the existing `layoutCommandBar` view.

---

## 16. Fix (corrected): the IME-margin fix from #15b didn't actually apply; mic listening still gave no visible feedback

**Date:** 2026-09-08

**Problem (from user follow-up with screenshots):**
After fix 15b, the command bar was still hidden behind the on-screen keyboard when tapping it or the bottom nav's chat icon — no change in behavior. Also: tapping the mic listens briefly then stops (per fix 15a), but nothing in the command box shows that it's listening during that time.

**Root cause of why 15b didn't work:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/utils/SystemBarsInsets.kt`
- `SystemBarsInsets.apply()` installs its `WindowInsets` listener on `root.getChildAt(0)` — the screen's own root content `LinearLayout` (`host` in that file) — and that listener **returns `WindowInsetsCompat.CONSUMED`** (line ~117). Consuming insets at a given view stops them from being dispatched any further down the view tree to its children.
- Fix 15b attached its own IME-inset listener directly to `binding.layoutCommandBar`, which is a **child** of that same root `LinearLayout`. Because `SystemBarsInsets`'s listener on the parent already consumed the insets before dispatch could reach any child, the command bar's listener was installed correctly but **never actually received any insets to react to** — so its margin logic never ran, and the keyboard kept overlapping the bar exactly as before.
- Separately, `toggleCommandBar()` and `onQuestion()` (which also show the command bar + keyboard) never explicitly requested a fresh insets pass after making the bar visible — even with dispatch reaching it correctly, a plain `View.GONE` → `View.VISIBLE` flip on an already-laid-out window doesn't reliably trigger a new `WindowInsets` dispatch on its own, so the margin could still end up stale (computed once at zero, before the keyboard was ever shown).

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
- `setupCommandBarImeInset()` now attaches its listener to `window.decorView.findViewById<View>(android.R.id.content)` — the actual window content root, one level higher than the `host` view `SystemBarsInsets` operates on — and does **not** consume the insets itself (returns them unmodified), so `SystemBarsInsets`'s own listener further down still runs normally afterward. This listener now genuinely receives the IME inset and applies it as extra bottom margin on `layoutCommandBar`.
- Added `View.requestApplyInsetsWhenReady()`, a small extension that calls `ViewCompat.requestApplyInsets()` immediately and again ~260ms later (`IME_SETTLE_MS`, matched to the keyboard's typical show-animation duration) — called from both `toggleCommandBar()` and `onQuestion()` right after making the command bar visible and requesting the keyboard, so the margin is recalculated against the keyboard's real, settled height rather than a stale/zero value from before it appeared.

**On mic listening feedback ("that 2 seconds also does not appear on the box"):**
- The mic already sets the box's hint text to "Listening…" and live-updates the box with partial transcription as speech comes in (`onListeningChanged`/`onPartial` in `WebBrowserActivity.kt`, unchanged) — but this was invisible for the same reason as above: the command bar (and therefore this feedback) was hidden behind the keyboard the whole time. With the keyboard-overlap fix above, this feedback should now actually be visible while listening. No separate change was needed here beyond fixing the underlying visibility bug.

**Behavior after fix:**
- Tapping the command bar / chat icon, or the agent asking a question, now correctly keeps the command bar (and the text being typed or transcribed into it) above the on-screen keyboard.
- The mic's "Listening…" hint and live partial transcription are now visible during the ~listening window, since the box itself is no longer hidden.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — rewrote `setupCommandBarImeInset()` to listen on `android.R.id.content` instead of the command bar itself; added `requestApplyInsetsWhenReady()` extension and `IME_SETTLE_MS` constant; called the new extension from `toggleCommandBar()` and `onQuestion()`.

**Not changed (reviewed, not needed):**
- `SystemBarsInsets.kt` — still not modified; the fix works around its insets-consuming behavior from the outside (listening one level higher, without consuming) rather than changing shared code every other screen depends on.
- Mic listening logic itself (`VoiceInputController.kt`, `onListeningChanged`/`onPartial` in `WebBrowserActivity.kt`) — already correct; the feedback just needed the box to actually be visible, which the insets fix above addresses.

---

## 17. Fix (Mark 1): no/misleading message when Bluetooth is off; missing disconnect toast

**Date:** 2026-09-08

**Problem:**
On Mark 1: (1) when internet or Bluetooth is off, the app doesn't show a proper/accurate message about what's actually wrong; (2) when the glasses disconnect over Bluetooth, there was no toast/notification telling the user that happened.

**Investigation (via subagent, confirmed with file:line references before changing anything):**
- **Bluetooth-radio-off vs. "glasses not paired/out of range"**: `Mark1MainActivity.isGlassConnected()` already checks `BluetoothAdapter.getDefaultAdapter()?.isEnabled` and correctly returns `false` when the radio itself is off, but the caller (`pollForGlassConnection`'s failure branch) treated that identically to "glasses just aren't in range" — both fell into the same generic gate text: **"no device" / "Connect your IMI glasses via your phone's Bluetooth settings, then tap Retry."** That instruction reads like a pairing/range problem and never actually tells the user Bluetooth itself needs to be turned on.
- **Internet-off message on Mark 1 specifically**: `GeminiLiveService` already classifies connection failures reasonably well (`"No internet connection"`, `"Network disconnected"`, HTTP-code-specific messages for 401/403/429, etc.) and passes that real message to `onError(error)`. `MainActivity`'s handler shows that real message in its Toast. **`Mark1MainActivity.onError(error)` discarded it entirely** and always showed a hardcoded `"Connection error — tap Quick Start to retry"` regardless of cause — so "no internet" and a revoked API key and a quota limit all looked identical, which is exactly why "no internet" wasn't recognizable as such.
- **Bluetooth disconnect toast**: `MainActivity` already has one (`Toast.makeText(this, "Glass Disconnected", ...)` in its `BluetoothEvent.DISCONNECTED` handler). **`Mark1MainActivity`'s equivalent handler had no toast at all** — it silently stopped any active conversation and swapped in the full-screen BLE gate (or, if a background/locked-phone conversation was active, handed off to a different UI with no disconnect indication whatsoever).

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt`
- **Disconnect toast** — added to the `BluetoothEvent.DISCONNECTED` branch of `onBluetoothEvent()`, mirroring `MainActivity`'s existing pattern:
```kotlin
BluetoothEvent.EventType.DISCONNECTED -> {
    Toast.makeText(this, "Glasses disconnected", Toast.LENGTH_SHORT).show()
    if (isGeminiLiveActive) stopConversation()
    checkBleAndShowGate()
}
```
- **Real error message on connection failure** — `onError(error)` now shows the actual classified reason instead of a hardcoded string:
```kotlin
Toast.makeText(this, "$error — tap Quick Start to retry", Toast.LENGTH_LONG).show()
```
- **Bluetooth-off-specific gate message** — added `showBleGateReason()`, called from `pollForGlassConnection`'s final failure branch, which checks `BluetoothAdapter.getDefaultAdapter()?.isEnabled` and swaps the gate's headline/title/description/button text between two states:
  - Bluetooth off: "bluetooth off" / "off." / *"Bluetooth is turned off on this phone. Turn it on to connect your IMI glasses, then tap Retry."* with the Retry button relabelled "Open Bluetooth Settings".
  - Bluetooth on but glasses unreachable: the original "no device" / "connected." / pairing-instructions text, unchanged.
- `btnBleGateRetry`'s click handler now checks Bluetooth state first: if it's off, it launches `Settings.ACTION_BLUETOOTH_SETTINGS` directly instead of re-running a connection check that's guaranteed to fail the same way; otherwise falls through to the existing permission-request / retry-poll logic, unchanged.
- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_mark1_main.xml`
- Added `android:id`s (`tvBleGateHeadline`, `tvBleGateTitle`, `tvBleGateDescription`) to the three previously-un-tagged `TextView`s in the "not connected" gate state, so their text can be swapped at runtime by `showBleGateReason()`. No visual/layout changes — same views, same styling, just now addressable.

**Behavior after fix:**
- Turning Bluetooth off and opening the Mark 1 gate now shows a distinct, accurate message ("bluetooth off" / turn it on) instead of the generic pairing instructions, with a Retry button that jumps straight to Bluetooth settings.
- A connection failure due to no internet (or an API key/quota issue) now shows the real reason on Mark 1, matching what Mark 2 (MainActivity) already showed.
- Glasses disconnecting over Bluetooth now always shows a "Glasses disconnected" toast on Mark 1, matching the existing Mark 2 behavior, in addition to the existing gate-screen/conversation-stop behavior.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt` — added disconnect Toast; fixed `onError` to show the real error message; added `showBleGateReason()`; updated `btnBleGateRetry`'s click handler to branch to Bluetooth settings when the radio is off.
2. `Mac_imicode_android/app/src/main/res/layout/activity_mark1_main.xml` — added IDs to the three gate-state `TextView`s (no visual change).

**Not changed (deliberately scoped to Mark 1 only, per user's request):**
- `MainActivity.kt` (Mark 2) — its equivalent disconnect Toast and error message already worked correctly; not touched.
- No proactive internet connectivity check was added before starting voice/AI features (the app still only discovers "no internet" reactively, after attempting to connect) — that's a larger change (a `ConnectivityManager` pre-flight check on every voice-start path) beyond what was scoped for this pass.
- `ListeningService.kt`'s background/locked-phone conversation path still ends completely silently on connection failure (no Toast is possible there — no foreground Activity — it would need a system notification instead). Flagged in the investigation but out of scope for this pass per the chosen scope (Mark 1 foreground UI only).
- `ScoConnectionHelper.kt`'s silent `BluetoothUtils.isBluetoothReady()` failure (logged only, no user feedback) — also out of scope for this pass.

---

## 18. Added: deleting Quick Notes (voice honesty + real manual delete UI)

**Date:** 2026-09-08

**Problem:**
Asking the assistant to "delete this note" did nothing useful — there was no delete_note tool at all, so the request either fell through to general conversation (risking a hallucinated "done!" that deleted nothing) or was silently ignored. Separately, there was **no way to delete a note anywhere in the app UI at all** — `QuickNotesManager.deleteNote(noteId)` already existed in code (and even synced deletions to the backend), but nothing in Quick Notes, the note editor, or anywhere else ever called it.

**Investigation:**
- `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesManager.kt:173` — `deleteNote(noteId)` was fully implemented (removes the note, cleans up its image file, syncs the deletion via `BackendSync`) but had **zero callers** anywhere in `app/src/main/java` outside its own file and the sync layer.
- No `delete_note` voice tool existed in `GeminiLiveService.kt`'s tool declarations, and none of the three tool-call dispatchers (`MainActivity.kt`, `Mark1MainActivity.kt`, `ListeningService.kt`) had a case for it.

**Fix — Part 1: manual delete UI (Quick Notes list + note editor)**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesAdapter.kt`
- Added an `onDelete: (QuickNote) -> Unit` callback parameter to the adapter, and wired `setOnLongClickListener { onDelete(item.note); true }` on both the Self-Written and AI-Written note card view holders — long-press a note in the list to delete it, without needing to open it first (standard Android list-delete pattern).
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesActivity.kt`
- Passes `onDelete = { note -> confirmDeleteNote(note) }` to the adapter. New `confirmDeleteNote()` shows an `AlertDialog` ("Delete this note? ... will be permanently deleted.") and, on confirmation, calls `notesManager.deleteNote(note.id)`, shows a "Note deleted" toast, and refreshes the list.
- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_note_editor.xml`
- Added a delete (trash) icon `ImageView` (`btn_delete`, using the already-existing `ic_delete` drawable) to the note editor's header, next to Share/Save. Starts `gone`.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/NoteEditorActivity.kt`
- `btn_delete` is only made visible when editing an existing note (`existingNoteId != null` — a note still being composed for the first time has nothing to delete yet). Tapping it calls new `confirmDelete()`, which shows the same style of confirmation `AlertDialog`, then calls `notesManager.deleteNote(id)`, shows a "Note deleted" toast, and finishes the editor.

**Fix — Part 2: voice assistant tells the truth instead of doing nothing/hallucinating**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
- Added a `delete_note` tool declaration (no parameters) with a description explicitly telling the model this is a **deliberate no-op**: it exists so the model has a correct, honest thing to say ("open Quick Notes and delete it there") instead of claiming a note was deleted when it wasn't, or ignoring the request outright. Voice deletion is intentionally not implemented at all — a spoken description can't reliably identify one specific note among a user's list the way tapping it can, so this is a permanent design choice, not a stopgap.
- Added a line to the QUICK NOTES section of the system prompt telling the model to call `delete_note` for "delete/remove/get rid of my note" requests, and not to claim success or say nothing.
- Wired the same handling into all three tool-call dispatchers, each returning the same clear spoken message:
```kotlin
"delete_note" ->
    "I can't delete notes by voice. Open Quick Notes and delete it there — " +
        "tap and hold a note, or open it and tap the delete icon."
```
  - `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt` (Mark 2)
  - `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt` (Mark 1)
  - `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ListeningService.kt` (background/locked-phone conversations)

**Behavior after fix:**
- Asking the assistant (by voice, on either device, in the foreground or with the phone locked) to delete a note now gets a clear, honest, consistent spoken response explaining that deletion has to be done manually in Quick Notes, and exactly how (long-press in the list, or the delete icon in the editor) — never a false "done" and never silence.
- In Quick Notes, long-pressing any note (Self-Written or AI-Written) now prompts to delete it, with a confirmation dialog.
- Opening an existing note in the editor now shows a delete icon in the header that does the same, also with confirmation.
- Deleting a note removes its attached image file (if any) and syncs the deletion to the backend, via the existing (previously unreachable) `QuickNotesManager.deleteNote()` logic — unchanged, just finally wired up.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesAdapter.kt` — added `onDelete` callback param; wired long-press on both note card types.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesActivity.kt` — passed the new callback; added `confirmDeleteNote()`.
3. `Mac_imicode_android/app/src/main/res/layout/activity_note_editor.xml` — added `btn_delete` `ImageView` to the header.
4. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/NoteEditorActivity.kt` — show `btn_delete` only for existing notes; added `confirmDelete()`.
5. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt` — added `delete_note` tool declaration and QUICK NOTES prompt guidance.
6. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt`, `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt`, `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ListeningService.kt` — added the `delete_note` case to each tool-call dispatcher.

**Not changed (reviewed, not needed):**
- `QuickNotesManager.kt` — `deleteNote()` itself was already fully correct; nothing needed changing there, only callers were missing.
- Swipe-to-delete was not added as an additional gesture — long-press was chosen as the minimal, discoverable, low-risk addition (no new RecyclerView touch-handling infrastructure); can be added later if wanted.

---

## 19. Fix: AI Chat mic showed an unexpected error box (same root cause as the earlier "two mics" bug)

**Date:** 2026-09-08

**Problem:**
In the AI Chat section, tapping the mic to dictate a message sometimes popped up a box with an error message instead of just listening.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- Same bug already fixed in the Web browser's mic (see item 14) and present here too: `onError()` in the inline `SpeechRecognizer`'s listener treated everyday, harmless recognizer conditions — `ERROR_NO_MATCH` (didn't catch words), `ERROR_SPEECH_TIMEOUT` (a short pause), `ERROR_CLIENT`, `ERROR_RECOGNIZER_BUSY` — as reasons to automatically call `launchSystemSpeechDialog()`, which launches **Android's own separate, full-screen system speech-recognition dialog** on top of the in-app mic UI.
- That system dialog shows its own error/empty state when conditions are still bad right after it opens (e.g. "Didn't catch that") — this is what appeared as "a box with an error message": not a genuine app error, but Google's own speech dialog surfacing unexpectedly on top of the mic the user had just tapped, in exactly the same everyday situations (brief silence, a short pause) that shouldn't be treated as failures at all.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- `onError()` no longer calls `launchSystemSpeechDialog()` for the recoverable error codes — it now just resets the listening UI back to idle (`showListeningUi(false)`) so the user can tap the mic again if they want to retry. Only genuinely unrecoverable errors (audio hardware problem, missing permission, no network, server error) still show a Toast via `speechErrorMessage(error)`.
- Removed the now-dead `launchSystemSpeechDialog()` function and its `speechLauncher` (`registerForActivityResult`) field entirely, since nothing calls them anymore — confirmed via `grep` that no other code in the file referenced them.

**Behavior after fix:**
- Tapping the mic in AI Chat now always uses just the one in-app listening UI — no second system dialog (and its own error box) ever appears on top of it.
- If the recognizer doesn't catch anything, the mic simply goes back to idle and the user can tap it again.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt` — removed the system-dialog fallback branch from `onError()`; removed `launchSystemSpeechDialog()` and `speechLauncher`.

**Not changed (reviewed, not needed):**
- The mic's hard-timeout watchdog added to the Web browser's `VoiceInputController` (item 15a) was **not** added here, since AI Chat's reported symptom was specifically the unexpected error box, not the mic staying open too long — kept this fix scoped to what was actually reported rather than porting every related change preemptively.
- `stopVoiceListening()`/`showListeningUi()` — unchanged; already correct.

---

## 20. Fix: keyboard also covered the "Ask AI" composer bar in AI Chat (same root cause as #16)

**Date:** 2026-09-08

**Problem (from user-provided screenshots):**
In the AI Chat ("IMI AI") screen, tapping the "Ask AI" input box brings up the keyboard, but the keyboard covers the composer bar (input field, mic, send button) and the bottom nav below it — the same overlap bug already fixed in the Web browser's command bar (item 16), now reported in this screen too.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- Identical cause to item 16: `SystemBarsInsets.apply(this)` turns on edge-to-edge window drawing, making the app responsible for consuming the on-screen-keyboard (IME) inset itself. `SystemBarsInsets`'s own listener only ever reads `systemBars()`/`displayCutout()` and consumes the insets at the screen's root content view — so nothing further down the tree (including the composer bar) ever saw the IME inset, and nothing pushed the composer bar up when the keyboard appeared. `ChatActivity` had no `windowSoftInputMode` declared for it either, which wouldn't have helped anyway since edge-to-edge windows bypass that legacy attribute.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_chat.xml`
- Added an `android:id="@+id/layoutComposerSlot"` to the `FrameLayout` that wraps the composer bar (previously un-tagged), so it can be adjusted at runtime. No visual/layout changes otherwise.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- Added `setupComposerImeInset()` (called from `onCreate()`), using the same pattern already established for the Web browser's command bar (item 16): listens for `WindowInsetsCompat.Type.ime()` on `window.decorView.findViewById(android.R.id.content)` — one level above where `SystemBarsInsets` consumes insets, so this listener actually receives the IME inset and `SystemBarsInsets`'s own listener further down still runs normally afterward — and grows `layoutComposerSlot`'s bottom margin by the keyboard's height while it's shown, on top of its existing base margin.
- Because `layoutComposerSlot` and the bottom nav bar are siblings inside the same vertical `LinearLayout`, pushing the composer slot up by its margin pushes the bottom nav up with it too, keeping both clear of the keyboard.

**Behavior after fix:**
- Tapping the "Ask AI" input field now keeps the whole composer bar (and the bottom nav below it) above the on-screen keyboard, instead of being covered by it — matches the fix already applied to the Web browser's command bar.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/res/layout/activity_chat.xml` — added `layoutComposerSlot` id to the composer's wrapping `FrameLayout`.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt` — added `setupComposerImeInset()` and its call from `onCreate()`; added `updateLayoutParams` import.

**Not changed (reviewed, not needed):**
- `SystemBarsInsets.kt` — still not modified, for the same reason as item 16: it's shared across every screen in the app, and the fix works around its insets-consuming behavior from the outside rather than changing shared code.
- No `requestApplyInsetsWhenReady()`-style re-request (added for the Web browser's command bar in item 16) was needed here — that screen's command bar toggles between `GONE`/`VISIBLE`, which doesn't reliably trigger a fresh insets dispatch on its own. AI Chat's composer bar is always visible and simply gains focus, so the keyboard's own show/hide triggers the insets dispatch normally without needing a manual nudge.

---

## 21. Fix (corrected): item 20's fix left a dead gap between the composer and the keyboard

**Date:** 2026-09-08

**Problem (from user-provided screenshot):**
After item 20's fix, the composer bar was no longer hidden under the keyboard — but the screen now looked visibly broken: a large empty gap sat between the composer bar and the keyboard, and the bottom nav bar had disappeared entirely (pushed down, off-screen under the keyboard).

**Root cause of why item 20 looked broken:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- Item 20 grew `layoutComposerSlot`'s own bottom margin by the keyboard's height. That was the wrong view to target: **margin only reserves empty space around the view it's set on — it does not move later siblings out of the way.** `bottomNavigation` renders directly after `layoutComposerSlot` in the same vertical `LinearLayout`, so it never moved; it just stayed exactly where it always was, which is now underneath the keyboard and invisible. The "dead gap" seen in the screenshot was `layoutComposerSlot`'s own inflated margin — genuinely empty space it was reserving for itself, immediately above the now-hidden nav bar.
- The obvious fix — put the margin on `bottomNavigation` instead, so the last view in the column gets pushed up and pulls the weighted message list (which shrinks) and the composer above it along with it — collides with `SystemBarsInsets.kt`, which **also** manages `bottomNavigation`'s bottom margin directly (it looks the view up by that exact id, to float it above the system nav bar / gesture inset). Two listeners writing to the same view's margin on every dispatch would either overwrite each other or race, depending on registration order.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
- `setupComposerImeInset()` now targets `bottomNavigation` (not `layoutComposerSlot`), but does so by attaching its `WindowInsets` listener **directly to `bottomNavigation` itself**, not to `android.R.id.content`. `WindowInsets` dispatch runs root-to-leaf, so `SystemBarsInsets`'s own listener (registered on the screen's root content view, an ancestor) always runs **first** on every dispatch and sets `bottomNavigation`'s margin to its own `navBaseMargin + systemBarsBottom`. This listener, registered on the descendant view itself, then runs **second** — it reads that already-updated live margin and adds the keyboard's height on top of it, rather than fighting over which listener's value wins:
```kotlin
private var lastAppliedImeInset = 0

private fun setupComposerImeInset() {
    val bottomNav = findViewById<View>(R.id.bottomNavigation)
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
        val currentMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val imeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            // Undo whatever IME contribution this same listener added last
            // time before adding the current one, so repeated dispatches
            // (keyboard show/hide, rotation) don't compound.
            bottomMargin = currentMargin - lastAppliedImeInset + imeHeight
        }
        lastAppliedImeInset = imeHeight
        insets
    }
}
```
- `lastAppliedImeInset` tracks what this listener itself last added, and is subtracted back out before adding the new value each time — otherwise repeated dispatches (keyboard opening further, screen rotation, etc.) would keep stacking IME height on top of IME height indefinitely.
- Because `bottomNavigation` is the last view in the screen's vertical `LinearLayout`, and the message list above the composer has `layout_weight="1"` (shrinks to fill whatever space remains), pushing `bottomNavigation` up by margin correctly pulls the composer bar up along with it too — both end up sitting directly above the keyboard with no gap, and the nav bar is visible again.
- The now-unused `layoutComposerSlot` id added to `activity_chat.xml` in item 20 was left in place (harmless, unused id) rather than reverting the layout file for a second time.

**Behavior after fix:**
- The composer bar and bottom nav now sit directly above the keyboard, flush, with no dead gap and with the nav bar visible — matching the intended fix from item 20 without the visual breakage.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt` — rewrote `setupComposerImeInset()` to target `bottomNavigation` via a listener registered directly on it (not on `android.R.id.content`), reading the live margin `SystemBarsInsets` already set and adding the IME height on top, with `lastAppliedImeInset` to avoid compounding across repeated dispatches.

**Not changed:**
- `activity_chat.xml` — no further layout changes; `layoutComposerSlot`'s id from item 20 remains but is no longer read by this fix.
- `SystemBarsInsets.kt` — still not modified; this fix works around it by deliberately running after it on the same view, rather than changing the shared class.

---

## 22. Fix (properly this time): keyboard overlap in BOTH AI Chat and the Web browser

**Date:** 2026-09-08

**Supersedes items 15b, 16, 20 and 21**, which each attempted this and did not actually work on device. Recording what was wrong with them, because the mistake was the same one three times over.

**Why the earlier attempts failed:**

1. **Margin was the wrong tool.** Items 15b/16/20/21 all adjusted a *margin* — first on the command/composer bar, later on the bottom nav. Margin only reserves blank space around the single view it is set on; it does **not** move that view's later siblings. On AI Chat this produced exactly what the screenshot showed: a dead gap under the composer, with `bottomNavigation` (the next sibling) still sitting unmoved underneath the keyboard.
2. **The listener was being overwritten.** `SystemBarsInsets.applyToView()` registers its listener on `root.getChildAt(0)` and rewrites that view's padding (or `bottomNavigation`'s margin) from snapshotted base values on *every* insets dispatch. Insets dispatch runs root-to-leaf, so a listener registered on an ancestor (`android.R.id.content`) always runs **first** and whatever it set was silently overwritten a moment later by `SystemBarsInsets`. That is why the Web browser fix appeared correct in code but changed nothing on screen.

**The actual fix — pad the content column, don't move individual views.**

Both screens are the same shape: one vertical `LinearLayout` filling the window, containing a `layout_weight="1"` scrolling/content child and fixed-height bars below it. Bottom padding on that column shrinks the space its children share; the weighted child is the only one that can give up height, so it alone shrinks and everything below it rides up together — flush above the keyboard, no gap, nothing hidden.

- File changed: `Mac_imicode_android/app/src/main/res/layout/activity_chat.xml`
  - Added `android:id="@+id/layoutChatContent"` to the inner vertical `LinearLayout` (the content column inside the `DrawerLayout`).
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt`
  - `setupComposerImeInset()` now sets bottom padding on `layoutChatContent` equal to its authored base plus the IME height.
  - Safe to own outright: `SystemBarsInsets` operates on `android.R.id.content`'s first child, which on this screen is the `DrawerLayout` wrapping this column, and from there it only touches `bottomNavigation`'s margin — never this column's padding. No conflict, no ordering dependency.
  - Removed the `lastAppliedImeInset` bookkeeping field from item 21, no longer needed (padding is recomputed from a fixed base each time rather than accumulated).
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt`
  - This screen's root **is** the content column, and `SystemBarsInsets` treats it as a "plain screen", overwriting its padding on all four sides every dispatch — so it could not simply be padded from elsewhere.
  - `setupCommandBarImeInset()` now **replaces** `SystemBarsInsets`' listener on that root (only one listener per view is possible) and does both jobs in one place: the same system-bar/cutout padding it applied, plus the keyboard height on the bottom. Called after `SystemBarsInsets.apply()` in `onCreate` so it wins, and documented as such.
  - Bottom padding uses `maxOf(bars.bottom, imeBottom)` rather than the sum — with the keyboard up the gesture/nav inset sits behind it, so adding both would double-count and reintroduce a dead gap.
  - Removed the now-unused `ViewGroup` and `updateLayoutParams` imports (also removed the latter from `ChatActivity.kt`).

**Behavior after fix:**
- AI Chat: composer bar and bottom nav sit directly above the keyboard, flush, nav bar visible.
- Web browser: command bar and bottom controls sit directly above the keyboard, and typed text is visible while typing.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/res/layout/activity_chat.xml` — added `layoutChatContent` id to the content column.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/ChatActivity.kt` — rewrote `setupComposerImeInset()` to pad the content column; dropped `lastAppliedImeInset` and the `updateLayoutParams` import.
3. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/WebBrowserActivity.kt` — rewrote `setupCommandBarImeInset()` to take over the root's insets listener and apply system-bar + IME padding together; dropped unused imports; corrected a stale comment.

**Not changed:**
- `SystemBarsInsets.kt` — still untouched. AI Chat sidesteps it (different view), and the Web browser deliberately replaces its listener for that one screen only; every other screen keeps the shared behavior exactly as-is.

**Worth verifying on device**, since the previous attempts looked right in code and were not.

---

## 23. Fix: wake word ("Hey IMI") listened on the phone mic instead of the glasses mic

**Date:** 2026-09-08

**Problem:**
Wake-word detection on Mark 1 was running off the phone's microphone rather than the glasses'. Visible in logcat as:
```
HotHelper: Using phone mic mode for wake detection (SCO bypassed)
HeyImiWakeWord: AudioRecord initialized with source=MIC
```

**Root cause:**
- `HotHelper.useGlassBLEAudio` is sticky, process-wide state. When false, `attemptScoThenStartDetector()` skips SCO entirely and captures straight from the phone mic; `start()` on its own just inherits whatever the last caller left the flag as.
- `Mark1MainActivity.preWarmWakeWord()` sets it to **false** at startup — reasonable in itself, since the pre-warm pass shouldn't try to bring SCO up before the glasses have even connected — but **nothing ever set it back to true**. `MainActivity` (Mark 2) sets it `true` immediately before each `start()`; Mark 1 had no equivalent, so every real wake-word session ran on the phone mic.
- `ListeningService` — which owns the detector for background/locked-phone use, and is the path that actually held the mic in the reported logcat — called bare `start()` at three sites, all inheriting that same false flag.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/HotHelper.kt`
  - Added `armOnGlassMic()`: sets the glasses-mic preference and starts the detector in one call, so the flag and the start can't drift apart again. Documented why a bare `start()` is unsafe here.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt`
  - `startWakeWordListening()` now calls `armOnGlassMic()` instead of `start()`.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ListeningService.kt`
  - All three `HotHelper...start()` call sites (initial arm in `onStartCommand`, the deferred arm after a vision capture, and the re-arm after a conversation ends) now call `armOnGlassMic()`.

**Behavior after fix:**
- Wake-word detection listens through the glasses' SCO mic in both the foreground app and the background service. Logcat should now show the SCO path being taken rather than `Using phone mic mode for wake detection`.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/HotHelper.kt` — added `armOnGlassMic()`.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/Mark1MainActivity.kt` — use it in `startWakeWordListening()`.
3. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ListeningService.kt` — use it at all three arm/re-arm sites.

**Not changed:**
- `preWarmWakeWord()`'s `setPreferGlassBleAudio(false)` — left as-is deliberately; pre-warm runs before the glasses are connected, and the real start paths now set the preference themselves.
- `MainActivity.kt` (Mark 2) — already set the flag correctly before each start; untouched.

---

## 24. Fix: AI replies cut off mid-sentence ("best restaurants in Jaipur" → "best restaurants in")

**Date:** 2026-09-08

**Problem:**
The assistant would start answering and stop partway through — a reply that should end "...best restaurants in Jaipur" came out as "...best restaurants in", then silence.

**Root cause:**
- File: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
- The audio playback loop decided the reply had finished **purely by guessing from a gap in arriving audio**: if the queue drained and no new chunk arrived within `AUDIO_END_TIMEOUT_MS`, it declared playback over, drained the track, handed the mic back and fired `onAudioPlaybackEnd()`.
- That timeout was **700ms**. A model pausing naturally mid-sentence, or a brief network stall, trivially exceeds 700ms — so the turn was ended early and every remaining chunk of the reply was discarded. The longer/more complex the answer, the likelier it was to be truncated.
- The server actually tells us when a turn is genuinely over (`turnComplete`, and `generationComplete` when audio generation finishes), but the playback loop never saw either — `turnComplete` was parsed only for transcription bookkeeping, and `generationComplete` wasn't parsed at all.

**Fix:**
- Added a `turnAudioComplete` flag, set when the server sends `turnComplete`, `generationComplete`, or `interrupted`, and cleared whenever new audio arrives for a turn (more audio means the turn isn't over) and on `interruptCurrentResponse()` (so one turn's end state can't leak into the next).
- The playback loop now ends a turn on `turnAudioComplete || timeSinceLastAudio > AUDIO_END_TIMEOUT_MS` — the server's own signal is the primary end condition; the timeout is only a fallback for a turn whose end signal never arrives at all.
- Because the timeout is no longer the primary signal, raised `AUDIO_END_TIMEOUT_MS` from **700ms to 5000ms**, so an ordinary pause or network hiccup can no longer truncate a reply.
- The end-of-playback log line now reports which condition ended the turn (`server end-of-turn` vs `fallback timeout`), which makes this diagnosable from logcat if it recurs.
- Draining is unchanged: the loop still only ends once the queue is empty and `waitForTrackToDrain()` has let the hardware play out everything written, so the existing "don't cut the tail off" behaviour is preserved.

**Behavior after fix:**
- Replies play to completion; a mid-sentence pause or brief network stall no longer ends the turn early.
- Logcat shows `🔇 Audio playback ended (server end-of-turn)` on normal turns; seeing `fallback timeout` instead would indicate the server signal genuinely didn't arrive.

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt` — added the `turnAudioComplete` flag and its set/clear points; made the playback loop end on the server signal; raised `AUDIO_END_TIMEOUT_MS` to 5000ms as a fallback only; clarified the end-of-playback log.

**Not changed:**
- Speech-recognizer silence timings (`EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS`) in `MainActivity`/`ChatActivity`/`VoiceInputController` — those govern how long the app listens to the **user**, which is a different thing; this bug was the AI's own reply being cut short.
- Half-duplex mic gating and the barge-in path — already correct; untouched.

---

## 25. Fix: browser never actually used — assistant just named a website instead

**Date:** 2026-09-08

**Problem:**
Reported as "the web browser feature is not working in Mark 1". The actual symptom: asking for something like flight information got a deflection — *"you can check the IndiGo flights website"* — rather than the assistant browsing and returning real information.

**Investigation:**
Checked the whole plumbing path first, and it was **all correct on Mark 1** — no code fix was needed there:
- Browser tools are dispatched in all three tool handlers (`Mark1MainActivity.onToolCall`, `ListeningService.handleBackgroundTool`, `MainActivity`), each routing `GlassBrowserTools.TOOL_NAMES` to `handleBlocking()`.
- The tool declarations are explicitly **not** mark-gated (`GeminiLiveService`: *"Browser tools work on both marks"*) — only vision is Mark 2 only.
- `GlassBrowserEngine.init()` runs from `MyApplication`, which is registered in the manifest.
- Tool calls are dispatched on `scope` (IO dispatcher), so `handleBlocking()`'s main-thread guard isn't tripped.
- `WebBrowserActivity` is reachable from `MoreActivity`, and prior logcat confirmed it launching on a Mark 1 device.

The real cause was **prompting**, so the model never called `browse_web` in the first place:
- The system prompt framed browsing as being for when the user wants *"something DONE on a website rather than just answered from memory"*. An information question ("find me flights to Delhi") reads as answerable-from-memory under that wording, so the model didn't reach for the tool.
- The `browse_web` tool description itself repeated the same framing (*"when the user wants something DONE on a site rather than just answered"*), reinforcing it.
- Combined with the prompt's "Reply FAST and CONCISELY" rule, naming a website was the cheapest response available.
- This is the same failure mode vision had, which was fixed the same way — with an explicit instruction never to deflect.

**Fix:**
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt`
  - Rewrote the WEB BROWSER prompt section: browsing is now required whenever the answer depends on **current or live** information (flights, prices, availability, timings, scores, news, stock, opening hours), with concrete must-browse examples, plus an explicit note that its own memory is out of date.
  - Added a direct never-deflect rule: naming a website and telling the user to look themselves is wrong — open it and report what was actually found, calling the tool *before* replying.
- File changed: `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserTools.kt`
  - Rewrote the `browse_web` tool description to match: it now covers looking things up on the live web (not just "doing" things on a site), lists the current-information cases, and states never to answer by sending the user off to a website.

**Behavior after fix:**
- Questions needing live data should now trigger an actual `browse_web` call and a real answer, instead of "check the … website".

**Files touched in this change:**
1. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt` — rewrote the WEB BROWSER system-prompt section.
2. `Mac_imicode_android/app/src/main/java/com/sdk/glassessdksample/ui/web/GlassBrowserTools.kt` — rewrote the `browse_web` tool description.

**Not changed:**
- All browser dispatch/plumbing on Mark 1 — verified correct as-is; nothing needed changing.
- The direct per-action tools (`browser_scroll`/`browser_click`/etc.) and their prompt block — untouched.

**Note:** this is a prompting change, so behaviour is a strong tendency rather than a guarantee — worth testing with a few live-data questions to confirm it now browses rather than deflects.

---
Oh, I Hmm.