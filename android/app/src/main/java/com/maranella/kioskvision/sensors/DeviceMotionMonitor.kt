package com.maranella.kioskvision.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Detects when the phone rotates noticeably, using the gyroscope. Positions measured in the
 * camera frame (OCR boxes) become stale as soon as the phone turns, so guidance pauses until
 * fresh OCR confirms the target again.
 *
 * Devices without a gyroscope report [isAvailable] = false and are always treated as steady;
 * guidance then relies on OCR re-confirmation alone.
 */
/**
 * Whether the phone is currently being moved. Behind an interface so motion-tolerance
 * experiments can drive the pipeline with a known movement profile instead of a real gyroscope.
 */
interface MotionSource {
    val isAvailable: Boolean

    /** `SystemClock.elapsedRealtime()` of the last rotation faster than the threshold. */
    val lastMotionMs: Long

    fun isSteady(nowMs: Long): Boolean
}

class DeviceMotionMonitor(context: Context) : SensorEventListener, MotionSource {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override val isAvailable: Boolean get() = gyroscope != null

    @Volatile
    override var lastMotionMs: Long = Long.MIN_VALUE / 2
        private set

    fun start() {
        gyroscope?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun isSteady(nowMs: Long): Boolean = nowMs - lastMotionMs > SETTLE_MS

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        if (sqrt(x * x + y * y + z * z) > MOTION_THRESHOLD_RAD_PER_S) {
            lastMotionMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** ~17°/s; normal hand tremor while holding a phone stays well below this. */
        const val MOTION_THRESHOLD_RAD_PER_S = 0.3f
        const val SETTLE_MS = 400L
    }
}
