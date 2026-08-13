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
import org.mcsmtp.wayfinder.util.TextFormat

/**
 * ④ 도착.
 *
 * 도착 발화 후 바로 앱을 닫지 않는다. 사용자가 아직 문을 찾지 못했을 수 있다.
 */
class ArrivalFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_arrival, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val text = view.findViewById<TextView>(R.id.arrival_text)
        val hint = view.findViewById<TextView>(R.id.arrival_hint)
        val newDest = view.findViewById<View>(R.id.btn_new_dest)
        val end = view.findViewById<View>(R.id.btn_end)

        // 시안은 목적지와 문 방향을 두 줄로 나눈다. 큰 줄은 어디에 왔는지, 작은 줄은 다음 행동이다.
        val dest = act.destination
        text.text = TextFormat.guidance(
            dest?.let { "${it.name}에\n도착했습니다" } ?: act.speech.lastUtterance.orEmpty()
        )

        val door = when (dest?.doorSide) {
            "left" -> getString(R.string.arrive_door_left)
            "right" -> getString(R.string.arrive_door_right)
            else -> null
        }
        if (door == null) hint.visibility = View.GONE else hint.text = door

        act.haptics.play(Haptics.Pattern.ARRIVE)

        // [새 목적지]는 홈으로 돌아가되 곧바로 음성 입력을 시작해 한 단계를 줄인다.
        newDest.setOnClickListener {
            act.destination = null
            act.route = null
            act.machine.transition(NavState.LISTENING)
        }
        end.setOnClickListener { act.stopNavigation() }

        act.moveAccessibilityFocus(newDest)
    }
}
