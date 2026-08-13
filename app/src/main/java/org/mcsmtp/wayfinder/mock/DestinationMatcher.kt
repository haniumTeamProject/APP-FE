package org.mcsmtp.wayfinder.mock

import org.mcsmtp.wayfinder.net.model.Destination
import java.util.Locale

/**
 * 음성 인식 결과를 목적지에 매칭한다.
 *
 * 층당 목적지가 24개 남짓이라 온디바이스에서 즉시 처리된다.
 * **목적지 후보가 소수의 고정 목록**이라는 점이 LLM을 쓰지 않기로 한 근거다.
 * 정규화 + 별칭 표 + 조사 제거로 충분하며, LLM 왕복은 지연과 비결정성만 더한다.
 *
 * Context에 의존하지 않으므로 JVM 유닛 테스트로 검증할 수 있다.
 */
object DestinationMatcher {

    /** 음성 인식 결과 뒤에 흔히 붙는 조사·서술어. 긴 것부터 지운다. */
    private val TAILS = listOf(
        "으로가줘", "로가줘", "으로가자", "로가자", "에가줘", "에가자",
        "으로안내해줘", "로안내해줘", "안내해줘", "찾아줘", "알려줘",
        "으로", "에서", "까지", "에게", "로", "에", "을", "를", "은", "는", "이", "가",
    )

    /** 부분 일치를 허용할 최소 길이. 한 글자면 아무 데나 걸린다. */
    private const val MIN_PARTIAL_LEN = 2

    /**
     * @return 후보 목록.
     *  - 1개 → 확정
     *  - 여러 개 → 되물어야 한다 (2차 DISAMBIGUATE)
     *  - 0개 → 재입력 유도
     */
    fun match(spoken: String, all: List<Destination>): List<Destination> {
        val q = stripTail(normalize(spoken))
        if (q.isEmpty()) return emptyList()

        // 1) 정식 명칭 또는 별칭과 정확히 일치
        val exact = all.filter { d -> keysOf(d).any { it == q } }
        if (exact.isNotEmpty()) return exact

        // 2) 부분 일치 — "409호실 알려줘" 처럼 군더더기가 남은 경우
        if (q.length < MIN_PARTIAL_LEN) return emptyList()
        return all.filter { d ->
            keysOf(d).any { k ->
                k.length >= MIN_PARTIAL_LEN && (q.contains(k) || k.contains(q))
            }
        }
    }

    private fun keysOf(d: Destination): List<String> =
        (d.aliases + d.name).map(::normalize).filter { it.isNotEmpty() }

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
