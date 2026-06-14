package com.example.coupleapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import com.example.coupleapp.data.repository.SleepFirebaseRepository
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Worker để nhắc nhở user bấm "Wake Up" nếu có active sleep session.
 * 
 * Chạy mỗi sáng từ 6h-9h để kiểm tra:
 * 1. Có active sleep session không?
 * 2. Session đã kéo dài > 4 giờ chưa? (đủ để coi là đã ngủ)
 * 3. Nếu có → Gửi notification nhắc user bấm Wake Up
 * 
 * Cũng đảm bảo Google Sleep API được bật làm fallback.
 */
class SleepWakeUpReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "SleepWakeUpWorker"
        const val WORK_NAME = "sleep_wakeup_reminder_worker"
        
        // Notification
        const val CHANNEL_ID_SLEEP = "sleep_reminder_channel"
        const val CHANNEL_NAME_SLEEP = "Sleep Reminders"
        const val NOTIFICATION_ID_WAKEUP = 2001
        
        // Thời gian tối thiểu ngủ để hiện notification (4 giờ)
        const val MIN_SLEEP_DURATION_HOURS = 4L
        
        // Thời gian tối đa session trước khi cảnh báo (14 giờ)
        const val MAX_SESSION_DURATION_HOURS = 14L
        
        /**
         * Schedule morning wake up reminder
         * Chạy mỗi ngày trong khung 6h-9h sáng
         */
        fun scheduleMorningReminder(context: Context) {
            val now = LocalTime.now()
            val targetTime = LocalTime.of(7, 0) // 7h sáng
            
            // Tính delay đến 7h sáng
            val delayMinutes = if (now.isBefore(targetTime)) {
                Duration.between(now, targetTime).toMinutes()
            } else {
                // Ngày mai
                Duration.between(now, LocalTime.MAX).toMinutes() +
                Duration.between(LocalTime.MIN, targetTime).toMinutes()
            }
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<SleepWakeUpReminderWorker>(
                1, TimeUnit.DAYS,
                2, TimeUnit.HOURS // Flex: có thể chạy từ 5h-9h
            )
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            
            Log.d(TAG, "Scheduled morning wake up reminder, initial delay: $delayMinutes minutes")
        }
        
        /**
         * Trigger check ngay lập tức (khi mở app)
         */
        fun triggerImmediateCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = OneTimeWorkRequestBuilder<SleepWakeUpReminderWorker>()
                .setConstraints(constraints)
                .addTag("immediate_wakeup_check")
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Triggered immediate wake up check")
        }
        
        /**
         * Cancel all reminders
         */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled all wake up reminders")
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting wake up reminder check")
            
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid
            
            if (userId == null) {
                Log.w(TAG, "No authenticated user, skipping")
                return@withContext Result.success()
            }
            
            // 1. Check và auto-enable Google Sleep API nếu có permission
            autoEnableGoogleSleepApiIfPossible()
            
            // 2. Check active sleep session
            checkAndNotifyActiveSleepSession(userId)
            
            Log.d(TAG, "Wake up reminder check completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in wake up reminder worker", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    /**
     * Auto-enable Google Sleep API nếu đã có Activity Recognition permission
     */
    private suspend fun autoEnableGoogleSleepApiIfPossible() {
        try {
            val googleSleepApiManager = GoogleSleepApiManager(context)
            val prefs = context.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)
            
            // Check nếu đã được enable manually
            val isManuallyEnabled = prefs.getBoolean("google_sleep_api_enabled", false)
            
            // Check nếu có permission
            val hasPermission = googleSleepApiManager.hasActivityRecognitionPermission()
            
            // Check nếu đã registered
            val isRegistered = googleSleepApiManager.isSleepTrackingRegistered()
            
            Log.d(TAG, "Google Sleep API: hasPermission=$hasPermission, isRegistered=$isRegistered, manuallyEnabled=$isManuallyEnabled")
            
            // Auto-enable nếu có permission nhưng chưa registered
            if (hasPermission && !isRegistered) {
                Log.d(TAG, "Auto-enabling Google Sleep API as fallback")
                val result = googleSleepApiManager.registerSleepUpdates()
                
                if (result.isSuccess) {
                    prefs.edit().putBoolean("google_sleep_api_enabled", true).apply()
                    Log.d(TAG, "Successfully auto-enabled Google Sleep API")
                } else {
                    Log.w(TAG, "Failed to auto-enable Google Sleep API: ${result.exceptionOrNull()?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-enabling Google Sleep API", e)
        }
    }
    
    /**
     * Check active sleep session và gửi notification nếu cần
     */
    private suspend fun checkAndNotifyActiveSleepSession(userId: String) {
        try {
            val repository = SleepFirebaseRepository(context)
            val sessionResult = repository.getActiveSleepSession(userId)
            val session = sessionResult.getOrNull()
            
            if (session == null || !session.isActive) {
                Log.d(TAG, "No active sleep session found")
                return
            }
            
            val startTime = session.startTime?.toDate()?.toInstant() ?: return
            val now = java.time.Instant.now()
            val durationHours = Duration.between(startTime, now).toHours()
            
            Log.d(TAG, "Active sleep session found, duration: $durationHours hours")
            
            when {
                durationHours >= MAX_SESSION_DURATION_HOURS -> {
                    // Session quá dài - có thể user quên bấm Wake Up
                    showWakeUpNotification(
                        title = "Phiên ngủ đang quá dài! 😴",
                        message = "Bạn đã bấm 'Đi ngủ' ${durationHours}h trước. Có vẻ bạn đã quên bấm 'Thức dậy'?",
                        isUrgent = true
                    )
                }
                durationHours >= MIN_SLEEP_DURATION_HOURS -> {
                    // Đủ thời gian ngủ - nhắc nhở bình thường
                    showWakeUpNotification(
                        title = "Chào buổi sáng! ☀️",
                        message = "Bạn đã ngủ ${durationHours}h. Bấm để ghi nhận giấc ngủ của bạn.",
                        isUrgent = false
                    )
                }
                else -> {
                    Log.d(TAG, "Sleep duration too short ($durationHours h), skipping notification")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active sleep session", e)
        }
    }
    
    /**
     * Hiển thị notification nhắc Wake Up
     */
    private fun showWakeUpNotification(title: String, message: String, isUrgent: Boolean) {
        createNotificationChannel()
        
        // Intent mở app đến Sleep screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_sleep", true)
            putExtra("show_wakeup", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_WAKEUP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SLEEP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_WAKEUP, notification)
        
        Log.d(TAG, "Showed wake up notification: $title")
    }
    
    /**
     * Tạo notification channel cho sleep reminders
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SLEEP,
                CHANNEL_NAME_SLEEP,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Nhắc nhở ghi nhận giấc ngủ"
                enableVibration(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
