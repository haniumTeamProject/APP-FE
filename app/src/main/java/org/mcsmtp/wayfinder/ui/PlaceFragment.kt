package org.mcsmtp.wayfinder.ui

import android.view.View
import androidx.core.view.ViewCompat
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Building
import org.mcsmtp.wayfinder.state.NavState

/**
 * 건물 선택 — 앱 진입점. 음성으로 건물 이름을 말하거나 목록에서 고른다.
 *
 * 이 서비스는 한 건물 전용이 아니라 여러 건물에 쓰인다(건물 → 층 → 목적지).
 * 층이 하나뿐인 건물은 층 선택을 건너뛰고 곧장 목적지 선택으로 간다 — 탭을 아낀다.
 * 온보딩은 1회성이라, 조작법을 잊었을 때 이 화면에서 길게 눌러 사용법을 다시 본다.
 */
class PlaceFragment : VoicePickFragment<Building>() {

    override fun loadItems(): List<Building> = act.api.buildings().buildings

    override fun titleOf(item: Building): String = item.name

    override fun keysOf(item: Building): List<String> = item.aliases + item.name

    override fun subtitleOf(item: Building): String =
        getString(R.string.place_floors, item.floors.size)

    override fun entryExampleSpeech(): String = getString(R.string.building_enter_example)

    override fun exampleHintText(): String = getString(R.string.building_example)

    override fun listCountText(count: Int): String =
        getString(R.string.building_pick_count, count)

    override fun onPick(item: Building) {
        act.selectedBuilding = item
        // 층이 하나뿐이면 자동 선택하고 곧장 목적지 선택으로 넘어간다.
        if (item.floors.size == 1) {
            act.selectedFloor = item.floors.first()
            act.machine.transition(NavState.LISTENING)
        } else {
            act.machine.transition(NavState.SELECTING_FLOOR)
        }
    }

    override fun onViewReady(view: View) {
        // 진입 화면에서 길게 눌러 사용법을 다시 볼 수 있게 한다(온보딩은 1회성이므로).
        // 항상 보이는 히어로(마이크 카드)에 건다 — 아래는 스크롤 목록이라 빈 곳이 없다.
        val hero = view.findViewById<View>(R.id.dest_hero) ?: return
        hero.setOnLongClickListener { act.showUsage(); true }
        ViewCompat.addAccessibilityAction(hero, getString(R.string.home_howto_action)) { _, _ ->
            act.showUsage(); true
        }
    }
}
