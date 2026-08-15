package org.mcsmtp.wayfinder

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import org.mcsmtp.wayfinder.mock.MockApi
import org.mcsmtp.wayfinder.net.model.Building
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.net.model.Floor
import org.mcsmtp.wayfinder.net.model.Route
import org.mcsmtp.wayfinder.onboarding.OnboardingFragment
import org.mcsmtp.wayfinder.onboarding.OnboardingPrefs
import org.mcsmtp.wayfinder.onboarding.UsageFragment
import org.mcsmtp.wayfinder.speech.SpeechOutput
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.state.NavStateMachine
import org.mcsmtp.wayfinder.ui.ArrivalFragment
import org.mcsmtp.wayfinder.ui.DestinationFragment
import org.mcsmtp.wayfinder.ui.NavigationFragment
import org.mcsmtp.wayfinder.util.Haptics

/**
 * 단일 Activity가 상태 머신을 소유하고, 상태에 맞는 Fragment를 띄운다.
 * Fragment는 화면을 그리기만 하고 전이는 여기로 모은다.
 */
class MainActivity : AppCompatActivity() {

    lateinit var machine: NavStateMachine; private set
    lateinit var speech: SpeechOutput; private set
    lateinit var haptics: Haptics; private set
    lateinit var api: MockApi; private set

    /**
     * 현재 건물·층. 사용자가 고르지 않고 [autoDetectLocation]이 정한다.
     * 실제로는 서버가 비콘으로 판별하고, 목에서는 기본 위치로 대체한다. Fragment 간 공유 상태.
     */
    var selectedBuilding: Building? = null
    var selectedFloor: Floor? = null

    /** 선택된 목적지와 계산된 경로. Fragment 간 공유 상태. */
    var destination: Destination? = null
    var route: Route? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragment_container)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        machine = NavStateMachine()
        speech = SpeechOutput(this)
        haptics = Haptics(this)
        api = MockApi(this)

        machine.addListener { _, to -> render(to) }

        // 뒤로가기: 안내 중에는 확인을 거친다.
        // 확인 없이 취소되면 사용자가 이동 중에 안내를 잃는다.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 사용법 화면이 떠 있으면 앱을 닫지 말고 그 화면만 닫는다.
                if (supportFragmentManager.findFragmentByTag(TAG_USAGE) != null) {
                    closeUsage(); return
                }
                when (machine.current) {
                    NavState.NAVIGATING -> confirmStop()
                    // 진입 화면(목적지 선택)에서 뒤로가기는 앱을 닫는다. 그 위로 갈 곳이 없다.
                    // 온보딩 중에도 machine.current는 LISTENING이라 이 분기가 적용되어 앱이 닫힌다 — 1회성 최초 실행 흐름이라 의도된 동작.
                    NavState.LISTENING -> finish()
                    else -> machine.reset()
                }
            }
        })

        if (savedInstanceState == null) {
            if (OnboardingPrefs(this).isDone()) startEntry()
            else showOnboarding()
        }
    }

    /** 진입: 위치를 자동 판별하고 곧바로 목적지 음성 입력을 연다. */
    private fun startEntry() {
        autoDetectLocation()
        render(NavState.LISTENING)
    }

    /**
     * 현재 위치(건물·층)를 정한다. 사용자는 고르지 않는다.
     * 실제로는 서버가 비콘(UUID→건물, major→층)으로 판별한다. 목에서는 기본 위치로 대체한다.
     * 실제 비콘/WS 연동(8단계)이 붙으면 이 자리를 서버 판별로 바꾼다.
     */
    private fun autoDetectLocation() {
        val buildings = runCatching { api.buildings().buildings }.getOrElse { emptyList() }
        val building = buildings.firstOrNull { it.id == "suwon_ict" } ?: buildings.firstOrNull()
        selectedBuilding = building
        selectedFloor = building?.floors?.firstOrNull { it.floor == 4 } ?: building?.floors?.firstOrNull()
    }

    override fun onResume() {
        super.onResume()
        // TalkBack이 꺼져 있으면 일반 안내도 자체 TTS로 읽어야 한다.
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        speech.talkBackEnabled = am?.isTouchExplorationEnabled == true
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    /** 첫 실행 온보딩을 띄운다. 상태 머신 바깥의 관문이라 별도 태그로 붙인다. */
    private fun showOnboarding() {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, OnboardingFragment(), TAG_ONBOARDING)
        }
    }

    /** 온보딩이 끝나면 호출된다. 위치 자동 판별 후 목적지 음성 입력으로 간다. */
    fun startAfterOnboarding() {
        startEntry()
    }

    /** 홈에서 길게 눌러 사용법 화면을 띄운다. 상태 머신 바깥의 화면이라 별도 태그로 붙인다. */
    fun showUsage() {
        if (supportFragmentManager.findFragmentByTag(TAG_USAGE) != null) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, UsageFragment(), TAG_USAGE)
        }
    }

    /** 사용법 화면을 닫고 현재 상태 화면으로 돌아간다. */
    fun closeUsage() {
        render(machine.current)
    }

    private fun render(state: NavState) {
        val fragment: Fragment = when (state) {
            NavState.LISTENING -> DestinationFragment()
            NavState.ROUTING, NavState.NAVIGATING -> NavigationFragment()
            NavState.ARRIVED -> ArrivalFragment()
        }
        // ROUTING -> NAVIGATING 은 같은 화면이므로 다시 붙이지 않는다.
        val tag = if (state == NavState.NAVIGATING) NavState.ROUTING.name else state.name
        if (supportFragmentManager.findFragmentByTag(tag) != null) return

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, fragment, tag)
        }
    }

    /** 안내 중지 확인. 포커스를 [예]에 고정해 화면 어디를 두 번 두드려도 같은 결과가 나오게 한다. */
    fun confirmStop() {
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.nav_stop_confirm)
            .setPositiveButton(R.string.nav_stop_yes) { _, _ -> stopNavigation() }
            .setNegativeButton(R.string.nav_stop_no, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { yes ->
                yes.requestFocus()
                yes.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            }
        }
        dialog.show()
    }

    fun stopNavigation() {
        destination = null
        route = null
        speech.clear()
        machine.reset()
    }

    /**
     * 화면 전환 후 TalkBack 포커스(초록 테두리)를 첫 요소로 강제 이동한다.
     * 하지 않으면 포커스가 유실되어 사용자가 "먹통"으로 느낀다. 가장 흔한 접근성 버그다.
     *
     * TYPE_VIEW_FOCUSED(입력 포커스)로는 TalkBack 포커스가 안 옮겨진다.
     * ACTION_ACCESSIBILITY_FOCUS 로 접근성 포커스를 직접 건다.
     * 화면 진입 직후엔 TalkBack이 자기 기본 포커스를 잡느라 덮어쓰므로 살짝 늦춰 건다.
     */
    fun moveAccessibilityFocus(view: View?) {
        view ?: return
        view.postDelayed({
            view.performAccessibilityAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null,
            )
        }, 300)
    }

    private companion object {
        const val TAG_ONBOARDING = "ONBOARDING"
        const val TAG_USAGE = "USAGE"
    }
}
