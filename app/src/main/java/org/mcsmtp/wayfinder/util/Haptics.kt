package org.mcsmtp.wayfinder.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 진동 3종.
 *
 * 방향(좌/우)은 진동으로 구분하지 않는다. 사람이 진동 패턴으로 방향을 구별하기 어렵다.
 * 방향은 음성으로만 전달하고, 진동은 주의 신호로만 쓴다.
 *
 * 무음 구간에는 진동을 주지 않는다.
 */
class Haptics(context: Context) {

    enum class Pattern { GUIDE, WARN, ARRIVE }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun play(pattern: Pattern?) {
        val v = vibrator ?: return
        if (pattern == null || !v.hasVibrator()) return

        val effect = when (pattern) {
            // 일반 안내 발화 직전
            Pattern.GUIDE -> VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            // 계단 · 경로 이탈
            Pattern.WARN -> VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            // 목적지 도달
            Pattern.ARRIVE -> VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        // 진동은 보조 신호다. 실패해도 안내는 계속돼야 한다.
        // 권한이나 제조사 사정으로 예외가 나도 여기서 삼킨다.
        runCatching { v.vibrate(effect) }
    }

    /** 서버가 내려주는 haptic 문자열을 패턴으로 옮긴다. */
    fun playByName(name: String?) = play(
        when (name) {
            "guide" -> Pattern.GUIDE
            "warn" -> Pattern.WARN
            "arrive" -> Pattern.ARRIVE
            else -> null
        }
    )
}
