package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.util.Haptics
import org.mcsmtp.wayfinder.util.ShakeDetector
import org.mcsmtp.wayfinder.util.TextFormat

/**
 * ④ 도착.
 *
 * 도착 발화 후 바로 앱을 닫지 않는다. 사용자가 아직 문을 찾지 못했을 수 있다.
 * 그동안 도착 안내를 놓쳤을 수 있으므로, 안내 화면과 똑같이 흔들기로 다시 듣게 한다.
 */
class ArrivalFragment : Fragment() {

    private var shake: ShakeDetector? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_arrival, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val text = view.findViewById<TextView>(R.id.arrival_text)
        val hint = view.findViewById<TextView>(R.id.arrival_hint)
        val newDest = view.findViewById<View>(R.id.btn_new_dest)
        val end = view.findViewById<View>(R.id.btn_end)

        val dest = act.destination
        text.text = TextFormat.guidance(
            dest?.let { "${it.name}에\n도착했습니다" } ?: act.speech.lastUtterance.orEmpty()
        )

        // 문 방향(왼쪽/오른쪽)은 안내하지 않는다. 실내 위치 정확도가 문 좌·우까지
        // 짚어줄 만큼은 아니라, 틀린 방향을 확신에 차 말하면 오히려 사용자를 헤매게 한다.
        hint.visibility = View.GONE

        act.haptics.play(Haptics.Pattern.ARRIVE)

        // 도착 안내는 이 화면이 맡는다. 재생 루프가 아니라 여기서 말해야
        // 화면과 발화가 함께 나온다. [다시 듣기]가 재생할 마지막 문장도 이걸로 잡힌다.
        // 목적지 이름이 없을 때는 "에 도착"으로 시작하지 않게 문장 자체를 바꾼다.
        val arriveMsg = dest?.name?.let { "${it}에 도착했습니다." } ?: "도착했습니다."
        act.speech.speak(view, arriveMsg)

        // 도착 안내를 놓쳤을 때 다시 듣는 유일한 수단. 안내 화면과 동일하게 흔들기로 재생한다.
        // (도착 화면엔 [다시 듣기] 버튼이 없고 버튼은 [새 목적지]·[처음으로]뿐이다.)
        shake = ShakeDetector(requireContext()).also { d ->
            d.start { if (isAdded) act.speech.replayLast(view) }
        }

        // [새 목적지]는 홈으로 돌아가되 곧바로 음성 입력을 시작해 한 단계를 줄인다.
        newDest.setOnClickListener {
            act.destination = null
            act.route = null
            act.machine.transition(NavState.LISTENING)
        }
        end.setOnClickListener { act.stopNavigation() }

        act.moveAccessibilityFocus(newDest)
    }

    override fun onDestroyView() {
        shake?.stop()
        shake = null
        super.onDestroyView()
    }
}
