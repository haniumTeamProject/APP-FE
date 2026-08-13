package org.mcsmtp.wayfinder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.speech.SttManager
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.util.Haptics

/**
 * ② 목적지.
 *
 * 음성으로 목적지를 받아 별칭 표와 매칭한다.
 * 인식이 실패해도 진행할 수 있도록 목록 선택을 항상 함께 제공한다.
 */
class DestinationFragment : Fragment() {

    private lateinit var act: MainActivity
    private var stt: SttManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var retryBtn: Button? = null

    private var destinations: List<Destination> = emptyList()

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginListening()
        else showFailed(getString(R.string.dest_mic_denied))
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_destination, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        act = requireActivity() as MainActivity
        statusText = view.findViewById(R.id.status_text)
        retryBtn = view.findViewById(R.id.btn_retry)

        stt = SttManager(requireContext())

        destinations = runCatching { act.api.destinations().destinations }.getOrElse { emptyList() }
        buildList(view.findViewById(R.id.dest_list))

        retryBtn?.setOnClickListener { ensurePermissionThenListen() }
        act.moveAccessibilityFocus(retryBtn)

        ensurePermissionThenListen()
    }

    private fun ensurePermissionThenListen() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) beginListening()
        else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * 마이크를 열기 전에 TTS를 멈추고 잠시 기다린다.
     * TalkBack이 화면 진입 안내를 읽는 중에 마이크를 열면 그 소리를 인식해버린다.
     */
    private fun beginListening() {
        act.speech.stop()
        statusText?.setText(R.string.dest_listening)
        retryBtn?.visibility = View.GONE

        handler.postDelayed({
            if (!isAdded) return@postDelayed
            stt?.start(sttCallback)
        }, MIC_OPEN_DELAY_MS)
    }

    private val sttCallback = object : SttManager.Callback {
        override fun onReady() {
            if (!isAdded) return
            statusText?.setText(R.string.dest_listening)
            act.haptics.play(Haptics.Pattern.GUIDE)
        }

        override fun onEndOfSpeech() {
            if (!isAdded) return
            statusText?.setText(R.string.dest_recognizing)
        }

        override fun onResult(candidates: List<String>) {
            if (!isAdded) return
            // 인식 후보를 순서대로 매칭해 첫 성공을 채택한다.
            for (spoken in candidates) {
                val matched = act.api.match(spoken, destinations)
                when {
                    matched.size == 1 -> {
                        select(matched.first()); return
                    }
                    // TODO(2차): DISAMBIGUATE 상태로 "409호가 두 곳입니다" 되묻기.
                    //            지금은 첫 후보를 채택한다.
                    matched.size > 1 -> {
                        select(matched.first()); return
                    }
                }
            }
            showFailed(getString(R.string.dest_failed_speech))
        }

        override fun onFailed(recoverable: Boolean) {
            if (!isAdded) return
            showFailed(getString(R.string.dest_failed_speech))
        }
    }

    private fun showFailed(message: String) {
        statusText?.setText(R.string.dest_failed)
        retryBtn?.visibility = View.VISIBLE
        act.speech.speak(view, message)
        act.moveAccessibilityFocus(retryBtn)
    }

    private fun select(dest: Destination) {
        stt?.cancel()
        act.destination = dest
        act.machine.transition(NavState.ROUTING)
    }

    private fun buildList(container: LinearLayout) {
        destinations.forEach { dest ->
            container.addView(makeButton(dest))
        }
    }

    private fun makeButton(dest: Destination): Button =
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
            setOnClickListener { select(dest) }
        }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        stt?.destroy()
        stt = null
        statusText = null
        retryBtn = null
        super.onDestroyView()
    }

    private companion object {
        /** TalkBack 안내가 끝나기를 기다리는 시간. 너무 짧으면 자기 음성을 인식한다. */
        const val MIC_OPEN_DELAY_MS = 900L
    }
}
