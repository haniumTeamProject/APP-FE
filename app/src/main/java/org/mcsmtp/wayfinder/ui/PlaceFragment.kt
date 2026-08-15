package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Building
import org.mcsmtp.wayfinder.state.NavState

/**
 * 건물 선택 — 앱 진입점.
 *
 * 이 서비스는 한 건물 전용이 아니라 여러 건물에 쓰인다. 1차는 자동 인식이 없으므로
 * 사용자가 건물을 먼저 고르고, 이어서 층을 고른다(건물 → 층 → 목적지).
 * 층이 하나뿐인 건물은 층 선택을 건너뛰고 곧장 목적지 선택으로 간다 — 탭을 아낀다.
 *
 * 온보딩은 1회성이라, 조작법을 잊었을 때 이 진입 화면에서 길게 눌러 사용법을 다시 본다.
 */
class PlaceFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_place, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val container = view.findViewById<LinearLayout>(R.id.place_list)

        view.findViewById<TextView>(R.id.place_title).setText(R.string.place_title)
        view.findViewById<TextView>(R.id.place_sub).setText(R.string.place_sub)

        val buildings = runCatching { act.api.buildings().buildings }.getOrElse { emptyList() }

        val inflater = LayoutInflater.from(requireContext())
        var firstRow: View? = null
        buildings.forEach { building ->
            val row = makeRow(inflater, container, act, building)
            container.addView(row)
            if (firstRow == null) firstRow = row
        }

        // 진입 화면에서 길게 눌러 사용법을 다시 볼 수 있게 한다(온보딩은 1회성이므로).
        // 아래는 스크롤 목록이라 빈 곳이 없으므로, 항상 보이는 머리말에 건다.
        // 머리말을 포커스 가능하게 만들어 TalkBack이 "사용법 듣기" 액션에 닿게 한다.
        val header = view.findViewById<View>(R.id.place_header)
        header.isFocusable = true
        header.contentDescription =
            getString(R.string.place_title) + ". " + getString(R.string.place_sub)
        header.setOnLongClickListener { act.showUsage(); true }
        ViewCompat.addAccessibilityAction(header, getString(R.string.home_howto_action)) { _, _ ->
            act.showUsage(); true
        }

        act.moveAccessibilityFocus(firstRow)
        act.speech.speak(view, getString(R.string.place_enter))
    }

    private fun makeRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        act: MainActivity,
        building: Building,
    ): View = inflater.inflate(R.layout.item_destination, parent, false).apply {
        findViewById<TextView>(R.id.row_title).text = building.name
        findViewById<TextView>(R.id.row_sub).text =
            getString(R.string.place_floors, building.floors.size)

        contentDescription = "${building.name}, 버튼"
        (layoutParams as LinearLayout.LayoutParams).bottomMargin =
            resources.getDimensionPixelSize(R.dimen.screen_gap)

        setOnClickListener {
            act.selectedBuilding = building
            // 층이 하나뿐이면 자동 선택하고 곧장 목적지 선택으로 넘어간다.
            if (building.floors.size == 1) {
                act.selectedFloor = building.floors.first()
                act.machine.transition(NavState.LISTENING)
            } else {
                act.machine.transition(NavState.SELECTING_FLOOR)
            }
        }
    }
}
