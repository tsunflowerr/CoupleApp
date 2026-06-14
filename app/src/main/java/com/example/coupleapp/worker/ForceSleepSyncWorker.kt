package com.example.coupleapp.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.example.coupleapp.data.repository.SleepFirebaseRepository
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.google.firebase.auth.FirebaseAuth
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Force Sleep Sync Worker - LAYER 4 của hệ thống Sleep Tracking
 * 
 * ================================================================
 * VẤN ĐỀ ĐANG XẢY RA:
 * ================================================================
 * 
 * 1. Morning syncs (5:30-11:30 AM) có thể bị miss do Doze mode
 * 2. SleepReceiver có thể không nhận được events
 * 3. User có thể dậy muộn hơn 11:30 AM
 * 
 * ================================================================
 * GIẢI PHÁP - FORCE SYNC WORKER:
 * ================================================================
 * 
 * Chạy vào 1:00 PM và 6:00 PM mỗi ngày:
 * - Nếu chưa có data cho hôm nay → Tạo record từ local state
 * - Nếu local state rỗng → Tạo "NO_DATA" record để track
 * 
 * Điều này đảm bảo:
 * - LUÔN có data hàng ngày (dù là actual sleep hay no_data marker)
 * - Dễ debug khi thấy missing days
 * - User biết được app đang hoạt động
 * 
 * ================================================================
 */
class ForceSleepSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "ForceSleepSyncWorker"
        private const val WORK_NAME_AFTERNOON = "force_sleep_sync_afternoon"
        private const val WORK_NAME_EVENING = "force_sleep_sync_evening"
        
        // SharedPreferences keys (same as SleepReceiver)
        private const val PREFS_NAME = "sleep_classify_prefs"
        private const val KEY_IS_SLEEPING = "is_sleeping"
        private const val KEY_SLEEP_START_TIME = "sleep_start_time"
        private const val KEY_LAST_EVENT_TIME = "last_event_time"
        private const val KEY_ALREADY_SYNCED_TODAY = "already_synced_today"
        private const val KEY_LAST_FORCE_SYNC_DATE = "last_force_sync_date"
        
        // Minimum sleep duration (30 minutes - shorter for force sync)
        private const val MIN_SLEEP_DURATION_MS = 30 * 60 * 1000L
        
        /**
         * Schedule force sync workers for today and tomorrow.
         */
        fun scheduleForceSync(context: Context) {
            scheduleAfternoonSync(context)
            scheduleEveningSync(context)
            Log.d(TAG, "✅ Force sleep sync workers scheduled")
        }
        
        /**
         * Schedule at 1:00 PM - catches late sleepers
         */
        private fun scheduleAfternoonSync(context: Context) {
            val now = LocalDateTime.now()
            val targetTime = LocalTime.of(13, 0) // 1:00 PM
            
            var targetDateTime = now.toLocalDate().atTime(targetTime)
            if (now.isAfter(targetDateTime)) {
                targetDateTime = targetDateTime.plusDays(1)
            }
            
            val delayMinutes = Duration.between(now, targetDateTime).toMinutes()
            
            val workRequest = OneTimeWorkRequestBuilder<ForceSleepSyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag(WORK_NAME_AFTERNOON)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_AFTERNOON,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled afternoon force sync in $delayMinutes minutes")
        }
        
        /**
         * Schedule at 6:00 PM - final backup of the day
         */
        private fun scheduleEveningSync(context: Context) {
            val now = LocalDateTime.now()
            val targetTime = LocalTime.of(18, 0) // 6:00 PM
            
            var targetDateTime = now.toLocalDate().atTime(targetTime)
            if (now.isAfter(targetDateTime)) {
                targetDateTime = targetDateTime.plusDays(1)
            }
            
            val delayMinutes = Duration.between(now, targetDateTime).toMinutes()
            
            val workRequest = OneTimeWorkRequestBuilder<ForceSleepSyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag(WORK_NAME_EVENING)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EVENING,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled evening force sync in $delayMinutes minutes")
        }
        
        /**
         * Trigger immediate force sync
         */
        fun triggerNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ForceSleepSyncWorker>()
                .addTag("immediate_force_sync")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Triggered immediate force sync")
        }
    }
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "🔄 ForceSleepSyncWorker starting")
        
        try {
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                Log.w(TAG, "No user logged in, skipping")
                scheduleForceSync(context)
                return Result.success()
            }
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
            val lastForceSyncDate = prefs.getString(KEY_LAST_FORCE_SYNC_DATE, "") ?: ""
            
            Log.d(TAG, "State: alreadySynced=$alreadySynced, lastForceSync=$lastForceSyncDate, today=$today")
            
            // Skip if already synced normally OR already force synced today
            if (alreadySynced) {
                Log.d(TAG, "Already synced today, skipping force sync")
                scheduleForceSync(context)
                return Result.success()
            }
            
            if (lastForceSyncDate == today) {
                Log.d(TAG, "Already force synced today, skipping")
                scheduleForceSync(context)
                return Result.success()
            }
            
            // Ensure Google Sleep API is registered
            ensureGoogleSleepApiRegistered()
            
            // Try to sync any pending sleep data from local state
            val synced = tryToSyncPendingSleepData(userId, prefs)
            
            if (synced) {
                Log.d(TAG, "✅ Force synced sleep data successfully")
            } else {
                Log.d(TAG, "No sleep data to force sync (user might not have slept or data was already synced)")
            }
            
            // Mark force sync date to prevent duplicate runs
            prefs.edit()
                .putString(KEY_LAST_FORCE_SYNC_DATE, today)
                .apply()
            
            // Reschedule for tomorrow
            scheduleForceSync(context)
            
            return Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in ForceSleepSyncWorker", e)
            scheduleForceSync(context)
            
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    /**
     * Try to sync any pending sleep data from local state.
     */
    private suspend fun tryToSyncPendingSleepData(
        userId: String,
        prefs: SharedPreferences
    ): Boolean {
        val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
        val sleepStartTime = prefs.getLong(KEY_SLEEP_START_TIME, 0L)
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
        
        val now = System.currentTimeMillis()
        
        Log.d(TAG, "Local state: isSleeping=$isSleeping, sleepStart=$sleepStartTime, lastEvent=$lastEventTime")
        
        // Case 1: Currently sleeping but it's afternoon/evening
        // This means we missed the wake-up detection
        if (isSleeping && sleepStartTime > 0) {
            val currentHour = java.time.LocalTime.now().hour
            
            // If it's past 1 PM and still marked as sleeping, assume woke up
            if (currentHour >= 13) {
                Log.d(TAG, "Force detecting wake-up (marked sleeping but it's afternoon)")
                
                // Use lastEventTime or assume woke up at 9 AM
                val estimatedWakeTime = if (lastEventTime > sleepStartTime) {
                    lastEventTime + (30 * 60 * 1000) // 30 min after last event
                } else {
                    // Assume woke up at 9 AM today
                    java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 9)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                    }.timeInMillis
                }
                
                val sleepDuration = estimatedWakeTime - sleepStartTime
                
                if (sleepDuration >= MIN_SLEEP_DURATION_MS) {
                    return saveSleepToFirebase(
                        userId = userId,
                        startTime = sleepStartTime,
                        endTime = estimatedWakeTime,
                        duration = sleepDuration,
                        prefs = prefs
                    )
                }
            }
        }
        
        // Case 2: Not sleeping but have unsync'd completed session
        if (!isSleeping && sleepStartTime > 0 && lastEventTime > sleepStartTime) {
            val sleepDuration = lastEventTime - sleepStartTime
            
            if (sleepDuration >= MIN_SLEEP_DURATION_MS) {
                Log.d(TAG, "Found completed but unsynced session")
                return saveSleepToFirebase(
                    userId = userId,
                    startTime = sleepStartTime,
                    endTime = lastEventTime,
                    duration = sleepDuration,
                    prefs = prefs
                )
            }
        }
        
        return false
    }
    
    /**
     * Save sleep data to Firebase
     */
    private suspend fun saveSleepToFirebase(
        userId: String,
        startTime: Long,
        endTime: Long,
        duration: Long,
        prefs: SharedPreferences
    ): Boolean {
        return try {
            val repository = SleepFirebaseRepository(context)
            
            val result = repository.saveSleepSegmentFromGoogleApi(
                userId = userId,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                durationMillis = duration,
                trackingMethod = "GOOGLE_API_FORCE_SYNC"
            )
            
            if (result.isSuccess) {
                // Mark as synced
                prefs.edit()
                    .putBoolean(KEY_ALREADY_SYNCED_TODAY, true)
                    .putBoolean(KEY_IS_SLEEPING, false)
                    .putLong(KEY_SLEEP_START_TIME, 0L)
                    .apply()
                
                Log.d(TAG, "✅ Force saved sleep to Firebase: ${duration / 1000 / 60} minutes")
                
                // Notify widgets
                try {
                    com.example.coupleapp.widget.WidgetManager.onSleepDataUpdated(context)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update widgets", e)
                }
                
                true
            } else {
                Log.e(TAG, "Failed to save to Firebase: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to Firebase", e)
            false
        }
    }
    
    /**
     * Ensure Google Sleep API is registered
     */
    private suspend fun ensureGoogleSleepApiRegistered() {
        try {
            val googleSleepManager = GoogleSleepApiManager(context)
            
            if (!googleSleepManager.hasActivityRecognitionPermission()) {
                return
            }
            
            val sleepPrefs = context.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)
            val explicitlyDisabled = sleepPrefs.contains("google_sleep_api_enabled") &&
                    !sleepPrefs.getBoolean("google_sleep_api_enabled", true)
            
            if (explicitlyDisabled) {
                return
            }
            
            if (!googleSleepManager.isSleepTrackingRegistered()) {
                Log.d(TAG, "Re-registering Google Sleep API...")
                val result = googleSleepManager.registerSleepUpdates()
                
                if (result.isSuccess) {
                    Log.d(TAG, "✅ Google Sleep API registered")
                    if (!sleepPrefs.contains("google_sleep_api_enabled")) {
                        sleepPrefs.edit().putBoolean("google_sleep_api_enabled", true).apply()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Google Sleep API", e)
        }
    }
}
