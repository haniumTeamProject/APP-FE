package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.NavEvent
import org.mcsmtp.wayfinder.state.NavState

/**
 * ③ 안내.
 *
 * 현재는 assets/mock/navigation_events.json 을 intervalMs 간격으로 재생해
 * 서버·BLE 없이 안내 흐름 전체를 재현한다.
 * 8단계에서 이 재생 루프를 WebSocket 수신으로 교체한다.
 */
class NavigationFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var events: List<NavEvent> = emptyList()
    private var index = 0
    private var intervalMs = 1000L

    private var instructionView: TextView? = null
    private var debugView: TextView? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_navigation, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity

        // 화면이 꺼지면 흔들기·버튼이 동작하지 않는다.
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        instructionView = view.findViewById(R.id.instruction_text)
        debugView = view.findViewById(R.id.beacon_debug)
        val replay = view.findViewById<Button>(R.id.btn_replay)
        val stop = view.findViewById<Button>(R.id.btn_stop)

        replay.setOnClickListener { act.speech.replayLast(view) }
        stop.setOnClickListener { act.confirmStop() }

        act.moveAccessibilityFocus(replay)

        // ROUTING: 경로 요청. 1초 이내면 알릴 필요가 없으므로 발화하지 않는다.
        instructionView?.setText(R.string.nav_routing)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            startNavigation(act, view)
        }, 600)
    }

    private fun startNavigation(act: MainActivity, root: View) {
        val route = runCatching { act.api.route() }.getOrNull()
        if (route == null) {
            act.speech.speak(root, getString(R.string.err_route_not_found))
            act.machine.reset()
            return
        }
        act.route = route
        act.machine.transition(NavState.NAVIGATING)

        val payload = runCatching { act.api.navigationEvents() }.getOrNull()
        events = payload?.events ?: emptyList()
        intervalMs = payload?.intervalMs ?: 1000L
        index = 0
        handler.post(tick)
    }

    /** 목 이벤트를 하나씩 흘려보낸다. 실제 서버가 붙으면 WebSocket onMessage 로 대체된다. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded || index >= events.size) return
            val act = requireActivity() as MainActivity
            val root = view ?: return
            val e = events[index++]

            debugView?.text = buildString {
                append(e.currentBeaconId ?: "-")
                e.nextBeaconId?.let { append(" → ").append(it) }
            }

            // utterance가 null이면 아무 말도 하지 않는다. 발화 억제는 서버가 판단해 내려준다.
            e.utterance?.let {
                instructionView?.text = it
                act.haptics.playByName(e.haptic)
                act.speech.speak(root, it)
            }

            if (e.event == "arrive") {
                act.machine.transition(NavState.ARRIVED)
                return
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        instructionView = null
        debugView = null
        super.onDestroyView()
    }
}
