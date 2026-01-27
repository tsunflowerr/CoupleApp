package com.example.coupleapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import com.example.coupleapp.ui.screens.profile.NotificationPreferences
import com.example.coupleapp.widget.LocketWidgetProvider
import com.example.coupleapp.widget.MissingWidgetProvider
import com.example.coupleapp.widget.data.WidgetDataRepository
import com.example.coupleapp.worker.PartnerDataSyncWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Unified Firebase Cloud Messaging Service
 * 
 * This single service handles BOTH:
 * 1. Silent Push (Data Messages) → Background Sync → Room DB → Widget Update
 * 2. Notification Messages → Show notifications to user
 * 
 * CRITICAL: Android only allows ONE FCM service to receive messages.
 * This service consolidates CoupleFirebaseMessagingService and SilentPushFCMService.
 * 
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    FCM Message Received                         │
 * │                           ↓                                     │
 * │            ┌──────────────┴──────────────┐                     │
 * │            ↓                              ↓                     │
 * │     Data Message Only              Notification + Data          │
 * │     (Silent Push)                  (User Notification)         │
 * │            ↓                              ↓                     │
 * │   PartnerDataSyncWorker           Show Notification            │
 * │   (Expedited Work)                      +                       │
 * │            ↓                    PartnerDataSyncWorker           │
 * │     Room Database                         ↓                     │
 * │            ↓                       Room Database                │
 * │     Widget Update                         ↓                     │
 * │                                    Widget Update                │
 * └─────────────────────────────────────────────────────────────────┘
 * 
 * Message Types:
 * 1. "partner_data_sync" - Silent sync (sleep, location, photos)
 * 2. "locket" - New photo from partner (notify + sync)
 * 3. "missing" - Partner misses you (notify + sync)
 * 4. "message" - Chat message (notify only)
 */
class UnifiedFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "UnifiedFCMService"
        
        // Notification Channels
        private const val CHANNEL_ID_MESSAGES = "messages_channel"
        private const val CHANNEL_NAME_MESSAGES = "Tin nhắn"
        private const val CHANNEL_ID_MISSING = "missing_channel"
        private const val CHANNEL_NAME_MISSING = "Missing"
        private const val CHANNEL_ID_LOCKET = "locket_channel"
        private const val CHANNEL_NAME_LOCKET = "Locket"
        private const val CHANNEL_ID_SYNC = "sync_channel"
        private const val CHANNEL_NAME_SYNC = "Background Sync"
        
        // Notification IDs
        private const val NOTIFICATION_ID_MESSAGE = 2001
        private const val NOTIFICATION_ID_MISSING = 2002
        private const val NOTIFICATION_ID_LOCKET = 2003
        
        // Deduplication: Track recently shown notifications with bounded size
        private val recentNotifications = object : LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                // Remove oldest entries when size exceeds 50 OR when entry is older than dedup window
                if (size > 50) return true
                eldest?.let {
                    if (System.currentTimeMillis() - it.value > NOTIFICATION_DEDUP_WINDOW_MS * 2) {
                        return true
                    }
                }
                return false
            }
        }
        private const val NOTIFICATION_DEDUP_WINDOW_MS = 5000L // 5 seconds window
        
        /**
         * Clear stale entries from deduplication map
         * Called periodically to prevent memory buildup
         */
        fun cleanupRecentNotifications() {
            val now = System.currentTimeMillis()
            recentNotifications.entries.removeIf { now - it.value > NOTIFICATION_DEDUP_WINDOW_MS * 2 }
        }
        
        // Data payload keys
        private const val KEY_TYPE = "type"
        private const val KEY_PARTNER_ID = "partnerId"
        private const val KEY_DATA_TYPE = "dataType"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_SENDER_NAME = "senderName"
        private const val KEY_SENDER_ID = "senderId"
        
        // Message types
        private const val TYPE_PARTNER_SYNC = "partner_data_sync"
        private const val TYPE_SLEEP_UPDATE = "sleep_update"
        private const val TYPE_LOCATION_UPDATE = "location_update"
        private const val TYPE_PHOTO_UPDATE = "photo_update"
        private const val TYPE_MISSING = "missing"
        private const val TYPE_LOCKET = "locket"
        private const val TYPE_MESSAGE = "message"
        
        // Priority levels
        private const val PRIORITY_HIGH = "high"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /**
     * Called when FCM token is refreshed.
     * Save new token to Firestore for push targeting.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        saveTokenToFirestore(token)
    }

    /**
     * Called when a message is received from FCM.
     * 
     * IMPORTANT: This handles BOTH data-only messages (silent push) AND
     * notification messages (user-visible notifications).
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")
        
        // Handle data payload (always, regardless of foreground/background)
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Data payload: ${remoteMessage.data}")
            handleDataPayload(remoteMessage.data)
        }

        // Handle notification payload (only when app is in background)
        // When app is in foreground, we handle notifications ourselves
        if (!CoupleApplication.isAppInForeground) {
            remoteMessage.notification?.let { notification ->
                Log.d(TAG, "Notification: ${notification.title} - ${notification.body}")
                showNotification(
                    title = notification.title ?: "Couple App",
                    body = notification.body ?: "Bạn có thông báo mới",
                    type = "general"
                )
            }
        }
    }

    /**
     * Handle data payload - determines whether to sync, notify, or both.
     */
    private fun handleDataPayload(data: Map<String, String>) {
        val type = data[KEY_TYPE] ?: ""
        val partnerId = data[KEY_PARTNER_ID] ?: ""
        val dataType = data[KEY_DATA_TYPE] ?: "all"
        val priority = data[KEY_PRIORITY] ?: "normal"
        val senderName = data[KEY_SENDER_NAME] ?: "Người yêu"
        val senderId = data[KEY_SENDER_ID] ?: ""
        
        Log.d(TAG, "Processing data: type=$type, partner=$partnerId, dataType=$dataType")
        
        // Don't process messages from self
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (senderId == currentUserId) {
            Log.d(TAG, "Ignoring message from self")
            return
        }
        
        when (type) {
            // ==================== SILENT SYNC MESSAGES ====================
            // These trigger background sync without showing notification
            TYPE_PARTNER_SYNC,
            TYPE_SLEEP_UPDATE,
            TYPE_LOCATION_UPDATE,
            TYPE_PHOTO_UPDATE -> {
                triggerBackgroundSync(partnerId, dataType, priority == PRIORITY_HIGH)
                // Also invalidate cache for immediate widget update
                invalidateLegacyCache()
            }
            
            // ==================== NOTIFICATION + SYNC MESSAGES ====================
            // These show notification AND trigger background sync
            TYPE_LOCKET -> {
                // 1. Trigger sync first (high priority for immediate widget update)
                triggerBackgroundSync(partnerId, "photos", true)
                
                // 2. Also invalidate legacy cache for backward compatibility
                invalidateLegacyCache()
                
                // 3. Show notification if app is in background
                if (!CoupleApplication.isAppInForeground) {
                    showNotification(
                        title = "Locket từ $senderName 📸",
                        body = "Bạn có ảnh mới từ người yêu",
                        type = TYPE_LOCKET,
                        notificationId = NOTIFICATION_ID_LOCKET
                    )
                }
            }
            
            TYPE_MISSING -> {
                // 1. Trigger sync
                triggerBackgroundSync(partnerId, "all", true)
                
                // 2. Invalidate legacy cache
                invalidateLegacyCache()
                
                // 3. Show notification if app is in background
                if (!CoupleApplication.isAppInForeground) {
                    showNotification(
                        title = "$senderName đang nhớ bạn! 💕",
                        body = "Tap để gửi nhớ lại",
                        type = TYPE_MISSING,
                        notificationId = NOTIFICATION_ID_MISSING
                    )
                }
            }
            
            // ==================== NOTIFICATION ONLY MESSAGES ====================
            TYPE_MESSAGE -> {
                if (!CoupleApplication.isAppInForeground && !CoupleApplication.isUserInChatScreen) {
                    showNotification(
                        title = senderName,
                        body = "Bạn có tin nhắn mới",
                        type = TYPE_MESSAGE,
                        notificationId = NOTIFICATION_ID_MESSAGE
                    )
                }
            }
            
            else -> {
                // Unknown type - default to full sync
                Log.d(TAG, "Unknown message type: $type, performing full sync")
                triggerBackgroundSync(partnerId, "all", priority == PRIORITY_HIGH)
            }
        }
    }

    /**
     * Trigger background sync using WorkManager.
     * This is the "Silent Push" strategy.
     */
    private fun triggerBackgroundSync(partnerId: String, dataType: String, isHighPriority: Boolean) {
        // Resolve partner ID if not provided
        val resolvedPartnerId = if (partnerId.isEmpty()) {
            resolvePartnerIdAsync()
            return // Will be handled in callback
        } else {
            partnerId
        }
        
        Log.d(TAG, "Triggering sync: partner=$resolvedPartnerId, type=$dataType, highPriority=$isHighPriority")
        
        val syncTypes = when (dataType.lowercase()) {
            "sleep" -> listOf(PartnerDataSyncWorker.SYNC_TYPE_SLEEP)
            "location" -> listOf(PartnerDataSyncWorker.SYNC_TYPE_LOCATION)
            "photos", "locket" -> listOf(PartnerDataSyncWorker.SYNC_TYPE_PHOTOS)
            else -> null // null = sync all
        }
        
        if (isHighPriority) {
            // Use expedited work for immediate widget updates
            PartnerDataSyncWorker.enqueueExpedited(
                context = applicationContext,
                partnerId = resolvedPartnerId,
                syncTypes = syncTypes,
                triggerSource = PartnerDataSyncWorker.TRIGGER_FCM
            )
        } else {
            // Use regular work for battery efficiency
            PartnerDataSyncWorker.enqueueRegular(
                context = applicationContext,
                partnerId = resolvedPartnerId,
                syncTypes = syncTypes,
                triggerSource = PartnerDataSyncWorker.TRIGGER_FCM
            )
        }
    }

    /**
     * Resolve partner ID asynchronously when not provided in message.
     */
    private fun resolvePartnerIdAsync() {
        serviceScope.launch {
            try {
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUserId)
                    .get()
                    .addOnSuccessListener { doc ->
                        val partnerId = doc.getString("partnerId")
                        if (partnerId != null) {
                            triggerBackgroundSync(partnerId, "all", true)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving partner ID", e)
            }
        }
    }

    /**
     * Invalidate legacy SharedPreferences cache for backward compatibility.
     * This ensures old WidgetDataRepository cache is cleared when new data arrives.
     */
    private fun invalidateLegacyCache() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Invalidate ALL widget caches
                WidgetDataRepository.invalidateLocketCache(this@UnifiedFCMService)
                WidgetDataRepository.invalidateMissingCache(this@UnifiedFCMService)
                WidgetDataRepository.invalidateSleepCache(this@UnifiedFCMService)
                WidgetDataRepository.invalidateLocationCache(this@UnifiedFCMService)
                
                // Also trigger immediate widget update via old system for ALL widgets
                LocketWidgetProvider.updateWidgets(this@UnifiedFCMService)
                MissingWidgetProvider.updateWidgets(this@UnifiedFCMService)
                com.example.coupleapp.widget.SleepWidgetProvider.updateWidgets(this@UnifiedFCMService)
                com.example.coupleapp.widget.LocationWidgetProvider.updateWidgets(this@UnifiedFCMService)
            } catch (e: Exception) {
                Log.e(TAG, "Error invalidating legacy cache", e)
            }
        }
    }

    /**
     * Show local notification with deduplication.
     * Respects user's notification preferences.
     */
    private fun showNotification(
        title: String,
        body: String,
        type: String,
        notificationId: Int = NOTIFICATION_ID_MESSAGE
    ) {
        // ========== CHECK NOTIFICATION PREFERENCES ==========
        val isNotificationEnabled = when (type) {
            TYPE_MESSAGE -> NotificationPreferences.isMessageNotificationEnabled(this)
            TYPE_MISSING -> NotificationPreferences.isMissingNotificationEnabled(this)
            TYPE_LOCKET -> NotificationPreferences.isLocketNotificationEnabled(this)
            else -> NotificationPreferences.isPushEnabled(this)
        }
        
        if (!isNotificationEnabled) {
            Log.d(TAG, "⚠️ Notification disabled in settings for type: $type")
            return
        }
        
        // ========== DEDUPLICATION CHECK ==========
        val dedupKey = "${type}_notification"
        val currentTime = System.currentTimeMillis()
        
        synchronized(recentNotifications) {
            val lastShownTime = recentNotifications[dedupKey] ?: 0L
            if (currentTime - lastShownTime < NOTIFICATION_DEDUP_WINDOW_MS) {
                Log.d(TAG, "⚠️ Skipping duplicate notification: $dedupKey (shown ${currentTime - lastShownTime}ms ago)")
                return
            }
            
            // Record this notification
            recentNotifications[dedupKey] = currentTime
            
            // Cleanup old entries
            recentNotifications.entries.removeIf { currentTime - it.value > 30000L }
        }
        
        val channelId = when (type) {
            TYPE_MESSAGE -> CHANNEL_ID_MESSAGES
            TYPE_MISSING -> CHANNEL_ID_MISSING
            TYPE_LOCKET -> CHANNEL_ID_LOCKET
            else -> CHANNEL_ID_MESSAGES
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("notification_type", type)
            when (type) {
                TYPE_MESSAGE -> putExtra("open_chat", true)
                TYPE_MISSING -> putExtra("navigate_to", "missing")
                TYPE_LOCKET -> putExtra("navigate_to", "locket")
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
        
        Log.d(TAG, "Notification shown: $title - $body")
    }

    /**
     * Create notification channels for Android O and above.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Audio attributes for notification sound
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Messages channel
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_MESSAGES, CHANNEL_NAME_MESSAGES, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Thông báo tin nhắn mới từ người yêu"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 100, 250)
                    enableLights(true)
                    lightColor = 0xFFFF69B4.toInt() // Pink
                    setSound(defaultSoundUri, audioAttributes)
                }
            )

            // Missing channel
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_MISSING, CHANNEL_NAME_MISSING, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Thông báo khi người yêu nhớ bạn"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300) // Heartbeat pattern
                    enableLights(true)
                    lightColor = 0xFFFF69B4.toInt() // Pink
                    setSound(defaultSoundUri, audioAttributes)
                }
            )

            // Locket channel
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_LOCKET, CHANNEL_NAME_LOCKET, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Thông báo ảnh Locket mới"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 100, 250)
                    enableLights(true)
                    lightColor = 0xFF9C27B0.toInt() // Purple
                    setSound(defaultSoundUri, audioAttributes)
                }
            )

            // Sync channel (low importance - background sync)
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_SYNC, CHANNEL_NAME_SYNC, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background data synchronization"
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * Save FCM token to Firestore.
     */
    private fun saveTokenToFirestore(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .update(
                mapOf(
                    "fcmToken" to token,
                    "fcmTokenUpdatedAt" to System.currentTimeMillis()
                )
            )
            .addOnSuccessListener {
                Log.d(TAG, "FCM token saved to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save FCM token", e)
                // Retry with merge if document doesn't exist
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .set(
                        mapOf(
                            "fcmToken" to token,
                            "fcmTokenUpdatedAt" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
            }
    }

    /**
     * Called when FCM messages are deleted (e.g., due to Doze mode).
     * Trigger full sync to catch up on missed data.
     */
    override fun onDeletedMessages() {
        super.onDeletedMessages()
        Log.w(TAG, "FCM messages deleted, triggering recovery sync")
        
        serviceScope.launch {
            try {
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUserId)
                    .get()
                    .addOnSuccessListener { doc ->
                        val partnerId = doc.getString("partnerId")
                        if (partnerId != null) {
                            PartnerDataSyncWorker.enqueueRegular(
                                context = applicationContext,
                                partnerId = partnerId,
                                syncTypes = null,
                                triggerSource = "fcm_recovery"
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling deleted messages", e)
            }
        }
    }
}
