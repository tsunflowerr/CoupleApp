package com.example.coupleapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.coupleapp.R
import com.example.coupleapp.data.repository.SleepFirebaseRepository
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.example.coupleapp.receiver.SleepReceiver
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Worker để sync dữ liệu giấc ngủ từ Google Sleep API.
 * 
 * Chiến lược giống Widgetable:
 * 1. Chạy vào sáng sớm (5:30-8:00 AM) để check sleep classify state
 * 2. Nếu phát hiện user đã thức (từ SharedPreferences) → sync Firebase ngay
 * 3. Fallback: nếu SleepReceiver bị miss → đọc state và sync
 * 
 * Điều này đảm bảo data có mặt sớm nhất có thể, ngay cả khi:
 * - App bị kill bởi Android
 * - Doze mode trì hoãn broadcasts
 * - User không mở app
 */
class GoogleSleepSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "GoogleSleepSyncWorker"
        const val WORK_NAME = "google_sleep_sync_worker"
        const val MORNING_WORK_NAME = "morning_google_sleep_sync"
        
        // SharedPreferences keys (same as SleepReceiver)
        private const val PREFS_NAME = "sleep_classify_prefs"
        private const val KEY_IS_SLEEPING = "is_sleeping"
        private const val KEY_SLEEP_START_TIME = "sleep_start_time"
        private const val KEY_LAST_EVENT_TIME = "last_event_time"
        private const val KEY_ALREADY_SYNCED_TODAY = "already_synced_today"
        private const val KEY_SLEEP_DATE = "sleep_date"
        
        // Minimum sleep duration to consider valid (60 minutes)
        private const val MIN_SLEEP_DURATION_MS = 60 * 60 * 1000L
        
        // Notification ID and channel for foreground service (required for expedited work on Android 11-)
        private const val SLEEP_SYNC_NOTIFICATION_ID = 10002
        private const val CHANNEL_ID_SYNC = "sync_channel"
        private const val CHANNEL_NAME_SYNC = "Background Sync"
        
        /**
         * Schedule aggressive morning sync (multiple times between 5:30 AM - 12:00 PM)
         * This is the KEY to getting data early like Widgetable!
         * Extended to 12h for late sleepers.
         * 
         * CRITICAL FIX: Always schedule for next occurrence (today OR tomorrow)
         * This ensures continuous daily operation, not just 1 day
         */
        fun scheduleAggressiveMorningSync(context: Context) {
            // Schedule syncs at: 5:30, 6:30, 7:30, 8:30, 9:30, 10:30, 11:30 AM
            val syncTimes = listOf(
                LocalTime.of(5, 30),
                LocalTime.of(6, 30),
                LocalTime.of(7, 30),
                LocalTime.of(8, 30),
                LocalTime.of(9, 30),
                LocalTime.of(10, 30),
                LocalTime.of(11, 30)
            )
            
            val now = LocalTime.now()
            var scheduledCount = 0
            
            syncTimes.forEachIndexed { index, targetTime ->
                val delayMinutes = calculateDelayMinutes(now, targetTime)
                
                // CRITICAL: Always schedule, even if delay is for tomorrow
                // This ensures syncs continue working every day
                scheduleOneShotSync(context, delayMinutes, "morning_sync_$index")
                scheduledCount++
            }
            
            Log.d(TAG, "Scheduled $scheduledCount aggressive morning syncs (today + tomorrow)")
        }
        
        /**
         * Schedule periodic sync every 2 hours during daytime (6 AM - 10 PM)
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<GoogleSleepSyncWorker>(
                2, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled periodic Google Sleep sync every 2 hours")
        }
        
        /**
         * Trigger immediate sync (when app opens)
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<GoogleSleepSyncWorker>()
                .setConstraints(constraints)
                .addTag("immediate_google_sleep_sync")
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Triggered immediate Google Sleep sync")
        }
        
        private fun calculateDelayMinutes(now: LocalTime, target: LocalTime): Long {
            return if (now.isBefore(target)) {
                Duration.between(now, target).toMinutes()
            } else {
                // Next day
                Duration.between(now, LocalTime.MAX).toMinutes() +
                Duration.between(LocalTime.MIN, target).toMinutes()
            }
        }
        
        private fun scheduleOneShotSync(context: Context, delayMinutes: Long, tag: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<GoogleSleepSyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(tag)
                .addTag(MORNING_WORK_NAME)
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${MORNING_WORK_NAME}_$tag",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled one-shot sync '$tag' with $delayMinutes min delay")
        }
        
        /**
         * Cancel all Google Sleep sync workers
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME)
            WorkManager.getInstance(context).cancelAllWorkByTag(MORNING_WORK_NAME)
            Log.d(TAG, "Cancelled all Google Sleep sync workers")
        }
    }
    
    /**
     * Required for expedited work on Android 11 (API 30) and below.
     * On Android 12+, expedited work uses Android 12's expedited job feature.
     * On older versions, WorkManager runs the work as a foreground service.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID_SYNC)
            .setSmallIcon(R.drawable.ic_heart_notification)
            .setContentTitle("Đang đồng bộ giấc ngủ")
            .setContentText("Đang cập nhật dữ liệu giấc ngủ...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return ForegroundInfo(SLEEP_SYNC_NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SYNC,
                CHANNEL_NAME_SYNC,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị khi đang đồng bộ dữ liệu"
                setShowBadge(false)
            }
            
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Google Sleep sync work")
            
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid
            
            if (userId == null) {
                Log.w(TAG, "No authenticated user, skipping sync")
                return@withContext Result.success()
            }
            
            // Check if Google Sleep API is enabled
            val googleSleepApiManager = GoogleSleepApiManager(context)
            if (!googleSleepApiManager.hasActivityRecognitionPermission()) {
                Log.w(TAG, "No Activity Recognition permission, skipping")
                return@withContext Result.success()
            }
            
            // Ensure Google Sleep API is registered (re-register if needed)
            if (!googleSleepApiManager.isSleepTrackingRegistered()) {
                Log.d(TAG, "Re-registering Google Sleep API...")
                googleSleepApiManager.registerSleepUpdates()
            }
            
            // Read sleep state from SharedPreferences (set by SleepReceiver)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val syncResult = checkAndSyncSleepData(userId, prefs)
            
            if (syncResult) {
                Log.d(TAG, "✅ Google Sleep sync completed successfully")
            } else {
                Log.d(TAG, "No sleep data to sync")
            }
            
            // Cleanup stale SharedPreferences state (handles stuck state from crashes/reboots)
            cleanupStaleLocalState(prefs)
            
            // Re-schedule morning syncs for tomorrow
            scheduleAggressiveMorningSync(context)
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during Google Sleep sync", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    /**
     * Check sleep state and sync to Firebase if needed.
     * 
     * This handles the case where SleepReceiver didn't run (app killed, Doze mode, etc.)
     * 
     * ENHANCED: Better handling of new day detection
     */
    private suspend fun checkAndSyncSleepData(userId: String, prefs: SharedPreferences): Boolean {
        val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
        val sleepStartTime = prefs.getLong(KEY_SLEEP_START_TIME, 0L)
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
        val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        val sleepDate = prefs.getString(KEY_SLEEP_DATE, "") ?: ""
        
        val now = System.currentTimeMillis()
        val todayDate = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())
        
        Log.d(TAG, "Sleep state: isSleeping=$isSleeping, sleepStart=$sleepStartTime, lastEvent=$lastEventTime")
        Log.d(TAG, "  alreadySynced=$alreadySynced, sleepDate=$sleepDate, today=$todayDate")
        
        // CRITICAL FIX: Reset alreadySynced if it's a new day
        // This handles the case where DailySleepResetWorker didn't run
        if (sleepDate.isNotEmpty() && sleepDate != todayDate && alreadySynced) {
            val currentHour = LocalTime.now().hour
            
            // If it's past 4 AM and the sync was from yesterday, reset the flag
            if (currentHour >= 4) {
                Log.d(TAG, "⚠️ alreadySynced flag is stale (from $sleepDate), resetting...")
                prefs.edit()
                    .putBoolean(KEY_ALREADY_SYNCED_TODAY, false)
                    .apply()
                
                // Continue with sync check
            }
        }
        
        // Re-read after potential reset
        val actuallyAlreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        
        // Case 1: Already synced today → skip
        if (actuallyAlreadySynced && sleepDate == todayDate) {
            Log.d(TAG, "Already synced today, skipping")
            return false
        }
        
        // Case 2: Currently marked as sleeping but last event was hours ago
        // This means SleepReceiver might have missed the wake-up event
        if (isSleeping && sleepStartTime > 0 && lastEventTime > 0) {
            val currentHour = LocalTime.now().hour
            val timeSinceLastEvent = now - lastEventTime
            val sleepDuration = now - sleepStartTime
            
            // If it's morning (5 AM - 12 PM) and no event for >1 hour, assume woke up
            val noEventForHours = timeSinceLastEvent > 60 * 60 * 1000 // 1 hour
            val isMorning = currentHour in 5..12
            val sleepLongEnough = sleepDuration >= MIN_SLEEP_DURATION_MS
            
            if (isMorning && noEventForHours && sleepLongEnough) {
                Log.d(TAG, "Detected missed wake-up! Syncing sleep data...")
                
                // Calculate wake time as last event time + 30 min (estimated)
                val estimatedWakeTime = lastEventTime + (30 * 60 * 1000)
                val actualSleepDuration = estimatedWakeTime - sleepStartTime
                
                if (actualSleepDuration >= MIN_SLEEP_DURATION_MS) {
                    return saveSleepToFirebase(
                        userId = userId,
                        startTime = sleepStartTime,
                        endTime = estimatedWakeTime,
                        duration = actualSleepDuration,
                        prefs = prefs
                    )
                }
            }
        }
        
        // Case 3: Not sleeping but has unsync'd completed sleep session
        // This can happen if SleepReceiver ran but Firebase sync failed
        if (!isSleeping && sleepStartTime > 0 && !actuallyAlreadySynced) {
            val sleepDuration = (lastEventTime - sleepStartTime).coerceAtLeast(0)
            
            if (sleepDuration >= MIN_SLEEP_DURATION_MS) {
                Log.d(TAG, "Found unsync'd completed sleep session, syncing...")
                return saveSleepToFirebase(
                    userId = userId,
                    startTime = sleepStartTime,
                    endTime = lastEventTime,
                    duration = sleepDuration,
                    prefs = prefs
                )
            }
        }
        
        // Case 4: Check if we need to reset stale state (new day)
        if (sleepDate.isNotEmpty() && sleepDate != todayDate) {
            val currentHour = LocalTime.now().hour
            if (currentHour >= 12) {
                Log.d(TAG, "New day (past noon), resetting stale sleep state")
                resetSleepState(prefs)
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
                trackingMethod = "GOOGLE_API_CLASSIFY" // From classify events
            )
            
            if (result.isSuccess) {
                // Mark as synced
                prefs.edit()
                    .putBoolean(KEY_ALREADY_SYNCED_TODAY, true)
                    .apply()
                
                Log.d(TAG, "✅ Saved sleep to Firebase: ${duration / 1000 / 60} minutes")
                
                // Notify widgets
                com.example.coupleapp.widget.WidgetManager.onSleepDataUpdated(context)
                
                true
            } else {
                Log.e(TAG, "Failed to save sleep to Firebase")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sleep to Firebase", e)
            false
        }
    }
    
    /**
     * Reset sleep state in SharedPreferences
     */
    private fun resetSleepState(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_IS_SLEEPING, false)
            .putLong(KEY_SLEEP_START_TIME, 0L)
            .putLong(KEY_LAST_EVENT_TIME, 0L)
            .putString(KEY_SLEEP_DATE, "")
            .putBoolean(KEY_ALREADY_SYNCED_TODAY, false)
            .apply()
    }
    
    /**
     * Cleanup stale local state in SharedPreferences.
     * 
     * Handles cases like:
     * - State from days ago that was never properly reset
     * - Corrupted data from crashes
     */
    private fun cleanupStaleLocalState(prefs: SharedPreferences) {
        try {
            val sleepDate = prefs.getString(KEY_SLEEP_DATE, "") ?: ""
            val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
            val now = System.currentTimeMillis()
            
            // If no sleep date set, nothing to cleanup
            if (sleepDate.isEmpty() && lastEventTime == 0L) {
                return
            }
            
            val todayDate = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val currentHour = LocalTime.now().hour
            
            // Case 1: Sleep date is from previous days and it's past noon
            if (sleepDate.isNotEmpty() && sleepDate != todayDate && currentHour >= 12) {
                Log.d(TAG, "Cleaning up stale state from date $sleepDate (today=$todayDate)")
                resetSleepState(prefs)
                return
            }
            
            // Case 2: Last event was more than 24 hours ago
            val timeSinceLastEvent = now - lastEventTime
            if (lastEventTime > 0 && timeSinceLastEvent > 24 * 60 * 60 * 1000L) {
                Log.d(TAG, "Cleaning up stale state (last event ${timeSinceLastEvent / 1000 / 60 / 60}h ago)")
                resetSleepState(prefs)
                return
            }
            
            Log.d(TAG, "Local state is fresh, no cleanup needed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during local state cleanup", e)
        }
    }
}
