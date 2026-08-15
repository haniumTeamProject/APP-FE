package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Floor
import org.mcsmtp.wayfinder.state.NavState

/**
 * 층 선택. 건물 선택 다음 단계로, 고른 건물의 층을 고른다.
 * 층이 하나뿐인 건물은 [PlaceFragment]에서 이 단계를 건너뛰므로 여기 오지 않는다.
 * 진입 화면과 같은 레이아웃(fragment_place)을 쓰되 머리말만 바꾼다.
 */
class FloorFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_place, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val building = act.selectedBuilding
        val container = view.findViewById<LinearLayout>(R.id.place_list)

        view.findViewById<TextView>(R.id.place_title).setText(R.string.floor_title)
        view.findViewById<TextView>(R.id.place_sub).text =
            building?.name ?: getString(R.string.floor_sub)

        val floors = building?.floors ?: emptyList()

        val inflater = LayoutInflater.from(requireContext())
        var firstRow: View? = null
        floors.forEach { floor ->
            val row = makeRow(inflater, container, act, floor)
            container.addView(row)
            if (firstRow == null) firstRow = row
        }

        act.moveAccessibilityFocus(firstRow)
        act.speech.speak(view, getString(R.string.floor_enter, building?.name ?: ""))
    }

    private fun makeRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        act: MainActivity,
        floor: Floor,
    ): View = inflater.inflate(R.layout.item_destination, parent, false).apply {
        findViewById<TextView>(R.id.row_title).text = floor.name
        findViewById<TextView>(R.id.row_sub).text =
            getString(R.string.place_count, floor.destinationCount)

        contentDescription = "${floor.name}, 버튼"
        (layoutParams as LinearLayout.LayoutParams).bottomMargin =
            resources.getDimensionPixelSize(R.dimen.screen_gap)

        setOnClickListener {
            act.selectedFloor = floor
            act.machine.transition(NavState.LISTENING)
        }
    }
}
