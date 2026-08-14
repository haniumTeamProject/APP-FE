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
     * 어느 상태에서든 READY로 돌아갈 수 있어야 한다 — 취소·중지·종료가 모두 홈으로 모인다.
     */
    private val allowed: Map<NavState, Set<NavState>> = mapOf(
        NavState.SELECTING_PLACE to setOf(NavState.READY),
        NavState.READY to setOf(NavState.LISTENING),
        NavState.LISTENING to setOf(NavState.ROUTING, NavState.READY),
        NavState.ROUTING to setOf(NavState.NAVIGATING, NavState.READY),
        NavState.NAVIGATING to setOf(NavState.ARRIVED, NavState.READY),
        NavState.ARRIVED to setOf(NavState.READY, NavState.LISTENING),
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

    /** 어느 상태에서든 홈으로. 취소·중지·종료 공통 경로. */
    fun reset() {
        if (current != NavState.READY) transition(NavState.READY)
    }

    private companion object {
        const val TAG = "NavStateMachine"
    }
}
