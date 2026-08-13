package org.mcsmtp.wayfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mcsmtp.wayfinder.mock.DestinationMatcher
import org.mcsmtp.wayfinder.net.model.Destination

/**
 * 별칭 매칭 검증. assets/mock/destinations.json 과 같은 데이터를 쓴다.
 * 실제 기기 없이 JVM에서 돌아간다.
 */
class DestinationMatcherTest {

    private val all = listOf(
        Destination("lm_401", "401호", listOf("401", "사백일", "401호실", "사백일호"), "room", "right"),
        Destination("lm_409a", "409호 앞문", listOf("409", "사백구", "409호실", "409호 앞문"), "room", "left"),
        Destination("lm_409b", "409호 뒷문", listOf("409", "사백구", "409호 뒷문"), "room", "left"),
        Destination("lm_wc1", "서편 화장실", listOf("화장실", "서편 화장실", "왼쪽 화장실"), "restroom", "left"),
        Destination("lm_wc2", "동편 화장실", listOf("화장실", "동편 화장실", "오른쪽 화장실"), "restroom", "right"),
        Destination("lm_elv1", "1번 엘리베이터", listOf("엘리베이터", "엘베", "승강기"), "elevator", null),
        Destination("lm_st2", "중앙 계단", listOf("계단", "중앙 계단"), "stairs", null),
    )

    private fun ids(spoken: String) = DestinationMatcher.match(spoken, all).map { it.id }

    // ─── 확정되는 경우 ────────────────────────────────────────────

    @Test
    fun `정식 명칭으로 확정`() {
        assertEquals(listOf("lm_401"), ids("401호"))
    }

    @Test
    fun `한글 발음으로 확정 - STT는 숫자를 한글로 인식하는 경우가 많다`() {
        assertEquals(listOf("lm_401"), ids("사백일호"))
        assertEquals(listOf("lm_401"), ids("사백일"))
    }

    @Test
    fun `조사가 붙어도 확정`() {
        assertEquals(listOf("lm_401"), ids("401호로"))
        assertEquals(listOf("lm_401"), ids("401호로 가줘"))
        assertEquals(listOf("lm_401"), ids("사백일호로 안내해줘"))
    }

    @Test
    fun `공백이 섞여도 확정`() {
        assertEquals(listOf("lm_401"), ids("4 0 1 호"))
    }

    @Test
    fun `줄임말 별칭으로 확정`() {
        assertEquals(listOf("lm_elv1"), ids("엘베"))
        assertEquals(listOf("lm_elv1"), ids("승강기"))
    }

    // ─── 되물어야 하는 경우 (2차 DISAMBIGUATE) ──────────────────

    @Test
    fun `화장실은 두 곳이라 후보가 둘이다`() {
        val r = ids("화장실")
        assertEquals(2, r.size)
        assertTrue(r.containsAll(listOf("lm_wc1", "lm_wc2")))
    }

    @Test
    fun `문이 두 개인 방은 후보가 둘이다`() {
        val r = ids("409호")
        assertEquals(2, r.size)
        assertTrue(r.containsAll(listOf("lm_409a", "lm_409b")))
    }

    @Test
    fun `방향을 붙이면 한 곳으로 좁혀진다`() {
        assertEquals(listOf("lm_wc1"), ids("서편 화장실"))
        assertEquals(listOf("lm_wc2"), ids("오른쪽 화장실"))
    }

    // ─── 실패하는 경우 ───────────────────────────────────────────

    @Test
    fun `없는 목적지는 후보가 없다`() {
        assertTrue(ids("옥상정원").isEmpty())
    }

    @Test
    fun `빈 입력은 후보가 없다`() {
        assertTrue(ids("").isEmpty())
        assertTrue(ids("   ").isEmpty())
    }

    @Test
    fun `한 글자는 아무 데나 걸리지 않는다`() {
        assertTrue(ids("4").isEmpty())
    }

    // ─── 정규화 단위 검증 ────────────────────────────────────────

    @Test
    fun `정규화는 공백과 문장부호를 지운다`() {
        assertEquals("409호", DestinationMatcher.normalize("4 0 9 호!"))
        assertEquals("서편화장실", DestinationMatcher.normalize("서편 화장실"))
    }

    @Test
    fun `조사 제거는 한 번만 일어난다`() {
        assertEquals("409호", DestinationMatcher.stripTail("409호로"))
        assertEquals("화장실", DestinationMatcher.stripTail("화장실에"))
        // 조사가 아니라 이름의 일부인 경우는 건드리지 않는다
        assertEquals("계단", DestinationMatcher.stripTail("계단"))
    }
}
