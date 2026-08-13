package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.state.NavState

/** ① 홈 — 포커스 요소 1개. 화면 어디를 탭해도 이 버튼에 포커스가 간다. */
class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val btn = view.findViewById<Button>(R.id.btn_speak)

        btn.setOnClickListener { act.machine.transition(NavState.LISTENING) }

        act.moveAccessibilityFocus(btn)
        act.speech.speak(view, getString(R.string.home_enter))
    }
}
