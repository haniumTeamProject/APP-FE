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
