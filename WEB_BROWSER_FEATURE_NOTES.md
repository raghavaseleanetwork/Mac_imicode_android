# Web (AI Browser) Feature — Research Notes & Module Plan

Feature: a new **Web** section in the app, reachable from the **More** tab.
An in-app browser the user can drive with typed or spoken natural-language
commands, planned by the Gemini API already wired into this app.

---

## 1. Research findings (existing codebase)

### Project shape
| Item | Value |
|---|---|
| Root | `Mac_imicode_android` |
| Module | `:app` (single module, `settings.gradle.kts`) |
| Build file | `app/build.gradle` (**Groovy**, not `.kts` — `.kts` is `.disabled`) |
| Package | `com.sdk.glassessdksample` |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| UI | **XML layouts + ViewBinding** (`buildFeatures { viewBinding = true }`). No Compose. |
| Navigation | Activity-per-screen + `startActivity`. No Nav component. |

### Bottom navigation
- Menu: `res/menu/menu_bottom_nav_mark1.xml` → `nav_home`, `nav_chat`, `nav_more`, `nav_profile`.
- Manager: `ui/BottomNavManager.kt` (`object`, `setup(bottomNav, currentTabId, activity)`).
  Home target resolves at runtime via `DevicePreferenceManager.getDeviceType()`
  → `Mark1MainActivity` or `MainActivity`.
- There is also `ui/Mark1BottomNavManager.kt` for the Mark 1 mode.
- **Decision: do NOT add a 5th bottom-nav tab.** The user asked for the feature
  inside the More tab. Adding a tab would need icon + glow (`NavGlow`) + both
  managers changed. Web is a row in More, opening its own Activity.

### The More screen (entry point we extend)
- `MoreActivity.kt` + `res/layout/activity_more.xml`.
- Pattern: each row is a `LinearLayout` with an `@+id/cardXxx`, background
  `@drawable/bg_more_quick_note`, height `74dp`, a 36dp icon on
  `@drawable/bg_more_note_icon`, a 16sp `#ADADAD` label, and a trailing
  `ic_arrow_right`. Wired in `setupActions()` via `open(XxxActivity::class.java)`.
- **So adding "Web" = one XML block + one line in `setupActions()`.**

### Visual language (match this)
- Screen bg: `@drawable/bg_more_screen_image`
- Accent orange: `#FF7F2E`
- Primary text: `#FFFFFF`; secondary/label text: `#ADADAD`
- Card bg: `@drawable/bg_more_quick_note`; pill: `@drawable/bg_more_action_pill`
- Bottom nav: 68dp, `@drawable/bg_home_nav`, 14dp side margins, unlabeled

### Gemini integration (reuse, don't rebuild)
- Client: `ui/GeminiAiClient.kt` → class `GeminiAIClient`.
- SDK: `com.google.ai.client.generativeai:generativeai:0.9.0`.
- Models: default `gemini-2.5-flash`; fallbacks `gemini-2.0-flash-lite`,
  `gemini-2.0-flash`. Client walks the list down on a 404.
- Key: `RemoteConfigManager.geminiApiKey` (Firebase Remote Config, key
  `gemini_api_key`), with `BuildConfig.GEMINI_API_KEY` from `local.properties`
  as the build-time fallback.
- Usage metering: `UsageLimitManager.tryConsume(context, mode)` +
  `TokenUsageTracker.track(context, mode, response.usageMetadata)`.
  **Every new AI call must go through these** or billing/limits break.
- `TokenUsageTracker.Mode` is an enum — Module 2 needs a mode for the browser
  agent (reuse `AI_CHAT` if adding an enum entry is risky).

### Voice input (reuse)
- `SpeechRecognizer` / `RecognizerIntent` already used in `MainActivity.kt`
  and `ui/ChatActivity.kt`. `RECORD_AUDIO` permission already in the manifest.
- Module 3 copies the `ChatActivity` mic pattern.

### WebView
- **No WebView anywhere in the project yet.** Clean slate, no conflicts.
- `INTERNET` permission already present (app makes network calls).

### Manifest
- `app/src/main/AndroidManifest.xml`, activities declared as
  `<activity android:name=".ui.XxxActivity" android:exported="false" android:label="..." />`
- App theme: `@style/Theme.GlassesSDKSample`.

### Other useful deps already present
okhttp 4.12.0, gson 2.10.1, Glide 4.15.1, Material 1.12.0, viewpager2, Firebase BOM 33.

---

## 2. Design decisions

