package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
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

        // 선택한 건물·층을 화면에 반영한다. 하드코딩하지 않는다 — 여러 건물에 쓰이는 서비스다.
        val building = act.selectedBuilding?.name ?: getString(R.string.home_building)
        val floor = act.selectedFloor?.name ?: getString(R.string.home_floor_short)
        view.findViewById<TextView>(R.id.floor_building).text = building
        view.findViewById<TextView>(R.id.floor_level).text = floor

        val toDestination = View.OnClickListener { act.machine.transition(NavState.LISTENING) }
        speak.setOnClickListener(toDestination)
        list.setOnClickListener(toDestination)

        // 건물·층 바꾸기 — 지금 위치를 함께 보여주고, 누르면 진입 화면으로 돌아간다.
        view.findViewById<TextView>(R.id.change_place_current).text =
            getString(R.string.home_change_place_current, building, floor)
        view.findViewById<View>(R.id.btn_change_place).setOnClickListener {
            act.machine.transition(NavState.SELECTING_PLACE)
        }

        act.moveAccessibilityFocus(speak)
        // 현재 건물·층을 먼저 알린다. 사용자가 위치를 확인할 유일한 창구다.
        act.speech.speak(view, getString(R.string.home_enter, building, floor))

        // 온보딩은 1회성이므로, 조작법을 홈에서 길게 눌러 사용법 화면으로 다시 볼 수 있게 한다.
        view.setOnLongClickListener {
            act.showUsage()
            true
        }
        // TalkBack에서 길게 누르기는 발견이 어려워, 커스텀 접근성 액션으로도 노출한다.
        ViewCompat.addAccessibilityAction(
            view, getString(R.string.home_howto_action)
        ) { _, _ ->
            act.showUsage()
            true
        }
    }
}
