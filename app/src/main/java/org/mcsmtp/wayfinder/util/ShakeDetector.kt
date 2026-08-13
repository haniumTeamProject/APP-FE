package org.mcsmtp.wayfinder.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 흔들기 감지 → [다시 듣기].
 *
 * 걸으면서 화면을 보지 않고 쓸 수 있는 유일한 입력이다.
 * TalkBack 제스처와 충돌하지 않는 것도 장점이다.
 *
 * 보행 중에는 몸이 계속 흔들리므로 임계값을 높게 잡고 최소 간격을 둔다.
 * 그렇지 않으면 걷기만 해도 계속 재생된다.
 */
class ShakeDetector(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var onShake: (() -> Unit)? = null
    private var lastShakeAt = 0L

    fun start(onShake: () -> Unit) {
        this.onShake = onShake
        val sensor = accelerometer ?: return
        manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(this)
        onShake = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        // 중력(약 9.8)을 뺀 순수 가속도 크기
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce < SHAKE_THRESHOLD_G) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAt < MIN_INTERVAL_MS) return
        lastShakeAt = now

        onShake?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** 보행 진동으로는 넘지 않는 값. 의도적으로 흔들어야 걸린다. */
        const val SHAKE_THRESHOLD_G = 2.4f

        /** 한 번 흔들면 여러 번 감지되므로 최소 간격을 둔다. */
        const val MIN_INTERVAL_MS = 1500L
    }
}
