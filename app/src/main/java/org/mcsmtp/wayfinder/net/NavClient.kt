package org.mcsmtp.wayfinder.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 서버와의 유일한 통로. `/ws/navigation` 규약을 그대로 말한다.
 *
 * 규약은 `backend-python/docs/사용자앱_API_명세.md` 다.
 *
 * ── 앱은 판단하지 않는다 ──────────────────────────────────────────
 *
 * 목적지를 고르는 것도, 경로를 만드는 것도, 언제 무엇을 말할지도 전부 서버가 한다.
 * 이 클래스는 **받아적은 말과 스캔된 비콘을 올리고, 내려온 것을 그대로 실행**할 뿐이다.
 *
 * 그래서 서버 메시지는 모양이 하나다. `event` 로 분기하지 않아도 동작한다.
 *
 *     utterance    읽을 문장. null 이면 아무 말도 하지 않는다
 *     listenAfter  true 면 **발화가 끝난 뒤** 마이크를 연다
 *     haptic       진동 패턴
 *     state        보여줄 화면
 *     screen       화면에 띄울 것
 *
 * 이렇게 두는 이유는 고칠 곳을 하나로 모으기 위해서다. 안내 문구나 매칭 규칙이
 * 앱에 있으면 손볼 때마다 다시 빌드해 배포하고 사용자가 업데이트하기를 기다려야 한다.
 */
class NavClient(private val serverUrl: String) {

    /** 서버가 내려준 메시지 하나. 명세 §2 의 모양 그대로. */
    data class ServerMessage(
        val event: String,
        val state: String,
        val utterance: String?,
        val listenAfter: Boolean,
        val haptic: String?,
        val screen: Screen?,
        val sessionId: String?,
        val requestId: String?,
    )

    data class Screen(
        val title: String?,
        val items: List<Item>,
        val step: Int?,
        val totalSteps: Int?,
    )

    data class Item(val id: String, val name: String)

    fun interface Listener {
        fun onMessage(msg: ServerMessage)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()

    private var webSocket: WebSocket? = null
    private var sessionId: String? = null

    fun addListener(l: Listener) = synchronized(listeners) { listeners += l }
    fun removeListener(l: Listener) = synchronized(listeners) { listeners -= l }

    // ---- 연결 ----------------------------------------------------------
    fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "연결됨 $serverUrl")
                // 끊겼다 붙은 것이면 서버에 현재 상태를 다시 달라고 한다.
                sessionId?.let { send(JSONObject().put("event", "resume").put("sessionId", it)) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "← $text")
                parse(text)?.let { msg ->
                    msg.sessionId?.let { sessionId = it }
                    // 화면을 건드리는 콜백이므로 메인 스레드로 넘긴다.
                    main.post { synchronized(listeners) { listeners.toList() }.forEach { it.onMessage(msg) } }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "연결 실패: ${t.message}")
                webSocket = null
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "닫힘: $reason")
                webSocket = null
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "정상 종료")
        webSocket = null
    }

    val isConnected: Boolean get() = webSocket != null

    // ---- 앱 → 서버 (명세 §1) ---------------------------------------------

    /**
     * 목적지를 말했다. **첫 발화와 되묻기 답변이 같은 이벤트다** —
     * 지금 되묻기에 답하는 중인지 앱은 몰라도 된다. 문맥은 서버가 들고 있다.
     */
    fun sendDestination(text: String, requestId: String? = null) {
        send(JSONObject().put("event", "destination").put("text", text)
            .apply { requestId?.let { put("requestId", it) } })
    }

    /** 목록에서 터치로 골랐다. id 를 보내면 서버가 해석을 건너뛴다. */
    fun sendDestinationId(id: String) {
        send(JSONObject().put("event", "destination").put("id", id))
    }

    /**
     * 비콘 하나를 관측했다. **스캔될 때마다 즉시 보낸다.**
     *
     * 묶어 보내지 않는 이유: 실측에서 비콘당 표본 간격이 87ms 였고 서버의 위치
     * 판정이 그 밀도에 맞춰 튜닝돼 있다. 1초로 묶으면 2.5초 판정 창에 표본이
     * 29개에서 2~3개로 줄어 판정이 무너진다. 게다가 누적 맵을 반복 전송하면
     * 아직 재스캔되지 않은 비콘의 옛 값이 새 측정으로 들어가 톱니 파형이 생긴다.
     *
     * `mac` 은 서버가 **건물을 가리는 데만** 쓴다 — major 는 층 번호일 뿐
     * 건물을 담지 않아서, A동 4층과 B동 4층이 둘 다 major=104 다.
     */
    fun sendBeacon(major: Int, minor: Int, rssi: Int, mac: String?, name: String?) {
        val one = JSONObject()
            .put("major", major).put("minor", minor).put("rssi", rssi)
        mac?.let { one.put("mac", it) }
        name?.let { one.put("name", it) }
        send(JSONObject()
            .put("event", "beacons")
            .put("ts", System.currentTimeMillis())
            .put("beacons", JSONArray().put(one)))
    }

    /** 음성이 안 될 때 화면에서 고르려고 목록을 청한다. */
    fun sendList() = send(JSONObject().put("event", "list"))

    /** 그만. 되묻기와 진행 중인 경로를 모두 버린다. */
    fun sendCancel() = send(JSONObject().put("event", "cancel"))

    private fun send(payload: JSONObject) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "연결이 없어 못 보냄: $payload")
            return
        }
        if (payload.optString("event") != "beacons") Log.d(TAG, "→ $payload")
        ws.send(payload.toString())
    }

    // ---- 파싱 ------------------------------------------------------------
    private fun parse(text: String): ServerMessage? = try {
        val o = JSONObject(text)
        ServerMessage(
            event = o.optString("event"),
            state = o.optString("state"),
            utterance = o.stringOrNull("utterance"),
            listenAfter = o.optBoolean("listenAfter", false),
            haptic = o.stringOrNull("haptic"),
            screen = o.optJSONObject("screen")?.let { s ->
                Screen(
                    title = s.stringOrNull("title"),
                    items = s.optJSONArray("items")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let {
                                Item(it.optString("id"), it.optString("name"))
                            }
                        }
                    } ?: emptyList(),
                    step = s.intOrNull("step"),
                    totalSteps = s.intOrNull("totalSteps"),
                )
            },
            sessionId = o.stringOrNull("sessionId"),
            requestId = o.stringOrNull("requestId"),
        )
    } catch (e: Exception) {
        Log.e(TAG, "메시지를 읽지 못함: $text", e)
        null
    }

    private companion object {
        const val TAG = "NavClient"

        /**
         * JSON null 을 Kotlin null 로 받는다.
         *
         * **`optString(name, null)` 을 그냥 쓰면 안 된다.** 안드로이드 org.json 은
         * JSON null 에 대해 문자열 `"null"` 을 돌려준다 — Kotlin null 이 아니다.
         * 그대로 두면 `utterance != null` 이 참이 되어 앱이 "널" 이라고 읽는다.
         * haptic 도 마찬가지로 진동 패턴 이름이 "null" 이 된다.
         */
        fun JSONObject.stringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

        fun JSONObject.intOrNull(name: String): Int? =
            if (isNull(name)) null else optInt(name).takeIf { has(name) }
    }
}
