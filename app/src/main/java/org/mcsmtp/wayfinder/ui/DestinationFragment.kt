package org.mcsmtp.wayfinder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var retryBtn: View? = null
    private var listContainer: LinearLayout? = null

    private var destinations: List<Destination> = emptyList()

    /** 음성 인식 실패 횟수. 2회가 되면 목록으로 넘긴다. */
    private var failCount = 0

    /**
     * 이번 인식 결과를 아직 처리하지 않았는가.
     * SpeechRecognizer 는 서버 끊김 등에서 onError 를 순식간에 여러 번 던지기도 한다.
     * 한 번의 인식은 한 번만 처리해, 카운터가 튀거나 발화가 겹치는 것을 막는다.
     */
    private var awaitingResult = false

    /** 권한 팝업이 뜬 사이 기억해 둘 "이번이 첫 시도인가". 첫 시도에만 예시를 발화한다. */
    private var pendingExample = true

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 권한을 거부하면 음성 자체가 불가능하므로 재시도 없이 바로 목록으로 넘긴다.
        if (granted) beginListening(withExample = pendingExample)
        else fallbackToList(getString(R.string.dest_mic_denied))
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_destination, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        act = requireActivity() as MainActivity
        statusText = view.findViewById(R.id.status_text)
        retryBtn = view.findViewById(R.id.btn_retry)
        listContainer = view.findViewById(R.id.dest_list)

        stt = SttManager(requireContext())

        val floorId = act.selectedFloor?.id
        destinations = runCatching {
            if (floorId != null) act.api.destinations(floorId).destinations else emptyList()
        }.getOrElse { emptyList() }
        buildList(listContainer!!)
        view.findViewById<TextView>(R.id.dest_count).text =
            getString(R.string.dest_pick_count, destinations.size)

        // [다시 말하기]는 예시 없이 곧장 마이크를 연다. 예시는 첫 진입에서 이미 들었다.
        retryBtn?.setOnClickListener { ensurePermissionThenListen(withExample = false) }

        ensurePermissionThenListen(withExample = true)
    }

    private fun ensurePermissionThenListen(withExample: Boolean) {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            beginListening(withExample)
        } else {
            pendingExample = withExample
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 마이크를 연다.
     *
     * 첫 진입에서는 예시를 먼저 발화하고 **그 발화가 끝난 뒤** 마이크를 연다.
     * 마이크가 열린 채로 발화하면 자기 목소리를 인식하므로, 고정 지연이 아니라
     * TTS 종료 콜백([SpeechOutput.speakThen])으로 정확한 시점에 연다.
     * 재시도에서는 예시 없이 짧게 기다렸다가 연다(TalkBack 잔여 발화 대기).
     */
    private fun beginListening(withExample: Boolean) {
        act.speech.stop()
        statusText?.setText(R.string.dest_listening)
        retryBtn?.visibility = View.GONE

        awaitingResult = true
        if (withExample) {
            // speakThen 의 콜백은 TTS 바인더 스레드에서 온다.
            // SpeechRecognizer 는 메인 스레드에서만 만들 수 있으므로 다시 넘겨준다.
            act.speech.speakThen(getString(R.string.dest_enter_example)) {
                handler.post { if (isAdded) stt?.start(sttCallback) }
            }
        } else {
            handler.postDelayed({
                if (!isAdded) return@postDelayed
                stt?.start(sttCallback)
            }, MIC_OPEN_DELAY_MS)
        }
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
            if (!isAdded || !awaitingResult) return
            awaitingResult = false
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
            // onResult 에서 넘어온 실패는 이미 awaitingResult 를 소비했다.
            handleFailure()
        }

        override fun onFailed(recoverable: Boolean) {
            if (!isAdded || !awaitingResult) return
            awaitingResult = false
            handleFailure()
        }
    }

    /**
     * 음성 인식 실패 처리.
     *
     * 1회는 다시 한 번 말할 기회를 준다. 2회째부터는 **목록으로 넘긴다.**
     * 24개짜리 고정 어휘라 두 번 실패했으면 지금 음성이 안 되는 것이므로,
     * 같은 방법을 더 반복시키지 않고 작동하는 방법으로 옮기는 것이 낫다.
     */
    private fun handleFailure() {
        stt?.cancel()
        failCount++
        if (failCount >= 2) fallbackToList(getString(R.string.dest_fallback_speech))
        else showRetry()
    }

    private fun showRetry() {
        statusText?.setText(R.string.dest_failed)
        retryBtn?.visibility = View.VISIBLE
        act.speech.speak(view, getString(R.string.dest_failed_speech))
        act.moveAccessibilityFocus(retryBtn)
    }

    /**
     * 음성을 접고 목록 선택으로 전환한다. 포커스를 목록 첫 항목으로 옮겨
     * 사용자가 곧바로 스와이프하며 고를 수 있게 한다.
     * [다시 말하기] 버튼은 남겨 두어 원하면 음성으로 되돌아갈 수 있다.
     */
    private fun fallbackToList(message: String) {
        stt?.cancel()
        statusText?.setText(R.string.dest_use_list)
        retryBtn?.visibility = View.VISIBLE
        act.speech.speak(view, message)

        val firstRow = listContainer?.getChildAt(0)
        if (firstRow != null) act.moveAccessibilityFocus(firstRow)
        else act.moveAccessibilityFocus(retryBtn)
    }

    private fun select(dest: Destination) {
        awaitingResult = false
        stt?.cancel()
        act.destination = dest
        act.machine.transition(NavState.ROUTING)
    }

    private fun buildList(container: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())
        destinations.forEach { dest ->
            container.addView(makeRow(inflater, container, dest))
        }
    }

    /** Figma 「06 목적지 목록」의 행. 제목만 읽히면 되므로 라벨은 행 전체에 건다. */
    private fun makeRow(inflater: LayoutInflater, parent: LinearLayout, dest: Destination): View =
        inflater.inflate(R.layout.item_destination, parent, false).apply {
            findViewById<TextView>(R.id.row_title).text = dest.name

            val sub = findViewById<TextView>(R.id.row_sub)
            val hint = subtitleOf(dest)
            if (hint == null) sub.visibility = View.GONE else sub.text = hint

            contentDescription = "${dest.name}, 버튼"
            (layoutParams as LinearLayout.LayoutParams).bottomMargin =
                resources.getDimensionPixelSize(R.dimen.screen_gap)
            setOnClickListener { select(dest) }
        }

    /**
     * 행의 부연 문구. 목 데이터에 실제로 있는 값만 쓴다.
     * 거리나 소요 시간은 서버가 경로를 계산해야 나오는 값이라 여기서는 알 수 없다.
     */
    private fun subtitleOf(dest: Destination): String? {
        val side = when (dest.doorSide) {
            "left" -> "왼쪽"
            "right" -> "오른쪽"
            else -> null
        }
        val kind = when (dest.type) {
            "restroom" -> "화장실"
            "elevator" -> "엘리베이터"
            "stairs" -> "계단"
            else -> null
        }
        return listOfNotNull(side, kind).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        stt?.destroy()
        stt = null
        statusText = null
        retryBtn = null
        listContainer = null
        super.onDestroyView()
    }

    private companion object {
        /** TalkBack 안내가 끝나기를 기다리는 시간. 너무 짧으면 자기 음성을 인식한다. */
        const val MIC_OPEN_DELAY_MS = 900L
    }
}
