package org.mcsmtp.wayfinder.ble;

/**
 * 서버 주소. 여기 한 곳만 바꾸면 된다.
 *
 * 사용자앱이 쓰는 통로는 `/ws/navigation` 하나다. `/ws` 는 붙어 있는 전부에게
 * 뿌리는 실측·모니터용이라 성격이 반대다 — 거기 붙으면 남의 RSSI 를 초당 수십 개씩
 * 받아 버려야 하고, 반대로 이 앱의 메시지가 모니터로 샌다.
 */
public final class ServerConfig {

    private ServerConfig() {}

    /**
     * **경로가 `/ws` 가 아니라 `/ws/navigation` 이다.**
     *
     * `/ws` 는 붙어 있는 전부에게 뿌리는 실측·모니터용이라, 거기 붙으면 남의 RSSI 를
     * 초당 수십 개씩 받아 버려야 하고 반대로 이 앱의 메시지가 모니터로 샌다.
     */
    public static final String NAVIGATION_URL = "wss://hanium.mcsmtp.org/ws/navigation";
}
//
//wss://hanium.mcsmtp.org/ws/navigation