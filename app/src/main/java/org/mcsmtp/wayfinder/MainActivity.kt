package org.mcsmtp.wayfinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import org.mcsmtp.wayfinder.ble.BleScanner
import org.mcsmtp.wayfinder.nav.NavCoordinator
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
    /**
     * 서버와의 통로. **판단은 전부 서버가 한다** — 목적지 매칭도, 경로도, 무엇을
     * 언제 말할지도. 예전에는 MockApi 가 assets JSON 으로 그 흉내를 냈는데,
     * 실제 서버가 붙었으므로 걷어냈다.
     */
    lateinit var nav: NavCoordinator; private set

    /**
     * 현재 건물·층. **서버가 비콘으로 판별한다** — 앱은 고르지 않는다.
     * 화면에 표시할 일이 있을 때만 쓰고, 판정에는 관여하지 않는다.
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
        val scanner = BleScanner.getInstance(this)
        nav = NavCoordinator(scanner.client(), speech, haptics, machine)
        nav.start()
        // 스캔이 시작되어야 WS 도 붙고 비콘도 올라간다. 위치를 알아야 목적지를
        // 찾을 수 있으므로 앱이 뜨자마자 켠다 — 단, **권한을 받은 뒤에.**
        //
        // API 31+ 에서 BLUETOOTH_SCAN 은 사용자 승인이 필요하다. 매니페스트에만
        // 적어두고 바로 부르면 SecurityException 으로 앱이 즉사한다.
        ensureBlePermissionThenScan()

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

    /** 진입: 곧바로 목적지 음성 입력을 연다.
     *
     * 건물·층은 **서버가 비콘으로 판별한다** — 앱은 묻지도 고르지도 않는다.
     * 위치가 정해지기 전에 목적지를 말하면 서버가 "아직 위치를 확인하지 못했습니다"
     * 라고 답하고 다시 듣는다.
     */
    private fun startEntry() {
        render(NavState.LISTENING)
    }

    /**
     * BLE 스캔 권한을 받고 스캔을 시작한다.
     *
     * 필요한 권한이 안드로이드 버전마다 다르다.
     *   API 31+   BLUETOOTH_SCAN (+ 연결용 BLUETOOTH_CONNECT)
     *   API 30-   ACCESS_FINE_LOCATION — 그때는 BLE 스캔이 위치 권한에 묶여 있었다
     *
     * 거절해도 앱은 죽지 않는다. 다만 위치를 알 수 없어 목적지를 찾지 못하므로
     * 그 사실을 말해준다 — 조용히 아무 일도 안 일어나면 사용자는 고장으로 여긴다.
     */
    private fun ensureBlePermissionThenScan() {
        // **위치 권한을 모든 버전에서 함께 받는다.**
        //
        // neverForLocation 을 빼면 안드로이드는 BLE 스캔 결과를 위치 정보로 취급한다.
        // 그래서 API 31+ 에서도 위치 권한이 없으면 비콘 광고가 걸러진다 —
        // 주변 가전은 보이는데 우리 비콘만 안 잡히는 상태가 된다.
        val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScanSafely(); return
        }
        blePermissionLauncher.launch(missing.toTypedArray())
    }

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // 위치 권한만 거절해도 스캔은 시작한다 — 다만 비콘이 안 잡힐 수 있어
            // 그 사실을 알려준다. 조용히 실패하면 사용자는 고장으로 여긴다.
            val bt = result.entries.filter { it.key != Manifest.permission.ACCESS_FINE_LOCATION }
            if (bt.all { it.value }) {
                if (result[Manifest.permission.ACCESS_FINE_LOCATION] == false) {
                    android.util.Log.w("MainActivity",
                        "위치 권한 거절 — BLE 스캔에서 비콘이 걸러질 수 있다")
                }
                startScanSafely()
            } else {
                speech.speak(null, getString(R.string.err_ble_permission))
            }
        }

    /**
     * 권한이 있어도 실패할 수 있다 — 블루투스가 꺼져 있거나 기기가 BLE 를 지원하지 않으면.
     * 그때 죽지 않고 이유를 말해준다.
     */
    private fun startScanSafely() {
        val scanner = BleScanner.getInstance(this)
        if (!scanner.isBluetoothEnabled) {
            speech.speak(null, getString(R.string.err_bluetooth_off))
            return
        }
        runCatching { scanner.startScan() }
            .onFailure { android.util.Log.e("MainActivity", "스캔 시작 실패", it) }
    }

    override fun onResume() {
        super.onResume()
        // TalkBack이 꺼져 있으면 일반 안내도 자체 TTS로 읽어야 한다.
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        speech.talkBackEnabled = am?.isTouchExplorationEnabled == true
    }

    override fun onDestroy() {
        nav.stop()
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
        // 서버에도 알린다. 안 알리면 서버는 계속 안내 중이라 여기고 비콘이 바뀔
        // 때마다 발화를 내려보낸다 — 사용자는 취소했는데 말이 계속 나온다.
        nav.cancel()
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
