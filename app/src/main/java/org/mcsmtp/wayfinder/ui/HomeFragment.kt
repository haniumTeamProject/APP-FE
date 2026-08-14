package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.state.NavState

/**
 * ① 홈.
 *
 * 시안(Figma 02)이 타일 2개를 쓰므로 포커스 요소도 2개다.
 * 기획의 "포커스 요소 1개" 규칙과는 어긋나며, 그 규칙을 되살릴 때는 [목적지 목록] 타일을 지운다.
 * 두 타일 모두 ② 목적지로 가고, 그 화면에 마이크와 목록이 함께 있다.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val speak = view.findViewById<View>(R.id.btn_speak)
        val list = view.findViewById<View>(R.id.btn_list)

        val toDestination = View.OnClickListener { act.machine.transition(NavState.LISTENING) }
        speak.setOnClickListener(toDestination)
        list.setOnClickListener(toDestination)

        act.moveAccessibilityFocus(speak)
        // 현재 건물·층을 먼저 알린다. 1차는 고정값이지만, 사용자가 위치를 확인할 유일한 창구다.
        act.speech.speak(
            view,
            getString(
                R.string.home_enter,
                getString(R.string.home_building),
                getString(R.string.home_floor_short),
            ),
        )
    }
}
