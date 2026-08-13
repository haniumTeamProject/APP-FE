package org.mcsmtp.wayfinder

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import org.mcsmtp.wayfinder.mock.MockApi
import org.mcsmtp.wayfinder.net.model.Destination
import org.mcsmtp.wayfinder.net.model.Route
import org.mcsmtp.wayfinder.speech.SpeechOutput
import org.mcsmtp.wayfinder.state.NavState
import org.mcsmtp.wayfinder.state.NavStateMachine
import org.mcsmtp.wayfinder.ui.ArrivalFragment
import org.mcsmtp.wayfinder.ui.DestinationFragment
import org.mcsmtp.wayfinder.ui.HomeFragment
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
                when (machine.current) {
                    NavState.NAVIGATING -> confirmStop()
                    NavState.READY -> finish()
                    else -> machine.reset()
                }
            }
        })

        if (savedInstanceState == null) render(NavState.READY)
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

    private fun render(state: NavState) {
        val fragment: Fragment = when (state) {
            NavState.READY -> HomeFragment()
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
     * 화면 전환 후 TalkBack 포커스를 첫 요소로 강제 이동한다.
     * 하지 않으면 포커스가 유실되어 사용자가 "먹통"으로 느낀다. 가장 흔한 접근성 버그다.
     */
    fun moveAccessibilityFocus(view: View?) {
        view?.post {
            view.requestFocus()
            view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }
}
