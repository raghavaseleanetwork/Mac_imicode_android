# iOS Handoff: Quick Notes & Meeting Minutes (Gemini Integration)

Audience: iOS team reimplementing this feature from the Android app.
Source of truth: this document describes the **current Android implementation exactly as it runs in production today**, including its known limitations, so iOS can replicate the same behavior (and optionally improve on the gaps called out at the end).

---

## 1. Feature Overview

There are **two separate but related features**, both reachable through the same always-on voice assistant ("Gemini Live"):

### 1.1 Quick Notes
A short text note (with an optional photo). Two ways a note gets created:

1. **Manual / typed**: user opens a note editor screen, types a title + body, saves. No AI involved. `createdBy = USER`.
2. **Voice-created (AI)**: while the user is talking to the voice assistant, they say something like *"remember this"*, *"note this down"*, *"add to notes"*. The voice assistant (Gemini Live) recognizes this intent and invokes a **tool/function call** named `create_note` (or `capture_photo_note` if a photo is involved) as part of the live conversation. The app receives the tool call's arguments (`title`, `content`) and writes the note directly — `createdBy = AI`.

   **Important:** there is no separate/extra Gemini API call to "summarize into a note." The title and content come straight out of the Live session's tool-call arguments — Gemini Live itself decided what to put in those fields as part of the realtime conversation.

3. Photo notes: triggers the glasses camera (BLE), photo is transferred over WiFi P2P, then attached to the note. This part is unrelated to Gemini (no vision/image API call happens during note creation).

Notes list UI has two tabs: "AI Written" and "Self-Written" (with search).

### 1.2 Meeting Minutes
A full audio-recording → transcription → summarization pipeline.

