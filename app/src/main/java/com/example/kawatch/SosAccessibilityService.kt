
package com.example.kawatch

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlin.math.sqrt

class SosAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Shake detection values
    private var shakeTimestamp: Long = 0
    private var shakeCount = 0
    private var resetShakeCountJob: Job? = null

    companion object {
        private const val SHAKE_THRESHOLD_GRAVITY = 2.7f
        private const val SHAKE_DEBOUNCE_MS = 500 // Ignore rapid succession of events from a single shake
        private const val REQUIRED_SHAKE_COUNT = 2
        private const val SHAKE_COUNT_WINDOW_MS = 3000L // Must have 2 shakes within 3 seconds
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SosAccessibilityService", "Service connected")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            Log.d("SosAccessibilityService", "Accelerometer listener registered.")
        } else {
            Log.e("SosAccessibilityService", "Accelerometer not available.")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH

            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                val now = System.currentTimeMillis()
                if (now - shakeTimestamp < SHAKE_DEBOUNCE_MS) {
                    return // Debounce
                }
                shakeTimestamp = now
                handleShake()
            }
        }
    }

    private fun handleShake() {
        shakeCount++
        Log.d("SosAccessibilityService", "Shake detected. Count: $shakeCount")
        resetShakeCountJob?.cancel()

        if (shakeCount >= REQUIRED_SHAKE_COUNT) {
            Log.d("SosAccessibilityService", "Required shakes met. Triggering SOS countdown.")
            triggerShakeAction()
            shakeCount = 0
        } else {
            resetShakeCountJob = scope.launch {
                delay(SHAKE_COUNT_WINDOW_MS)
                if (isActive) {
                    Log.d("SosAccessibilityService", "Resetting shake count window.")
                    shakeCount = 0
                }
            }
        }
    }

    private fun triggerShakeAction() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_TRIGGER_SOS_FROM_SHAKE"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Not needed */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Not needed */ }

    override fun onInterrupt() { /* Not needed */ }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        scope.cancel() // Cancel coroutines
        Log.d("SosAccessibilityService", "Service destroyed and resources released.")
    }
}
