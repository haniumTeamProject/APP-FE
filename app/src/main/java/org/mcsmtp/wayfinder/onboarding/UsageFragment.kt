package org.mcsmtp.wayfinder.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R

/**
 * 사용법 화면 — Figma 「22 사용법」. 홈에서 길게 눌러 진입한다.
 *
 * 온보딩은 1회성이라, 조작법을 잊었을 때 여기서 다시 듣는다.
 * 앱에 실제로 있는 제스처만 싣는다(두 번 두드리기·흔들기·길게 누르기).
 */
class UsageFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_usage, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity

        val close = view.findViewById<View>(R.id.usage_close)
        close.setOnClickListener { act.closeUsage() }

        act.speech.speak(view, getString(R.string.usage_speech))
        act.moveAccessibilityFocus(view.findViewById(R.id.usage_header))
    }
}
