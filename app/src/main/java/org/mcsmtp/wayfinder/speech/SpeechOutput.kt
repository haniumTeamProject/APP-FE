package org.mcsmtp.wayfinder.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import java.util.Locale

/**
 * 발화 출력.
 *
 * 앱 TTS와 TalkBack은 서로의 발화 상태를 모르는 **별개 엔진**이다.
 * 동시에 말하면 둘 다 알아들을 수 없으므로 우선순위를 두고 경로를 나눈다.
 *
 * - [Priority.SAFETY] — 계단·경로 이탈·횡단. 자체 TTS로 QUEUE_FLUSH 하여 **즉시** 말한다.
 * - [Priority.NORMAL] — 직진·회전·도착. `announceForAccessibility()`로 **TalkBack 큐에 위임**한다.
 *   TalkBack이 자기 탐색 안내를 끝낸 뒤 순서대로 읽으므로 겹침이 원천적으로 사라진다.
 *
 * TalkBack이 꺼져 있으면 announceForAccessibility는 아무 소리도 내지 않으므로,
 * 그 경우에는 자체 TTS로 대신 읽는다([talkBackEnabled]).
 */
class SpeechOutput(context: Context) {

    enum class Priority { SAFETY, NORMAL }

    private var tts: TextToSpeech? = null
    private var ready = false

    /** TalkBack(터치 탐색)이 켜져 있는지. 꺼져 있으면 일반 안내도 자체 TTS로 읽는다. */
    var talkBackEnabled: Boolean = false

    /** [다시 듣기]와 흔들기가 재생할 마지막 문장. */
    var lastUtterance: String? = null
        private set

    /** 자체 TTS가 지금 말하고 있는지. STT를 열기 전에 반드시 확인한다. */
    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    private var onDone: (() -> Unit)? = null

    /** TTS 준비 전에 들어온 발화. 준비되면 한 번 재생한다(진입 화면 안내가 콜드 스타트에 묻히는 것 방지). */
    private var pending: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS 초기화 실패: $status")
                return@TextToSpeech
            }
            tts?.apply {
                language = Locale.KOREAN
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        onDone?.invoke(); onDone = null
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDone?.invoke(); onDone = null
                    }
                })
            }
            ready = true
            // TTS 바인딩은 앱 시작보다 늦다. 준비 전에 들어온 첫 발화(진입 화면 안내)를 여기서 재생한다.
            pending?.invoke()
            pending = null
        }
    }

    /**
     * 사용자 TTS 속도는 시스템 설정을 따른다.
     * TalkBack 숙련 사용자는 2~3배속을 쓰므로 앱에서 배속을 고정하면 안 된다.
     */
    fun speak(root: View?, text: String?, priority: Priority = Priority.NORMAL) {
        if (text.isNullOrBlank()) return
        lastUtterance = text

        val useOwnTts = priority == Priority.SAFETY || !talkBackEnabled || root == null
        if (useOwnTts) {
            // 자체 TTS는 늘 이전 발화를 끊고(QUEUE_FLUSH) 새 안내를 말한다.
            // 큐에 쌓으면 발화가 화면 전환보다 길 때(경로 안내 1초 tick 등) 소리가
            // 화면보다 뒤처져 밀리고, 앞 화면 발화가 다음 화면과 겹쳐 들린다.
            // 항상 "지금 화면의 안내"만 들리게 한다.
            speakWithTts(text, flush = true)
        } else {
            announce(root, text)
        }
    }

    /** 발화가 끝나면 [after]를 호출한다. 마이크를 열기 전 TTS 종료를 기다릴 때 쓴다. */
    fun speakThen(text: String?, after: () -> Unit) {
        if (text.isNullOrBlank()) {
            after(); return
        }
        // 준비 전이면 준비될 때까지 미뤄 둔다. 그래야 진입 안내가 나온 뒤 마이크가 열린다.
        if (!ready) {
            pending = { speakThen(text, after) }
            return
        }
        lastUtterance = text
        onDone = after
        speakWithTts(text, flush = true)
    }

    private fun speakWithTts(text: String, flush: Boolean) {
        if (!ready) {
            pending = { speakWithTts(text, flush) }
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "u${System.currentTimeMillis()}")
    }

    /** 마지막 문장을 다시 읽는다. 소음으로 한 번 놓쳤을 때 쓰며 실사용 빈도가 가장 높다. */
    fun replayLast(root: View?) {
        val t = lastUtterance ?: return
        if (talkBackEnabled && root != null) announce(root, t)
        else speakWithTts(t, flush = true)
    }

    /**
     * TalkBack 큐에 문장을 넣는다.
     *
     * announceForAccessibility 는 최신 API에서 deprecated 이지만, 대체 수단인
     * accessibilityLiveRegion 은 "화면의 특정 뷰 텍스트가 바뀔 때"만 동작해
     * 임의 시점의 안내 발화에는 맞지 않는다. 동작에는 문제가 없으므로 그대로 쓴다.
     */
    @Suppress("DEPRECATION")
    private fun announce(root: View, text: String) {
        root.announceForAccessibility(text)
    }

    fun stop() {
        tts?.stop()
        onDone = null
    }

    fun clear() {
        lastUtterance = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "SpeechOutput"
    }
}
