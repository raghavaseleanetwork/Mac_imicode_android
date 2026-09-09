package com.sdk.glassessdksample.ui

// Defines all possible voice actions the app can understand
enum class GlassAction {
    WAKE_UP,
    TAKE_PHOTO,
    START_VIDEO,
    STOP_VIDEO,
    CALL_SOMEONE,
    NAVIGATE_TO,
    CALCULATE_DISTANCE,
    PLAY_YOUTUBE,
    PLAY_MUSIC,
    STOP_MUSIC,
    SEARCH_WEB,
    GET_NEWS,
    TRACK_LOCATION,
    GENERAL_CHAT // Fallback for any command not explicitly handled
}

/**
 * A simple interpreter to map spoken phrases to a specific GlassAction.
 */
object VoiceCommandInterpreter {

    fun detectAction(raw: String): Pair<GlassAction, String> {
        val text = raw.lowercase().trim()

        // --- Wake Words (ONLY "Hey Imi") ---
        if (text.contains("hey imi")) {
            return GlassAction.WAKE_UP to text
        }

        // --- Mute Command ---
        if (text.contains("imi mute") || text.contains("hey mute") || text.contains("mute ai")) {
            return GlassAction.GENERAL_CHAT to "mute"
        }

        // --- Specific Commands ---

        if (listOf("photo", "take a picture", "click photo").any { text.contains(it) }) {
            return GlassAction.TAKE_PHOTO to ""
        }

        if (listOf("record video", "start video").any { text.contains(it) }) {
            return GlassAction.START_VIDEO to ""
        }

        if (text.startsWith("call") || text.startsWith("dial")) {
            val name = text.replace("call", "").replace("dial", "").trim()
            return GlassAction.CALL_SOMEONE to name
        }
        
        if (text.contains("distance from")) {
            return GlassAction.CALCULATE_DISTANCE to text
        }

        if (text.startsWith("navigate to") || text.startsWith("go to")) {
            val place = text.replace("navigate to", "").replace("go to", "").trim()
            return GlassAction.NAVIGATE_TO to place
        }

        if (text.startsWith("play on youtube")) {
            val query = text.replace("play on youtube", "").trim()
            return GlassAction.PLAY_YOUTUBE to query
        }
        
        if (text.startsWith("play")) { // Catches "play music", "play song" etc.
            val query = text.replace("play", "").trim()
            return GlassAction.PLAY_MUSIC to query
        }

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

        if (text.contains("where am i") || text.contains("my location")) {
            return GlassAction.TRACK_LOCATION to ""
        }
        
        // --- Fallback ---

        // If no specific command is found, treat it as a general chat question for Gemini
        return GlassAction.GENERAL_CHAT to text
    }
}
