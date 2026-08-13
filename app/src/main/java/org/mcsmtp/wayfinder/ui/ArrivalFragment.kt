package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.util.Haptics

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
        val newDest = view.findViewById<Button>(R.id.btn_new_dest)
        val end = view.findViewById<Button>(R.id.btn_end)

        val message = act.speech.lastUtterance
            ?: act.destination?.let { d ->
                val side = when (d.doorSide) {
                    "left" -> "왼쪽"; "right" -> "오른쪽"; else -> null
                }
                if (side != null) "${d.name}입니다. 문은 ${side}에 있습니다." else "${d.name}입니다."
            }
            ?: ""
        text.text = message

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