1. **Package**: new `com.sdk.glassessdksample.ui.web` — keeps the feature isolated.
2. **Sandboxed session**: dedicated WebView data dir so browser cookies never
   mix with the rest of the app. Cookies **persist** so the user logs in once.
3. **Security rules — hard-coded in the executor, not just the prompt:**
   - The agent may **never** type into `input[type=password]`.
   - The agent may **never** solve or bypass a CAPTCHA.
   - Login, CAPTCHA and payment ⇒ agent pauses, hands control to the user,
     resumes only on an explicit "Continue" tap.
   - Gemini returns a **whitelisted structured action**, never raw JS.
4. **Consent gate**: a warning + consent screen before the first agent run.
5. **Reality check (already agreed with the user)**: bot-protected sites
   (Uber, ticketing, banks) will block automated interaction. The agent is
   best-effort with a graceful "I'm stuck — you take over" handoff.
6. **Reading Claude/ChatGPT history**: only works for sessions the user logs
   into *inside this WebView*. The app cannot read Chrome-on-PC cookies. Same
   account ⇒ same server-side history, so the outcome is equivalent.

---

## 3. The four modules

### Module 1 — Browser section + entry point  ✅
Manual browser, no AI. Must be independently usable.
- `res/layout/activity_more.xml`: add the **Web** row.
- `MoreActivity.kt`: wire `cardWeb` → `WebBrowserActivity`.
- `ui/web/WebBrowserActivity.kt`: WebView + address bar + go/back/forward/
  reload/stop, progress bar, page title, error handling.
- `ui/web/WebSessionManager.kt`: persistent cookies, JS, DOM storage,
  desktop/mobile UA, clear-session.
- `res/layout/activity_web_browser.xml` in the app's visual language.
- Icon `ic_web.xml`, drawables, manifest entry.
- **Exit criteria**: user opens More → Web, browses any site, logs into a site,
  kills the app, reopens → still logged in.

### Module 2 — Command engine (Gemini planner + executor)  ✅
- `ui/web/PageReader.kt` — injected JS returning a JSON page summary
  (url, title, inputs, buttons, links, visible text) with stable selectors.
- `ui/web/BrowserAction.kt` — sealed action schema: `Open`, `Search`, `Click`,
  `Type`, `Scroll`, `Back`, `Forward`, `Reload`, `AskUser`, `HandoffToUser`, `Done`.
- `ui/web/WebAgentPlanner.kt` — Gemini call (goal + page summary + history →
  one action as JSON), via `GeminiAIClient` conventions + usage metering.
- `ui/web/ActionValidator.kt` — enforces the security rules above.
- `ui/web/ActionExecutor.kt` — runs actions through `evaluateJavascript`,
  dispatching real `input`/`change` events so React sites register typing.
- `ui/web/WebAgentSession.kt` — the loop: read → plan → validate → execute →
  wait → repeat, with a step cap and a visible **Stop**.
- UI: command bar, agent status strip, handoff banner + Continue button.

### Module 3 — Voice + seamless UX  ✅
- Mic button reusing the `ChatActivity` `SpeechRecognizer` pattern.
- Spoken clarifying questions (TTS via the existing `GoogleCloudTTS`).
- Command history/suggestions; consent + warnings screen; per-site trust list.

### Module 4 — AI-service integration & summaries  ✅
- "Summarize my Claude/ChatGPT project" as a first-class command: open the
  logged-in site, extract the conversation, summarize with Gemini.
- Save summaries into the existing Quick Notes / Conversation History stores.
- Optional glasses hand-off (speak the summary through the Mark 1 pipeline).

---

## 4. Status
- [x] Research
- [x] **Module 1 — DONE, `:app:assembleDebug` passes**
- [x] **Module 2 — DONE, `:app:assembleDebug` passes**
- [x] **Module 3 — DONE, `:app:assembleDebug` passes**
- [x] **Module 4 — DONE, `:app:assembleDebug` passes**

**All four modules complete.**

### Module 1 — files delivered
| File | Change |
|---|---|
| `ui/web/WebSessionManager.kt` | **new** — persistent cookies/DOM storage, desktop UA, clear-session, URL-or-search parsing |
| `ui/web/WebBrowserActivity.kt` | **new** — the browser screen |
| `res/layout/activity_web_browser.xml` | **new** — top bar, address bar, progress, WebView, start screen, bottom controls |
| `res/drawable/ic_web.xml`, `ic_refresh.xml` | **new** icons |
| `res/drawable/bg_web_toolbar.xml`, `bg_web_screen.xml` | **new** backgrounds |
| `res/layout/activity_more.xml` | **edited** — added the `cardWeb` row above Meeting Minutes |
| `MoreActivity.kt` | **edited** — import + `cardWeb` click → `WebBrowserActivity` |
| `AndroidManifest.xml` | **edited** — activity entry with `adjustResize` + `configChanges` |

