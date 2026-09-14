package com.zikriyo.geminisharh.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Simple Android TextToSpeech wrapper.
 * Note: Stock Android TTS has limited Uzbek support on many devices.
 * For production quality Uzbek neural voices consider Google Cloud TTS
 * or a third-party engine later.
 */
class TtsHelper(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // Prefer Uzbek, fallback Russian / English
                val result = tts?.setLanguage(Locale("uz"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("ru"))
                }
            }
        }
    }

    suspend fun speakToFile(text: String, outFile: File): Boolean {
        if (!ready || tts == null) return false

        return suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    cont.resume(outFile.exists() && outFile.length() > 0)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    cont.resume(false)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    cont.resume(false)
                }
            })

            val params = android.os.Bundle()
            val result = tts?.synthesizeToFile(text, params, outFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                cont.resume(false)
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
