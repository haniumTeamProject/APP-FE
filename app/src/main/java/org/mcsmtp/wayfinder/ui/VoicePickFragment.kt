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
import org.mcsmtp.wayfinder.mock.VoiceMatcher
import org.mcsmtp.wayfinder.speech.SttManager
import org.mcsmtp.wayfinder.util.Haptics

/**
 * 음성으로 항목을 고르는 화면의 공통 뼈대. 건물·층·목적지 선택이 모두 같은 흐름을 쓴다.
 *
 * 음성으로 이름을 받아 [VoiceMatcher]로 후보와 매칭하고, 인식이 실패해도 진행할 수 있게
 * 목록 선택을 항상 함께 제공한다. 목적지 화면 레이아웃(fragment_destination)을 재사용한다.
 *
 * 서브클래스는 "무엇을 고를지"만 정하면 된다 — 항목 목록·이름·매칭 키·선택 동작·안내 문구.
 */
abstract class VoicePickFragment<T> : Fragment() {

    protected lateinit var act: MainActivity
    private var stt: SttManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private var statusText: TextView? = null
    private var statusHint: TextView? = null
    private var retryBtn: View? = null
    private var listContainer: LinearLayout? = null
    private var listScroll: View? = null

    private var items: List<T> = emptyList()

    private var failCount = 0
    private var awaitingResult = false
    private var pendingExample = true

    // ─── 서브클래스가 정의한다 ──────────────────────────────────────────

    /** 고를 항목 목록. */
    protected abstract fun loadItems(): List<T>

    /** 목록 행에 보일 이름. */
    protected abstract fun titleOf(item: T): String

    /** 음성 매칭에 쓸 키(이름 + 별칭). */
    protected abstract fun keysOf(item: T): List<String>

    /** 항목이 확정됐을 때. 다음 화면으로 전이한다. */
    protected abstract fun onPick(item: T)

    /** 진입 시 발화할 안내(예시 포함). 예: "어느 건물에 계신가요? 예를 들어 도서관." */
    protected abstract fun entryExampleSpeech(): String

    /** "듣고 있어요" 아래 힌트. 예: "예를 들어 도서관". */
    protected abstract fun exampleHintText(): String

    /** 목록 머리말 아래 개수 문구. 예: "건물 3곳". */
    protected abstract fun listCountText(count: Int): String

    /** 목록 행의 부제(없으면 null). 예: 건물은 "2개 층", 층은 "목적지 24곳". */
    protected open fun subtitleOf(item: T): String? = null

    // ─── 공통 흐름 ────────────────────────────────────────────────────

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_destination, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        act = requireActivity() as MainActivity
        statusText = view.findViewById(R.id.status_text)
        statusHint = view.findViewById(R.id.status_hint)
        retryBtn = view.findViewById(R.id.btn_retry)
        listContainer = view.findViewById(R.id.dest_list)
        listScroll = view.findViewById(R.id.dest_scroll)

        stt = SttManager(requireContext())

        items = runCatching { loadItems() }.getOrElse { emptyList() }
        buildList(listContainer!!)
        statusHint?.text = exampleHintText()
        view.findViewById<TextView>(R.id.dest_count).text = listCountText(items.size)

        // 듣는 중에는 목록을 TalkBack에서 숨긴다(마이크가 목록 발화를 인식하지 않게).
        setListTalkBackVisible(false)

        retryBtn?.setOnClickListener { ensurePermissionThenListen(withExample = false) }
        onViewReady(view)
        ensurePermissionThenListen(withExample = true)
    }

    /** 서브클래스가 진입 시 뷰에 추가로 손댈 일이 있으면 재정의한다(예: 사용법 진입점). */
    protected open fun onViewReady(view: View) = Unit

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginListening(withExample = pendingExample)
        else fallbackToList(getString(R.string.dest_mic_denied))
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

    private fun beginListening(withExample: Boolean) {
        act.speech.stop()
        statusText?.setText(R.string.dest_listening)
        retryBtn?.visibility = View.GONE
        setListTalkBackVisible(false)

        awaitingResult = true
        if (withExample) {
            // speakThen 콜백은 TTS 바인더 스레드에서 온다. SpeechRecognizer는 메인 스레드에서만
            // 만들 수 있으므로 다시 넘겨준다.
            act.speech.speakThen(entryExampleSpeech()) {
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
            for (spoken in candidates) {
                val matched = VoiceMatcher.match(spoken, items) { keysOf(it) }
                // 1개면 확정, 여러 개면 첫 후보 채택(2차 DISAMBIGUATE 대상).
                if (matched.isNotEmpty()) {
                    select(matched.first()); return
                }
            }
            handleFailure()
        }

        override fun onFailed(recoverable: Boolean) {
            if (!isAdded || !awaitingResult) return
            awaitingResult = false
            handleFailure()
        }
    }

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

    private fun fallbackToList(message: String) {
        stt?.cancel()
        statusText?.setText(R.string.dest_use_list)
        retryBtn?.visibility = View.VISIBLE
        setListTalkBackVisible(true)
        act.speech.speak(view, message)

        val firstRow = listContainer?.getChildAt(0)
        act.moveAccessibilityFocus(firstRow ?: retryBtn)
    }

    private fun setListTalkBackVisible(visible: Boolean) {
        listScroll?.importantForAccessibility =
            if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun select(item: T) {
        awaitingResult = false
        stt?.cancel()
        onPick(item)
    }

    private fun buildList(container: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())
        items.forEach { item ->
            container.addView(makeRow(inflater, container, item))
        }
    }

    private fun makeRow(inflater: LayoutInflater, parent: LinearLayout, item: T): View =
        inflater.inflate(R.layout.item_destination, parent, false).apply {
            findViewById<TextView>(R.id.row_title).text = titleOf(item)

            val sub = findViewById<TextView>(R.id.row_sub)
            val hint = subtitleOf(item)
            if (hint == null) sub.visibility = View.GONE else sub.text = hint

            contentDescription = "${titleOf(item)}, 버튼"
            (layoutParams as LinearLayout.LayoutParams).bottomMargin =
                resources.getDimensionPixelSize(R.dimen.screen_gap)
            setOnClickListener { select(item) }
        }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        stt?.destroy()
        stt = null
        statusText = null
        statusHint = null
        retryBtn = null
        listContainer = null
        listScroll = null
        super.onDestroyView()
    }

    private companion object {
        const val MIC_OPEN_DELAY_MS = 900L
    }
}
