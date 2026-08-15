package org.mcsmtp.wayfinder.state

/**
 * 앱의 상태. 이 앱은 화면이 아니라 상태로 설계한다.
 * 사용자가 화면을 보지 않으므로 "지금 어떤 상태이고 그것을 어떻게 알리나"가 설계의 본체다.
 *
 * 1차 개발 범위는 아래 6개다.
 * 2차: DEVIATED · SIGNAL_LOST · LOCATION_UNKNOWN · DISAMBIGUATE · CONFIRM
 */
enum class NavState {
    /**
     * 건물 선택 — 앱 진입점.
     * 1차는 사용자가 직접 고른다. 2차에서 BLE major 로 자동 인식되면 이 단계를 건너뛴다.
     */
    SELECTING_PLACE,

    /**
     * 층 선택. 고른 건물의 층을 고른다.
     * 층이 하나뿐인 건물은 이 단계를 자동으로 건너뛰고 곧장 목적지 선택으로 간다.
     */
    SELECTING_FLOOR,

    /** 목적지 선택(음성 입력) — STT 동작, 별칭 매칭. 건물·층을 고르면 곧바로 이리로 온다. */
    LISTENING,

    /** 경로 계산 — 서버(현재는 목 데이터)에 경로 요청 */
    ROUTING,

    /** 안내 중 — RSSI 전송, 비콘 전환마다 발화 */
    NAVIGATING,

    /** 도착 — 문 위치 안내 */
    ARRIVED,
}
