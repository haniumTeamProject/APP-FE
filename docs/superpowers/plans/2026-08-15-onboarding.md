# 온보딩 + 권한 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 첫 실행에서 앱을 음성으로 소개하고, 두 번 두드리기 제스처를 연습으로 익히게 하고, 마이크·블루투스·알림 권한을 한 흐름으로 받는 온보딩을 만든다.

**Architecture:** 온보딩은 안내 상태 머신 바깥의 첫 실행 관문이다. `MainActivity`가 SharedPreferences 플래그로 첫 실행을 판별해 `OnboardingFragment`(내부 step 인덱스로 4단계 관리) 또는 기존 `PlaceFragment`를 띄운다. 순서·권한선택 로직은 Context 비의존 순수 객체로 빼 JVM 유닛 테스트하고, Fragment·권한 팝업·TTS 타이밍은 에뮬레이터로 검증한다(기존 코드베이스 패턴과 동일: `DestinationMatcher`는 JVM 테스트, UI는 에뮬레이터).

**Tech Stack:** Kotlin, Android View(XML), JUnit4(JVM 유닛 테스트), SharedPreferences, ActivityResultContracts, 기존 `SpeechOutput`/`Haptics`.

## Global Constraints

- minSdk 26, targetSdk 36, AGP 8.13.0, Kotlin 2.2.21, JDK 17 — 임의 변경 금지.
- 색·폰트는 기존 리소스만 쓴다: 히어로 `@color/hero_bg`(#C5EBFF), 민트 `@drawable/bg_tile_mint`, 카드 `@drawable/bg_card`, 제목 `@style/Text.Headline`(Ria Sans), 소제목 `@style/Text.Title`, 본문 `@style/Text.Body`(Gothic A1).
- 접근성: 새 화면 진입마다 `act.moveAccessibilityFocus(...)`로 첫 요소에 포커스. 장식 컨테이너는 `importantForAccessibility="noHideDescendants"`.
- 권한은 이미 `AndroidManifest.xml`에 선언돼 있다(RECORD_AUDIO, BLUETOOTH_SCAN, POST_NOTIFICATIONS). 추가 선언 불필요.
- 커밋은 각 Task 끝에서. 커밋 메시지는 한국어 `feat:`/`test:` 접두.
- 유닛 테스트만 새로 쓴다(JVM). 그 외는 에뮬레이터 스크린샷으로 검증. Robolectric·Mockito 없음.
- 새 파일은 패키지 `org.mcsmtp.wayfinder.onboarding`에 모은다.

---

### Task 1: 권한 선택 로직 (OnboardingPermissions, 순수·TDD)

SDK 버전에 맞는 요청 권한 목록을 고르는 순수 함수. Context 비의존이라 JVM 테스트.

**Files:**
- Create: `app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingPermissions.kt`
- Test: `app/src/test/java/org/mcsmtp/wayfinder/OnboardingPermissionsTest.kt`

**Interfaces:**
- Produces: `object OnboardingPermissions { fun required(sdkInt: Int): List<String> }`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package org.mcsmtp.wayfinder

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mcsmtp.wayfinder.onboarding.OnboardingPermissions

class OnboardingPermissionsTest {

    @Test
    fun `API 26 은 마이크만`() {
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), OnboardingPermissions.required(26))
    }

    @Test
    fun `API 31 은 마이크 + 블루투스`() {
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_SCAN),
            OnboardingPermissions.required(Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `API 33 은 마이크 + 블루투스 + 알림`() {
        assertEquals(
            listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            OnboardingPermissions.required(Build.VERSION_CODES.TIRAMISU),
        )
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew testDebugUnitTest --tests "org.mcsmtp.wayfinder.OnboardingPermissionsTest"`
Expected: FAIL — `Unresolved reference: OnboardingPermissions`.

- [ ] **Step 3: 최소 구현**

```kotlin
package org.mcsmtp.wayfinder.onboarding

import android.Manifest
import android.os.Build

/**
 * 온보딩에서 요청할 런타임 권한을 SDK에 맞게 고른다.
 * BLUETOOTH_SCAN 은 API 31+, POST_NOTIFICATIONS 는 API 33+ 에서만 런타임 팝업이 뜬다.
 * Context 비의존이라 JVM 유닛 테스트로 검증한다.
 */
object OnboardingPermissions {
    fun required(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "org.mcsmtp.wayfinder.OnboardingPermissionsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingPermissions.kt app/src/test/java/org/mcsmtp/wayfinder/OnboardingPermissionsTest.kt
git commit -m "feat: 온보딩 SDK별 권한 선택 로직 + 테스트"
```

---

### Task 2: 온보딩 순서 로직 (OnboardingFlow, 순수·TDD)

4단계 순서와 건너뛰기 규칙. Context 비의존 → JVM 테스트.

**Files:**
- Create: `app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingFlow.kt`
- Test: `app/src/test/java/org/mcsmtp/wayfinder/OnboardingFlowTest.kt`

**Interfaces:**
- Produces:
  - `enum class OnboardingStep { INTRO, PRACTICE, PERMISSIONS, DONE }`
  - `object OnboardingFlow { fun next(step: OnboardingStep): OnboardingStep; fun skipTarget(): OnboardingStep; fun canSkip(step: OnboardingStep): Boolean }`

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package org.mcsmtp.wayfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mcsmtp.wayfinder.onboarding.OnboardingFlow
import org.mcsmtp.wayfinder.onboarding.OnboardingStep

class OnboardingFlowTest {

    @Test
    fun `순서대로 다음 단계로`() {
        assertEquals(OnboardingStep.PRACTICE, OnboardingFlow.next(OnboardingStep.INTRO))
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingFlow.next(OnboardingStep.PRACTICE))
        assertEquals(OnboardingStep.DONE, OnboardingFlow.next(OnboardingStep.PERMISSIONS))
    }

    @Test
    fun `DONE 의 다음은 DONE`() {
        assertEquals(OnboardingStep.DONE, OnboardingFlow.next(OnboardingStep.DONE))
    }

    @Test
    fun `건너뛰기는 권한 단계로 점프`() {
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingFlow.skipTarget())
    }

    @Test
    fun `소개와 연습만 건너뛸 수 있다`() {
        assertTrue(OnboardingFlow.canSkip(OnboardingStep.INTRO))
        assertTrue(OnboardingFlow.canSkip(OnboardingStep.PRACTICE))
        assertFalse(OnboardingFlow.canSkip(OnboardingStep.PERMISSIONS))
        assertFalse(OnboardingFlow.canSkip(OnboardingStep.DONE))
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew testDebugUnitTest --tests "org.mcsmtp.wayfinder.OnboardingFlowTest"`
Expected: FAIL — `Unresolved reference: OnboardingFlow`.

- [ ] **Step 3: 최소 구현**

```kotlin
package org.mcsmtp.wayfinder.onboarding

/** 온보딩 4단계. 소개·연습은 건너뛸 수 있고, 권한은 필수 관문이다. */
enum class OnboardingStep { INTRO, PRACTICE, PERMISSIONS, DONE }

/**
 * 온보딩 단계 순서 로직. Context 비의존 → JVM 유닛 테스트.
 * 건너뛰면 소개·연습을 지나 권한 단계로 점프한다.
 */
object OnboardingFlow {
    private val order = listOf(
        OnboardingStep.INTRO,
        OnboardingStep.PRACTICE,
        OnboardingStep.PERMISSIONS,
        OnboardingStep.DONE,
    )

    fun next(step: OnboardingStep): OnboardingStep {
        val i = order.indexOf(step)
        return if (i < 0 || i == order.lastIndex) OnboardingStep.DONE else order[i + 1]
    }

    fun skipTarget(): OnboardingStep = OnboardingStep.PERMISSIONS

    fun canSkip(step: OnboardingStep): Boolean =
        step == OnboardingStep.INTRO || step == OnboardingStep.PRACTICE
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "org.mcsmtp.wayfinder.OnboardingFlowTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingFlow.kt app/src/test/java/org/mcsmtp/wayfinder/OnboardingFlowTest.kt
git commit -m "feat: 온보딩 단계 순서 로직 + 테스트"
```

---

### Task 3: 문자열 리소스

온보딩 전 단계의 화면 문구·음성 문구를 추가한다.

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (닫는 `</resources>` 앞에 삽입)

- [ ] **Step 1: 문자열 추가**

`</resources>` 바로 앞에 붙인다:

```xml
    <!-- 온보딩 -->
    <string name="onboarding_intro_title">길을 안내할게요</string>
    <string name="onboarding_intro_sub">화면을 두 번 두드리면 다음으로</string>
    <string name="onboarding_intro_speech">길을 안내할게요. 목적지를 말하면 소리로 안내해요. 다음으로 넘어가려면 화면을 두 번 두드리세요.</string>

    <string name="onboarding_practice_title">이렇게 써요</string>
    <string name="onboarding_practice_sub">화면을 두 번 두드리면 말하기</string>
    <string name="onboarding_practice_prompt">지금 두 번 두드려 보세요</string>
    <string name="onboarding_practice_prompt_sub">성공하면 진동과 함께 넘어가요</string>
    <string name="onboarding_practice_speech">말하기는 화면을 두 번 두드리면 돼요. 지금 한번 해보세요.</string>
    <string name="onboarding_practice_success">좋아요. 이렇게 하면 돼요.</string>
    <string name="onboarding_practice_autopass">괜찮아요. 넘어갈게요.</string>
    <string name="onboarding_practice_hint">5초간 두드리지 않으면 자동으로 넘어갑니다</string>

    <string name="onboarding_perm_title">허락이 필요해요</string>
    <string name="onboarding_perm_sub">안내에 아래 권한을 사용해요</string>
    <string name="onboarding_perm_speech">안내에 마이크, 블루투스, 알림을 사용해요. 허용해 주세요.</string>
    <string name="onboarding_perm_mic">마이크</string>
    <string name="onboarding_perm_mic_sub">목적지를 말로 알려주세요</string>
    <string name="onboarding_perm_bt">블루투스</string>
    <string name="onboarding_perm_bt_sub">실내 위치를 확인하는 데 씁니다</string>
    <string name="onboarding_perm_noti">알림</string>
    <string name="onboarding_perm_noti_sub">안내 중 상태를 표시합니다</string>
    <string name="onboarding_perm_allow">권한 허용하고 계속</string>
    <string name="onboarding_perm_allow_desc">권한 허용하고 계속, 버튼</string>
    <string name="onboarding_perm_hint">허용하지 않아도 목록으로 안내받을 수 있어요</string>

    <string name="onboarding_mic_denied_speech">음성으로 목적지를 말하려면 마이크가 필요해요.</string>
    <string name="onboarding_open_settings">설정 열기</string>
    <string name="onboarding_open_settings_sub">권한 화면으로 이동</string>
    <string name="onboarding_continue">계속</string>

    <string name="onboarding_done_title">준비됐어요</string>
    <string name="onboarding_done_sub">화면을 두 번 두드리세요</string>
    <string name="onboarding_done_speech">준비됐어요. 이제 어디로 갈지 정해요.</string>
    <string name="onboarding_start">시작하기</string>

    <string name="onboarding_next">다음</string>
    <string name="onboarding_skip">건너뛰기</string>

    <!-- 홈 사용법 재열람 -->
    <string name="home_howto_action">사용법 듣기</string>
    <string name="home_howto_speech">말하려면 화면을 두 번 두드리세요. 목적지를 말하거나 목록에서 고를 수 있어요.</string>
```

- [ ] **Step 2: 빌드로 리소스 검증**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL (문자열 XML 문법 오류 없음).

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat: 온보딩 문자열 리소스"
```

---

### Task 4: 온보딩 레이아웃 (fragment_onboarding.xml)

한 레이아웃을 단계별로 재구성한다. 히어로(제목·소제목·아이콘) + 단계별 추가 영역(연습 민트 카드 / 권한 목록) + 하단 액션·건너뛰기·힌트.

**Files:**
- Create: `app/src/main/res/layout/fragment_onboarding.xml`

**Interfaces:**
- Produces (id): `ob_icon_wrap`, `ob_icon`, `ob_title`, `ob_sub`, `ob_practice_card`, `ob_practice_title`, `ob_practice_sub`, `ob_perm_list`, `ob_settings`, `ob_action`, `ob_action_label`, `ob_skip`, `ob_hint`.

- [ ] **Step 1: 레이아웃 작성**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  첫 실행 온보딩. 한 레이아웃을 OnboardingFragment 가 단계별로 재구성한다.
  히어로(제목·소제목·아이콘) 아래에 연습 민트 카드 또는 권한 목록이 토글되고,
  하단에 액션 타일·건너뛰기·힌트가 놓인다.
-->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/screen_bg"
    android:paddingStart="@dimen/screen_pad_h"
    android:paddingEnd="@dimen/screen_pad_h"
    android:paddingTop="@dimen/screen_pad_top"
    android:paddingBottom="@dimen/screen_pad_bottom">

    <LinearLayout
        style="@style/Card.Hero"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_horizontal">

        <FrameLayout
            android:id="@+id/ob_icon_wrap"
            android:layout_width="@dimen/mic_size"
            android:layout_height="@dimen/mic_size"
            android:layout_marginBottom="@dimen/gap_hero"
            android:background="@drawable/circle_mint"
            android:importantForAccessibility="no">

            <ImageView
                android:id="@+id/ob_icon"
                android:layout_width="37dp"
                android:layout_height="37dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_arrow_right"
                android:contentDescription="@null" />
        </FrameLayout>

        <TextView
            android:id="@+id/ob_title"
            style="@style/Text.Headline"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:text="@string/onboarding_intro_title" />

        <TextView
            android:id="@+id/ob_sub"
            style="@style/Text.Body"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_hero"
            android:gravity="center"
            android:text="@string/onboarding_intro_sub" />
    </LinearLayout>

    <!-- 연습 단계: 민트 카드 (해야 할 동작) -->
    <LinearLayout
        android:id="@+id/ob_practice_card"
        style="@style/Card.Sub"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/screen_gap"
        android:background="@drawable/bg_tile_mint"
        android:visibility="gone"
        android:importantForAccessibility="noHideDescendants">

        <TextView
            android:id="@+id/ob_practice_title"
            style="@style/Text.Title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/on_mint"
            android:text="@string/onboarding_practice_prompt" />

        <TextView
            android:id="@+id/ob_practice_sub"
            style="@style/Text.Body"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_text"
            android:textColor="@color/on_mint"
            android:text="@string/onboarding_practice_prompt_sub" />
    </LinearLayout>

    <!-- 권한 단계: 3개 권한 행 -->
    <LinearLayout
        android:id="@+id/ob_perm_list"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/screen_gap"
        android:orientation="vertical"
        android:visibility="gone"
        android:importantForAccessibility="noHideDescendants">

        <LinearLayout style="@style/Card.Sub"
            android:layout_width="match_parent" android:layout_height="wrap_content">
            <TextView style="@style/Text.Title"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/onboarding_perm_mic" />
            <TextView style="@style/Text.Body"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_text"
                android:text="@string/onboarding_perm_mic_sub" />
        </LinearLayout>

        <LinearLayout style="@style/Card.Sub"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/screen_gap">
            <TextView style="@style/Text.Title"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/onboarding_perm_bt" />
            <TextView style="@style/Text.Body"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_text"
                android:text="@string/onboarding_perm_bt_sub" />
        </LinearLayout>

        <LinearLayout style="@style/Card.Sub"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/screen_gap">
            <TextView style="@style/Text.Title"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="@string/onboarding_perm_noti" />
            <TextView style="@style/Text.Body"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/gap_text"
                android:text="@string/onboarding_perm_noti_sub" />
        </LinearLayout>
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <!-- 마이크 영구 차단 시에만 -->
    <LinearLayout
        android:id="@+id/ob_settings"
        style="@style/Tile"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="@dimen/screen_gap"
        android:background="@drawable/bg_tile_gray"
        android:visibility="gone"
        android:contentDescription="@string/onboarding_open_settings">

        <TextView style="@style/Text.Title"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="@string/onboarding_open_settings"
            android:importantForAccessibility="no" />
        <TextView style="@style/Text.Body"
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/gap_text"
            android:text="@string/onboarding_open_settings_sub"
            android:importantForAccessibility="no" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/ob_action"
        style="@style/Tile"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_tile_mint"
        android:contentDescription="@string/onboarding_next">

        <TextView
            android:id="@+id/ob_action_label"
            style="@style/Text.Title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/onboarding_next"
            android:importantForAccessibility="no" />
    </LinearLayout>

    <TextView
        android:id="@+id/ob_skip"
        style="@style/Text.Body"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/screen_gap"
        android:gravity="center"
        android:focusable="true"
        android:clickable="true"
        android:text="@string/onboarding_skip" />

    <TextView
        android:id="@+id/ob_hint"
        style="@style/Text.Body"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="@dimen/screen_gap"
        android:gravity="center"
        android:importantForAccessibility="no"
        android:text="" />

</LinearLayout>
```

- [ ] **Step 2: 빌드 검증**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/res/layout/fragment_onboarding.xml
git commit -m "feat: 온보딩 레이아웃"
```

---

### Task 5: OnboardingPrefs + OnboardingFragment

첫 실행 플래그 저장소와, 4단계를 구동하는 Fragment. Fragment는 `MainActivity.startAfterOnboarding()`(Task 6에서 추가)을 호출해 안내로 넘어간다.

**Files:**
- Create: `app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingPrefs.kt`
- Create: `app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingFragment.kt`

**Interfaces:**
- Consumes: `OnboardingFlow`, `OnboardingStep`, `OnboardingPermissions` (Task 1·2); `MainActivity.speech`/`haptics`/`moveAccessibilityFocus` (기존); `MainActivity.startAfterOnboarding()` (Task 6).
- Produces:
  - `class OnboardingPrefs(context: Context) { fun isDone(): Boolean; fun markDone() }`
  - `class OnboardingFragment : Fragment()`

- [ ] **Step 1: OnboardingPrefs 작성**

```kotlin
package org.mcsmtp.wayfinder.onboarding

import android.content.Context

/** 온보딩 1회성 완료 플래그. */
class OnboardingPrefs(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(KEY_DONE, false)
    fun markDone() = prefs.edit().putBoolean(KEY_DONE, true).apply()

    private companion object {
        const val KEY_DONE = "onboarding_done"
    }
}
```

- [ ] **Step 2: OnboardingFragment 작성**

```kotlin
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
```

- [ ] **Step 3: 컴파일 확인 (Task 6 전이라 startAfterOnboarding 미정의 → 다음 Task와 함께 빌드)**

이 Task 단독으로는 `act.startAfterOnboarding()`이 아직 없어 컴파일되지 않는다. Task 6에서 메서드를 추가한 뒤 함께 빌드·검증한다. 여기서는 파일만 커밋한다.

- [ ] **Step 4: 커밋**

```bash
git add app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingPrefs.kt app/src/main/java/org/mcsmtp/wayfinder/onboarding/OnboardingFragment.kt
git commit -m "feat: 온보딩 Fragment + 첫 실행 플래그 저장소"
```

---

### Task 6: MainActivity 첫 실행 관문

첫 실행이면 온보딩을, 아니면 기존 진입(SELECTING_PLACE)을 띄운다. 온보딩 완료 시 안내로 전환하는 `startAfterOnboarding()`을 추가한다.

**Files:**
- Modify: `app/src/main/java/org/mcsmtp/wayfinder/MainActivity.kt`

**Interfaces:**
- Consumes: `OnboardingPrefs`, `OnboardingFragment` (Task 5).
- Produces: `fun MainActivity.startAfterOnboarding()` (Fragment가 호출).

- [ ] **Step 1: import 추가**

`MainActivity.kt` 상단 import 블록에 추가:

```kotlin
import org.mcsmtp.wayfinder.onboarding.OnboardingFragment
import org.mcsmtp.wayfinder.onboarding.OnboardingPrefs
```

- [ ] **Step 2: onCreate 첫 화면 분기 교체**

`onCreate` 끝의 아래 줄을

```kotlin
        if (savedInstanceState == null) render(NavState.SELECTING_PLACE)
```

다음으로 바꾼다:

```kotlin
        if (savedInstanceState == null) {
            if (OnboardingPrefs(this).isDone()) render(NavState.SELECTING_PLACE)
            else showOnboarding()
        }
```

- [ ] **Step 3: 온보딩 표시·완료 메서드 추가**

`render(state: NavState)` 함수 바로 위에 추가:

```kotlin
    /** 첫 실행 온보딩을 띄운다. 상태 머신 바깥의 관문이라 별도 태그로 붙인다. */
    private fun showOnboarding() {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container, OnboardingFragment(), TAG_ONBOARDING)
        }
    }

    /** 온보딩이 끝나면 호출된다. 안내 진입 화면으로 전환한다. */
    fun startAfterOnboarding() {
        render(NavState.SELECTING_PLACE)
    }
```

그리고 `MainActivity` 안의 companion 이 없으므로, 클래스 최하단(마지막 `}` 앞)에 태그 상수를 추가:

```kotlin
    private companion object {
        const val TAG_ONBOARDING = "ONBOARDING"
    }
```

- [ ] **Step 4: 빌드 + 설치**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, `Installed on 1 device.` (Task 5의 `startAfterOnboarding` 참조가 이제 해결됨).

- [ ] **Step 5: 에뮬레이터 첫 실행 검증**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell pm clear org.mcsmtp.wayfinder
adb shell am start -n org.mcsmtp.wayfinder/.MainActivity
```

기대: 앱이 **온보딩 소개 화면**("길을 안내할게요")으로 시작한다. 스크린샷으로 확인:

```bash
adb exec-out screencap -p > /tmp/ob_intro.png
```

- [ ] **Step 6: 4단계 통과 후 재실행 시 온보딩 스킵 검증**

액션 타일을 눌러 소개→연습→권한(팝업 허용)→완료→건물 선택까지 진행한 뒤:

```bash
adb shell am force-stop org.mcsmtp.wayfinder
adb shell am start -n org.mcsmtp.wayfinder/.MainActivity
adb exec-out screencap -p > /tmp/ob_second_run.png
```

기대: 두 번째 실행은 온보딩 없이 **건물 선택 화면**으로 바로 시작(플래그 저장 확인).

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/org/mcsmtp/wayfinder/MainActivity.kt
git commit -m "feat: 첫 실행 온보딩 관문 + 완료 후 안내 전환"
```

---

### Task 7: 홈 사용법 재열람 (길게 누르기)

온보딩은 1회성이라, 홈에서 길게 눌러 조작법을 다시 들을 수 있게 한다. TalkBack에서 발견되도록 커스텀 접근성 액션도 건다.

**Files:**
- Modify: `app/src/main/java/org/mcsmtp/wayfinder/ui/HomeFragment.kt`

**Interfaces:**
- Consumes: `MainActivity.speech`, `R.string.home_howto_speech`, `R.string.home_howto_action` (Task 3).

- [ ] **Step 1: HomeFragment 현재 구조 확인**

Run: `sed -n '1,60p' app/src/main/java/org/mcsmtp/wayfinder/ui/HomeFragment.kt`
루트 뷰(`onViewCreated`의 `view`)와 `act`(MainActivity) 접근 방식을 확인한다.

- [ ] **Step 2: 길게 누르기 + 접근성 액션 추가**

`HomeFragment.onViewCreated`의 끝(기존 로직 뒤)에 아래를 추가한다. `act`는 이미 `requireActivity() as MainActivity`로 잡혀 있다고 가정하며, 없으면 그 줄을 먼저 추가한다:

```kotlin
        // 온보딩은 1회성이므로, 조작법을 홈에서 길게 눌러 다시 들을 수 있게 한다.
        view.setOnLongClickListener {
            act.speech.speak(view, getString(R.string.home_howto_speech))
            true
        }
        // TalkBack에서 길게 누르기는 발견이 어려워, 커스텀 접근성 액션으로도 노출한다.
        ViewCompat.addAccessibilityAction(
            view, getString(R.string.home_howto_action)
        ) { _, _ ->
            act.speech.speak(view, getString(R.string.home_howto_speech))
            true
        }
```

`HomeFragment.kt` 상단 import에 없으면 추가:

```kotlin
import androidx.core.view.ViewCompat
```

- [ ] **Step 3: 빌드 + 설치**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, `Installed on 1 device.`

- [ ] **Step 4: 에뮬레이터 검증**

건물 선택 → 홈 진입 후, 홈 화면을 길게 누른다:

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell input swipe 540 1200 540 1200 800
```

기대: "말하려면 화면을 두 번 두드리세요…" 음성이 재생된다(TTS 로그 또는 소리로 확인). 화면은 그대로 홈.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/org/mcsmtp/wayfinder/ui/HomeFragment.kt
git commit -m "feat: 홈에서 길게 눌러 사용법 다시 듣기"
```

---

### Task 8: 전체 흐름 검증 + 유닛 테스트 회귀

새 코드 전체가 함께 도는지 확인한다.

**Files:** (없음 — 검증만)

- [ ] **Step 1: 유닛 테스트 전체 통과**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL — 기존 `DestinationMatcherTest` + 신규 `OnboardingPermissionsTest`·`OnboardingFlowTest` 모두 PASS.

- [ ] **Step 2: 클린 첫 실행 전체 워크스루**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell pm clear org.mcsmtp.wayfinder
adb shell am start -n org.mcsmtp.wayfinder/.MainActivity
```

각 단계에서 스크린샷을 찍어 설계와 대조:
- 소개("길을 안내할게요") → 액션 탭
- 연습("이렇게 써요" + 민트 카드) → 탭하면 진동 + 다음 / 5초 미조작 시 자동 통과
- 권한("허락이 필요해요") → 액션 탭 → 마이크·(SDK에 따라)블루투스·알림 팝업 순차
- 완료("준비됐어요") → 액션 탭 → 건물 선택

```bash
adb exec-out screencap -p > /tmp/ob_walk_$(date +%s).png
```

- [ ] **Step 3: 건너뛰기 경로 검증**

`pm clear` 후 재실행, 소개에서 "건너뛰기" 탭 → 곧장 권한 단계로 점프하는지 확인.

- [ ] **Step 4: 권한 거부 경로 검증**

`pm clear` 후 재실행, 권한 단계에서 마이크 팝업을 거부 → "마이크가 필요해요" 안내 + 액션이 "계속"으로 바뀌는지, 두 번째 거부(영구 차단) 시 "설정 열기"가 노출되는지 확인. "계속" 탭 시 완료로 진행되는지.

- [ ] **Step 5: 최종 커밋(문서 체크 반영, 코드 변경 없으면 생략)**

검증 중 수정이 있었으면 해당 Task로 돌아가 고치고 커밋한다. 없으면 이 Task는 커밋 없이 종료.

---

## Self-Review 결과

- **Spec coverage:** 설계의 결정 1~5(권한 범위 B·건너뛰기 A·연습 B·거부 B·조작 모델), 아키텍처(관문·플래그·단일 Fragment), 4단계 흐름, 권한 SDK 분기, 홈 재열람 — 모두 Task 1~7에 매핑됨. 범위 밖(스플래시·로그인·설정)은 계획에 없음(의도적).
- **Placeholder scan:** 코드 단계는 모두 실제 코드 포함. "적절히 처리" 류 없음.
- **Type consistency:** `OnboardingStep`·`OnboardingFlow.next/skipTarget/canSkip`·`OnboardingPermissions.required`·`OnboardingPrefs.isDone/markDone`·`MainActivity.startAfterOnboarding`·`TAG_ONBOARDING` 이름이 정의처와 사용처에서 일치.
- **알려진 제약:** 헤드라인은 앱에서 Ria Sans 유지(Figma 자동화 환경 한계와 무관, 앱은 res/font 번들 사용). Task 5는 Task 6의 `startAfterOnboarding` 없이는 컴파일 불가 — 두 Task를 연달아 실행한다(Task 5 Step 3에 명시).