### Module 1 — behaviour
- More → **Web** opens the browser as its own screen.
- Address bar: a domain loads directly, anything else becomes a Google search.
- Back / forward / home / reload-stop, live progress bar, page title.
- Quick-link chips: Google, YouTube, Gemini.
- Menu: desktop/mobile site toggle, clear browsing data (confirmed first).
- Cookies flush on pause and page finish ⇒ **logins survive app restarts**.
- Last page is restored on reopen.
- Non-http schemes (`intent:`, `mailto:`, `upi:`) blocked with a message.
- Downloads explicitly declined for now (told to the user, not silent).
- WebView detached before destroy to avoid leaking the Activity.
- The AI button is present but reports that commands land in the next update.

---

### Module 2 — files delivered
| File | Change |
|---|---|
| `ui/web/BrowserAction.kt` | **new** — closed action set + JSON parsing + `ActionResult` |
| `ui/web/PageReader.kt` | **new** — injected JS page summariser, `PageSnapshot` |
| `ui/web/ActionValidator.kt` | **new** — the security boundary |
| `ui/web/ActionExecutor.kt` | **new** — performs actions via `evaluateJavascript` |
| `ui/web/WebAgentPlanner.kt` | **new** — Gemini planner, JSON response mode |
| `ui/web/WebAgentSession.kt` | **new** — the read/plan/validate/execute loop |
| `res/layout/activity_web_browser.xml` | **edited** — agent status strip + command bar |
| `res/drawable/bg_web_agent_strip.xml`, `bg_web_agent_cta.xml` | **new** |
| `ui/web/WebBrowserActivity.kt` | **edited** — command bar, listener, consent, teardown |

### Module 2 — how the loop works
```
read page (PageReader JS)  →  plan one action (Gemini)  →  validate  →  execute  →  repeat
                                                              ↓
                                          handoff / ask user / confirm  →  park, wait for user
```
- **One action per turn.** The model never plans a sequence up front; pages
  change under you, so a plan made three steps ago is usually wrong.
- **Stable selectors.** The JS stamps `data-imi-ref` on candidate elements, so
  selectors survive hashed/regenerated CSS classes.
- **JSON response mode** (`responseMimeType = "application/json"`, temp 0.1)
  removes the markdown-fence parsing that breaks these loops.
- Model ladder + usage metering match `GeminiAIClient`: `gemini-2.5-flash` →
  `2.0-flash` → `2.0-flash-lite`, through `UsageLimitManager.tryConsume` and
  `TokenUsageTracker.track`.
- **Metering mode**: reuses `TokenUsageTracker.Mode.AI_CHAT`. Adding a 4th enum
  entry would have meant touching `Snapshot`, `buildFeatureUsage` and the limit
  maps — deliberately left alone.
- Hard cap of **15 steps**, then it stops on its own.

### Module 2 — security rules (code, not prompt)
These are enforced in Kotlin/JS so a confused model — or a page trying to talk
the model into something — still cannot get past them:
1. `ActionValidator` hands off on any field the page reported `[SENSITIVE]`
   (type=password, or name/id/autocomplete matching pass/otp/cvv/card/pin).
2. It also hands off on *text that looks like* a credential (4–8 bare digits,
   12+ digit runs), so the model can't route around rule 1 by picking another field.
3. `ActionExecutor` independently refuses `input[type=password]` in JS — an
   executor that can be tricked into typing a secret is a bug whatever calls it.
4. CAPTCHA detected ⇒ nothing but handoff/done/failed is permitted.
5. Login-ish button labels ⇒ handoff. Payment/irreversible labels ⇒ explicit
   user confirmation dialog.
6. `open` accepts http/https only — blocks `javascript:` and `data:` URLs.
7. Selectors not present in the current snapshot are rejected (no invented ones).

### Module 2 — UX
- AI button (bottom bar) toggles the command bar.
- Consent dialog on first run: states what it will and won't do.
- Status strip shows each step live, with **Stop** always available.
- Agent question ⇒ the command box becomes the answer box.
- Handoff ⇒ strip explains, user drives the WebView, taps **Continue**.
- Back button cancels a running agent before it navigates.

### Module 2 — known limits (expected, not bugs)
- Bot-protected sites (Uber, ticketing, banks) will still block this.
- Heavy SPAs may need a `wait` step; the planner has one.
- `awaitPageSettle` is a readyState poll + fixed render delay, not a true network-idle.

