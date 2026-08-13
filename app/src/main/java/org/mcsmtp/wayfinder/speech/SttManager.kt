package org.mcsmtp.wayfinder.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 음성 입력. Android [SpeechRecognizer]를 감싼다.
 *
 * 주의점 두 가지를 여기서 처리한다.
 * 1. **마이크가 열린 것을 알려야 한다.** TalkBack 사용자는 화면의 마이크 아이콘을 볼 수 없으므로
 *    짧은 효과음으로 알린다. 효과음은 마이크를 열기 **전에** 재생한다(열린 뒤 내면 그 소리를 인식한다).
 * 2. **마이크가 열려 있는 동안에는 발화하지 않는다.** 자기 TTS를 인식해버린다.
 *    호출하는 쪽에서 TTS 종료를 기다린 뒤 [start]를 부른다.
 *
 * AndroidManifest 의 `<queries>` 에 RecognitionService 를 선언해야 한다.
 * 없으면 API 30+ 패키지 가시성 제한 때문에 조용히 실패한다.
 */
class SttManager(private val context: Context) {

    interface Callback {
        /** 마이크가 실제로 열렸을 때. 화면 표시를 "듣고 있어요"로 바꾼다. */
        fun onReady()

        /** 사용자가 말을 마쳤을 때. 이 시점부터 발화해도 안전하다. */
        fun onEndOfSpeech()

        /** 인식 결과. 신뢰도 순으로 여러 개가 올 수 있다. */
        fun onResult(candidates: List<String>)

        /** 인식 실패. [recoverable]이 true면 재시도를 권한다. */
        fun onFailed(recoverable: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var tone: ToneGenerator? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(callback: Callback) {
        this.callback = callback

        if (!isAvailable) {
            Log.e(TAG, "이 기기에서 음성 인식을 쓸 수 없다")
            callback.onFailed(recoverable = false)
            return
        }

        // 마이크를 열기 전에 알린다. 열린 뒤 내면 효과음 자체가 인식된다.
        playOpenTone()

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(buildIntent())
        }
    }

    fun cancel() {
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tone?.release()
        tone = null
        callback = null
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        // 목적지 이름은 짧으므로 후보를 여러 개 받아 별칭 매칭 성공률을 올린다.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun playOpenTone() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: RuntimeException) {
            Log.w(TAG, "효과음 재생 실패", e)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callback?.onReady()
        }

        override fun onEndOfSpeech() {
            callback?.onEndOfSpeech()
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (list.isEmpty()) callback?.onFailed(recoverable = true)
            else callback?.onResult(list)
        }

        override fun onError(error: Int) {
            Log.w(TAG, "음성 인식 오류: $error")
            val recoverable = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_CLIENT -> false
                else -> true
            }
            callback?.onFailed(recoverable)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "SttManager"
    }
}
