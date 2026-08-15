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
