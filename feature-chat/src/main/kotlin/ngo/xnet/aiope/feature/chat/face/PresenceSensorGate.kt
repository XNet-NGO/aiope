package ngo.xnet.aiope.feature.chat.face

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides whether now is a sensible moment to run face identification, using cheap on-device
 * sensors. The goal is to only trigger the (relatively expensive, privacy-sensitive) camera +
 * inference when the user is plausibly holding the device and looking at it:
 *
 *  - Light sensor above a floor  -> not in a pocket / face-down.
 *  - Accelerometer near 1g and reasonably stable -> being held, not in free-fall/jostling.
 *  - Low gyro rotation rate      -> settled, not mid-motion.
 *
 * Sampling is brief and one-shot: [evaluate] registers listeners, collects a short window, then
 * unregisters and returns a verdict. This avoids continuous sensor drain.
 */
class PresenceSensorGate(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    companion object {
        private const val LIGHT_FLOOR_LUX = 8f          // below this ~ pocket/dark/face-down
        private const val GRAVITY = 9.81f
        private const val ACCEL_TOLERANCE = 2.5f        // |‖a‖ - g| tolerance (m/s^2)
        private const val GYRO_MAX_RAD_S = 1.2f         // max rotation rate to be "settled"
        private const val SAMPLE_MS = 350L
    }

    @Volatile private var lastLux: Float = Float.NaN
    @Volatile private var lastAccelMag: Float = Float.NaN
    @Volatile private var maxGyro: Float = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_LIGHT -> lastLux = e.values[0]
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                    lastAccelMag = sqrt(x * x + y * y + z * z)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val r = sqrt(
                        e.values[0] * e.values[0] +
                            e.values[1] * e.values[1] +
                            e.values[2] * e.values[2],
                    )
                    if (r > maxGyro) maxGyro = r
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Sample sensors briefly and decide whether to proceed with identification.
     * Blocking for ~[SAMPLE_MS]; call off the main thread.
     */
    fun shouldIdentify(): Boolean {
        lastLux = Float.NaN
        lastAccelMag = Float.NaN
        maxGyro = 0f
        val delay = SensorManager.SENSOR_DELAY_UI
        accel?.let { sm.registerListener(listener, it, delay) }
        gyro?.let { sm.registerListener(listener, it, delay) }
        light?.let { sm.registerListener(listener, it, delay) }
        try {
            Thread.sleep(SAMPLE_MS)
        } catch (_: InterruptedException) {
        } finally {
            sm.unregisterListener(listener)
        }

        // Light: if a light sensor exists and reads dark, skip. If no sensor, don't block on it.
        if (light != null && !lastLux.isNaN() && lastLux < LIGHT_FLOOR_LUX) return false
        // Accelerometer: require near-1g (device held roughly still, not in violent motion).
        if (accel != null) {
            if (lastAccelMag.isNaN()) return false
            if (abs(lastAccelMag - GRAVITY) > ACCEL_TOLERANCE) return false
        }
        // Gyro: require settled (low peak rotation).
        if (gyro != null && maxGyro > GYRO_MAX_RAD_S) return false
        return true
    }
}