1. User says *"start meeting minutes"* (voice tool call `start_meeting`) or taps a manual UI button.
2. This **ends/stops the Gemini Live voice session first** (can't use the mic for both at once), then opens a dedicated "Active Meeting" screen.
3. The entire meeting is recorded locally as an audio file using the OS audio recorder (Android: `MediaRecorder`; iOS equivalent: `AVAudioRecorder`/`AVAudioEngine`) — **this is plain audio recording, not a Gemini Live session.**
4. When the user taps "End Meeting":
   - The full recorded audio file is sent to Gemini in **one single-shot multimodal request** for transcription (audio in, text out).
   - The resulting transcript text is then sent to Gemini in a **second single-shot text-only request** for summarization (text in, text out).
5. Transcript + summary are saved locally and synced to a backend; viewable later in a meeting list/detail screen.

**Relationship between the two features:** both are entry points off the same voice assistant's tool-call schema. Quick Notes makes **zero** extra Gemini calls (data comes from the Live tool call itself). Meeting Minutes makes **two** extra plain (non-Live) Gemini calls after recording stops.

---

## 2. Gemini API Details

### 2.1 Models used (exact strings)

| Purpose | Model string |
|---|---|
| Meeting audio transcription | `gemini-2.5-flash` |
| Meeting summary generation | `gemini-2.5-flash` |
| General AI chat (notes/meetings Q&A, unrelated feature) | `gemini-2.5-flash` (primary), with fallback chain to `gemini-2.0-flash-lite` / `gemini-2.0-flash` |
| Image/vision analysis (unrelated feature) | `gemini-2.5-flash` |
| Gemini Live (voice assistant that hosts the `create_note` / `capture_photo_note` / `start_meeting` tool calls) | `gemini-2.5-flash-native-audio-preview-09-2025` (primary), fallback `gemini-live-2.5-flash-preview` |

There is **no dedicated model for "Quick Notes summarization"** — Quick Notes never independently calls Gemini for content generation.

### 2.2 SDK / transport

- **Meeting transcription & summarization**: standard REST-backed Gemini SDK calls (Android used `com.google.ai.client.generativeai:generativeai:0.9.0` — the Google AI SDK for Android/Kotlin). These are plain single-shot `generateContent()` calls, **not streaming**.
  - On iOS, use the equivalent Google AI / Gemini Swift SDK (`generative-ai-swift` or the official `google-generativeai` package if iOS team is targeting that), or call the REST endpoint directly:
    ```
    POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=API_KEY
    ```
- **Gemini Live (voice assistant)**: a raw **WebSocket** connection, not the convenience SDK:
  ```
  wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY
  ```
  This is hand-rolled JSON messages over the socket (the Android app builds these manually with Gson — it does not use an SDK wrapper for Live). The two feature-relevant tool calls (`create_note`, `capture_photo_note`, `start_meeting`) are declared as function declarations sent in the Live session's `setup` message (see §2.5).

### 2.3 Auth / API key

- Runtime source of truth: **Firebase Remote Config**, key name `gemini_api_key`, fetched via the Firebase SDK with a 1-hour minimum fetch interval, default value `""` (forces a fetch on first run).
- All three Gemini call sites (transcription, summarization, Live session) read the API key from Remote Config at call time — not from a hardcoded build constant.
- A secondary `BuildConfig`/build-config-injected key (from `local.properties`) also exists in the codebase as a fallback path, but **Remote Config is what's actually used at runtime**.

**What iOS needs:**
1. A Firebase project (or shared one) with Remote Config parameter `gemini_api_key` set to a valid Gemini API key.
2. Firebase iOS SDK (`FirebaseRemoteConfig`) integrated, fetched and activated at app startup before any Gemini call is made.
3. `GoogleService-Info.plist` wired up (iOS equivalent of `google-services.json`).

### 2.4 Exact prompts (verbatim — copy these exactly)

**Transcription prompt**, sent together with the raw audio blob:
```
Transcribe this audio recording completely and accurately. Return only the spoken words, nothing else.
```

**Summarization prompt** (sent as a single text prompt, `$transcript` interpolated with the transcription result):
```
Please provide a concise meeting summary based on this transcript:

$transcript

Include:
1. Key Discussion Points (bullet points)
2. Decisions Made (if any)
3. Action Items (if any)
4. Important Notes

Keep it clear and professional.
```

**Gemini Live system instruction** (the voice assistant's persona — this is what makes the assistant decide *when* to call `create_note` / `capture_photo_note` / `start_meeting`; relevant excerpt):
```
You are Imi Glass, a smart glasses assistant.
IMPORTANT LANGUAGE RULE: ALWAYS Reply in the EXACT SAME LANGUAGE the user speaks.
- If user speaks English -> Reply in English.
- If user speaks Hindi -> Reply in Hindi.
- If user speaks Hinglish -> Reply in Hinglish.

CRITICAL: Reply FAST and CONCISELY. No filler words. Match the user's vibe.

QUICK NOTES: When the user asks to "remember this", "add to notes", "note this down", or mentions saving information, use the create_note tool to save it.
When the user asks to "take a pic and add to notes", "click photo and save in notes", "capture this and note it", or wants to photograph something AND save it as a note, use the capture_photo_note tool.

MEETING MINUTES: When the user asks to "start meeting minutes", "record this meeting", "start recording the meeting", or similar, use the start_meeting tool to begin recording. If they mention a specific meeting name (e.g., "start meeting minutes for Raghav Meeting"), extract the meeting name and pass it in the 'title' parameter. Otherwise leave title empty for auto-generation.
```
(There's a fourth NOTIFICATIONS tool in the full instruction, unrelated to these two features — omitted here.)

### 2.5 Tool / function declarations (Gemini Live `setup` message)

These three function declarations are passed in the `tools[].function_declarations` array of the Live session setup, alongside ~15 other unrelated tools (take_photo, send_message, get_weather, etc.) and a `google_search` tool:

```json
{
  "type": "function",
  "name": "create_note",
  "description": "Create a quick note or reminder when user asks to remember something or add to notes",
  "parameters": {
    "type": "object",
    "properties": {
      "title": { "type": "string", "description": "Short title for the note" },
      "content": { "type": "string", "description": "Content of the note" }
    },
    "required": ["title", "content"]
  }
}
```

```json
{
  "type": "function",
  "name": "capture_photo_note",
  "description": "Take a photo with the glasses camera and attach it to a new note. Use when user says 'take a pic and add to notes', 'click photo and save in notes', 'capture this and note it down', or similar requests to photograph something and save it as a note.",
  "parameters": {
    "type": "object",
    "properties": {
      "title": { "type": "string", "description": "Short title for the photo note" },
      "content": { "type": "string", "description": "Optional text description to go with the photo" }
    },
    "required": ["title"]
  }
}
```

```json
{
  "type": "function",
  "name": "start_meeting",
  "description": "Start meeting minutes recording with speech-to-text transcription",
  "parameters": {
    "type": "object",
    "properties": {
      "title": { "type": "string", "description": "Optional meeting title, auto-generated if not provided" }
    }
  }
}
```

When Gemini Live decides to call one of these (based on the system instruction above + user speech), the app receives the tool call event over the WebSocket, parses the arguments, and executes the corresponding local action (write a note / launch the meeting recorder). **The app — not Gemini — performs the actual save/launch action.** Gemini only signals intent + provides the arguments.

### 2.6 Generation config

- **Transcription & summarization calls**: **no explicit generation config is set** — default temperature/topP/topK/maxOutputTokens are used (SDK defaults), and no `responseMimeType`/`responseSchema` (i.e., **no structured/JSON output** — both calls return plain freeform text).
- **Gemini Live setup message** generation config:
  ```json
  {
    "generation_config": {
      "response_modalities": ["AUDIO"],
      "speech_config": {
        "voice_config": { "prebuilt_voice_config": { "voice_name": "Kore" } }
      }
    },
    "input_audio_transcription": {},
    "output_audio_transcription": {}
  }
  ```
  No temperature/topP/topK is set here either. Live audio is PCM16, 16kHz input / 24kHz output, base64-encoded over the WebSocket. (This config is for the voice conversation itself, not relevant to note/meeting content generation.)

### 2.7 Audio handling — direct multimodal, no separate STT step

This is an important architectural point: **there is no separate speech-to-text engine**. No on-device speech recognizer, no Cloud Speech-to-Text, no Whisper — the recorded audio file's raw bytes are sent **directly to Gemini** as an inline blob alongside the transcription prompt:

```kotlin
val audioBytes = audioFile.readBytes()
val response = model.generateContent(
    content {
        text("Transcribe this audio recording completely and accurately. Return only the spoken words, nothing else.")
        blob("audio/mp4", audioBytes)
    }
)
```

- **MIME type sent to Gemini:** `audio/mp4`
- **Recording format that produces this:** AAC audio inside an MPEG-4/`.m4a` container, **128 kbps, 44.1 kHz** sample rate.
- **No chunking or streaming** — the entire file (regardless of meeting length) is read into memory and sent in a single request. There is no size cap or splitting logic in the current implementation (see Known Gaps, §6).

iOS equivalent: record with `AVAudioRecorder` using AAC/`.m4a` output at the same bitrate/sample rate (or close to it — exact match isn't required, just keep MIME type `audio/mp4` consistent with what you send to Gemini), then read the file into `Data` and send as an inline blob with MIME type `audio/mp4` in the `generateContent` request.

---

## 3. Data Flow / Architecture (Meeting Minutes end-to-end)

### 3.1 Recording
- Output file: `meeting_<epochMillis>.m4a`, AAC, MPEG-4 container, 128 kbps, 44.1 kHz, stored in app-local storage.
- Pause/resume supported.
- Amplitude polled every 100ms purely to drive a waveform UI animation — no AI relevance.
- Audio session/focus: exclusive transient gain, voice-communication usage / speech content type (Android `AudioManager` terms — iOS equivalent: `AVAudioSession` category `.playAndRecord` with `.voiceChat` or similar mode).

### 3.2 End-of-meeting call chain
1. Stop/release recorder.
2. UI shows "Transcribing audio…" state.
3. Read full audio file → call Gemini (`gemini-2.5-flash`) with the transcription prompt + audio blob → get back plain text transcript (`response.text?.trim()`).
4. **If transcript is blank/empty → the entire meeting is discarded** (not saved), user sees a toast: *"No speech detected in recording"*.
5. Otherwise, save the transcript to the in-progress meeting record (local + best-effort push to backend).
6. UI shows "Generating Summary…" state.
7. Call Gemini again (`gemini-2.5-flash`) with the summarization prompt (transcript interpolated in) → get back plain text summary.
8. Finalize the meeting record (`endTime`, `summary`, `isActive=false`), save locally, push update to backend.
9. **Delete the local audio file** — audio is never retained after transcription, only the transcript + summary text persist.
10. Show summary screen: title, date, duration, word count (computed client-side from transcript word-split — not provided by Gemini), and the summary text.

### 3.3 Data models

```
QuickNote {
  id: String (UUID)
  title: String
  content: String
  imagePath: String?       // local path to attached photo, if any
  timestamp: Long
  createdBy: enum { USER, AI }
}

MeetingMinute {
  id: String (UUID)
  title: String
  startTime: Long
  endTime: Long
  transcript: String       // plain text from Gemini transcription call
  summary: String          // plain text from Gemini summarization call (numbered/bulleted markdown-ish, NOT JSON)
  participants: String     // comma-separated; never auto-populated by AI — always manual/empty in current impl
  isActive: Boolean
}
```

**Note:** both `transcript` and `summary` are **plain freeform text**, not JSON. Neither Gemini call uses structured output / response schema. Don't build a JSON parser expecting structured fields from Gemini's response — just store the text as-is.

**Speaker diarization is NOT implemented** despite some UI/model scaffolding existing for it in the Android code (`speakerCount`, `speakerTranscript` fields exist on the model but are dead — always 0/empty, never populated). Gemini is never asked to identify speakers. iOS does not need to build this to match current behavior.

### 3.4 Storage
- **Local**: Android uses `SharedPreferences` with Gson-serialized JSON blobs (`quick_notes_prefs` / `notes_list`, `meeting_minutes_prefs` / `meetings_list` + `active_meeting`). iOS can use `UserDefaults` + `Codable` JSON, or a local DB (Core Data/SQLite) — either works, this isn't dictated by the API, just keep it simple since the data is small.
- Note images saved locally as JPEG (quality 85).
- **Remote sync**: REST backend, JWT bearer auth. Key endpoints:
  - Notes: `POST /v1/notes`, `PUT /v1/notes/{id}`, `DELETE /v1/notes/{id}`, `GET /v1/notes` (paginated), `POST /v1/notes/bulk`, `POST /v1/notes/{id}/image` (multipart).
  - Meetings: `POST /v1/meetings`, `GET /v1/meetings/active`, `PATCH /v1/meetings/{id}/transcript`, `PUT /v1/meetings/{id}`, `POST /v1/meetings/{id}/cancel`, `DELETE /v1/meetings/{id}`, `GET /v1/meetings`, `POST /v1/meetings/bulk`.
  - Sync is best-effort/fire-and-forget — local storage is the source of truth for the UI; backend exists for cross-device sync. (See `BACKEND_HANDOFF_QUICK_NOTES_AND_MEETING_MINUTES.md` in this repo for full backend API contract if iOS needs it.)

### 3.5 Error handling, retries, timeouts
- Backend REST client: connect timeout 20s, read/write timeout 30s. No general network retry, except: a 409 conflict on starting a meeting (an active meeting already exists) triggers a one-time "clear stale active meeting, retry" path; failed update (PUT) for a not-found note/meeting falls back to create (POST/upsert).
- **The Gemini transcription/summarization calls have no explicit timeout override and no retry logic.** A single try/catch wraps each call. On failure, the error message itself (e.g. `"Audio recorded but transcription failed: <message>"` or `"Error generating summary: <message>"`) is saved into the record **as if it were the transcript/summary** — i.e. failures aren't a distinct error state, they just become the displayed text. This is a known wart; iOS can do better (see §6).

---

## 4. Quick Notes vs Meeting Minutes — Comparison Table

| Aspect | Quick Notes | Meeting Minutes |
|---|---|---|
| Trigger | Voice tool call (`create_note`/`capture_photo_note`) mid-conversation, or manual entry | Voice tool call (`start_meeting`) or manual button — ends the Live session first |
| Extra Gemini calls | **None** | **Two**, sequential: transcribe, then summarize |
| Model | N/A (whatever Live model is active) | `gemini-2.5-flash` for both calls |
| Input | Text args from Live tool call (+ optional photo, non-Gemini) | Raw `.m4a` audio bytes, MIME `audio/mp4`, sent multimodally |
| Output | Plain title + content strings | Plain-text transcript, then plain-text summary |
| Structured/JSON output | No | No |
| Local storage | `quick_notes_prefs` | `meeting_minutes_prefs` |
| Backend endpoints | `/v1/notes*` | `/v1/meetings*` |

---

## 5. Dependencies (Android reference versions)

```
com.google.ai.client.generativeai:generativeai:0.9.0   # Google AI SDK (Gemini) — transcription/summary calls
com.squareup.okhttp3:okhttp:4.12.0                      # REST + Live WebSocket transport
com.google.code.gson:gson:2.10.1                        # JSON for prefs + Live protocol messages
firebase-bom:33.0.0
firebase-config-ktx                                     # Remote Config — API key delivery
firebase-analytics-ktx
```
Required permissions: microphone access, internet access.

For iOS: use the official Google Generative AI Swift SDK (or raw REST/URLSession calls) for transcription/summarization, `URLSessionWebSocketTask` (or a WebSocket library) for the Live connection, `FirebaseRemoteConfig` for the API key, and standard `AVFoundation` (`AVAudioRecorder`) for meeting recording. Request `NSMicrophoneUsageDescription` in Info.plist.

---

## 6. Known Gaps / Things iOS Should Be Aware Of (current Android behavior, not necessarily "correct")

These aren't requirements to replicate — they're honest flags about what the current implementation does or doesn't do, so the iOS team can decide whether to match it or improve it:

1. **No file size/duration cap** before sending audio to Gemini — a very long meeting could hit Gemini's request size/token limits with only a generic error message surfacing (saved into the transcript field as text, not a distinct error UI).
2. **No retry logic** on the two Gemini calls — a transient network blip fails the whole flow.
3. **Failures are stored as content**, not flagged as a separate error state (e.g., a failed summary literally has the string "Error generating summary: ..." saved as the `summary` field).
4. **No structured output** — if iOS wants more reliable parsing (e.g., separate action items array, decisions array), consider using Gemini's `responseSchema`/JSON mode instead of copying the current freeform-text approach. This would be an improvement over current behavior, not a requirement to match it.
5. **No language hint** passed to the transcription prompt (relies on Gemini auto-detect); the summarization prompt and output are always in English regardless of the transcript's language.
6. **Audio is deleted immediately after transcription** — there's no way to re-transcribe or download the original recording later. Confirm with product whether iOS should keep this behavior or retain audio.
7. **Speaker diarization fields exist in the data model but are unused/dead** — don't spend time implementing this to match parity; it was scaffolding only.

---

## 7. Reference: Android Source Files (for deeper questions)

- `ActiveMeetingActivity.kt` — full Meeting Minutes recording + Gemini transcribe/summarize pipeline
- `MeetingMinutesManager.kt`, `MeetingMinute.kt` — meeting storage/model
- `QuickNotesManager.kt`, `QuickNote.kt`, `NoteEditorActivity.kt`, `QuickNotesActivity.kt` — Quick Notes storage/UI
- `GeminiLiveService.kt` — Live WebSocket session, tool declarations, system instruction
- `MainActivity.kt` — tool-call handlers that actually create notes / launch meetings when Gemini Live invokes a tool
- `RemoteConfigManager.kt` — API key delivery via Firebase Remote Config
- `NotesMeetingsApi.kt`, `BackendSync.kt` — backend REST sync layer
- `MeetingDetailsDialog.kt`, `AllMeetingMinutesActivity.kt` — display/detail screens
- `app/build.gradle` — dependency versions

Also see in this repo: `BACKEND_HANDOFF_QUICK_NOTES_AND_MEETING_MINUTES.md` (backend API contract) and `MEETING_MINUTES_IMPLEMENTATION.md` (original Android implementation notes) for additional cross-checking.
