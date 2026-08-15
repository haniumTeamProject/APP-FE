package org.mcsmtp.wayfinder.mock

import org.mcsmtp.wayfinder.net.model.Destination

/**
 * 음성 인식 결과를 목적지에 매칭한다. 매칭 로직 자체는 [VoiceMatcher]가 담당하며,
 * 여기서는 목적지의 매칭 키(별칭 + 이름)를 넘겨줄 뿐이다.
 * 건물·층 선택도 같은 [VoiceMatcher]를 쓴다.
 */
object DestinationMatcher {

    /**
     * @return 후보 목록.
     *  - 1개 → 확정
     *  - 여러 개 → 되물어야 한다 (2차 DISAMBIGUATE)
     *  - 0개 → 재입력 유도
     */
    fun match(spoken: String, all: List<Destination>): List<Destination> =
        VoiceMatcher.match(spoken, all) { it.aliases + it.name }

    fun normalize(s: String): String = VoiceMatcher.normalize(s)

    fun stripTail(s: String): String = VoiceMatcher.stripTail(s)
}
