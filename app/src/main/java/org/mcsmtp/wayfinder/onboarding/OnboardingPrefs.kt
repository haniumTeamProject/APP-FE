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
