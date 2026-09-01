package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The agent loop: read the page, ask Gemini for one action, check it, run it,
 * repeat.
 *
 * The loop is capped and interruptible. Every path that involves the user's
 * credentials, a CAPTCHA, or spending money exits the loop and waits for the
 * user — the session resumes only when they say so.
 */
class WebAgentSession(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    /** How the session talks to the screen. All calls are on the UI thread. */
    interface Listener {
        /** Progress line for the status strip. */
        fun onStatus(message: String)

        /** The agent needs an answer before it can continue. */
        fun onQuestion(question: String)

        /** The user must drive the WebView themselves for this step. */
        fun onHandoff(reason: String)

        /** A destructive or paid action needs an explicit yes. */
        fun onConfirmationNeeded(prompt: String)

        /** The run ended. [success] false means it gave up or was stopped. */
        fun onFinished(success: Boolean, summary: String)
    }

    private val planner = WebAgentPlanner(context)
    private val executor = ActionExecutor(webView)

    private val history = mutableListOf<ActionResult>()
    private val answers = mutableListOf<Pair<String, String>>()

    private var job: Job? = null
    private var goal: String = ""

    /** Set while the loop is parked waiting for the user. */
    private var pendingQuestion: String? = null
    private var resumeSignal: ((String?) -> Unit)? = null

    val isRunning: Boolean get() = job?.isActive == true

    // ------------------------------------------------------------------ start

    /** Starts a fresh run. Any previous run is cancelled first. */
    fun start(userGoal: String) {
        stop(notify = false)
        goal = userGoal
        history.clear()
        answers.clear()

        job = scope.launch {
            try {
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error", e)
                listener.onFinished(false, "Something went wrong: ${e.message}")
            }
        }
    }

    /** Cancels the run. Called by the Stop button and on screen teardown. */
    fun stop(notify: Boolean = true) {
        resumeSignal = null
        pendingQuestion = null
        job?.cancel()
        job = null
        if (notify) listener.onFinished(false, "Stopped.")
    }

    // ----------------------------------------------------- user interactions

    /** Feeds back the answer to an [Listener.onQuestion]. */
    fun provideAnswer(answer: String) {
        val question = pendingQuestion
        if (question != null) {
            answers.add(question to answer)
            pendingQuestion = null
        }
        resumeSignal?.invoke(answer)
        resumeSignal = null
    }

    /** The user finished their manual step (login, CAPTCHA) and tapped Continue. */
    fun resumeAfterHandoff() {
        history.add(
            ActionResult(
                BrowserAction.Wait("user handled this step"),
                true,
                "The user completed the step manually. Continue from the current page."
            )
        )
        resumeSignal?.invoke(null)
        resumeSignal = null
    }

    /** Result of a confirmation prompt. */
    fun provideConfirmation(approved: Boolean) {
        resumeSignal?.invoke(if (approved) CONFIRM_YES else CONFIRM_NO)
        resumeSignal = null
    }

    // ------------------------------------------------------------- the loop

    private suspend fun runLoop() {
        listener.onStatus("Reading the page…")

        var steps = 0
        // The loop's OWN job, not the enclosing lifecycleScope: Stop cancels
        // this job alone, and the scope would still report itself active.
        while (currentCoroutineContext().isActive && steps < MAX_STEPS) {
            steps++

            val page = PageReader.read(webView)

            listener.onStatus("Thinking… (step $steps)")
            val action = planner.planNext(goal, page, history, answers)

            if (action == null) {
                listener.onFinished(false, "I couldn't work out what to do next.")
                return
            }

            // Terminal actions end the run before anything is executed.
            when (action) {
                is BrowserAction.Done -> {
                    listener.onFinished(true, action.summary)
                    return
                }
                is BrowserAction.Failed -> {
                    listener.onFinished(false, action.reason)
                    return
                }
                is BrowserAction.AskUser -> {
                    val answer = askUser(action.question) ?: return
                    history.add(ActionResult(action, true, "User answered: $answer"))
                    continue
                }
                is BrowserAction.HandoffToUser -> {
                    if (!handOff(action.reason)) return
                    continue
                }
                else -> Unit
            }

            when (val verdict = ActionValidator.validate(action, page)) {
                is ActionValidator.Verdict.Allow -> Unit

                is ActionValidator.Verdict.Handoff -> {
                    if (!handOff(verdict.reason)) return
                    continue
                }

                is ActionValidator.Verdict.Reject -> {
                    // Not fatal: tell the planner why and let it try again.
                    Log.d(TAG, "Rejected ${action.describe()}: ${verdict.reason}")
                    history.add(ActionResult(action, false, verdict.reason))
                    continue
                }

                is ActionValidator.Verdict.NeedsConfirmation -> {
                    val approved = confirm(verdict.prompt) ?: return
                    if (!approved) {
                        listener.onFinished(false, "Cancelled — I didn't tap it.")
                        return
                    }
                }
            }

            listener.onStatus(action.describe())
            val result = executor.execute(action)
            history.add(result)

            if (!result.success) {
                Log.d(TAG, "Action failed: ${result.detail}")
            }

            // Breathing room so a client-side render finishes before the read.
            delay(STEP_GAP_MS)
        }

        if (steps >= MAX_STEPS) {
            listener.onFinished(
                false,
                "I've taken $MAX_STEPS steps without finishing. Stopping so this doesn't run away."
            )
        }
    }

    // -------------------------------------------------------------- suspends

    /** Parks the loop until [provideAnswer]. Null means the run was cancelled. */
    private suspend fun askUser(question: String): String? {
        pendingQuestion = question
        listener.onQuestion(question)
        return awaitUser()
    }

    /** Parks until [resumeAfterHandoff]. False means the run was cancelled. */
    private suspend fun handOff(reason: String): Boolean {
        listener.onHandoff(reason)
        return awaitUser() != null
    }

    /** Parks until [provideConfirmation]. Null means cancelled. */
    private suspend fun confirm(prompt: String): Boolean? {
        listener.onConfirmationNeeded(prompt)
        val reply = awaitUser() ?: return null
        return reply == CONFIRM_YES
    }

    private suspend fun awaitUser(): String? =
        suspendCancellableCoroutine { cont ->
            resumeSignal = { value ->
                if (cont.isActive) cont.resume(value ?: RESUMED)
            }
            cont.invokeOnCancellation { resumeSignal = null }
        }

    companion object {
        private const val TAG = "WebAgentSession"

        /** Hard cap so a confused agent can't browse forever on the user's data. */
        private const val MAX_STEPS = 15
        private const val STEP_GAP_MS = 350L

        private const val RESUMED = "__resumed__"
        private const val CONFIRM_YES = "__yes__"
        private const val CONFIRM_NO = "__no__"
    }
}
