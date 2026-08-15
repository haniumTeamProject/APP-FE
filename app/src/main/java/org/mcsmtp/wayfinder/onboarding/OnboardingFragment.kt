package org.mcsmtp.wayfinder.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

    private var iconWrap: View? = null
    private var icon: ImageView? = null
    private var title: TextView? = null
    private var sub: TextView? = null
    private var practiceCard: View? = null
    private var permListView: View? = null
    private var settingsBtn: View? = null
    private var actionBtn: View? = null
    private var actionLabel: TextView? = null
    private var skipBtn: View? = null
    private var hint: TextView? = null

    /** 권한 단계에서 팝업을 이미 한 바퀴 돌렸는가. 그 뒤 액션은 "계속"으로 다음에 넘긴다. */
    private var permsRequested = false

    private var pendingQueue: MutableList<String> = mutableListOf()
    private var requesting: String? = null
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onPermissionResult() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        act = requireActivity() as MainActivity
        prefs = OnboardingPrefs(requireContext())

        iconWrap = view.findViewById(R.id.ob_icon_wrap)
        icon = view.findViewById(R.id.ob_icon)
        title = view.findViewById(R.id.ob_title)
        sub = view.findViewById(R.id.ob_sub)
        practiceCard = view.findViewById(R.id.ob_practice_card)
        permListView = view.findViewById(R.id.ob_perm_list)
        settingsBtn = view.findViewById(R.id.ob_settings)
        actionBtn = view.findViewById(R.id.ob_action)
        actionLabel = view.findViewById(R.id.ob_action_label)
        skipBtn = view.findViewById(R.id.ob_skip)
        hint = view.findViewById(R.id.ob_hint)

        actionBtn?.setOnClickListener { onAdvance() }
        skipBtn?.setOnClickListener { onSkip() }
        settingsBtn?.setOnClickListener { openAppSettings() }

        render(step)
    }

    private fun render(s: OnboardingStep) {
        step = s
        practiceCard?.visibility = View.GONE
        permListView?.visibility = View.GONE
        settingsBtn?.visibility = View.GONE
        skipBtn?.visibility = if (OnboardingFlow.canSkip(s)) View.VISIBLE else View.GONE
        hint?.text = ""

        when (s) {
            OnboardingStep.INTRO -> {
                iconWrap?.visibility = View.VISIBLE
                iconWrap?.setBackgroundResource(R.drawable.circle_mint)
                icon?.setImageResource(R.drawable.ic_arrow_right)
                title?.setText(R.string.onboarding_intro_title)
                sub?.setText(R.string.onboarding_intro_sub)
                actionLabel?.setText(R.string.onboarding_next)
                actionBtn?.contentDescription = getString(R.string.onboarding_next)
                speak(R.string.onboarding_intro_speech)
                act.moveAccessibilityFocus(actionBtn)
            }
            OnboardingStep.PRACTICE -> {
                iconWrap?.visibility = View.GONE
                title?.setText(R.string.onboarding_practice_title)
                sub?.setText(R.string.onboarding_practice_sub)
                practiceCard?.visibility = View.VISIBLE
                actionLabel?.setText(R.string.onboarding_next)
                actionBtn?.contentDescription = getString(R.string.onboarding_practice_prompt)
                hint?.setText(R.string.onboarding_practice_hint)
                speak(R.string.onboarding_practice_speech)
                act.moveAccessibilityFocus(actionBtn)
                handler.postDelayed(autoPass, PRACTICE_TIMEOUT_MS)
            }
            OnboardingStep.PERMISSIONS -> {
                permsRequested = false
                iconWrap?.visibility = View.GONE
                title?.setText(R.string.onboarding_perm_title)
                sub?.setText(R.string.onboarding_perm_sub)
                permListView?.visibility = View.VISIBLE
                actionLabel?.setText(R.string.onboarding_perm_allow)
                actionBtn?.contentDescription = getString(R.string.onboarding_perm_allow_desc)
                hint?.setText(R.string.onboarding_perm_hint)
                speak(R.string.onboarding_perm_speech)
                act.moveAccessibilityFocus(actionBtn)
            }
            OnboardingStep.DONE -> {
                iconWrap?.visibility = View.VISIBLE
                iconWrap?.setBackgroundResource(R.drawable.circle_blue)
                icon?.setImageResource(R.drawable.ic_check)
                title?.setText(R.string.onboarding_done_title)
                sub?.setText(R.string.onboarding_done_sub)
                actionLabel?.setText(R.string.onboarding_start)
                actionBtn?.contentDescription = getString(R.string.onboarding_start)
                speak(R.string.onboarding_done_speech)
                act.moveAccessibilityFocus(actionBtn)
            }
        }
    }

    private val autoPass = Runnable {
        if (step == OnboardingStep.PRACTICE) {
            act.speech.speak(view, getString(R.string.onboarding_practice_autopass))
            goNext()
        }
    }

    private fun onAdvance() {
        when (step) {
            OnboardingStep.INTRO -> goNext()
            OnboardingStep.PRACTICE -> {
                handler.removeCallbacks(autoPass)
                act.haptics.play(Haptics.Pattern.GUIDE)
                act.speech.speak(view, getString(R.string.onboarding_practice_success))
                goNext()
            }
            OnboardingStep.PERMISSIONS ->
                if (permsRequested) goNext() else startPermissionRequests()
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
        requesting = next
        requestPerm.launch(next)
    }

    private fun onPermissionResult() {
        requesting = null
        requestNext()
    }

    /**
     * 팝업을 한 바퀴 다 돌린 뒤. 마이크가 허용됐으면 곧장 다음.
     * 거부됐으면 이유를 안내하고, 영구 차단이면 설정 열기를 노출한다.
     * 어느 경우든 앱은 진입 가능하므로, 액션 타일을 "계속"으로 바꿔 사용자가 넘어가게 한다.
     */
    private fun onAllRequested() {
        permsRequested = true
        val micGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            goNext()
            return
        }
        act.speech.speak(view, getString(R.string.onboarding_mic_denied_speech))
        actionLabel?.setText(R.string.onboarding_continue)
        actionBtn?.contentDescription = getString(R.string.onboarding_continue)
        val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (permanentlyDenied) {
            settingsBtn?.visibility = View.VISIBLE
            act.moveAccessibilityFocus(settingsBtn)
        } else {
            act.moveAccessibilityFocus(actionBtn)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null),
            )
        )
    }

    private fun finishOnboarding() {
        prefs.markDone()
        act.startAfterOnboarding()
    }

    private fun speak(resId: Int) = act.speech.speak(view, getString(resId))

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        iconWrap = null; icon = null; title = null; sub = null
        practiceCard = null; permListView = null; settingsBtn = null
        actionBtn = null; actionLabel = null; skipBtn = null; hint = null
        super.onDestroyView()
    }

    private companion object {
        const val PRACTICE_TIMEOUT_MS = 5000L
    }
}
