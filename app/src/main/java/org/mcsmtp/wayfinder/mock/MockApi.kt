package org.mcsmtp.wayfinder.mock

import android.content.Context
import com.google.gson.Gson
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.net.model.DestinationResponse
import org.mcsmtp.wayfinder.net.model.NavEventList
import org.mcsmtp.wayfinder.net.model.Route
import java.util.Locale

/**
 * assets 의 mock 폴더에 있는 JSON을 읽어 서버 응답을 흉내 낸다.
 *
 * API가 확정되기 전에도 화면·상태 흐름을 끝까지 만들 수 있게 하는 것이 목적이다.
 * 실제 서버가 붙으면 이 클래스를 ApiClient 로 교체하면 되고, 나머지 코드는 그대로 둔다.
 */
class MockApi(private val context: Context) {

    private val gson = Gson()

    private fun readAsset(name: String): String =
        context.assets.open("mock/$name").bufferedReader().use { it.readText() }

    fun destinations(): DestinationResponse =
        gson.fromJson(readAsset("destinations.json"), DestinationResponse::class.java)

    fun route(): Route =
        gson.fromJson(readAsset("route.json"), Route::class.java)

    fun navigationEvents(): NavEventList =
        gson.fromJson(readAsset("navigation_events.json"), NavEventList::class.java)

    /**
     * 음성 인식 결과를 목적지에 매칭한다.
     *
     * 층당 목적지가 24개 남짓이라 온디바이스에서 즉시 처리된다.
     * 목적지 후보가 소수의 고정 목록이라는 점이 LLM을 쓰지 않기로 한 근거다.
     *
     * @return 후보 목록. 1개면 확정, 여러 개면 되물어야 하고(2차), 0개면 재입력을 유도한다.
     */
    fun match(spoken: String, all: List<Destination>): List<Destination> {
        val q = normalize(spoken)
        if (q.isEmpty()) return emptyList()

        // 1) 정식 명칭 또는 별칭과 정확히 일치
        val exact = all.filter { d ->
            normalize(d.name) == q || d.aliases.any { normalize(it) == q }
        }
        if (exact.isNotEmpty()) return exact

        // 2) 부분 일치 ("사백구호로 가줘" 처럼 조사가 붙는 경우)
        return all.filter { d ->
            val keys = d.aliases + d.name
            keys.any { k ->
                val nk = normalize(k)
                nk.isNotEmpty() && (q.contains(nk) || nk.contains(q))
            }
        }
    }

    /** 공백·문장부호를 제거하고 소문자로 맞춘다. */
    private fun normalize(s: String): String =
        s.lowercase(Locale.KOREA).replace(Regex("[\\s.,!?~·]"), "")
}
