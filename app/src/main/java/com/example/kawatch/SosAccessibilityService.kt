
package com.example.kawatch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class SosAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isVolumeUpPressed = false
    private var isVolumeDownPressed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SosAccessibilityService", "Service connected")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode
        val action = event?.action

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    isVolumeUpPressed = true
                    checkDualPress()
                } else if (action == KeyEvent.ACTION_UP) {
                    isVolumeUpPressed = false
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    isVolumeDownPressed = true
                    checkDualPress()
                } else if (action == KeyEvent.ACTION_UP) {
                    isVolumeDownPressed = false
                }
            }
        }
        
        // We return false to allow the system to handle volume changes normally 
        // unless we specifically want to block them.
        return super.onKeyEvent(event)
    }

    private fun checkDualPress() {
        if (isVolumeUpPressed && isVolumeDownPressed) {
            Log.d("SosAccessibilityService", "Both volume keys pressed. Triggering SOS.")
            // Reset states to prevent multiple triggers from one hold
            isVolumeUpPressed = false
            isVolumeDownPressed = false
            triggerSosAction()
        }
    }

    private fun triggerSosAction() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_TRIGGER_SOS_FROM_KEYS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Not needed */ }

    override fun onInterrupt() { /* Not needed */ }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.d("SosAccessibilityService", "Service destroyed.")
    }
}
