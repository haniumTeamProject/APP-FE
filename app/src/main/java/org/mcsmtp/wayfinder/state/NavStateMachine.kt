package org.mcsmtp.wayfinder.state

import android.util.Log

/**
 * 상태 전이를 한 곳에서만 처리한다.
 *
 * "상태가 바뀌면 반드시 소리 또는 진동으로 알린다"는 원칙을 여기서 강제한다.
 * Fragment마다 흩어두면 반드시 빠뜨린다.
 */
class NavStateMachine {

    fun interface Listener {
        fun onStateChanged(from: NavState, to: NavState)
    }

    var current: NavState = NavState.SELECTING_PLACE
        private set

    private val listeners = mutableListOf<Listener>()

    /**
     * 허용된 전이만 정의한다. 정의되지 않은 전이는 무시된다.
     * 어느 상태에서든 건물 선택(SELECTING_PLACE)으로 돌아갈 수 있어야 한다 —
     * 취소·중지·종료가 모두 진입 화면으로 모인다.
     */
    private val allowed: Map<NavState, Set<NavState>> = mapOf(
        // 건물을 고르면 층 선택으로. 층이 하나뿐이면 곧장 목적지 선택으로 건너뛴다.
        NavState.SELECTING_PLACE to setOf(NavState.SELECTING_FLOOR, NavState.LISTENING),
        // 층을 고르면 목적지 선택으로. 뒤로 가면 건물 선택으로.
        NavState.SELECTING_FLOOR to setOf(NavState.LISTENING, NavState.SELECTING_PLACE),
        NavState.LISTENING to setOf(NavState.ROUTING, NavState.SELECTING_PLACE),
        NavState.ROUTING to setOf(NavState.NAVIGATING, NavState.SELECTING_PLACE),
        NavState.NAVIGATING to setOf(NavState.ARRIVED, NavState.SELECTING_PLACE),
        NavState.ARRIVED to setOf(NavState.SELECTING_PLACE, NavState.LISTENING),
    )

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** 전이를 시도한다. 허용되지 않으면 아무 일도 일어나지 않고 false를 돌려준다. */
    fun transition(next: NavState): Boolean {
        if (next == current) return false
        if (allowed[current]?.contains(next) != true) {
            Log.w(TAG, "허용되지 않은 전이: $current -> $next")
            return false
        }
        val from = current
        current = next
        Log.d(TAG, "상태 전이: $from -> $next")
        // 순회 중 리스너가 제거될 수 있으므로 복사본을 돈다
        listeners.toList().forEach { it.onStateChanged(from, next) }
        return true
    }

    /** 어느 상태에서든 건물 선택으로. 취소·중지·종료 공통 경로. */
    fun reset() {
        if (current != NavState.SELECTING_PLACE) transition(NavState.SELECTING_PLACE)
    }

    private companion object {
        const val TAG = "NavStateMachine"
    }
}
