package com.sarvarbek.expense_tracker.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * On-device STT over the platform [SpeechRecognizer]. Open so tests can
 * subclass it with a fake transcript (no microphone). Call from the main thread.
 * The caller holds the RECORD_AUDIO permission.
 */
open class SpeechService(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    open fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Streams partial transcripts to [onResult]; [onEnd] once it stops (silence, error or [stop]). */
    open fun listen(locale: String, onResult: (String) -> Unit, onEnd: () -> Unit) {
        release()
        val r = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { words(b)?.takeIf { it.isNotBlank() }?.let(onResult) }
            override fun onResults(b: Bundle?) { words(b)?.let(onResult); release(); onEnd() }
            override fun onError(error: Int) { release(); onEnd() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.replace('_', '-')) // uz_UZ -> uz-UZ
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    /** Stops listening; the final result still arrives, then onEnd. */
    open fun stop() { recognizer?.stopListening() }

    open fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
