package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.NavEvent
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.util.ShakeDetector
import org.mcsmtp.wayfinder.util.TextFormat

/**
 * ③ 안내.
 *
 * 현재는 assets 의 mock 폴더에 있는 navigation_events.json 을 intervalMs 간격으로 재생해
 * 서버·BLE 없이 안내 흐름 전체를 재현한다.
 * 8단계에서 이 재생 루프를 WebSocket 수신으로 교체한다.
 *
 * 화면은 Figma 「09 안내 중」이 기본이고, 위험 신호가 오면 「10 위험 경고」로 바뀐다.
 * 두 프레임은 같은 화면의 상태 차이라 배경과 아이콘만 갈아끼운다.
 */
class NavigationFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var events: List<NavEvent> = emptyList()
    private var index = 0
    private var intervalMs = 1000L
    private var totalSteps = 0

    private var shake: ShakeDetector? = null
    private var navCard: View? = null
    private var navIcon: ImageView? = null
    private var instructionView: TextView? = null
    private var progressTitle: TextView? = null
    private var dots: LinearLayout? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_navigation, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity

        // 화면이 꺼지면 흔들기·버튼이 동작하지 않는다.
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        navCard = view.findViewById(R.id.nav_card)
        navIcon = view.findViewById(R.id.nav_icon)
        instructionView = view.findViewById(R.id.instruction_text)
        progressTitle = view.findViewById(R.id.beacon_debug)
        dots = view.findViewById(R.id.progress_dots)

        val replay = view.findViewById<View>(R.id.btn_replay)
        val stop = view.findViewById<View>(R.id.btn_stop)

        replay.setOnClickListener { act.speech.replayLast(view) }

        // 흔들기도 [다시 듣기]와 동일하게 동작한다.
        // 걸으면서 화면을 보지 않고 쓸 수 있는 유일한 입력이다.
        shake = ShakeDetector(requireContext()).also { d ->
            d.start { if (isAdded) act.speech.replayLast(view) }
        }
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
        val route = runCatching { act.api.route(act.destination) }.getOrNull()
        if (route == null) {
            act.speech.speak(root, getString(R.string.err_route_not_found))
            act.machine.reset()
            return
        }
        act.route = route
        act.machine.transition(NavState.NAVIGATING)

        val payload = runCatching { act.api.navigationEvents(act.destination) }.getOrNull()
        events = payload?.events ?: emptyList()
        intervalMs = payload?.intervalMs ?: 1000L
        totalSteps = route.steps.size.takeIf { it > 0 } ?: events.maxOfOrNull { it.currentStep } ?: 0
        index = 0
        buildDots(totalSteps)
        handler.post(tick)
    }

    /** 목 이벤트를 하나씩 흘려보낸다. 실제 서버가 붙으면 WebSocket onMessage 로 대체된다. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded || index >= events.size) return
            val act = requireActivity() as MainActivity
            val root = view ?: return
            val e = events[index++]

            // 도착은 발화하지 않고 화면만 넘긴다. 도착 안내는 도착 화면이 맡아
            // 화면과 발화가 함께 나오게 한다. 여기서 발화하면 소리가 끝나기 전에
            // 화면이 먼저 넘어가 "말은 아직인데 화면이 바뀐" 상태가 된다.
            if (e.event == "arrive") {
                act.machine.transition(NavState.ARRIVED)
                return
            }

            fillDots(e.currentStep)
            if (totalSteps > 0 && e.currentStep > 0) {
                progressTitle?.text = getString(R.string.nav_progress, totalSteps, e.currentStep)
            }

            // utterance가 null이면 아무 말도 하지 않는다. 발화 억제는 서버가 판단해 내려준다.
            //
            // 위험 표시도 여기서만 바꾼다. 매 tick 마다 갱신하면
            // 다음 이벤트가 1초 뒤에 들어오면서 경고가 곧바로 지워진다.
            // 화면에 남아 있는 문장과 상자 색이 항상 같은 이벤트를 가리켜야 한다.
            e.utterance?.let {
                // 화면은 다듬은 문구를, 발화는 원문을 쓴다.
                instructionView?.text = TextFormat.guidance(it)
                markDanger(e.haptic == "warn")
                act.haptics.playByName(e.haptic)
                act.speech.speak(root, it)
            }

            handler.postDelayed(this, intervalMs)
        }
    }

    /** 위험 신호가 오면 상자를 분홍으로 바꾼다. 보이는 사용자를 위한 표시다. */
    private fun markDanger(danger: Boolean) {
        navCard?.setBackgroundResource(
            if (danger) R.drawable.bg_hero_danger else R.drawable.bg_hero
        )
        navIcon?.setImageResource(
            if (danger) R.drawable.ic_alert else R.drawable.ic_arrow_right
        )
    }

    private fun buildDots(count: Int) {
        val box = dots ?: return
        box.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.dot_size)
        val gap = resources.getDimensionPixelSize(R.dimen.dot_gap)
        repeat(count) { i ->
            val dot = View(requireContext())
            dot.setBackgroundResource(R.drawable.dot_off)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                if (i > 0) marginStart = gap
            }
            box.addView(dot)
        }
    }

    private fun fillDots(currentStep: Int) {
        val box = dots ?: return
        for (i in 0 until box.childCount) {
            box.getChildAt(i).setBackgroundResource(
                if (i < currentStep) R.drawable.dot_on else R.drawable.dot_off
            )
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        shake?.stop()
        shake = null
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navCard = null
        navIcon = null
        instructionView = null
        progressTitle = null
        dots = null
        super.onDestroyView()
    }
}
