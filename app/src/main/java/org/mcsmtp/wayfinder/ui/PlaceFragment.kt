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
import org.mcsmtp.wayfinder.net.model.Building
import org.mcsmtp.wayfinder.net.model.Floor
import org.mcsmtp.wayfinder.state.NavState

/**
 * 건물·층 선택 — 앱 진입점.
 *
 * 이 서비스는 한 건물 전용이 아니라 여러 건물에 쓰인다.
 * 1차는 자동 인식이 없으므로 사용자가 직접 고른다. 건물마다 층을 펼쳐
 * "수원대 ICT관 4층"처럼 한 줄로 나열한다 — 두 단계로 나누면 탭이 늘어난다.
 */
class PlaceFragment : Fragment() {

    /** 화면에 뿌리기 좋게 (건물, 층)을 한 쌍으로 묶어 둔다. */
    private data class Place(val building: Building, val floor: Floor)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_place, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val container = view.findViewById<LinearLayout>(R.id.place_list)

        val buildings = runCatching { act.api.buildings().buildings }.getOrElse { emptyList() }
        val places = buildings.flatMap { b -> b.floors.map { Place(b, it) } }

        val inflater = LayoutInflater.from(requireContext())
        var firstRow: View? = null
        places.forEach { place ->
            val row = makeRow(inflater, container, act, place)
            container.addView(row)
            if (firstRow == null) firstRow = row
        }

        act.moveAccessibilityFocus(firstRow)
        act.speech.speak(view, getString(R.string.place_enter))
    }

    private fun makeRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        act: MainActivity,
        place: Place,
    ): View = inflater.inflate(R.layout.item_destination, parent, false).apply {
        val label = "${place.building.name} ${place.floor.name}"
        findViewById<TextView>(R.id.row_title).text = label
        findViewById<TextView>(R.id.row_sub).text =
            getString(R.string.place_count, place.floor.destinationCount)

        contentDescription = "$label, 버튼"
        (layoutParams as LinearLayout.LayoutParams).bottomMargin =
            resources.getDimensionPixelSize(R.dimen.screen_gap)

        setOnClickListener {
            act.selectedBuilding = place.building
            act.selectedFloor = place.floor
            act.machine.transition(NavState.READY)
        }
    }
}
