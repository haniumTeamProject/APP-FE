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
import androidx.core.view.ViewCompat
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
    /** 목록을 감싼 ScrollView. 듣는 중 TalkBack에서 숨길 때는 이 스크롤 영역째로 가린다. */
    private var listScroll: View? = null

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
        listScroll = view.findViewById(R.id.dest_scroll)

        stt = SttManager(requireContext())

        val floorId = act.selectedFloor?.id
        destinations = runCatching {
            if (floorId != null) act.api.destinations(floorId).destinations else emptyList()
        }.getOrElse { emptyList() }
        buildList(listContainer!!)
        view.findViewById<TextView>(R.id.dest_count).text =
            getString(R.string.dest_pick_count, destinations.size)

        // 듣는 중에는 목록을 TalkBack에서 숨긴다. 마이크가 열린 채 TalkBack이 목록을 읽으면
        // 그 소리를 STT가 인식한다. 음성이 실패해 목록으로 넘어갈 때 다시 드러낸다.
        // (화면 명세: "듣는 중" 포커스 요소 0)
        setListTalkBackVisible(false)

        // [다시 말하기]는 예시 없이 곧장 마이크를 연다. 예시는 첫 진입에서 이미 들었다.
        retryBtn?.setOnClickListener { ensurePermissionThenListen(withExample = false) }

        // 앱 진입 화면이므로, 히어로를 길게 눌러 사용법을 다시 볼 수 있게 한다(온보딩은 1회성이므로).
        val hero = view.findViewById<View>(R.id.dest_hero)
        hero?.setOnLongClickListener { act.showUsage(); true }
        ViewCompat.addAccessibilityAction(hero, getString(R.string.home_howto_action)) { _, _ ->
            act.showUsage(); true
        }

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
        // 듣는 동안 목록을 다시 숨긴다. 폴백 후 [다시 말하기]로 돌아온 경우까지 커버한다.
        setListTalkBackVisible(false)

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
        // 이제 목록으로 고르므로 TalkBack에 다시 드러낸다.
        setListTalkBackVisible(true)
        act.speech.speak(view, message)

        val firstRow = listContainer?.getChildAt(0)
        if (firstRow != null) act.moveAccessibilityFocus(firstRow)
        else act.moveAccessibilityFocus(retryBtn)
    }

    /**
     * 목록을 TalkBack 탐색에 넣을지 뺄지 정한다.
     * 시각적으로는 늘 보이지만(저시력 사용자용), 듣는 중에는 TalkBack 발화를 막아
     * 마이크가 자기 음성을 인식하지 않게 한다.
     */
    private fun setListTalkBackVisible(visible: Boolean) {
        // 스크롤 영역째로 가린다. 안쪽 행만 숨기면 ScrollView 가 스크롤 컨테이너로 포커스를 먹는다.
        listScroll?.importantForAccessibility =
            if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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

    /**
     * Figma 「06 목적지 목록」의 행. 이름만 보여준다.
     *
     * 부제는 두지 않는다. doorSide(왼쪽/오른쪽)는 걷기 전이라 기준이 없어 선택 단계에선 뜻이 없고
     * (방향은 안내 중에만 말한다), type(화장실·엘리베이터 등)은 이름에 이미 들어 있어 겹친다.
     */
    private fun makeRow(inflater: LayoutInflater, parent: LinearLayout, dest: Destination): View =
        inflater.inflate(R.layout.item_destination, parent, false).apply {
            findViewById<TextView>(R.id.row_title).text = dest.name
            // 공유 행 레이아웃의 부제는 이 화면에선 쓰지 않는다(이름만).
            findViewById<TextView>(R.id.row_sub).visibility = View.GONE
            contentDescription = "${dest.name}, 버튼"
            (layoutParams as LinearLayout.LayoutParams).bottomMargin =
                resources.getDimensionPixelSize(R.dimen.screen_gap)
            setOnClickListener { select(dest) }
        }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        stt?.destroy()
        stt = null
        statusText = null
        retryBtn = null
        listContainer = null
        listScroll = null
        super.onDestroyView()
    }

    private companion object {
        /** TalkBack 안내가 끝나기를 기다리는 시간. 너무 짧으면 자기 음성을 인식한다. */
        const val MIC_OPEN_DELAY_MS = 900L
    }
}
