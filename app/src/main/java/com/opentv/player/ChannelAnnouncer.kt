package com.opentv.player

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 9.3.0 — Spoken channel names. Announces the channel whenever
 * playback switches to one, when the setting is on (default OFF).
 *
 * One process-lifetime TextToSpeech instance, created lazily on the
 * first announce — a zap must not wait on engine init, and
 * re-creating the engine on every channel change would stutter
 * through channels. Speech is QUEUE_FLUSH, so zapping fast only
 * ever speaks the latest channel. A speak that lands before init
 * finishes is held as pending text and spoken in onInit.
 *
 * Language: the device default locale when the engine has it,
 * otherwise the engine's own default (leaving it unset). The
 * engine may be missing entirely on some TVs — onInit ERROR just
 * means nothing is ever spoken, never a crash.
 *
 * The spoken text is just the channel name: "CNN". No programme,
 * no "now playing" prefix — the info overlay already shows the
 * rest, and a long sentence on every zap would talk over the
 * channel itself.
 */
object ChannelAnnouncer {

    private const val UTTERANCE_ID = "opentv-channel"

    private var tts: TextToSpeech? = null
    private var initDone = false
    private var initOk = false
    private var pendingText: String? = null
    private var initializing = false

    /** The text spoken for a channel — the name, nothing else.
     * Pure, so it is unit-testable. */
    fun utteranceFor(channelName: String): String = channelName

    /** Announce a channel change. No-ops when the setting is off,
     * the name is blank, or the engine can't be started. Safe to
     * call from any thread; the engine calls happen on the main
     * thread via the TTS callbacks. */
    fun announce(context: Context, channelName: String) {
        if (!AppSettings.speakChannelNames(context)) return
        val text = utteranceFor(channelName)
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && initDone) {
            if (initOk) speak(engine, text)
            return
        }
        // Not ready yet: hold the latest text and (once) start init.
        pendingText = text
        if (initializing) return
        initializing = true
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            initDone = true
            initOk = status == TextToSpeech.SUCCESS
            if (initOk) {
                val engine2 = tts
                if (engine2 != null) {
                    // Default locale when the engine has it; a
                    // missing/unsupported locale leaves the engine
                    // default rather than failing the whole announce.
                    val locale = Locale.getDefault()
                    val availability = engine2.isLanguageAvailable(locale)
                    if (availability >= TextToSpeech.LANG_AVAILABLE) {
                        engine2.language = locale
                    }
                    pendingText?.let { speak(engine2, it) }
                }
            }
            pendingText = null
        }
    }

    private fun speak(engine: TextToSpeech, text: String) {
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Test hook: forget the engine so the next announce re-inits.
     * Never called by the app — the engine lives for the process. */
    internal fun resetForTest() {
        tts?.shutdown()
        tts = null
        initDone = false
        initOk = false
        pendingText = null
        initializing = false
    }
}
