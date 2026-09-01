package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Runs the browser agent with nobody watching, for the glasses.
 *
 * Same read-plan-validate-execute loop as [WebAgentSession], but it cannot ask
 * a screen for anything, so every branch has to end in something speakable:
 *
 * - Needs an answer from the user → return the question. The live model asks it
 *   out loud, and the user's reply comes back as a fresh `browse_web` call.
 * - Needs the user to log in or clear a security check → park the goal, tell
 *   them to use the phone, resume on "continue".
 * - Needs confirmation to spend money or do something final → **refuse and hand
 *   over**. A spoken "yes" is too weak a gate for an irreversible action the
 *   user cannot see, so those steps stay on the phone where they can be read.
 */
class HeadlessAgentRunner(private val context: Context) {

    data class Outcome(
        val success: Boolean,
        /** Exactly what the glasses should say. */
        val spokenResult: String
    )

    private val planner = WebAgentPlanner(context)

    suspend fun run(goal: String, resuming: Boolean = false): Outcome {
        val history = mutableListOf<ActionResult>()

        if (resuming) {
            history.add(
                ActionResult(
                    BrowserAction.Wait("user handled this step"),
                    true,
                    "The user completed the manual step. Continue from the current page."
                )
            )
        }

        var steps = 0
        while (steps < MAX_STEPS) {
            steps++

            val page = GlassBrowserEngine.readPage()
            val action = planner.planNext(goal, page, history, emptyList())
                ?: return Outcome(false, "I couldn't work out how to do that.")

            when (action) {
                is BrowserAction.Done ->
                    return Outcome(true, action.summary)

                is BrowserAction.Failed ->
                    return Outcome(false, action.reason)

                is BrowserAction.AskUser -> {
                    // The question itself is the spoken turn. The user's answer
                    // arrives as a new tool call with a fuller goal.
                    GlassBrowserEngine.requireUser(action.question, goal)
                    return Outcome(true, action.question)
                }

                is BrowserAction.HandoffToUser ->
                    return handOff(action.reason, goal)

                else -> Unit
            }

            when (val verdict = ActionValidator.validate(action, page)) {
                is ActionValidator.Verdict.Allow -> Unit

                is ActionValidator.Verdict.Handoff ->
                    return handOff(verdict.reason, goal)

                is ActionValidator.Verdict.Reject -> {
                    Log.d(TAG, "Rejected ${action.describe()}: ${verdict.reason}")
                    history.add(ActionResult(action, false, verdict.reason))
                    continue
                }

                is ActionValidator.Verdict.NeedsConfirmation -> {
                    // Deliberately not confirmable by voice. See the class note.
                    GlassBrowserEngine.requireUser(verdict.prompt, goal)
                    return Outcome(
                        false,
                        "This next step spends money or can't be undone, so I won't do it " +
                            "from here. Open the Web section on your phone to finish it."
                    )
                }
            }

            val executor = GlassBrowserEngine.executor()
            val result = executor.execute(action)
            history.add(result)

            delay(STEP_GAP_MS)
        }

        return Outcome(
            false,
            "I tried several steps but couldn't finish that one. " +
                "You might have better luck on the phone."
        )
    }

    /**
     * Parks the goal and tells the user, in speech, what only they can do.
     * This is the "this is your time to do it" moment.
     */
    private fun handOff(reason: String, goal: String): Outcome {
        GlassBrowserEngine.requireUser(reason, goal)
        return Outcome(
            false,
            "$reason Open the Web section on your phone to do it, " +
                "then say continue and I'll carry on."
        )
    }

    companion object {
        private const val TAG = "HeadlessAgentRunner"

        /**
         * Lower than the on-screen cap: nobody is watching this one, and a long
         * silence while it grinds through steps is bad on glasses.
         */
        private const val MAX_STEPS = 10
        private const val STEP_GAP_MS = 300L
    }
}
