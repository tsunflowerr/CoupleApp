package com.example.coupleapp.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Worker để reset sleep state hàng ngày.
 * 
 * ================================================================
 * VẤN ĐỀ GỐC RỄ CỦA BUG "CHỈ ĐO ĐƯỢC 1-2 LẦN":
 * ================================================================
 * 
 * Flag `already_synced_today` trong SharedPreferences:
 * - Được set = TRUE sau lần sync đầu tiên
 * - Logic reset CHỈ chạy khi `currentHour >= 12 && sleepDate != todayDate`
 * - Vấn đề: Nếu app không active vào thời điểm đó → flag KHÔNG reset
 * - Kết quả: Ngày hôm sau vẫn nghĩ là "đã sync" → bỏ qua mọi data!
 * 
 * ================================================================
 * GIẢI PHÁP:
 * ================================================================
 * 
 * DailySleepResetWorker chạy vào:
 * - 00:05 AM mỗi ngày → Reset flag để sẵn sàng cho ngày mới
 * - 04:00 AM mỗi ngày → Reset lần nữa (backup)
 * 
 * Điều này đảm bảo flag LUÔN được reset, bất kể app có active hay không.
 * ================================================================
 */
class DailySleepResetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "DailySleepResetWorker"
        private const val WORK_NAME_MIDNIGHT = "daily_sleep_reset_midnight"
        private const val WORK_NAME_EARLY_MORNING = "daily_sleep_reset_early"
        
        // SharedPreferences keys (same as SleepReceiver)
        private const val PREFS_NAME = "sleep_classify_prefs"
        private const val KEY_IS_SLEEPING = "is_sleeping"
        private const val KEY_ALREADY_SYNCED_TODAY = "already_synced_today"
        private const val KEY_SLEEP_DATE = "sleep_date"
        private const val KEY_LAST_RESET_DATE = "last_reset_date" // NEW: Track when we last reset
        
        /**
         * Schedule daily reset workers.
         * CRITICAL: Call this on app startup AND after boot!
         */
        fun scheduleDailyReset(context: Context) {
            scheduleMidnightReset(context)
            scheduleEarlyMorningReset(context)
            Log.d(TAG, "✅ Daily sleep reset workers scheduled")
        }
        
        /**
         * Schedule reset at midnight (00:05 AM)
         */
        private fun scheduleMidnightReset(context: Context) {
            val now = LocalDateTime.now()
            val targetTime = LocalTime.of(0, 5) // 00:05 AM
            
            var targetDateTime = now.toLocalDate().atTime(targetTime)
            if (now.isAfter(targetDateTime)) {
                // Already past midnight, schedule for tomorrow
                targetDateTime = targetDateTime.plusDays(1)
            }
            
            val delayMinutes = Duration.between(now, targetDateTime).toMinutes()
            
            val workRequest = OneTimeWorkRequestBuilder<DailySleepResetWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag(WORK_NAME_MIDNIGHT)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_MIDNIGHT,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled midnight reset in $delayMinutes minutes")
        }
        
        /**
         * Schedule reset at 4:00 AM (backup)
         */
        private fun scheduleEarlyMorningReset(context: Context) {
            val now = LocalDateTime.now()
            val targetTime = LocalTime.of(4, 0) // 04:00 AM
            
            var targetDateTime = now.toLocalDate().atTime(targetTime)
            if (now.isAfter(targetDateTime)) {
                targetDateTime = targetDateTime.plusDays(1)
            }
            
            val delayMinutes = Duration.between(now, targetDateTime).toMinutes()
            
            val workRequest = OneTimeWorkRequestBuilder<DailySleepResetWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag(WORK_NAME_EARLY_MORNING)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_EARLY_MORNING,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled early morning reset in $delayMinutes minutes")
        }
        
        /**
         * Force reset NOW (for debugging or manual trigger)
         */
        fun triggerImmediateReset(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<DailySleepResetWorker>()
                .addTag("immediate_reset")
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Triggered immediate sleep reset")
        }
        
        /**
         * Check if reset is needed (for external callers)
         */
        fun needsReset(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            return lastResetDate != today
        }
    }
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "🔄 DailySleepResetWorker starting")
        
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "") ?: ""
            val sleepDate = prefs.getString(KEY_SLEEP_DATE, "") ?: ""
            val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
            val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
            
            Log.d(TAG, "Current state:")
            Log.d(TAG, "  today=$today, lastResetDate=$lastResetDate")
            Log.d(TAG, "  sleepDate=$sleepDate, isSleeping=$isSleeping, alreadySynced=$alreadySynced")
            
            // Only reset if this is a new day
            if (lastResetDate != today) {
                Log.d(TAG, "📅 New day detected! Resetting sleep state...")
                
                // CRITICAL: Reset the sync flag
                // But DON'T reset if user is currently sleeping (might lose data)
                if (!isSleeping) {
                    prefs.edit()
                        .putBoolean(KEY_ALREADY_SYNCED_TODAY, false)
                        .putString(KEY_SLEEP_DATE, "")
                        .putString(KEY_LAST_RESET_DATE, today)
                        .apply()
                    
                    Log.d(TAG, "✅ Sleep state reset for new day")
                } else {
                    // User is still sleeping, just mark reset date
                    prefs.edit()
                        .putString(KEY_LAST_RESET_DATE, today)
                        .apply()
                    
                    Log.d(TAG, "⚠️ User is sleeping, keeping state but marking reset date")
                }
            } else {
                Log.d(TAG, "Already reset today, skipping")
            }
            
            // CRITICAL: Re-register Google Sleep API
            // This ensures PendingIntent is alive after RAM clear
            ensureGoogleSleepApiRegistered()
            
            // Re-schedule for tomorrow
            scheduleDailyReset(context)
            
            return Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in DailySleepResetWorker", e)
            
            // Still reschedule even on error
            scheduleDailyReset(context)
            
            return if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    /**
     * Ensure Google Sleep API is registered.
     * This is the KEY fix for PendingIntent being lost!
     */
    private suspend fun ensureGoogleSleepApiRegistered() {
        try {
            val googleSleepManager = GoogleSleepApiManager(context)
            
            if (!googleSleepManager.hasActivityRecognitionPermission()) {
                Log.w(TAG, "No Activity Recognition permission")
                return
            }
            
            // Check sleep_prefs (used by SleepAlarmReceiver and others)
            val sleepPrefs = context.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)
            val explicitlyDisabled = sleepPrefs.contains("google_sleep_api_enabled") &&
                    !sleepPrefs.getBoolean("google_sleep_api_enabled", true)
            
            if (explicitlyDisabled) {
                Log.d(TAG, "Google Sleep API disabled by user")
                return
            }
            
            // Re-register if not registered
            if (!googleSleepManager.isSleepTrackingRegistered()) {
                Log.d(TAG, "🔄 Re-registering Google Sleep API...")
                val result = googleSleepManager.registerSleepUpdates()
                
                if (result.isSuccess) {
                    Log.d(TAG, "✅ Google Sleep API re-registered successfully")
                    
                    // Auto-enable if not explicitly set
                    if (!sleepPrefs.contains("google_sleep_api_enabled")) {
                        sleepPrefs.edit().putBoolean("google_sleep_api_enabled", true).apply()
                    }
                } else {
                    Log.e(TAG, "❌ Failed to re-register: ${result.exceptionOrNull()?.message}")
                }
            } else {
                Log.d(TAG, "Google Sleep API already registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring Google Sleep API registered", e)
        }
    }
}
