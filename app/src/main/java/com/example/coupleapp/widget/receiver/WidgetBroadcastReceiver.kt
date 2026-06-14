package com.example.coupleapp.widget.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.example.coupleapp.service.SignificantLocationManager
import com.example.coupleapp.service.SmartGeofenceManager
import com.example.coupleapp.widget.LocationWidgetProvider
import com.example.coupleapp.widget.LocketWidgetProvider
import com.example.coupleapp.widget.MissingWidgetProvider
import com.example.coupleapp.widget.SleepWidgetProvider
import com.example.coupleapp.widget.WidgetManager
import com.example.coupleapp.widget.data.WidgetDataRepository
import com.example.coupleapp.widget.worker.WidgetUpdateWorker
import com.example.coupleapp.worker.BackgroundLocationWorker
import com.example.coupleapp.worker.SleepSyncWorker
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Broadcast receiver for widget-related events
 * Handles system events that should trigger widget updates
 * 
 * Events handled:
 * - Boot completed: Reschedule periodic updates
 * - Time zone changed: Update time-sensitive widgets
 * - Date changed: Reset daily counters in widgets
 * - Custom app events: Update specific widgets
 */
class WidgetBroadcastReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "WidgetBroadcastReceiver"
        
        // Custom actions
        const val ACTION_UPDATE_SLEEP = "com.example.coupleapp.widget.UPDATE_SLEEP"
        const val ACTION_UPDATE_LOCKET = "com.example.coupleapp.widget.UPDATE_LOCKET"
        const val ACTION_UPDATE_MISSING = "com.example.coupleapp.widget.UPDATE_MISSING"
        const val ACTION_UPDATE_LOCATION = "com.example.coupleapp.widget.UPDATE_LOCATION"
        const val ACTION_UPDATE_ALL = "com.example.coupleapp.widget.UPDATE_ALL"
        const val ACTION_NEW_LOCKET_RECEIVED = "com.example.coupleapp.widget.NEW_LOCKET"
        const val ACTION_NEW_MISSING_RECEIVED = "com.example.coupleapp.widget.NEW_MISSING"
        const val ACTION_LOCATION_CHANGED = "com.example.coupleapp.widget.LOCATION_CHANGED"
        
        /**
         * Send broadcast to update specific widget
         */
        fun sendUpdateBroadcast(context: Context, action: String) {
            val intent = Intent(action)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            // System events
            Intent.ACTION_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
            Intent.ACTION_TIMEZONE_CHANGED -> {
                handleTimezoneChanged(context)
            }
            Intent.ACTION_DATE_CHANGED -> {
                handleDateChanged(context)
            }
            Intent.ACTION_TIME_CHANGED -> {
                handleTimeChanged(context)
            }
            
            // Custom widget update events
            ACTION_UPDATE_SLEEP -> {
                SleepWidgetProvider.updateWidgets(context)
            }
            ACTION_UPDATE_LOCKET -> {
                LocketWidgetProvider.updateWidgets(context)
            }
            ACTION_UPDATE_MISSING -> {
                MissingWidgetProvider.updateWidgets(context)
            }
            ACTION_UPDATE_LOCATION -> {
                LocationWidgetProvider.updateWidgets(context)
            }
            ACTION_UPDATE_ALL -> {
                WidgetManager.updateAllWidgets(context)
            }
            
            // Real-time notification events
            ACTION_NEW_LOCKET_RECEIVED -> {
                handleNewLocketReceived(context)
            }
            ACTION_NEW_MISSING_RECEIVED -> {
                handleNewMissingReceived(context)
            }
            ACTION_LOCATION_CHANGED -> {
                handleLocationChanged(context)
            }
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        Log.d(TAG, "Boot completed, rescheduling all background workers")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if user is logged in
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Log.d(TAG, "No user logged in, skipping worker scheduling")
                    return@launch
                }
                
                // ========== CRITICAL: Re-register Google Sleep API ==========
                // PendingIntents are cleared on reboot, must re-register!
                val prefs = context.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)
                val googleSleepApiEnabled = prefs.getBoolean("google_sleep_api_enabled", false)
                
                if (googleSleepApiEnabled) {
                    Log.d(TAG, "Re-registering Google Sleep API after boot")
                    val googleSleepManager = GoogleSleepApiManager(context)
                    if (googleSleepManager.hasActivityRecognitionPermission()) {
                        googleSleepManager.registerSleepUpdates()
                        Log.d(TAG, "✅ Google Sleep API re-registered successfully")
                    } else {
                        Log.w(TAG, "⚠️ Activity Recognition permission not granted")
                    }
                }
                
                // ========== Re-start Significant Location Tracking ==========
                // Location PendingIntents are also cleared on reboot
                Log.d(TAG, "Re-starting significant location tracking")
                SignificantLocationManager.getInstance(context).startTracking()
                
                // ========== Re-schedule Background Location Worker ==========
                BackgroundLocationWorker.schedule(context)
                
                // ========== Re-schedule Location Alarm (MOST RELIABLE) ==========
                // AlarmManager survives clear RAM better than WorkManager
                com.example.coupleapp.receiver.LocationAlarmManager.scheduleLocationAlarm(context)
                
                // ========== Re-register Smart Geofences ==========
                // Geofences are also cleared on reboot!
                Log.d(TAG, "Re-registering smart geofences")
                SmartGeofenceManager.getInstance(context).reRegisterAllGeofences()
                
                // ========== Re-schedule Sleep Sync Workers ==========
                SleepSyncWorker.schedulePeriodicSync(context)
                SleepSyncWorker.scheduleMorningSync(context)
                
                // ========== Reschedule Widget Updates ==========
                WidgetUpdateWorker.schedulePeriodicUpdates(context)
                
                Log.d(TAG, "✅ All background workers rescheduled after boot")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling workers after boot", e)
            }
            
            // Force update all widgets with fresh data (on Main thread)
            CoroutineScope(Dispatchers.Main).launch {
                WidgetManager.forceRefreshAllWidgets(context)
            }
        }
    }
    
    private fun handleTimezoneChanged(context: Context) {
        Log.d(TAG, "Timezone changed, updating widgets")
        CoroutineScope(Dispatchers.Main).launch {
            // Clear cache as timestamps may be affected
            WidgetDataRepository.clearCache(context)
            WidgetManager.forceRefreshAllWidgets(context)
        }
    }
    
    private fun handleDateChanged(context: Context) {
        Log.d(TAG, "Date changed, resetting daily widgets")
        CoroutineScope(Dispatchers.Main).launch {
            // Invalidate caches for date-sensitive data
            WidgetDataRepository.invalidateSleepCache(context)
            WidgetDataRepository.invalidateMissingCache(context)
            
            // Update widgets
            SleepWidgetProvider.updateWidgets(context)
            MissingWidgetProvider.updateWidgets(context)
        }
    }
    
    private fun handleTimeChanged(context: Context) {
        Log.d(TAG, "Time changed, checking bedtime reminder")
        CoroutineScope(Dispatchers.Main).launch {
            // Update sleep widget for bedtime reminder
            SleepWidgetProvider.updateWidgets(context)
        }
    }
    
    private fun handleNewLocketReceived(context: Context) {
        Log.d(TAG, "New Locket received, updating widget")
        CoroutineScope(Dispatchers.Main).launch {
            WidgetDataRepository.invalidateLocketCache(context)
            WidgetUpdateWorker.requestImmediateUpdate(context, WidgetUpdateWorker.WIDGET_TYPE_LOCKET)
        }
    }
    
    private fun handleNewMissingReceived(context: Context) {
        Log.d(TAG, "New Missing received, updating widget")
        CoroutineScope(Dispatchers.Main).launch {
            WidgetDataRepository.invalidateMissingCache(context)
            WidgetUpdateWorker.requestImmediateUpdate(context, WidgetUpdateWorker.WIDGET_TYPE_MISSING)
        }
    }
    
    private fun handleLocationChanged(context: Context) {
        Log.d(TAG, "Location changed, updating widget")
        CoroutineScope(Dispatchers.Main).launch {
            WidgetDataRepository.invalidateLocationCache(context)
            LocationWidgetProvider.updateWidgets(context)
        }
    }
}
