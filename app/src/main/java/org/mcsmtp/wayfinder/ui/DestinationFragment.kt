package org.mcsmtp.wayfinder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.state.NavState

/**
 * ② 목적지.
 *
 * 7단계에서 SpeechRecognizer를 붙이기 전까지는 목록 선택으로 대신한다.
 * 목록은 STT가 붙은 뒤에도 인식 실패 시 대안으로 남겨둘 수 있다.
 */
class DestinationFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_destination, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val act = requireActivity() as MainActivity
        val status = view.findViewById<TextView>(R.id.status_text)
        val retry = view.findViewById<Button>(R.id.btn_retry)
        val list = view.findViewById<LinearLayout>(R.id.dest_list)

        // TODO(7단계): SpeechRecognizer 연동. 마이크가 열리면 효과음 + 진동(GUIDE)으로 알린다.
        //              마이크가 열려 있는 동안에는 발화하지 않는다 — 자기 TTS를 인식한다.
        status.setText(R.string.dest_pick_title)

        retry.setOnClickListener { status.setText(R.string.dest_listening) }

        val destinations = runCatching { act.api.destinations().destinations }
            .getOrElse { emptyList() }

        destinations.forEach { dest ->
            list.addView(makeButton(dest) { select(act, it) })
        }

        act.moveAccessibilityFocus(list.getChildAt(0))
    }

    private fun makeButton(dest: Destination, onPick: (Destination) -> Unit): Button =
        Button(requireContext()).apply {
            text = dest.name
            contentDescription = "${dest.name}, 버튼"
            textSize = 24f
            setBackgroundResource(R.color.surface)
            setTextColor(resources.getColor(R.color.fg, null))
            minHeight = (72 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { onPick(dest) }
        }

    private fun select(act: MainActivity, dest: Destination) {
        act.destination = dest
        act.machine.transition(NavState.ROUTING)
    }
}
