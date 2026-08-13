package org.mcsmtp.wayfinder.state

/**
 * 앱의 상태. 이 앱은 화면이 아니라 상태로 설계한다.
 * 사용자가 화면을 보지 않으므로 "지금 어떤 상태이고 그것을 어떻게 알리나"가 설계의 본체다.
 *
 * 1차 개발 범위는 아래 5개다.
 * 2차: DEVIATED · SIGNAL_LOST · LOCATION_UNKNOWN · DISAMBIGUATE · CONFIRM
 */
enum class NavState {
    /** 대기 — "어디로 가시겠어요?" */
    READY,

    /** 음성 입력 — STT 동작, 별칭 매칭 */
    LISTENING,

    /** 경로 계산 — 서버(현재는 목 데이터)에 경로 요청 */
    ROUTING,

    /** 안내 중 — RSSI 전송, 비콘 전환마다 발화 */
    NAVIGATING,

    /** 도착 — 문 위치 안내 */
    ARRIVED,
}
