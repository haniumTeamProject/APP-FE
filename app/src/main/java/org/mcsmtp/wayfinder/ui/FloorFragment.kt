package org.mcsmtp.wayfinder.ui

import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Floor
import org.mcsmtp.wayfinder.state.NavState

/**
 * 층 선택. 건물 선택 다음 단계로, 고른 건물의 층을 음성으로 말하거나 목록에서 고른다.
 * 층이 하나뿐인 건물은 [PlaceFragment]에서 이 단계를 건너뛰므로 여기 오지 않는다.
 */
class FloorFragment : VoicePickFragment<Floor>() {

    override fun loadItems(): List<Floor> = act.selectedBuilding?.floors ?: emptyList()

    override fun titleOf(item: Floor): String = item.name

    override fun keysOf(item: Floor): List<String> = item.aliases + item.name

    override fun subtitleOf(item: Floor): String =
        getString(R.string.place_count, item.destinationCount)

    /** "몇 층에 계신가요? 1층부터 4층이 있습니다." 처럼 있는 층 범위를 함께 알려준다. */
    override fun entrySpeech(): String {
        val floors = (act.selectedBuilding?.floors ?: emptyList()).sortedBy { it.floor }
        if (floors.size < 2) return getString(R.string.floor_enter_example)
        val range = getString(R.string.floor_range, floors.first().name, floors.last().name)
        return getString(R.string.floor_enter_desc, range)
    }

    override fun listCountText(count: Int): String =
        getString(R.string.floor_pick_count, count)

    override fun onPick(item: Floor) {
        act.selectedFloor = item
        act.machine.transition(NavState.LISTENING)
    }
}