---

### Module 3 — files delivered
| File | Change |
|---|---|
| `ui/web/VoiceInputController.kt` | **new** — SpeechRecognizer wrapper with system-dialog fallback |
| `ui/web/AgentSpeaker.kt` | **new** — TextToSpeech for agent questions/results |
| `ui/web/WebCommandHistory.kt` | **new** — last 20 commands, on-device only |
| `res/layout/activity_web_browser.xml` | **edited** — mic + history buttons in command bar |
| `ui/web/WebBrowserActivity.kt` | **edited** — voice wiring, menu toggles, teardown |

### Module 3 — decisions
- **TTS**: Android's built-in `TextToSpeech`, matching `VisionChatActivity`
  (`Locale("en","IN")`, rate 0.95, `USAGE_ASSISTANT`). Note: `ui/GoogleCloudTTS.kt`
  exists but is **unused anywhere in the app** and needs a separate API key —
  deliberately not adopted here.
- **STT**: mirrors the `ChatActivity` recognizer, including its key lesson —
  `ERROR_NO_MATCH / SPEECH_TIMEOUT / CLIENT / RECOGNIZER_BUSY` fall back to the
  system speech dialog instead of showing the user an error. Many OEM
  recognizers also refuse to start without `EXTRA_CALLING_PACKAGE`.
- **Review before run (default).** A spoken *goal* lands in the command box for
  the user to check; a spoken *answer* to the agent's own question goes straight
  through, since it's unambiguous. Menu toggle switches to hands-free.
- Consent still gates voice-started runs — auto-run calls `submitCommand()`
  rather than bypassing it.
- Mic and TTS never run together: starting to listen stops speech, so the mic
  doesn't hear the assistant.
- "Stopped." is not spoken — the user did it, reading it back is noise.

### Module 3 — UX
- Mic button in the command bar; dims when idle, full accent while listening.
- Live partial transcription into the command box; hint becomes "Listening…".
- History button ▾ lists recent commands, tap to reuse, Clear to wipe.
- Menu adds: **Mute replies / Speak replies**, **Review voice commands /
  Run voice commands instantly**.
- `RECORD_AUDIO` was already in the manifest; the screen requests it on first
  mic tap and continues to work by typing if declined.
- `onPause` stops the mic and speech; `onDestroy` releases both.

---

### Module 4 — files delivered
| File | Change |
|---|---|
| `ui/web/PageContentExtractor.kt` | **new** — readability pass, full prose up to 12k chars |
| `ui/web/PageSummarizer.kt` | **new** — Gemini summariser, 3 styles |
| `ui/web/AiServiceCatalog.kt` | **new** — `AiService` enum + `CommandRouter` |
| `res/layout/activity_web_browser.xml` | **edited** — Claude / ChatGPT chips |
| `ui/web/WebBrowserActivity.kt` | **edited** — routing, summary flow, note saving, menu |

### Module 4 — how a command is routed
`CommandRouter.route()` is **plain keyword matching**, not another Gemini call —
routing is cheap and predictable that way, and a misroute is more annoying than
a slightly rigid rule. Three outcomes:

| Command example | Route |
|---|---|
| "summarise this page", "what does this say" | `SummarizeCurrent(GENERAL)` |
| "where did I leave off", "how far is this project" | `SummarizeCurrent(PROJECT_STATUS)` |
| "brief me on my Claude project" | `CatchUpOnService(CLAUDE)` |
| anything else | `Agent` (Module 2) |

### Module 4 — two different page readers, on purpose
- `PageReader` (Module 2) → short summary of **interactive elements** so the
  agent can decide what to click. Truncates aggressively; sent every turn.
- `PageContentExtractor` (Module 4) → the **prose itself**, up to 12k chars,
  after dropping nav/header/footer/script/aside and preferring a declared
  `main`/`article` container. Sent once per summary.

### Module 4 — the Claude/ChatGPT catch-up flow
This is the user's original ask ("tell me how far my Claude project is").
1. Command matched → open `claude.ai/recents` in **this** WebView.
2. Wait `SERVICE_LOAD_MS` (4.5s) for client-side rendering.
3. Extract content. **Under `MIN_CONTENT_CHARS` (400) ⇒ treat as a login wall**
   and tell the user to sign in once, rather than summarising a sign-in screen.
4. Summarise with `PROJECT_STATUS` style → dialog + spoken + optional save.

**Hard limitation, stated in code:** this does **not** read Chrome-on-PC history
— an Android app cannot reach another browser's cookies. It works because these
services keep conversations server-side, so the same account logged in here
shows the same history. Equivalent outcome, different mechanism.

