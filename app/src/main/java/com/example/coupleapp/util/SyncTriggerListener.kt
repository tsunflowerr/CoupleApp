package com.example.coupleapp.util

import android.content.Context
import android.util.Log
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.worker.PartnerDataSyncWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Listens to Firestore for sync triggers from partner.
 * 
 * This is a CLIENT-SIDE alternative to FCM push notifications.
 * Instead of Cloud Functions sending FCM when data changes,
 * partner's app writes to "sync_triggers" collection, and
 * this listener picks it up and triggers sync + shows notification.
 * 
 * Benefits:
 * - No Cloud Functions needed (no Blaze plan required!)
 * - Real-time updates when app is open
 * - Local notifications when partner sends data
 * - Fallback to periodic sync when app is closed
 * 
 * Limitations:
 * - Only works when app is in foreground/background with listener active
 * - When app is killed, falls back to 15-minute periodic sync
 */
object SyncTriggerListener {
    
    private const val TAG = "SyncTriggerListener"
    private const val COLLECTION_SYNC_TRIGGERS = "sync_triggers"
    
    // Deduplication: Track recently shown notifications with bounded size (LRU cache)
    private const val MAX_DEDUP_ENTRIES = 50
    private val recentNotifications = object : LinkedHashMap<String, Long>(MAX_DEDUP_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > MAX_DEDUP_ENTRIES
        }
    }
    private const val NOTIFICATION_DEDUP_WINDOW_MS = 5000L // 5 seconds window
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    
    // Make scope nullable and recreatable to properly manage lifecycle
    private var scope: CoroutineScope? = null
    private var scopeJob: kotlinx.coroutines.Job? = null
    
    private fun getOrCreateScope(): CoroutineScope {
        if (scope == null) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob!! + Dispatchers.IO)
        }
        return scope!!
    }
    
    private var listenerRegistration: ListenerRegistration? = null
    private var isListening = false
    
    /**
     * Start listening for sync triggers from partner.
     * Call this when app starts or user logs in.
     */
    fun startListening(context: Context) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "User not logged in, cannot start listening")
            return
        }
        
        if (isListening) {
            Log.d(TAG, "Already listening for sync triggers")
            return
        }
        
        Log.d(TAG, "Starting sync trigger listener for user: ${currentUser.uid}")
        
        try {
            // Listen for triggers targeted at current user
            // Note: This query requires a composite index on (targetUserId, processed)
            listenerRegistration = firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .whereEqualTo("targetUserId", currentUser.uid)
                .whereEqualTo("processed", false)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        // Log error but don't crash - common for missing Firestore indexes
                        Log.e(TAG, "Error listening for sync triggers (may need Firestore index: sync_triggers [targetUserId, processed])", error)
                        return@addSnapshotListener
                    }
                    
                    if (snapshots == null || snapshots.isEmpty) {
                        return@addSnapshotListener
                    }
                    
                    try {
                        // Process new triggers
                        for (doc in snapshots.documents) {
                            val dataType = doc.getString("dataType") ?: "all"
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: "Người yêu"
                            val priority = doc.getString("priority") ?: "normal"
                            val extraData = doc.getString("extraData") // For question text, etc.
                            
                            Log.d(TAG, "📥 Received sync trigger: $dataType from $senderName")
                            
                            // Trigger sync worker and show notification
                            getOrCreateScope().launch {
                                try {
                                    // Show notification based on data type
                                    showNotificationForTrigger(
                                        context = context,
                                        dataType = dataType,
                                        senderName = senderName,
                                        senderId = senderId,
                                        extraData = extraData
                                    )
                                    
                                    // Get partner ID for sync
                                    val userDoc = firestore.collection("users")
                                        .document(currentUser.uid)
                                        .get()
                                        .await()
                                    
                                    val partnerId = userDoc.getString("partnerId")
                                    if (!partnerId.isNullOrEmpty()) {
                                        // Enqueue expedited sync
                                        val syncTypes = when (dataType) {
                                            "all" -> null
                                            else -> listOf(dataType)
                                        }
                                        
                                        PartnerDataSyncWorker.enqueueExpedited(
                                            context = context,
                                            partnerId = partnerId,
                                            syncTypes = syncTypes,
                                            triggerSource = "firestore_trigger"
                                        )
                                        
                                        Log.d(TAG, "✅ Triggered sync for: $dataType")
                                    }
                                    
                                    // Delete trigger immediately after processing (instead of just marking processed)
                                    // This prevents Firestore bloat from accumulated triggers
                                    doc.reference.delete().await()
                                    Log.d(TAG, "🧹 Deleted processed trigger: ${doc.id}")
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing sync trigger", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing sync trigger snapshots", e)
                    }
                }
            
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sync trigger listener", e)
        }
    }
    
    /**
     * Show notification based on trigger type
     * Includes deduplication to avoid showing same notification twice
     */
    private suspend fun showNotificationForTrigger(
        context: Context,
        dataType: String,
        senderName: String,
        senderId: String,
        extraData: String?
    ) {
        // ========== DEDUPLICATION CHECK ==========
        // Create unique key for this notification type + sender
        val dedupKey = "${dataType}_${senderId}"
        val currentTime = System.currentTimeMillis()
        
        // Check if we recently showed this notification
        val lastShownTime = recentNotifications[dedupKey] ?: 0L
        if (currentTime - lastShownTime < NOTIFICATION_DEDUP_WINDOW_MS) {
            Log.d(TAG, "⚠️ Skipping duplicate notification: $dedupKey (shown ${currentTime - lastShownTime}ms ago)")
            return
        }
        
        // Record this notification
        recentNotifications[dedupKey] = currentTime
        
        // Cleanup old entries (older than 30 seconds)
        recentNotifications.entries.removeIf { currentTime - it.value > 30000L }
        
        // Skip notification if app is in foreground and user is viewing the relevant screen
        if (CoupleApplication.isAppInForeground) {
            Log.d(TAG, "App in foreground, showing subtle notification")
            // Still show notification but user might see it in status bar
        }
        
        when (dataType) {
            SyncTriggerHelper.DataType.MISSING -> {
                // Get today's miss count for richer notification
                val missCount = getMissCountFromFirestore(senderId)
                PartnerNotificationManager.showMissingNotification(
                    context = context,
                    senderName = senderName,
                    missCount = missCount
                )
                
                // Update Missing widget immediately when partner sends missing
                com.example.coupleapp.widget.data.WidgetDataRepository.invalidateMissingCache(context)
                com.example.coupleapp.widget.MissingWidgetProvider.forceUpdateWidgets(context)
            }
            
            SyncTriggerHelper.DataType.PHOTOS -> {
                val locketType = extraData ?: "photo"
                PartnerNotificationManager.showLocketNotification(
                    context = context,
                    senderName = senderName,
                    locketType = locketType
                )
            }
            
            "question" -> {
                val questionText = extraData ?: "Có câu hỏi mới từ người yêu"
                PartnerNotificationManager.showQuestionNotification(
                    context = context,
                    senderName = senderName,
                    questionText = questionText
                )
            }
            
            // Chat message notification
            SyncTriggerHelper.DataType.MESSAGE -> {
                // Only show if not in chat screen
                if (!CoupleApplication.isUserInChatScreen) {
                    val messagePreview = extraData ?: "Bạn có tin nhắn mới"
                    PartnerNotificationManager.showChatNotification(
                        context = context,
                        senderName = senderName,
                        messagePreview = messagePreview
                    )
                } else {
                    Log.d(TAG, "User is in chat screen, skip notification")
                }
            }
            
            // Location and sleep updates are silent (no notification)
            SyncTriggerHelper.DataType.LOCATION,
            SyncTriggerHelper.DataType.SLEEP -> {
                Log.d(TAG, "Silent sync for $dataType - no notification")
            }
        }
    }
    
    /**
     * Get today's miss count for partner
     */
    private suspend fun getMissCountFromFirestore(senderId: String): Int {
        return try {
            val currentUserId = auth.currentUser?.uid ?: return 1
            val coupleId = listOf(currentUserId, senderId).sorted().joinToString("_")
            val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            val recordId = "${coupleId}_${senderId}_$today"
            
            val doc = firestore.collection("missing_records")
                .document(recordId)
                .get()
                .await()
            
            doc.getLong("count")?.toInt() ?: 1
        } catch (e: Exception) {
            Log.e(TAG, "Error getting miss count", e)
            1
        }
    }
    
    /**
     * Stop listening for sync triggers.
     * Call this when user logs out or app is destroyed.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping sync trigger listener")
        listenerRegistration?.remove()
        listenerRegistration = null
        isListening = false
    }
    
    /**
     * Full cleanup - cancel scope and stop listening
     * Call this on app termination or logout
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up SyncTriggerListener")
        stopListening()
        scopeJob?.cancel()
        scopeJob = null
        scope = null
    }
    
    /**
     * Check if currently listening.
     */
    fun isActive(): Boolean = isListening
}
