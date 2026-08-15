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
