package com.example.coupleapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.example.coupleapp.service.SignificantLocationManager
import com.example.coupleapp.worker.BackgroundLocationWorker
import com.example.coupleapp.worker.DailySleepResetWorker
import com.example.coupleapp.worker.ForceSleepSyncWorker
import com.example.coupleapp.worker.GoogleSleepSyncWorker
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receiver to restart location tracking when system events occur.
 * 
 * This receiver handles multiple system events that may kill our app or clear our tracking:
 * - BOOT_COMPLETED: Device restarted
 * - MY_PACKAGE_REPLACED: App was updated
 * - LOCKED_BOOT_COMPLETED: Device unlocked after restart
 * - QUICKBOOT: Samsung/HTC quick boot
 * 
 * ================================================================
 * RECOVERY STRATEGY:
 * ================================================================
 * After boot/update, this receiver restarts all 3 background layers:
 * - Layer 1: SignificantLocationManager (PASSIVE piggyback)
 * - Layer 2: BackgroundLocationWorker (Active LOW_POWER)
 * - Layer 3: LocationAlarmManager (Reliable fallback)
 * 
 * Note: Layer 4 (LocationTrackingService) is NOT started here
 * because it's a foreground service that requires user interaction.
 * ================================================================
 */
class LocationRestartReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "LocationRestartReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // HTC/Samsung quick boot
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                restartLocationTracking(context)
                restartSleepTracking(context)
            }
        }
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private fun restartLocationTracking(context: Context) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "No user logged in, skipping location restart")
            return
        }
        
        Log.d(TAG, "Restarting location tracking for user: ${currentUser.uid}")
        
        try {
            // Layer 1: Restart SignificantLocationManager (PASSIVE - piggyback from other apps)
            // This is zero-battery when no other app uses GPS
            SignificantLocationManager.getInstance(context).startTracking()
            Log.d(TAG, "✅ Layer 1: SignificantLocationManager restarted (PASSIVE mode)")
            
            // Layer 2: Ensure BackgroundLocationWorker is scheduled (Active LOW_POWER)
            // This actively requests location every 15-20 minutes
            BackgroundLocationWorker.schedule(context)
            Log.d(TAG, "✅ Layer 2: BackgroundLocationWorker scheduled")
            
            // Layer 3: Schedule persistent alarm for extra reliability
            // Guaranteed to fire even in Doze mode
            LocationAlarmManager.scheduleLocationAlarm(context)
            Log.d(TAG, "✅ Layer 3: LocationAlarm scheduled")
            
            // Note: Layer 4 (LocationTrackingService) is NOT started here
            // It's a foreground service started only when user opens DistanceScreen
            
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting location tracking", e)
        }
    }
    
    /**
     * Restart Google Sleep API tracking after boot.
     * This ensures sleep tracking continues even after device restart.
     * 
     * ENHANCED: Now schedules all sleep-related workers for complete coverage
     */
    private fun restartSleepTracking(context: Context) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.d(TAG, "No user logged in, skipping sleep tracking restart")
            return
        }
        
        Log.d(TAG, "Restarting sleep tracking for user: ${currentUser.uid}")
        
        scope.launch {
            try {
                // CRITICAL FIX: Schedule daily reset worker FIRST
                // This ensures the alreadySynced flag resets properly each day
                DailySleepResetWorker.scheduleDailyReset(context)
                Log.d(TAG, "✅ Daily sleep reset worker scheduled")
                
                // Re-register Google Sleep API (Layer 1)
                val googleSleepApiManager = GoogleSleepApiManager(context)
                
                if (googleSleepApiManager.hasActivityRecognitionPermission()) {
                    // Use new ensureRegistered with autoEnable=true for recovery after boot
                    val registered = googleSleepApiManager.ensureRegistered(autoEnable = true)
                    if (registered) {
                        Log.d(TAG, "✅ Layer 1: Google Sleep API registered")
                    } else {
                        Log.w(TAG, "⚠️ Layer 1: Failed to register Google Sleep API")
                    }
                }
                
                // Schedule Google Sleep sync workers (Layer 2)
                GoogleSleepSyncWorker.schedulePeriodicSync(context)
                GoogleSleepSyncWorker.scheduleAggressiveMorningSync(context)
                Log.d(TAG, "✅ Layer 2: Google Sleep sync workers scheduled")
                
                // Schedule Sleep AlarmManager (Layer 3 - most reliable)
                SleepAlarmManager.scheduleAllMorningAlarms(context)
                Log.d(TAG, "✅ Layer 3: Sleep alarms scheduled")
                
                // CRITICAL FIX: Schedule Force Sleep Sync (Layer 4 - backup)
                // This ensures we get data even if all other layers fail
                ForceSleepSyncWorker.scheduleForceSync(context)
                Log.d(TAG, "✅ Layer 4: Force sleep sync workers scheduled")
                
                // Check if we need to reset sleep state (new day after boot)
                if (DailySleepResetWorker.needsReset(context)) {
                    Log.d(TAG, "New day detected after boot, triggering immediate reset")
                    DailySleepResetWorker.triggerImmediateReset(context)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting sleep tracking", e)
            }
        }
    }
}
