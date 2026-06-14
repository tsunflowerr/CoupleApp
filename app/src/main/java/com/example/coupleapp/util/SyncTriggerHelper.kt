package com.example.coupleapp.util

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Helper to send sync triggers to partner when local data changes.
 * 
 * This is the SENDER side of the sync mechanism:
 * 1. User uploads locket/sends missing → App calls SyncTriggerHelper.send()
 * 2. Creates document in "sync_triggers" collection with targetUserId = partnerId
 * 3. Partner's SyncTriggerListener picks up the trigger
 * 4. Partner's app syncs data from Firebase and updates widget
 * 
 * This replaces Cloud Functions for simple use cases:
 * - No Blaze plan required
 * - Works when partner's app is open (real-time)
 * - Falls back to 15-30min periodic sync when partner's app is closed
 */
object SyncTriggerHelper {
    
    private const val TAG = "SyncTriggerHelper"
    private const val COLLECTION_SYNC_TRIGGERS = "sync_triggers"
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    
    /**
     * Send a sync trigger to partner.
     * Call this whenever you upload data that partner's widget should display.
     * 
     * @param context Application context
     * @param dataType Type of data that changed (photos, missing, sleep, location, all)
     * @param priority High priority will attempt expedited sync on partner side
     * @return true if trigger sent successfully
     */
    suspend fun sendTriggerToPartner(
        context: Context,
        dataType: String,
        priority: String = "normal"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Log.w(TAG, "Cannot send trigger: user not logged in")
                return@withContext false
            }
            
            // Get partner ID
            val userDoc = firestore.collection("users")
                .document(currentUser.uid)
                .get()
                .await()
            
            val partnerId = userDoc.getString("partnerId")
            if (partnerId.isNullOrEmpty()) {
                Log.w(TAG, "Cannot send trigger: no partner linked")
                return@withContext false
            }
            
            val senderName = userDoc.getString("displayName") ?: "Partner"
            
            // Create trigger document
            val triggerData = hashMapOf(
                "senderId" to currentUser.uid,
                "senderName" to senderName,
                "targetUserId" to partnerId,
                "dataType" to dataType,
                "priority" to priority,
                "timestamp" to com.google.firebase.Timestamp.now(),
                "processed" to false
            )
            
            // Add to Firestore
            firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .add(triggerData)
                .await()
            
