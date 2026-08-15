package org.mcsmtp.wayfinder

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mcsmtp.wayfinder.mock.VoiceMatcher

/**
 * 건물·층 음성 매칭 검증. 에뮬레이터에서 실제 음성 입력이 어려우므로 매칭 로직을 JVM에서 검증한다.
 */
class VoiceMatcherTest {

    private data class Item(val id: String, val name: String, val aliases: List<String>)

    private val buildings = listOf(
        Item("suwon", "수원대 ICT관", listOf("수원대", "ICT관", "아이시티관")),
        Item("future", "미래혁신관", listOf("미래관", "혁신관")),
        Item("library", "도서관", listOf("도서관")),
    )

    private val floors = listOf(
        Item("f4", "4층", listOf("사층", "4", "사")),
        Item("f1", "1층", listOf("일층", "1", "일")),
    )

    private fun ids(spoken: String, items: List<Item>) =
        VoiceMatcher.match(spoken, items) { it.aliases + it.name }.map { it.id }

    @Test
    fun `건물 정식 명칭으로 확정`() {
        assertEquals(listOf("suwon"), ids("수원대 ICT관", buildings))
        assertEquals(listOf("library"), ids("도서관", buildings))
    }

    @Test
    fun `건물 별칭으로 확정`() {
        assertEquals(listOf("future"), ids("미래관", buildings))
        assertEquals(listOf("suwon"), ids("아이시티관", buildings))
    }

    @Test
    fun `건물 부분 일치`() {
        assertEquals(listOf("suwon"), ids("수원대", buildings))
    }

    @Test
    fun `층 한글 발음으로 확정 - STT는 숫자를 한글로 인식하는 경우가 많다`() {
        assertEquals(listOf("f4"), ids("사층", floors))
        assertEquals(listOf("f1"), ids("일층", floors))
    }

    @Test
    fun `층 숫자 표기로 확정`() {
        assertEquals(listOf("f4"), ids("4층", floors))
    }

    @Test
    fun `뒤에 붙은 조사를 지운다`() {
        assertEquals(listOf("library"), ids("도서관으로", buildings))
        assertEquals(listOf("f4"), ids("4층으로", floors))
    }

    @Test
    fun `못 알아들으면 빈 목록`() {
        assertEquals(emptyList<String>(), ids("아무거나", buildings))
    }
}
