package org.mcsmtp.wayfinder.mock

import java.util.Locale

/**
 * 음성 인식 결과를 소수의 고정 후보에 매칭하는 공통 로직.
 *
 * 목적지뿐 아니라 건물·층 선택도 같은 방식으로 매칭한다 — 후보가 적고(수 개), 이름과 별칭이
 * 정해져 있어 LLM 없이 정규화 + 별칭 표 + 조사 제거로 충분하다.
 *
 * Context에 의존하지 않으므로 JVM 유닛 테스트로 검증할 수 있다.
 */
object VoiceMatcher {

    /** 음성 인식 결과 뒤에 흔히 붙는 조사·서술어. 긴 것부터 지운다. */
    private val TAILS = listOf(
        "으로가줘", "로가줘", "으로가자", "로가자", "에가줘", "에가자",
        "으로안내해줘", "로안내해줘", "안내해줘", "찾아줘", "알려줘",
        "으로", "에서", "까지", "에게", "로", "에", "을", "를", "은", "는", "이", "가",
    )

    /** 부분 일치를 허용할 최소 길이. 한 글자면 아무 데나 걸린다. */
    private const val MIN_PARTIAL_LEN = 2

    /**
     * @param keysOf 항목 하나의 매칭 키(이름 + 별칭)를 돌려준다.
     * @return 후보 목록. 1개=확정, 여러 개=되물어야 함, 0개=재입력 유도.
     */
    fun <T> match(spoken: String, items: List<T>, keysOf: (T) -> List<String>): List<T> {
        val q = stripTail(normalize(spoken))
        if (q.isEmpty()) return emptyList()

        fun keys(item: T) = keysOf(item).map(::normalize).filter { it.isNotEmpty() }

        // 1) 정식 명칭 또는 별칭과 정확히 일치
        val exact = items.filter { keys(it).any { k -> k == q } }
        if (exact.isNotEmpty()) return exact

        // 2) 부분 일치 — "409호실 알려줘" 처럼 군더더기가 남은 경우
        if (q.length < MIN_PARTIAL_LEN) return emptyList()
        return items.filter { item ->
            keys(item).any { k -> k.length >= MIN_PARTIAL_LEN && (q.contains(k) || k.contains(q)) }
        }
    }

    /** 공백·문장부호를 제거하고 소문자로 맞춘다. */
    fun normalize(s: String): String =
        s.lowercase(Locale.KOREA).replace(Regex("[\\s.,!?~·\\-()]"), "")

    /** 끝에 붙은 조사를 한 번만 지운다. "409호로" → "409호" */
    fun stripTail(s: String): String {
        for (t in TAILS) {
            if (s.length > t.length && s.endsWith(t)) return s.dropLast(t.length)
        }
        return s
    }
}
