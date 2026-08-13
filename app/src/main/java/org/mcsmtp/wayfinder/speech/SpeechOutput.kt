package org.mcsmtp.wayfinder.speech

import android.view.View

/**
 * 발화 출력.
 *
 * 앱 TTS와 TalkBack은 서로의 발화 상태를 모르는 별개 엔진이라, 동시에 말하면 둘 다 알아들을 수 없다.
 * 그래서 **일반 안내는 TalkBack 큐에 위임**한다. announceForAccessibility()로 넣으면
 * TalkBack이 자기 탐색 안내를 끝낸 뒤 순서대로 읽어주므로 겹침이 원천적으로 사라진다.
 *
 * TODO(6단계): 안전 안내(계단·이탈·횡단)를 자체 TextToSpeech + QUEUE_FLUSH 로 처리해
 *              TalkBack 발화를 끊고 즉시 말하게 한다. 지금은 전부 TalkBack에 위임한다.
 */
class SpeechOutput {

    enum class Priority {
        /** 계단 · 경로 이탈 · 횡단. TalkBack을 끊고 즉시 발화해야 한다. */
        SAFETY,

        /** 직진 · 회전 · 도착. TalkBack 발화가 끝난 뒤 발화한다. */
        NORMAL,
    }

    /** [다시 듣기]와 흔들기가 재생할 마지막 문장. */
    var lastUtterance: String? = null
        private set

    fun speak(root: View?, text: String?, priority: Priority = Priority.NORMAL) {
        if (text.isNullOrBlank() || root == null) return
        lastUtterance = text
        // 6단계에서 priority == SAFETY 분기를 추가한다.
        root.announceForAccessibility(text)
    }

    /** 마지막 문장을 다시 읽는다. 소음으로 한 번 놓쳤을 때 쓰며 실사용 빈도가 가장 높다. */
    fun replayLast(root: View?) {
        val t = lastUtterance ?: return
        root?.announceForAccessibility(t)
    }

    fun clear() {
        lastUtterance = null
    }
}
