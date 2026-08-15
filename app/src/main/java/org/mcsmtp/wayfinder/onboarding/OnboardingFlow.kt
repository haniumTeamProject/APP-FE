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