            Log.d(TAG, "📤 Sent sync trigger to partner: type=$dataType, priority=$priority")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sync trigger", e)
            false
        }
    }
    
    /**
     * Send trigger when a new locket photo is uploaded
     */
    suspend fun notifyLocketUploaded(context: Context): Boolean {
        return sendTriggerToPartner(
            context = context,
            dataType = DataType.PHOTOS,
            priority = "high" // Photos should update immediately
        )
    }
    
    /**
     * Send trigger when "missing you" is sent
     */
    suspend fun notifyMissingSent(context: Context): Boolean {
        return sendTriggerToPartner(
            context = context,
            dataType = DataType.MISSING,
            priority = "high" // Missing signals are time-sensitive
        )
    }
    
    /**
     * Send trigger when sleep data is updated
     */
    suspend fun notifySleepUpdated(context: Context): Boolean {
        return sendTriggerToPartner(
            context = context,
            dataType = DataType.SLEEP,
            priority = "normal"
        )
    }
    
    /**
     * Send trigger when location is updated
     */
    suspend fun notifyLocationUpdated(context: Context): Boolean {
        return sendTriggerToPartner(
            context = context,
            dataType = DataType.LOCATION,
            priority = "normal"
        )
    }
    
    /**
     * Send trigger when a question is asked
     */
    suspend fun notifyQuestionAsked(context: Context, questionText: String): Boolean {
        return sendTriggerToPartnerWithExtra(
            context = context,
            dataType = "question",
            priority = "high",
            extraData = questionText
        )
    }
    
    /**
     * Send trigger when a chat message is sent.
     * This enables push notification to partner when app is closed.
     * 
     * @param context Application context
     * @param messagePreview Short preview of the message (first 50 chars)
     * @return true if trigger sent successfully
     */
    suspend fun notifyMessageSent(context: Context, messagePreview: String): Boolean {
        return sendTriggerToPartnerWithExtra(
            context = context,
            dataType = DataType.MESSAGE,
            priority = "high",
            extraData = messagePreview
        )
    }
    
    /**
     * Data types that can be synced
     */
    object DataType {
        const val ALL = "all"
        const val PHOTOS = "photos"     // Locket photos
        const val MISSING = "missing"   // Missing you signals
        const val SLEEP = "sleep"       // Sleep data
        const val LOCATION = "location" // Location data
        const val MESSAGE = "message"   // Chat messages
    }
    
    /**
     * Send trigger with extra data (for questions, locket types, etc.)
     */
    suspend fun sendTriggerToPartnerWithExtra(
        context: Context,
        dataType: String,
        priority: String,
        extraData: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Log.w(TAG, "Cannot send trigger: user not logged in")
                return@withContext false
            }
            
            // Get partner ID
            val userDoc = firestore.collection("users")
                .document(currentUser.uid)
                .get()
                .await()
            
            val partnerId = userDoc.getString("partnerId")
            if (partnerId.isNullOrEmpty()) {
                Log.w(TAG, "Cannot send trigger: no partner linked")
                return@withContext false
            }
            
            val senderName = userDoc.getString("displayName") ?: "Người yêu"
            
            // Create trigger document with extra data
            val triggerData = hashMapOf(
                "senderId" to currentUser.uid,
                "senderName" to senderName,
                "targetUserId" to partnerId,
                "dataType" to dataType,
                "priority" to priority,
                "extraData" to extraData,
                "timestamp" to com.google.firebase.Timestamp.now(),
                "processed" to false
            )
            
            // Add to Firestore
            firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .add(triggerData)
                .await()
            
            Log.d(TAG, "📤 Sent sync trigger with extra: type=$dataType")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sync trigger", e)
            false
        }
    }
    
    /**
     * Clean up old triggers (older than 24 hours)
     * Call this periodically to prevent Firestore bloat
     * 
     * Note: Triggers are only useful for REAL-TIME updates when partner's app is open.
     * When partner opens app later, the app fetches fresh data directly from Firebase,
     * so old triggers are not needed. We keep them for 24h just in case.
     */
    suspend fun cleanupOldTriggers(): Int = withContext(Dispatchers.IO) {
        try {
            val currentUser = auth.currentUser ?: return@withContext 0
            
            // Delete triggers older than 24 hours
            val oneDayAgo = com.google.firebase.Timestamp(
                java.util.Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000) // 24 hours ago
            )
            
            var deleted = 0
            
            // Clean triggers sent by current user
            val sentTriggers = firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .whereEqualTo("senderId", currentUser.uid)
                .whereLessThan("timestamp", oneDayAgo)
                .get()
                .await()
            
            for (doc in sentTriggers.documents) {
                doc.reference.delete().await()
                deleted++
            }
            
            // Also clean triggers targeting current user (already processed or stale)
            val receivedTriggers = firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .whereEqualTo("targetUserId", currentUser.uid)
                .whereLessThan("timestamp", oneDayAgo)
                .get()
                .await()
            
            for (doc in receivedTriggers.documents) {
                doc.reference.delete().await()
                deleted++
            }
            
            if (deleted > 0) {
                Log.d(TAG, "🧹 Cleaned up $deleted old sync triggers")
            }
            
            deleted
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                Log.w(TAG, "Firestore index not ready yet for cleanup query. This is normal on first use. Index will be built automatically.")
            } else {
                Log.e(TAG, "Error cleaning up old triggers", e)
            }
            0
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old triggers", e)
            0
        }
    }
    
    /**
     * Aggressive cleanup: Delete ALL processed triggers immediately
     * Call this to clean up accumulated triggers in Firestore
     * 
     * This is more aggressive than cleanupOldTriggers() which only deletes triggers > 24h
     */
    suspend fun cleanupAllProcessedTriggers(): Int = withContext(Dispatchers.IO) {
        try {
            val currentUser = auth.currentUser ?: return@withContext 0
            
            var deleted = 0
            
            // Delete all processed triggers sent by current user
            val sentProcessed = firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .whereEqualTo("senderId", currentUser.uid)
                .whereEqualTo("processed", true)
                .get()
                .await()
            
            for (doc in sentProcessed.documents) {
                doc.reference.delete().await()
                deleted++
            }
            
            // Delete all processed triggers targeting current user
            val receivedProcessed = firestore.collection(COLLECTION_SYNC_TRIGGERS)
                .whereEqualTo("targetUserId", currentUser.uid)
                .whereEqualTo("processed", true)
                .get()
                .await()
            
            for (doc in receivedProcessed.documents) {
                doc.reference.delete().await()
                deleted++
            }
            
            if (deleted > 0) {
                Log.d(TAG, "🧹 Aggressively cleaned up $deleted processed sync triggers")
            }
            
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error in aggressive cleanup", e)
            0
        }
    }
}
