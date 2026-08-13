package org.mcsmtp.wayfinder.mock

import android.content.Context
import com.google.gson.Gson
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

    fun destinations(): DestinationResponse =
        gson.fromJson(readAsset("destinations.json"), DestinationResponse::class.java)

    fun route(): Route =
        gson.fromJson(readAsset("route.json"), Route::class.java)

    fun navigationEvents(): NavEventList =
        gson.fromJson(readAsset("navigation_events.json"), NavEventList::class.java)

    /** 매칭은 [DestinationMatcher]에 위임한다. Context가 필요 없어 유닛 테스트로 검증된다. */
    fun match(spoken: String, all: List<Destination>): List<Destination> =
        DestinationMatcher.match(spoken, all)
}
