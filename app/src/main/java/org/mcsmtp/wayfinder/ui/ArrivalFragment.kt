package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.util.Haptics
import org.mcsmtp.wayfinder.util.TextFormat

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
        val hint = view.findViewById<TextView>(R.id.arrival_hint)
        val newDest = view.findViewById<View>(R.id.btn_new_dest)
        val end = view.findViewById<View>(R.id.btn_end)

        // 목적지 이름은 **서버가 준 것**을 먼저 본다.
        //
        // `act.destination` 은 목록에서 골랐을 때만 채워진다. 말로 목적지를 정하면
        // 서버가 알아듣고 경로까지 만들어 내려주는데 앱에는 그 결과가 안 남아서,
        // 여기서 이름이 빈 문자열이 됐다.
        val msg = act.nav.lastMessage
        val name = act.destination?.name ?: msg?.screen?.title
        text.text = TextFormat.guidance(
            name?.let { "${it}에\n도착했습니다" } ?: act.speech.lastUtterance.orEmpty()
        )

        // 문 방향(왼쪽/오른쪽)은 안내하지 않는다. 실내 위치 정확도가 문 좌·우까지
        // 짚어줄 만큼은 아니라, 틀린 방향을 확신에 차 말하면 오히려 사용자를 헤매게 한다.
        hint.visibility = View.GONE

        // 도착 발화는 `NavCoordinator` 가 이미 서버 문장으로 했다. 여기서 또 말하면
        // 안 된다 — `speak()` 는 QUEUE_FLUSH 라, 화면이 붙는 순간 서버 문장을 잘라먹고
        // 앱이 지어낸 문장으로 바꿔치기한다. 실제로 "407입니다" 가 시작되자마자
        // 끊기고 "에 도착했습니다" 만 들렸다.
        //
        // 진동도 마찬가지로 `haptic="arrive"` 를 받아 이미 울렸다.
        // 화면이 다시 붙는 경우(회전·사용법 닫기)에만 여기서 보완한다.
        if (savedInstanceState != null) {
            act.haptics.play(Haptics.Pattern.ARRIVE)
        }

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
