package com.example.coupleapp.manager

import android.content.Context
import android.util.Log
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.data.model.FirebaseUser
import com.example.coupleapp.data.repository.FirebaseAuthRepository
import com.example.coupleapp.data.repository.FirebaseFirestoreRepository
import com.example.coupleapp.ui.screens.profile.NotificationPreferences
import com.example.coupleapp.utils.NotificationHelper
import com.google.firebase.database.*
import kotlinx.coroutines.*

/**
 * Singleton manager for handling realtime message notifications
 * This runs globally regardless of which screen the user is viewing
 */
object MessageNotificationManager {
    private const val TAG = "MsgNotificationManager"
    
    private var appContext: Context? = null
    private var notificationHelper: NotificationHelper? = null
    private var messagesListener: ValueEventListener? = null
    private var messagesRef: DatabaseReference? = null
    private var lastKnownMessageIds: Set<String> = emptySet()
    private var isInitialized = false
    private var currentUserId: String? = null
    private var partnerId: String? = null
    private var partnerName: String? = null
    
    private val realtimeDatabase: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(
            com.example.coupleapp.util.FirebaseConstants.REALTIME_DATABASE_URL
        )
    }
    
    private val authRepository = FirebaseAuthRepository()
    private val firestoreRepository = FirebaseFirestoreRepository()
    
    // Make scope nullable and recreatable to properly manage lifecycle
    private var scope: CoroutineScope? = null
    private var scopeJob: Job? = null
    
    private fun getOrCreateScope(): CoroutineScope {
        if (scope == null) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.IO + scopeJob!!)
        }
        return scope!!
    }
    
    /**
     * Initialize the manager with application context
     * Should be called once when the app starts
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, checking for user changes...")
            checkAndReconnect()
            return
        }
        
        appContext = context.applicationContext
        notificationHelper = NotificationHelper(context.applicationContext)
        isInitialized = true
        Log.d(TAG, "MessageNotificationManager initialized")
        
        // Start listening for messages
        startListening()
    }
    
    /**
     * Check if user has changed and reconnect if needed
     */
    private fun checkAndReconnect() {
        val currentFirebaseUser = authRepository.currentUser
        if (currentFirebaseUser?.uid != currentUserId) {
            Log.d(TAG, "User changed, reconnecting...")
            stopListening()
            startListening()
        }
    }
    
    /**
     * Start listening for new messages
     */
    fun startListening() {
        getOrCreateScope().launch {
            try {
                val firebaseUser = authRepository.currentUser
                if (firebaseUser == null) {
                    Log.d(TAG, "No user logged in, cannot start listening")
                    return@launch
                }
                
                currentUserId = firebaseUser.uid
                Log.d(TAG, "Starting message listener for user: $currentUserId")
                
                // Load user data to get partnerId
                val userResult = firestoreRepository.getDocument(
                    FirebaseFirestoreRepository.USERS_COLLECTION,
                    currentUserId!!,
                    FirebaseUser::class.java
                )
                
                val user = userResult.getOrNull()
                if (user == null) {
                    Log.e(TAG, "Failed to load user data")
                    return@launch
                }
                
                partnerId = user.partnerId
                if (partnerId.isNullOrEmpty()) {
                    Log.d(TAG, "User has no partner, cannot listen for messages")
                    return@launch
                }
                
                // Load partner name
                val partnerResult = firestoreRepository.getDocument(
                    FirebaseFirestoreRepository.USERS_COLLECTION,
                    partnerId!!,
                    FirebaseUser::class.java
                )
                partnerName = partnerResult.getOrNull()?.displayName ?: "Người yêu"
                
                Log.d(TAG, "Partner found: $partnerName ($partnerId)")
                
                // Setup realtime listener
                withContext(Dispatchers.Main) {
                    setupMessageListener()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting message listener", e)
            }
        }
    }
    
    /**
     * Setup Firebase Realtime Database listener for messages
     */
    private fun setupMessageListener() {
        val userId = currentUserId ?: return
        val partner = partnerId ?: return
        
        // Remove any existing listener
        stopListening()
        
        // Generate couple ID
        val coupleId = listOf(userId, partner).sorted().joinToString("_")
        Log.d(TAG, "Setting up listener for coupleId: $coupleId")
        
        // Reference to messages
        messagesRef = realtimeDatabase.getReference("chats/$coupleId/messages")
        
        // Create listener
        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                processMessageSnapshot(snapshot, userId, partner)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Message listener cancelled: ${error.message}")
            }
        }
        
        // Attach listener
        messagesRef?.addValueEventListener(messagesListener!!)
        Log.d(TAG, "✅ Global message listener attached")
    }
    
    /**
     * Process message snapshot and show notification if needed
     * Respects user's notification preferences
     */
    private fun processMessageSnapshot(snapshot: DataSnapshot, userId: String, partnerId: String) {
        Log.d(TAG, "📨 Message update received: ${snapshot.childrenCount} messages")
        
        val newMessages = mutableListOf<Pair<String, Boolean>>() // id to isRead
        
        for (messageSnapshot in snapshot.children) {
            try {
                val messageId = messageSnapshot.key ?: continue
                val senderId = messageSnapshot.child("senderId").getValue(String::class.java) ?: continue
                val isRead = messageSnapshot.child("isRead").getValue(Boolean::class.java) ?: false
                
                // Only count messages from partner
                if (senderId == partnerId) {
                    newMessages.add(messageId to isRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: ${e.message}")
            }
        }
        
        // Check for truly new unread messages
        val currentMessageIds = newMessages.map { it.first }.toSet()
        val newUnreadMessages = newMessages.filter { (id, isRead) ->
            !isRead && !lastKnownMessageIds.contains(id)
        }
        
        // Check if message notifications are enabled in settings
        val context = appContext
        val isNotificationEnabled = context?.let { 
            NotificationPreferences.isMessageNotificationEnabled(it) 
        } ?: true
        
        // Show notification if:
        // 1. There are new unread messages from partner
        // 2. App is in foreground (for background, FCM handles it)
        // 3. User is NOT in chat screen
        // 4. Notifications are enabled in settings
        val shouldShowNotification = newUnreadMessages.isNotEmpty() && 
            CoupleApplication.isAppInForeground && 
            !CoupleApplication.isUserInChatScreen &&
            isNotificationEnabled
        
        if (shouldShowNotification) {
            notificationHelper?.showMessageNotification(
                senderName = partnerName ?: "Người yêu",
                messageText = "Bạn có tin nhắn mới",
                messageCount = newUnreadMessages.size
            )
            Log.d(TAG, "🔔 Notification shown: ${newUnreadMessages.size} new messages")
        } else if (!isNotificationEnabled) {
            Log.d(TAG, "⚠️ Message notifications disabled in settings")
        }
        
        // Update known message IDs
        lastKnownMessageIds = currentMessageIds
    }
    
    /**
     * Stop listening for messages
     */
    fun stopListening() {
        messagesListener?.let { listener ->
            messagesRef?.removeEventListener(listener)
            Log.d(TAG, "Message listener removed")
        }
        messagesListener = null
        messagesRef = null
    }
    
    /**
     * Refresh listener (call when user logs in/out or partner changes)
     */
    fun refresh() {
        Log.d(TAG, "Refreshing message listener...")
        lastKnownMessageIds = emptySet()
        currentUserId = null
        partnerId = null
        partnerName = null
        stopListening()
        startListening()
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopListening()
        scopeJob?.cancel()
        scopeJob = null
        scope = null
        isInitialized = false
        currentUserId = null
        partnerId = null
        partnerName = null
        appContext = null
        lastKnownMessageIds = emptySet()
        Log.d(TAG, "MessageNotificationManager cleaned up")
    }
}
