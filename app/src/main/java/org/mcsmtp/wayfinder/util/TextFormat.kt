package org.mcsmtp.wayfinder.util

/**
 * 화면에 뿌리기 직전에 안내 문구를 다듬는다.
 *
 * 발화용 문자열은 건드리지 않는다. TTS는 문장 끝 온점으로 억양을 내리고,
 * 여기서 넣는 이음 문자는 소리로 읽을 값이 아니다.
 * 그래서 이 함수들의 결과는 **화면 표시에만** 쓴다.
 */
object TextFormat {

    /** 줄바꿈 금지 문자. 폭이 0이라 화면에는 보이지 않는다. */
    private const val WORD_JOINER = '⁠'

    /**
     * 문장 끝 온점을 지운다.
     *
     * 화면의 안내 문구는 라벨에 가깝다. 마침표를 붙이면 문단처럼 읽혀 시선이 한 번 더 머문다.
     * 문장 중간의 온점은 두 문장을 가르는 구분이라 남긴다.
     */
    fun forDisplay(text: String): String = text.trimEnd().removeSuffix(".")

    /**
     * 어절 가운데에서 줄이 넘어가지 않게 한다.
     *
     * 안드로이드는 한글을 글자 단위로 끊어서 "손을 떼 / 고 직진하세요" 처럼 쪼갠다.
     * 한 어절 안의 글자 사이에 이음 문자를 넣어 붙여두면 공백에서만 줄이 넘어간다.
     *
     * API 33 부터는 lineBreakWordStyle="phrase" 로도 되지만 minSdk 가 26 이라
     * 구형 기기에서만 문장이 쪼개지는 상황이 생긴다. 모든 기기에서 같게 보이도록 여기서 처리한다.
     */
    fun keepWords(text: String): String = buildString(text.length * 2) {
        text.forEachIndexed { i, ch ->
            append(ch)
            val next = text.getOrNull(i + 1) ?: return@forEachIndexed
            if (!ch.isWhitespace() && !next.isWhitespace()) append(WORD_JOINER)
        }
    }

    /** 안내 문구를 화면용으로 다듬는다. */
    fun guidance(text: String): String = keepWords(forDisplay(text))
}
