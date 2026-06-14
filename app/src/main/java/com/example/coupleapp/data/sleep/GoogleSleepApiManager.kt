package com.example.coupleapp.data.sleep

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.coupleapp.receiver.SleepReceiver
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentRequest
import kotlinx.coroutines.tasks.await

/**
 * Manager for Google Sleep API
 * Handles registration and unregistration of sleep tracking
 * 
 * ================================================================
 * CRITICAL FIXES FOR DAILY SLEEP TRACKING:
 * ================================================================
 * 
 * PROBLEM: PendingIntent bị mất sau:
 * - Reboot device
 * - Clear RAM (kill background apps)
 * - App update
 * - Force stop
 * 
 * SOLUTION: 
 * 1. Auto re-register khi phát hiện PendingIntent không tồn tại
 * 2. Check trạng thái thường xuyên hơn
 * 3. Log chi tiết để debug
 * 
 * ================================================================
 */
class GoogleSleepApiManager(private val context: Context) {
    
    companion object {
        private const val TAG = "GoogleSleepApiManager"
        private const val SLEEP_SEGMENT_REQUEST_CODE = 1001
        private const val SLEEP_CLASSIFY_REQUEST_CODE = 1002
        
        // SharedPreferences for tracking registration state
        private const val PREFS_NAME = "google_sleep_api_prefs"
        private const val KEY_LAST_REGISTRATION_TIME = "last_registration_time"
        private const val KEY_REGISTRATION_COUNT = "registration_count"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Check if activity recognition permission is granted
     */
    fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required below Android 10
        }
    }
    
    /**
     * Register for sleep segment updates
     * 
     * ENHANCED: Tracks registration time and count for debugging
     */
    suspend fun registerSleepUpdates(): Result<Unit> {
        return try {
            if (!hasActivityRecognitionPermission()) {
                Log.w(TAG, "registerSleepUpdates: Activity Recognition permission NOT granted")
                return Result.failure(SecurityException("Activity recognition permission not granted"))
            }
            
            Log.d(TAG, "📍 Registering sleep segment updates...")
            
            val pendingIntent = createSleepSegmentPendingIntent()
            val client = ActivityRecognition.getClient(context)
            
            client.requestSleepSegmentUpdates(
                pendingIntent,
                SleepSegmentRequest.getDefaultSleepSegmentRequest()
            ).await()
            
            // Track registration for debugging
            val count = prefs.getInt(KEY_REGISTRATION_COUNT, 0) + 1
            prefs.edit()
                .putLong(KEY_LAST_REGISTRATION_TIME, System.currentTimeMillis())
                .putInt(KEY_REGISTRATION_COUNT, count)
                .apply()
            
            Log.d(TAG, "✅ Successfully registered for sleep segment updates (count=$count)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering sleep segment updates", e)
            Result.failure(e)
        }
    }
    
    /**
     * Unregister sleep segment updates
     */
    suspend fun unregisterSleepUpdates(): Result<Unit> {
        return try {
            Log.d(TAG, "Unregistering sleep segment updates")
            
            val pendingIntent = createSleepSegmentPendingIntent()
            val client = ActivityRecognition.getClient(context)
            
            client.removeSleepSegmentUpdates(pendingIntent).await()
            
            Log.d(TAG, "Successfully unregistered sleep segment updates")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sleep segment updates", e)
            Result.failure(e)
        }
    }
    
    /**
     * Create PendingIntent for sleep segment events
     */
    private fun createSleepSegmentPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepReceiver::class.java).apply {
            action = SleepReceiver.ACTION_SLEEP_SEGMENT
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        return PendingIntent.getBroadcast(
            context,
            SLEEP_SEGMENT_REQUEST_CODE,
            intent,
            flags
        )
    }
    
    /**
     * Create PendingIntent for sleep classification events
     */
    private fun createSleepClassifyPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepReceiver::class.java).apply {
            action = SleepReceiver.ACTION_SLEEP_CLASSIFY
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        return PendingIntent.getBroadcast(
            context,
            SLEEP_CLASSIFY_REQUEST_CODE,
            intent,
            flags
        )
    }
    
    /**
     * Check if sleep tracking is registered
     * 
     * ENHANCED: Also logs last registration info for debugging
     */
    fun isSleepTrackingRegistered(): Boolean {
        // Check if PendingIntent exists
        val intent = Intent(context, SleepReceiver::class.java).apply {
            action = SleepReceiver.ACTION_SLEEP_SEGMENT
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            SLEEP_SEGMENT_REQUEST_CODE,
            intent,
            flags
        )
        
        val isRegistered = pendingIntent != null
        
        // Update last check time
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
        
        if (!isRegistered) {
            val lastRegTime = prefs.getLong(KEY_LAST_REGISTRATION_TIME, 0L)
            val regCount = prefs.getInt(KEY_REGISTRATION_COUNT, 0)
            
            if (lastRegTime > 0) {
                val hoursSinceReg = (System.currentTimeMillis() - lastRegTime) / 1000 / 60 / 60
                Log.w(TAG, "⚠️ PendingIntent NOT found! Last registered ${hoursSinceReg}h ago (total $regCount times)")
            } else {
                Log.w(TAG, "⚠️ PendingIntent NOT found! Never registered before")
            }
        }
        
        return isRegistered
    }
    
    /**
     * Ensure sleep tracking is registered.
     * Call this frequently to recover from lost PendingIntent.
     * 
     * @param autoEnable If true, will enable even if user hasn't explicitly enabled
     * @return true if already registered or successfully re-registered
     */
    suspend fun ensureRegistered(autoEnable: Boolean = false): Boolean {
        if (!hasActivityRecognitionPermission()) {
            Log.w(TAG, "ensureRegistered: No permission")
            return false
        }
        
        // Check if user explicitly disabled
        val sleepPrefs = context.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)
        val explicitlyDisabled = sleepPrefs.contains("google_sleep_api_enabled") &&
                !sleepPrefs.getBoolean("google_sleep_api_enabled", true)
        
        if (explicitlyDisabled && !autoEnable) {
            Log.d(TAG, "ensureRegistered: Disabled by user")
            return false
        }
        
        if (isSleepTrackingRegistered()) {
            return true
        }
        
        Log.d(TAG, "🔄 ensureRegistered: Re-registering...")
        val result = registerSleepUpdates()
        
        if (result.isSuccess) {
            // Auto-enable if requested or not explicitly set
            if (autoEnable || !sleepPrefs.contains("google_sleep_api_enabled")) {
                sleepPrefs.edit().putBoolean("google_sleep_api_enabled", true).apply()
                Log.d(TAG, "Auto-enabled Google Sleep API")
            }
            return true
        }
        
        return false
    }
    
    /**
     * Get debug info about registration status
     */
    fun getDebugInfo(): String {
        val isRegistered = isSleepTrackingRegistered()
        val lastRegTime = prefs.getLong(KEY_LAST_REGISTRATION_TIME, 0L)
        val regCount = prefs.getInt(KEY_REGISTRATION_COUNT, 0)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        val hasPermission = hasActivityRecognitionPermission()
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        
        return buildString {
            appendLine("=== Google Sleep API Status ===")
            appendLine("Registered: $isRegistered")
            appendLine("Has Permission: $hasPermission")
            appendLine("Total Registrations: $regCount")
            if (lastRegTime > 0) {
                appendLine("Last Registered: ${dateFormat.format(java.util.Date(lastRegTime))}")
            }
            if (lastCheckTime > 0) {
                appendLine("Last Checked: ${dateFormat.format(java.util.Date(lastCheckTime))}")
            }
        }
    }
}
