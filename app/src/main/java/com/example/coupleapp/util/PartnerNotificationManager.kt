package com.example.coupleapp.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Centralized Notification Helper for CoupleApp
 * 
 * Handles all local push notifications when partner sends:
 * - Missing (heart) signals
 * - Locket photos
 * - Questions
 * 
 * Features:
 * - Rich notifications with large icons
 * - Custom sounds for different notification types
 * - Grouped notifications
 * - Deep linking to specific screens
 * - Notification channels for Android 8+
 * 
 * This works WITHOUT Cloud Functions by using SyncTriggerListener
 * to detect when partner sends data and showing local notifications.
 */
object PartnerNotificationManager {
    
    private const val TAG = "PartnerNotificationMgr"
    
    // Notification Channel IDs
    const val CHANNEL_ID_MISSING = "channel_missing"
    const val CHANNEL_ID_LOCKET = "channel_locket"
    const val CHANNEL_ID_QUESTION = "channel_question"
    const val CHANNEL_ID_CHAT = "channel_chat"
    const val CHANNEL_ID_GENERAL = "channel_general"
    
    // Notification IDs (unique for each type to avoid overwriting)
    private const val NOTIFICATION_ID_MISSING_BASE = 3000
    private const val NOTIFICATION_ID_LOCKET_BASE = 4000
    private const val NOTIFICATION_ID_QUESTION_BASE = 5000
    private const val NOTIFICATION_ID_CHAT_BASE = 6000
    
    // Group keys for notification grouping
    private const val GROUP_KEY_MISSING = "group_missing"
    private const val GROUP_KEY_LOCKET = "group_locket"
    private const val GROUP_KEY_QUESTION = "group_question"
    private const val GROUP_KEY_CHAT = "group_chat"
    
    // Deep link actions
    const val ACTION_OPEN_MISSING = "com.example.coupleapp.OPEN_MISSING"
    const val ACTION_OPEN_LOCKET = "com.example.coupleapp.OPEN_LOCKET"
    const val ACTION_OPEN_QUESTION = "com.example.coupleapp.OPEN_QUESTION"
    const val ACTION_OPEN_CHAT = "com.example.coupleapp.OPEN_CHAT"
    
