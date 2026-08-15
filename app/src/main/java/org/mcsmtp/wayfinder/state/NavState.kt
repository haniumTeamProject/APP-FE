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
     * 목적지 선택(음성 입력) — 앱 진입점. STT 동작.
     *
     * 건물·층은 사용자가 고르지 않는다. 비콘(UUID→건물, major→층)으로 서버가 자동 판별한다.
     * (목에서는 기본 위치로 대체.) 그래서 진입하면 곧바로 "목적지를 말씀하세요"로 시작한다.
     */
    LISTENING,

    /** 경로 계산 — 서버(현재는 목 데이터)에 경로 요청 */
    ROUTING,

    /** 안내 중 — RSSI 전송, 비콘 전환마다 발화 */
    NAVIGATING,

    /** 도착 — 문 위치 안내 */
    ARRIVED,
}
