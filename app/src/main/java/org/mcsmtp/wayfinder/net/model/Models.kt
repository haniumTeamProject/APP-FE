package org.mcsmtp.wayfinder.net.model

import com.google.gson.annotations.SerializedName

// ─── GET /api/buildings ───────────────────────────────────────────────

/**
 * 건물·층 목록. 이 서비스는 수원대 ICT관 한 곳이 아니라 여러 건물을 다룬다.
 * 1차는 사용자가 직접 건물·층을 고르고, 2차에서 BLE major(=100+층)로 자동 인식한다.
 */
data class BuildingResponse(
    val buildings: List<Building> = emptyList(),
)

data class Building(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),  // 음성 매칭용 별칭
    val floors: List<Floor> = emptyList(),
)

data class Floor(
    val id: String,          // floorId — 목적지·경로 요청의 키
    val floor: Int,          // 층 번호
    val name: String,        // "4층"
    val aliases: List<String> = emptyList(),  // 음성 매칭용 별칭("사층","4" 등)
    val major: Int? = null,  // 100 + 층. 2차 자동 인식에 쓴다
    val destinationCount: Int = 0,
)

// ─── GET /api/floors/{floorId}/destinations ───────────────────────────

data class DestinationResponse(
    val floorId: String,
    val floorName: String,
    val destinations: List<Destination>,
)

/**
 * @param aliases 음성 매칭용 별칭. "화1" 같은 내부 명칭과 사용자가 실제로 말하는
 *                "화장실"을 잇는 장치다. LLM 없이 매칭이 가능한 근거이기도 하다.
 * @param doorSide 도착 후 "문은 오른쪽에 있습니다" 안내에 쓴다. left / right / null
 */
data class Destination(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val type: String? = null,
    val doorSide: String? = null,
)

// ─── POST /api/route ──────────────────────────────────────────────────

data class Route(
    val routeId: String,
    val floorId: String? = null,
    val fromBeaconId: String? = null,
    val toDestinationId: String? = null,
    val toDestinationName: String? = null,
    val totalDistanceM: Double = 0.0,
    val estimatedSeconds: Int = 0,
    val steps: List<RouteStep> = emptyList(),
)

/**
 * 런타임 추적 단위는 경로노드가 아니라 **비콘**이다.
 * 경로노드는 서버의 경로 계산에만 쓰이고 응답에 나오지 않는다.
 *
 * @param template 어떤 규칙으로 만들어진 문장인지. 진동 패턴 선택에 쓴다.
 * @param instruction 서버가 완성한 발화 문장. null이면 무음 구간이다.
 */
data class RouteStep(
    val seq: Int,
    val beaconId: String,
    val turn: String? = null,
    val template: String? = null,
    val instruction: String? = null,
    @SerializedName("isArrival") val isArrival: Boolean = false,
)

// ─── WS /ws/navigation ────────────────────────────────────────────────

data class NavEventList(
    val routeId: String? = null,
    val intervalMs: Long = 1000,
    val events: List<NavEvent> = emptyList(),
)

/**
 * @param event none / advance / back / deviate / arrive
 * @param utterance null이면 앱은 아무 말도 하지 않는다.
 *                  같은 문장을 반복 발화하면 사용자가 매우 괴로우므로
 *                  발화 억제는 서버가 판단해 이 필드로 내려준다.
 * @param haptic guide / warn / arrive / null
 */
data class NavEvent(
    val currentStep: Int = 0,
    val currentBeaconId: String? = null,
    val nextBeaconId: String? = null,
    val progress: Float = 0f,
    val event: String = "none",
    val utterance: String? = null,
    val haptic: String? = null,
)
