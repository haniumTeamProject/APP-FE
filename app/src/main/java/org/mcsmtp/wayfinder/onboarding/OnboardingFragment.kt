package org.mcsmtp.wayfinder.onboarding

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.mcsmtp.wayfinder.MainActivity
import org.mcsmtp.wayfinder.R
import org.mcsmtp.wayfinder.util.Haptics

/**
 * 첫 실행 온보딩. 한 화면을 4단계(소개·연습·권한·완료)로 재구성한다.
 *
 * 진행은 액션 타일 클릭으로 한다. TalkBack 켜짐이면 두 번 탭으로, 꺼짐이면 단일 탭으로
 * 클릭이 발생한다(홈 타일과 같은 방식). 연습 단계는 이 클릭이 곧 "두 번 두드리기" 성공이다.
 */
class OnboardingFragment : Fragment() {

    private lateinit var act: MainActivity
    private lateinit var prefs: OnboardingPrefs
    private val handler = Handler(Looper.getMainLooper())

    private var step = OnboardingStep.INTRO

    private var hero: View? = null
    private var topSpacer: View? = null
    private var iconWrap: View? = null
    private var icon: ImageView? = null
    private var title: TextView? = null
    private var sub: TextView? = null
    private var practiceCard: View? = null
    private var permListView: View? = null
    private var actionBtn: View? = null
    private var actionLabel: TextView? = null
    private var skipBtn: View? = null
    private var hint: TextView? = null

    private var pendingQueue: MutableList<String> = mutableListOf()
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onPermissionResult() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        act = requireActivity() as MainActivity
        prefs = OnboardingPrefs(requireContext())

        hero = view.findViewById(R.id.ob_hero)
        topSpacer = view.findViewById(R.id.ob_top_spacer)
        iconWrap = view.findViewById(R.id.ob_icon_wrap)
        icon = view.findViewById(R.id.ob_icon)
        title = view.findViewById(R.id.ob_title)
        sub = view.findViewById(R.id.ob_sub)
        practiceCard = view.findViewById(R.id.ob_practice_card)
        permListView = view.findViewById(R.id.ob_perm_list)
        actionBtn = view.findViewById(R.id.ob_action)
        actionLabel = view.findViewById(R.id.ob_action_label)
        skipBtn = view.findViewById(R.id.ob_skip)
        hint = view.findViewById(R.id.ob_hint)

        // 히어로 = "다음" 대상(권한 단계 제외). 버튼은 권한 단계에서만 액션.
        hero?.setOnClickListener { onAdvance() }
        actionBtn?.setOnClickListener { onAdvance() }
        skipBtn?.setOnClickListener { onSkip() }

