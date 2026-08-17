package org.mcsmtp.wayfinder.nav

import android.util.Log
import org.mcsmtp.wayfinder.net.NavClient
import org.mcsmtp.wayfinder.speech.SpeechOutput
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.state.NavStateMachine
import org.mcsmtp.wayfinder.util.Haptics

/**
 * 서버가 내려준 메시지 하나를 그대로 실행한다. **앱의 판단은 여기에도 없다.**
 *
 * 하는 일이 네 가지뿐이다(`docs/사용자앱_API_명세.md` §0).
 *
 *     1. 말한다   utterance 를 읽는다
 *     2. 듣는다   listenAfter 면 발화가 끝난 뒤 마이크를 연다
 *     3. 떤다     haptic 대로 진동한다
 *     4. 보낸다   받아적은 말과 스캔된 비콘을 올린다
 *
 * 예전에는 목적지 매칭(`DestinationMatcher`)과 경로 재생(`MockApi.navigationEvents`)이
 * 앱에 있었다. 그러면 안내 문구 하나를 고칠 때마다 앱을 다시 빌드해 배포해야 하고,
 * 무엇보다 **앱에 둔 판단은 조용히 틀린다** — 후보가 여럿일 때 말없이 첫 번째를
 * 고르던 코드가 그랬다. 화면을 볼 수 없는 사용자는 잘못 간 것을 알 방법이 없다.
 */
class NavCoordinator(
    private val client: NavClient,
    private val speech: SpeechOutput,
    private val haptics: Haptics,
    private val machine: NavStateMachine,
) {

    /** 화면들이 이걸 구독해서 그린다. 서버가 준 마지막 화면 정보. */
    fun interface ScreenListener {
        fun onScreen(msg: NavClient.ServerMessage)
    }

    /** 마이크를 열어야 할 때 불린다. STT 는 화면(Fragment)이 들고 있다. */
    fun interface MicListener {
        fun openMic()
    }

    var lastMessage: NavClient.ServerMessage? = null
        private set

    private val screenListeners = mutableListOf<ScreenListener>()
    private var micListener: MicListener? = null

    /**
     * 마이크를 열어달라는 요청이 밀려 있는가.
     *
     * 서버는 연결 직후 "목적지를 말씀해 주세요"(listenAfter=true)를 보내는데,
     * 그때 화면은 아직 안 붙어 있다. 그 신호를 그냥 흘리면 **마이크가 영영 안 열린다** —
     * 화면에는 "듣고 있어요"만 뜨고 아무 일도 안 일어난다.
     */
    private var micPending = false

    fun addScreenListener(l: ScreenListener) {
        screenListeners += l
        lastMessage?.let { l.onScreen(it) }   // 늦게 붙어도 지금 상태를 받는다
    }

    fun removeScreenListener(l: ScreenListener) {
        screenListeners -= l
    }

    fun setMicListener(l: MicListener?) {
        micListener = l
        // 늦게 붙었으면 밀려 있던 요청을 지금 처리한다.
        if (l != null && micPending) {
            micPending = false
            Log.d(TAG, "밀려 있던 마이크 요청을 처리")
            l.openMic()
        }
    }

    private val listener = NavClient.Listener { msg -> apply(msg) }

    fun start() = client.addListener(listener)
    fun stop() = client.removeListener(listener)

    /** 서버 메시지 하나를 실행한다. */
    private fun apply(msg: NavClient.ServerMessage) {
        Log.d(TAG, "실행: ${msg.event} state=${msg.state} 말=${msg.utterance != null}")
        lastMessage = msg

        haptics.playByName(msg.haptic)
        toNavState(msg.state)?.let { machine.transition(it) }
        screenListeners.toList().forEach { it.onScreen(msg) }

        // **마이크는 발화가 끝난 뒤에 연다.** 읽는 도중에 열면 자기 TTS 를
        // 그대로 받아적어서 무한 루프가 된다. 타이머로 때려맞히면 안 되고
        // 완료 콜백(speakThen)을 써야 한다.
        //
        // utterance 가 null 이어도 speakThen 이 곧바로 콜백을 부르므로
        // listenAfter 만 온 경우도 그대로 처리된다.
        speech.speakThen(msg.utterance) {
            if (!msg.listenAfter) return@speakThen
            val l = micListener
            if (l == null) {
                // 화면이 아직 안 붙었다. 붙는 즉시 처리하도록 남겨둔다.
                micPending = true
                Log.d(TAG, "마이크를 열 화면이 아직 없다 — 보류")
            } else {
                l.openMic()
            }
        }
    }

    // ---- 앱 → 서버 ----
    fun say(text: String) = client.sendDestination(text)
    fun pick(id: String) = client.sendDestinationId(id)
    fun requestList() = client.sendList()
    fun cancel() = client.sendCancel()

    private companion object {
        const val TAG = "NavCoordinator"

        /**
         * 서버가 준 상태를 앱 화면에 맞춘다.
         *
         * `ready`·`listening` 이 둘 다 LISTENING 인 이유: 앱에서는 "목적지를
         * 기다리는 화면" 하나다. 되묻는 중인지 처음인지는 화면이 구분할 필요가 없다 —
         * 어느 쪽이든 사용자는 말하면 되고, 문맥은 서버가 들고 있다.
         */
        fun toNavState(state: String): NavState? = when (state) {
            "ready", "listening" -> NavState.LISTENING
            "navigating" -> NavState.NAVIGATING
            "arrived" -> NavState.ARRIVED
            else -> null
        }
    }
}
