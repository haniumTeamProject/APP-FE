package org.mcsmtp.wayfinder.mock

import android.content.Context
import com.google.gson.Gson
import org.mcsmtp.wayfinder.net.model.BuildingResponse
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.net.model.DestinationResponse
import org.mcsmtp.wayfinder.net.model.NavEventList
import org.mcsmtp.wayfinder.net.model.Route

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

    /** GET /api/buildings — 건물·층 목록. */
    fun buildings(): BuildingResponse =
        gson.fromJson(readAsset("buildings.json"), BuildingResponse::class.java)

    /**
     * GET /api/floors/{floorId}/destinations.
     * 층마다 파일이 하나씩 있다. 실제 서버가 붙으면 floorId 로 요청만 바꾸면 된다.
     */
    fun destinations(floorId: String): DestinationResponse =
        gson.fromJson(readAsset("destinations_$floorId.json"), DestinationResponse::class.java)

    /**
     * 실제 서버는 목적지마다 다른 경로를 계산해 준다.
     * 목 데이터는 경로가 하나뿐이므로, 최소한 **목적지 이름과 문 방향**은 선택값으로 바꿔 준다.
     * 그렇지 않으면 무엇을 골라도 409호로 안내되어 화면과 발화가 어긋난다.
     */
    fun route(dest: Destination?): Route {
        val route = gson.fromJson(readAsset("route.json"), Route::class.java)
        return if (dest == null) route
        else route.copy(toDestinationId = dest.id, toDestinationName = dest.name)
    }

    fun navigationEvents(dest: Destination?): NavEventList {
        val payload = gson.fromJson(readAsset("navigation_events.json"), NavEventList::class.java)
        if (dest == null) return payload

        // 목적지 이름이 들어가는 문장(출발)만 갈아 끼운다. 중간 회전·직진 안내는
        // 경로 모양에 따른 것이라 목 데이터의 예시 경로를 그대로 재생한다.
        // 도착 발화는 재생 루프가 아니라 도착 화면이 맡으므로 여기서 다루지 않는다.
        val events = payload.events.map { e ->
            val u = e.utterance
            if (u != null && u.contains("안내합니다")) {
                e.copy(utterance = "${dest.name}로 안내합니다. 손이 닿는 벽을 짚고 걸어주세요.")
            } else {
                e
            }
        }
        return payload.copy(events = events)
    }

    /** 매칭은 [DestinationMatcher]에 위임한다. Context가 필요 없어 유닛 테스트로 검증된다. */
    fun match(spoken: String, all: List<Destination>): List<Destination> =
        DestinationMatcher.match(spoken, all)
}