        // 화면 어디를 눌러도 넘어가게 루트 전체를 탭 대상으로 둔다.
        // 앞을 못 보는 사용자는 히어로 카드를 조준할 수 없어, 빈 영역 탭이 무반응이면
        // "화면을 두 번 두드리면 다음"이라는 안내와 어긋난다. TalkBack 탐색엔 잡히지 않게 둔다
        // (자식 요소는 그대로 읽힌다). 권한 단계만 render 에서 이 탭을 끈다.
        view.setOnClickListener { onAdvance() }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        render(step)
    }

    private fun render(s: OnboardingStep) {
        step = s
        // 공통 초기화
        topSpacer?.visibility = View.GONE
        practiceCard?.visibility = View.GONE
        permListView?.visibility = View.GONE
        actionBtn?.visibility = View.GONE
        skipBtn?.visibility = if (OnboardingFlow.canSkip(s)) View.VISIBLE else View.GONE
        hint?.text = ""
        // 히어로는 기본적으로 "다음" 대상. 권한 단계만 예외로 정보 표시로 바꾼다.
        hero?.isClickable = true
        hero?.isFocusable = true
        // 권한 단계는 배경 탭으로 넘어가지 않는다(권한 팝업이 뜨면 안 됨). 나머지는 화면 전체가 "다음".
        view?.isClickable = s != OnboardingStep.PERMISSIONS

        when (s) {
            OnboardingStep.INTRO -> {
                // 흰 배경 스플래시: 히어로 배경을 지우고 위·아래 스페이서로 중앙에 둔다.
                topSpacer?.visibility = View.VISIBLE
                hero?.setBackgroundResource(0)
                iconWrap?.visibility = View.VISIBLE
                iconWrap?.setBackgroundResource(R.drawable.circle_mint)
                icon?.setImageResource(R.drawable.ic_arrow_right)
                title?.setText(R.string.onboarding_intro_title)
                sub?.setText(R.string.onboarding_intro_sub)
                hero?.contentDescription =
                    getString(R.string.onboarding_intro_title) + ". " +
                        getString(R.string.onboarding_advance_desc)
                speak(R.string.onboarding_intro_speech)
                act.moveAccessibilityFocus(hero)
            }
            OnboardingStep.PRACTICE -> {
                hero?.setBackgroundResource(R.drawable.bg_hero)
                iconWrap?.visibility = View.VISIBLE
                iconWrap?.setBackgroundResource(R.drawable.circle_mint)
                icon?.setImageResource(R.drawable.ic_mic)
                title?.setText(R.string.onboarding_practice_title)
                sub?.setText(R.string.onboarding_practice_sub)
                practiceCard?.visibility = View.VISIBLE
                hint?.setText(R.string.onboarding_practice_hint)
                hero?.contentDescription = getString(R.string.onboarding_practice_prompt)
                act.moveAccessibilityFocus(hero)
                // 연습 안내가 끝난 뒤부터 5초를 센다. 안내 발화(~5초)와 대기를 겹쳐 돌리면
                // "말하자마자 자동 통과"처럼 느껴진다. 발화 종료 콜백에서 타이머를 건다.
                act.speech.speakThen(getString(R.string.onboarding_practice_speech)) {
                    handler.post {
                        if (isAdded && step == OnboardingStep.PRACTICE) {
                            handler.postDelayed(autoPass, PRACTICE_TIMEOUT_MS)
                        }
                    }
                }
            }
            OnboardingStep.PERMISSIONS -> {
                hero?.setBackgroundResource(R.drawable.bg_hero)
                iconWrap?.visibility = View.GONE
                title?.setText(R.string.onboarding_perm_title)
                sub?.setText(R.string.onboarding_perm_sub)
                permListView?.visibility = View.VISIBLE
                // 이 단계는 히어로가 아니라 하단 버튼이 액션이다. 히어로는 정보 표시로 둔다.
                hero?.isClickable = false
                hero?.contentDescription =
                    getString(R.string.onboarding_perm_title) + ". " +
                        getString(R.string.onboarding_perm_sub)
                actionBtn?.visibility = View.VISIBLE
                actionLabel?.setText(R.string.onboarding_perm_allow)
                actionBtn?.contentDescription = getString(R.string.onboarding_perm_allow_desc)
                hint?.setText(R.string.onboarding_perm_hint)
                speak(R.string.onboarding_perm_speech)
                act.moveAccessibilityFocus(actionBtn)
            }
            OnboardingStep.DONE -> {
                hero?.setBackgroundResource(R.drawable.bg_hero)
                iconWrap?.visibility = View.VISIBLE
                iconWrap?.setBackgroundResource(R.drawable.circle_blue)
                icon?.setImageResource(R.drawable.ic_check)
                title?.setText(R.string.onboarding_done_title)
                sub?.setText(R.string.onboarding_done_sub)
                hero?.contentDescription =
                    getString(R.string.onboarding_done_title) + ". " +
                        getString(R.string.onboarding_start_desc)
                speak(R.string.onboarding_done_speech)
                act.moveAccessibilityFocus(hero)
            }
        }
    }

    private val autoPass = Runnable {
        if (step == OnboardingStep.PRACTICE) {
            // 안내를 끝까지 말한 뒤 화면을 넘긴다. 말하는 즉시 넘기면 다음 화면 안내와 겹친다.
            act.speech.speakThen(getString(R.string.onboarding_practice_autopass)) {
                handler.post { if (isAdded) goNext() }
            }
        }
    }

    private fun onAdvance() {
        when (step) {
            OnboardingStep.INTRO -> goNext()
            OnboardingStep.PRACTICE -> {
                handler.removeCallbacks(autoPass)
                act.haptics.play(Haptics.Pattern.GUIDE)
                // 성공 안내를 끝까지 말한 뒤 넘긴다(다음 화면 안내와 겹치지 않게).
                act.speech.speakThen(getString(R.string.onboarding_practice_success)) {
                    handler.post { if (isAdded) goNext() }
                }
            }
            OnboardingStep.PERMISSIONS -> startPermissionRequests()
            OnboardingStep.DONE -> finishOnboarding()
        }
    }

    private fun onSkip() {
        handler.removeCallbacks(autoPass)
        render(OnboardingFlow.skipTarget())
    }

    private fun goNext() = render(OnboardingFlow.next(step))

    private fun startPermissionRequests() {
        pendingQueue = OnboardingPermissions.required(Build.VERSION.SDK_INT)
            .filter {
                ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            .toMutableList()
        requestNext()
    }

    private fun requestNext() {
        val next = pendingQueue.removeFirstOrNull()
        if (next == null) {
            onAllRequested()
            return
        }
        requestPerm.launch(next)
    }

    private fun onPermissionResult() = requestNext()

    /** 팝업을 한 바퀴 다 돌린 뒤. 허용이든 거부든 다음 단계로 진행한다. 거부된 권한은
     * 각 기능의 사용 시점에서 대체 수단으로 처리한다(예: 마이크 거부 시 목적지 화면의 목록 선택). */
    private fun onAllRequested() = goNext()

    private fun finishOnboarding() {
        prefs.markDone()
        act.startAfterOnboarding()
    }

    private fun speak(resId: Int) = act.speech.speak(view, getString(resId))

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        hero = null; topSpacer = null
        iconWrap = null; icon = null; title = null; sub = null
        practiceCard = null; permListView = null
        actionBtn = null; actionLabel = null; skipBtn = null; hint = null
        super.onDestroyView()
    }

    private companion object {
        const val PRACTICE_TIMEOUT_MS = 5000L
    }
}