**ToS caveat (flagged to the user):** reading the Claude/ChatGPT web UI this way
is fine personally but is against their terms as a shipped product feature, and
breaks whenever their markup changes. The supported alternative is an API key,
which cannot see claude.ai conversation history. Decision deliberately left open.

### Module 4 — output
- Result shown in a dialog: **Close / Read aloud / Save to Notes**.
- Saving uses the existing `QuickNotesManager.createNote(...)` with
  `QuickNote.CreatedBy.AI`, and appends `Source: <url>`. So summaries show up in
  Quick Notes *and* flow into `GeminiAIClient`'s notes context — the assistant
  elsewhere in the app can then answer questions about them.
- Summaries are spoken through Module 3's `AgentSpeaker`.
- Menu gains **Summarise this page** and **Show last summary**.
- Summary flow skips the agent consent gate: reading a page doesn't act on it.
- `summarizeJob` is cancelled on a new request and on destroy.

---

## Module 5 — Glasses-first control (voice via the existing live pipeline)

The user's correction: the Web section was phone-first with voice bolted on. It
should feel like **controlling the whole browser from the glasses**, with the
phone only for the steps a person must do themselves.

### The key research finding
The app **already has** a full realtime voice pipeline — `ui/GeminiLiveService.kt`:
- Gemini Live / GPT Realtime over WebSocket, `Dispatchers.IO` scope
- glasses mic in and glasses speaker out (BLE/SCO), VAD, barge-in
- **a tool-call mechanism**: `onToolCall(name, args): String`, where the returned
  string is fed back to the model and spoken

So the right design was **not** a second voice stack. The browser becomes
**tools the live session can call**. The user talks to the glasses exactly as
they already do; the browser is just something the assistant can now use.

### Files delivered
| File | Change |
|---|---|
| `ui/web/GlassBrowserEngine.kt` | **new** — off-screen WebView singleton, shares cookies with the Web screen |
| `ui/web/GlassBrowserTools.kt` | **new** — 4 tool declarations + `handleBlocking` bridge |
| `ui/web/HeadlessAgentRunner.kt` | **new** — the agent loop with no screen to talk to |
| `ui/GeminiLiveService.kt` | **edited** — declares browser tools + system instruction |
| `ListeningService.kt` | **edited** — dispatch (works with app backgrounded) |
| `ui/Mark1MainActivity.kt` | **edited** — dispatch |
| `MainActivity.kt` | **edited** — dispatch |
| `ui/MyApplication.kt` | **edited** — `GlassBrowserEngine.init()` at startup |
| `ui/web/WebBrowserActivity.kt` | **edited** — shows the pending handoff on resume |

### The four tools
| Tool | Spoken trigger |
|---|---|
| `browse_web` | "open my email and check", "search Amazon and tell me the price" |
| `browser_continue` | "done", "logged in", "carry on", "ho gaya" |
| `read_current_page` | "what does it say", "read that" |
| `catch_up_on_ai` | "how far is my Claude project" |

Browser tools are **not mark-gated** (unlike vision): they drive a phone-side
WebView, not glasses hardware, so Mark 1 and Mark 2 both get them.

### The handoff — "this is your time to do it"
When the headless runner hits a login or CAPTCHA:
1. `GlassBrowserEngine.requireUser(reason, goal)` parks the goal.
2. The tool returns a spoken line: *"I need you signed in to Claude. Open the
   Web section on your phone and log in — you only have to do it once. Then say
   continue."*
3. Opening the Web screen shows the same message in the status strip
   (`showGlassHandoffIfWaiting`), so arriving on the phone explains itself.
4. User says "continue" → `browser_continue` → `resume()` returns the parked
   goal → the loop picks up from the current page.

### Deliberate difference from the on-screen agent
`NeedsConfirmation` (payment / irreversible) is **not confirmable by voice**.
A spoken "yes" is too weak a gate for something the user cannot see, so the
headless runner refuses and sends them to the phone. `MAX_STEPS` is also lower
(10 vs 15) — a long silence while it grinds is bad on glasses.

### Threading
`onToolCall` is a plain function; the browser is suspend and touches the WebView
on the main thread. `handleBlocking` bridges with `runBlocking`, but **refuses
if called on the main thread** (returns a spoken apology) — `GeminiLiveService`
calls from `Dispatchers.IO`, but the Activity dispatchers can be reached from
the main thread and would otherwise deadlock.

### Status
- [x] Module 5 — DONE, `:app:assembleDebug` passes
