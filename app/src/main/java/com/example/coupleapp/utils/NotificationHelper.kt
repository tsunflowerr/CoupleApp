package com.example.coupleapp.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import com.example.coupleapp.ui.screens.profile.NotificationPreferences

/**
 * Helper class for managing notifications
 */
class NotificationHelper(private val context: Context) {
    
    companion object {
        private const val CHANNEL_ID_MESSAGES = "messages_channel"
        private const val CHANNEL_NAME_MESSAGES = "Tin nhắn"
        private const val CHANNEL_DESC_MESSAGES = "Thông báo tin nhắn mới từ người yêu"
        private const val NOTIFICATION_ID_MESSAGE = 1001
    }
    
    init {
        createNotificationChannels()
    }
    
    /**
     * Create notification channels for Android O and above
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Messages channel
            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                CHANNEL_NAME_MESSAGES,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC_MESSAGES
                enableVibration(true)
                enableLights(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messagesChannel)
        }
    }
    
    /**
     * Show notification for new message
     * Note: For privacy, we don't show message content - only that there's a new message
     * Respects user's notification preferences
     */
    fun showMessageNotification(
        senderName: String,
        messageText: String,
        messageCount: Int = 1
    ) {
        // Check if notifications are enabled in settings
        if (!NotificationPreferences.isMessageNotificationEnabled(context)) {
            android.util.Log.d("NotificationHelper", "Message notifications disabled in settings")
            return
        }
        
        // Create intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_chat", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Privacy: Hide actual message content, only show that there's a new message
        val notificationText = if (messageCount > 1) {
            "Bạn có $messageCount tin nhắn mới"
        } else {
            "Bạn có tin nhắn mới"
        }
        
        // Build notification - content is hidden for privacy
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // TODO: Add custom icon
            .setContentTitle(senderName)
            .setContentText(notificationText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide content on lock screen
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.new_message_received))
                    .build()
            )
            .apply {
                if (messageCount > 1) {
                    setNumber(messageCount)
                }
            }
            .build()
        
        // Show notification
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MESSAGE, notification)
        } catch (e: SecurityException) {
            // Permission not granted, ignore
            android.util.Log.w("NotificationHelper", "Notification permission not granted")
        }
    }
    
    /**
     * Cancel message notification
     */
    fun cancelMessageNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_MESSAGE)
    }
}
