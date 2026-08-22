package org.mcsmtp.wayfinder.ui

import android.os.Bundle
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
import org.mcsmtp.wayfinder.nav.NavCoordinator
import org.mcsmtp.wayfinder.net.NavClient
import org.mcsmtp.wayfinder.util.ShakeDetector
import org.mcsmtp.wayfinder.util.TextFormat

/**
 * ③ 안내.
 *
 * **서버가 보내주는 것을 그리기만 한다.** 이 화면은 다음 지점으로 넘어갈 때를
 * 스스로 판단하지 않는다 — 비콘이 바뀌는 시점은 서버가 정하고, 그때마다
 * 메시지가 하나씩 내려온다.
 *
 *     utterance   화면에 띄울 문장 (발화는 NavCoordinator 가 맡는다)
 *     haptic      "warn" 이면 위험 표시로 바꾼다
 *     screen      step / totalSteps — 진행 점
 *
 * 예전에는 목 이벤트 목록을 타이머로 1초마다 재생했다(`tick`). 실제 위치와 무관하게
 * 시간만 흐르면 다음 지점으로 넘어가는 방식이라 서버가 붙으면서 걷어냈는데,
 * **그 일을 아무도 넘겨받지 않아 화면이 통째로 멈춰 있었다.** 진입할 때 한 번 읽은
 * "경로를 찾는 중"이 도착할 때까지 그대로 떠 있고, 진행 점도 안 차고, 경로를
 * 벗어나도 상자가 안 붉어졌다. 소리만 나가고 화면은 죽은 상태였다.
 *
 * 화면은 Figma 「09 안내 중」이 기본이고, 위험 신호가 오면 「10 위험 경고」로 바뀐다.
 * 두 프레임은 같은 화면의 상태 차이라 배경과 아이콘만 갈아끼운다.
 */
class NavigationFragment : Fragment() {

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

        // 등록하는 즉시 서버가 보낸 마지막 메시지를 한 번 받는다(addScreenListener).
        // 화면이 붙기 전에 온 안내 시작 메시지를 놓치지 않으려는 것이다.
        act.nav.addScreenListener(screenListener)
    }

    /**
     * 서버 메시지 하나를 화면에 반영한다. **여기서 말하지 않는다.**
     *
     * 발화와 진동은 `NavCoordinator` 가 이미 했다. 화면이 또 말하면 서로 끊어먹는다
     * (도착 화면에서 실제로 그랬다 — `speak()` 가 QUEUE_FLUSH 라 서버 문장이 잘렸다).
     */
    private val screenListener = NavCoordinator.ScreenListener { msg -> draw(msg) }

    private fun draw(msg: NavClient.ServerMessage) {
        if (!isAdded) return
        val screen = msg.screen

        // 칸 수는 안내가 시작될 때 한 번 온다. 목적지를 바꾸면 다시 온다.
        screen?.totalSteps?.let {
            if (it != totalSteps) {
                totalSteps = it
                buildDots(it)
            }
        }
        screen?.step?.let { step ->
            fillDots(step)
            if (totalSteps > 0 && step > 0) {
                progressTitle?.text = getString(R.string.nav_progress, totalSteps, step)
            }
        }

        // **문장과 상자 색을 함께 바꾼다.**
        //
        // utterance 가 null 인 칸이 있다 — 할 말이 없으면 서버가 무음으로 내려보낸다.
        // 그때 글씨를 지우면 화면이 빈 채로 남고, 색만 되돌리면 상자에 적힌 문장과
        // 색이 서로 다른 안내를 가리키게 된다. 그래서 둘 다 그대로 둔다.
        msg.utterance?.let {
            instructionView?.text = TextFormat.guidance(it)
            markDanger(msg.haptic == "warn")
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
        (activity as? MainActivity)?.nav?.removeScreenListener(screenListener)
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