    /**
     * Initialize notification channels.
     * Must be called at app startup (in Application.onCreate)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            
            // Missing Channel - High importance with vibration
            val missingChannel = NotificationChannel(
                CHANNEL_ID_MISSING,
                "Nhớ Nhung ❤️",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi người yêu gửi trái tim nhớ nhung"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300) // Heartbeat pattern
                enableLights(true)
                lightColor = 0xFFFF69B4.toInt() // Pink
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            
            // Locket Channel - High importance
            val locketChannel = NotificationChannel(
                CHANNEL_ID_LOCKET,
                "Locket 📸",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi người yêu gửi ảnh Locket"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
                enableLights(true)
                lightColor = 0xFF9C27B0.toInt() // Purple
            }
            
            // Question Channel - Default importance
            val questionChannel = NotificationChannel(
                CHANNEL_ID_QUESTION,
                "Câu hỏi 💬",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Thông báo khi người yêu đặt câu hỏi"
                enableVibration(true)
            }
            
            // Chat Channel - High importance for messages
            val chatChannel = NotificationChannel(
                CHANNEL_ID_CHAT,
                "Tin nhắn 💬",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi người yêu gửi tin nhắn"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                enableLights(true)
                lightColor = 0xFF4CAF50.toInt() // Green
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            
            // General Channel
            val generalChannel = NotificationChannel(
                CHANNEL_ID_GENERAL,
                "Thông báo chung",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Các thông báo khác từ ứng dụng"
            }
            
            notificationManager.createNotificationChannels(
                listOf(missingChannel, locketChannel, questionChannel, chatChannel, generalChannel)
            )
            
            Log.d(TAG, "Notification channels created")
        }
    }
    
    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    /**
     * Show notification when partner sends a Missing (heart) signal
     */
    fun showMissingNotification(
        context: Context,
        senderName: String,
        missCount: Int = 1,
        senderAvatarUrl: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "No notification permission")
            return
        }
        
        val notificationId = NOTIFICATION_ID_MISSING_BASE + (System.currentTimeMillis() % 1000).toInt()
        
        // Create intent to open Missing screen
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_MISSING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "missing")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val title = "$senderName đang nhớ bạn! ❤️"
        val body = when {
            missCount > 10 -> "Đã gửi $missCount trái tim hôm nay! Họ rất nhớ bạn 💕"
            missCount > 5 -> "Gửi $missCount trái tim. Bạn có nhớ họ không? 💗"
            missCount > 1 -> "Đã gửi $missCount trái tim cho bạn 💖"
            else -> "Gửi một trái tim nhớ nhung cho bạn 💝"
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_MISSING)
            .setSmallIcon(R.drawable.ic_heart_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_MISSING)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setColor(0xFFFF69B4.toInt())
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
        
        // Add large icon if avatar available (async load would be better)
        // For now, use default heart icon
        builder.setLargeIcon(
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_heart_large)
        )
        
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d(TAG, "Missing notification shown: $title")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: no permission", e)
        }
    }
    
    /**
     * Show notification when partner sends a Locket photo
     */
    suspend fun showLocketNotification(
        context: Context,
        senderName: String,
        locketType: String = "photo", // photo, emoji, drawing, text
        imageUrl: String? = null,
        senderAvatarUrl: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "No notification permission")
            return
        }
        
        val notificationId = NOTIFICATION_ID_LOCKET_BASE + (System.currentTimeMillis() % 1000).toInt()
        
        // Create intent to open Locket screen
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_LOCKET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "locket")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Content based on locket type
        val (title, body, emoji) = when (locketType) {
            "emoji" -> Triple(
                "$senderName gửi emoji! 😊",
                "Có emoji mới từ người yêu",
                "😊"
            )
            "drawing" -> Triple(
                "$senderName gửi bức vẽ! 🎨",
                "Có bức vẽ mới từ người yêu",
                "🎨"
            )
            "text" -> Triple(
                "$senderName gửi tin nhắn! 💌",
                "Có tin nhắn mới từ người yêu",
                "💌"
            )
            else -> Triple(
                "$senderName gửi ảnh! 📸",
                "Có ảnh Locket mới từ người yêu",
                "📸"
            )
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LOCKET)
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_LOCKET)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .setColor(0xFF9C27B0.toInt())
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
        
        // Try to load image for BigPictureStyle
        if (!imageUrl.isNullOrEmpty() && locketType == "photo") {
            try {
                val bitmap = loadBitmapFromUrl(imageUrl)
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    builder.setLargeIcon(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load locket image", e)
            }
        }
        
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d(TAG, "Locket notification shown: $title")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: no permission", e)
        }
    }
    
    /**
     * Show notification when partner asks a question
     */
    fun showQuestionNotification(
        context: Context,
        senderName: String,
        questionText: String,
        questionId: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "No notification permission")
            return
        }
        
        val notificationId = NOTIFICATION_ID_QUESTION_BASE + (System.currentTimeMillis() % 1000).toInt()
        
        // Create intent to open Question/Calendar screen
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_QUESTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "calendar")
            putExtra("question_id", questionId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = "$senderName đặt câu hỏi cho bạn 💬"
        val displayQuestion = if (questionText.length > 100) {
            questionText.take(97) + "..."
        } else {
            questionText
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_QUESTION)
            .setSmallIcon(R.drawable.ic_question_notification)
            .setContentTitle(title)
            .setContentText(displayQuestion)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayQuestion))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_QUESTION)
            .setColor(0xFF2196F3.toInt())
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d(TAG, "Question notification shown: $title")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: no permission", e)
        }
    }
    
    /**
     * Show notification when partner sends a chat message
     */
    fun showChatNotification(
        context: Context,
        senderName: String,
        messagePreview: String,
        senderAvatarUrl: String? = null
    ) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "No notification permission")
            return
        }
        
        val notificationId = NOTIFICATION_ID_CHAT_BASE + (System.currentTimeMillis() % 1000).toInt()
        
        // Create intent to open Chat screen
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "chat")
            putExtra("open_chat", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = senderName
        val displayMessage = if (messagePreview.length > 100) {
            messagePreview.take(97) + "..."
        } else {
            messagePreview
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_CHAT)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle(title)
            .setContentText(displayMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY_CHAT)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setColor(0xFF4CAF50.toInt()) // Green
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
        
        // Add large icon (use message icon as default)
        builder.setLargeIcon(
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_message_large)
        )
        
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            Log.d(TAG, "Chat notification shown: $title - $displayMessage")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification: no permission", e)
        }
    }
    
    /**
     * Cancel all notifications of a specific type
     */
    fun cancelNotifications(context: Context, type: String) {
        val notificationManager = NotificationManagerCompat.from(context)
        when (type) {
            "missing" -> {
                // Cancel all missing notifications
                for (i in 0..999) {
                    notificationManager.cancel(NOTIFICATION_ID_MISSING_BASE + i)
                }
            }
            "locket" -> {
                for (i in 0..999) {
                    notificationManager.cancel(NOTIFICATION_ID_LOCKET_BASE + i)
                }
            }
            "question" -> {
                for (i in 0..999) {
                    notificationManager.cancel(NOTIFICATION_ID_QUESTION_BASE + i)
                }
            }
            "chat" -> {
                for (i in 0..999) {
                    notificationManager.cancel(NOTIFICATION_ID_CHAT_BASE + i)
                }
            }
            "all" -> {
                notificationManager.cancelAll()
            }
        }
    }
    
    /**
     * Load bitmap from URL (for notification images)
     */
    private suspend fun loadBitmapFromUrl(url: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()
                BitmapFactory.decodeStream(connection.getInputStream())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap from URL", e)
                null
            }
        }
    }
}
