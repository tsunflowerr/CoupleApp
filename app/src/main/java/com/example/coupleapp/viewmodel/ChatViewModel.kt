package com.example.coupleapp.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.data.model.ChatMessage
import com.example.coupleapp.data.model.FirebaseChatMessage
import com.example.coupleapp.data.model.FirebaseUser
import com.example.coupleapp.data.model.MessageType
import com.example.coupleapp.data.repository.FirebaseAuthRepository
import com.example.coupleapp.data.repository.FirebaseFirestoreRepository
import com.example.coupleapp.util.FirebaseConstants
import com.example.coupleapp.utils.NotificationHelper
import com.google.firebase.database.*
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.UUID

/**
 * ViewModel for Chat Screen with Firebase integration
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val authRepository = FirebaseAuthRepository()
    private val firestoreRepository = FirebaseFirestoreRepository()
    private val realtimeDatabase: FirebaseDatabase = FirebaseDatabase.getInstance(
        FirebaseConstants.REALTIME_DATABASE_URL
    )
    private val notificationHelper = NotificationHelper(application.applicationContext)
    private val appContext = application.applicationContext
    private var messagesListener: ValueEventListener? = null
    private var messagesRef: DatabaseReference? = null
    private var isInChatScreen = false

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _partner = MutableStateFlow<FirebaseUser?>(null)
    val partner: StateFlow<FirebaseUser?> = _partner.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _isUploadingImage = MutableStateFlow(false)
    val isUploadingImage: StateFlow<Boolean> = _isUploadingImage.asStateFlow()

    val currentUserId: String
        get() = authRepository.currentUser?.uid ?: ""

    init {
        Log.d(TAG, "ChatViewModel initialized")
        loadCurrentUserAndPartner()
    }

    override fun onCleared() {
        super.onCleared()
        // Remove realtime listener when ViewModel is cleared
        messagesListener?.let { listener ->
            messagesRef?.removeEventListener(listener)
        }
        isInChatScreen = false
        CoupleApplication.isUserInChatScreen = false
        Log.d(TAG, "ChatViewModel cleared, realtime listener removed")
    }
    
    /**
     * Set whether user is currently viewing the chat screen
     */
    fun setInChatScreen(inChat: Boolean) {
        isInChatScreen = inChat
        CoupleApplication.isUserInChatScreen = inChat
        if (inChat) {
            // Cancel notifications when user enters chat screen
            notificationHelper.cancelMessageNotification()
        }
    }

    /**
     * Load current user and partner info, then start listening to messages
     */
    private fun loadCurrentUserAndPartner() {
        viewModelScope.launch {
            _isLoading.value = true

            val currentUserId = authRepository.currentUser?.uid
            if (currentUserId == null) {
                Log.e(TAG, "No user logged in")
                _error.value = "Chưa đăng nhập"
                _isLoading.value = false
                return@launch
            }

            Log.d(TAG, "Loading user data for: $currentUserId")

            // Load current user to get partnerId
            firestoreRepository.getDocument(
                FirebaseFirestoreRepository.USERS_COLLECTION,
                currentUserId,
                FirebaseUser::class.java
            ).onSuccess { currentUser ->
                Log.d(TAG, "Current user loaded: ${currentUser?.displayName}")
                Log.d(TAG, "Partner ID: ${currentUser?.partnerId}")

                val partnerId = currentUser?.partnerId
                if (partnerId != null) {
                    // Load partner info
                    firestoreRepository.getDocument(
                        FirebaseFirestoreRepository.USERS_COLLECTION,
                        partnerId,
                        FirebaseUser::class.java
                    ).onSuccess { partnerUser ->
                        Log.d(TAG, "Partner loaded: ${partnerUser?.displayName}")
                        _partner.value = partnerUser
                        _isLoading.value = false

                        // Start listening to messages
                        listenToMessages(currentUserId, partnerId)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to load partner: ${error.message}", error)
                        _error.value = "Không thể tải thông tin partner"
                        _isLoading.value = false
                    }
                } else {
                    Log.w(TAG, "User has no partner")
                    _error.value = "Chưa liên kết với ai"
                    _isLoading.value = false
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load current user: ${error.message}", error)
                _error.value = "Không thể tải thông tin người dùng"
                _isLoading.value = false
            }
        }
    }

    /**
     * Listen to messages realtime between current user and partner
     */
    private fun listenToMessages(userId: String, partnerId: String) {
        Log.d(TAG, "[CHAT] 🔥 Setting up realtime listener for messages")
        
        // Generate couple ID
        val coupleId = listOf(userId, partnerId).sorted().joinToString("_")
        Log.d(TAG, "[CHAT] Couple ID: $coupleId")

        // Reference to messages in Realtime Database
        messagesRef = realtimeDatabase.getReference("chats/$coupleId/messages")
        
        // Create realtime listener
        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "[CHAT] 📨 Realtime update received, ${snapshot.childrenCount} messages")
                
                val messagesList = mutableListOf<ChatMessage>()
                
                for (messageSnapshot in snapshot.children) {
                    try {
                        val messageId = messageSnapshot.key ?: continue
                        val senderId = messageSnapshot.child("senderId").getValue(String::class.java) ?: continue
                        val message = messageSnapshot.child("message").getValue(String::class.java) ?: ""
                        val messageType = messageSnapshot.child("messageType").getValue(String::class.java) ?: "text"
                        val timestamp = messageSnapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                        val isRead = messageSnapshot.child("isRead").getValue(Boolean::class.java) ?: false
                        
                        val receiverId = if (senderId == userId) partnerId else userId
                        val createdAt = Instant.ofEpochMilli(timestamp)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()
                        
                        val chatMessage = ChatMessage(
                            id = messageId,
                            senderId = senderId,
                            receiverId = receiverId,
                            content = message,
                            type = MessageType.valueOf(messageType.uppercase()),
                            createdAt = createdAt,
                            isRead = isRead
                        )
                        
                        messagesList.add(chatMessage)
                    } catch (e: Exception) {
                        Log.e(TAG, "[CHAT] Error parsing message: ${e.message}", e)
                    }
                }
                
                // Sort by timestamp
                val sortedMessages = messagesList.sortedBy { it.createdAt }
                
                // Check for new messages from partner and show notification
                val previousMessages = _messages.value
                val newMessagesFromPartner = sortedMessages.filter { msg ->
                    msg.senderId == partnerId && 
                    !msg.isRead && 
                    previousMessages.none { it.id == msg.id }
                }
                
                // Show notification if:
                // 1. There are new messages from partner
                // 2. App is in background OR user is not in chat screen (using global flag)
                val shouldShowNotification = newMessagesFromPartner.isNotEmpty() && 
                    (!CoupleApplication.isAppInForeground || !CoupleApplication.isUserInChatScreen)
                
                if (shouldShowNotification) {
                    val partnerName = _partner.value?.displayName ?: "Người yêu"
                    val latestMessage = newMessagesFromPartner.last()
                    notificationHelper.showMessageNotification(
                        senderName = partnerName,
                        messageText = latestMessage.content,
                        messageCount = newMessagesFromPartner.size
                    )
                    Log.d(TAG, "[CHAT] 🔔 Notification shown: ${newMessagesFromPartner.size} new messages")
                }
                
                _messages.value = sortedMessages
                
                Log.d(TAG, "[CHAT] ✅ Updated ${sortedMessages.size} messages in UI")
                
                // Mark unread messages as read
                markUnreadMessagesAsRead(userId, sortedMessages)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[CHAT] ❌ Realtime listener cancelled: ${error.message}")
                _error.value = "Lỗi kết nối realtime: ${error.message}"
            }
        }
        
        // Attach listener
        messagesRef?.addValueEventListener(messagesListener!!)
        Log.d(TAG, "[CHAT] ✅ Realtime listener attached")
    }

    /**
     * Mark unread messages as read in Realtime Database
     */
    private fun markUnreadMessagesAsRead(currentUserId: String, messages: List<ChatMessage>) {
        val unreadMessages = messages.filter { it.senderId != currentUserId && !it.isRead }
        if (unreadMessages.isEmpty()) return
        
        viewModelScope.launch {
            val coupleId = listOf(currentUserId, _partner.value?.id ?: "").sorted().joinToString("_")
            val messagesRef = realtimeDatabase.getReference("chats/$coupleId/messages")
            
            unreadMessages.forEach { message ->
                messagesRef.child(message.id).child("isRead").setValue(true)
                    .addOnSuccessListener {
                        Log.d(TAG, "[CHAT] Marked message ${message.id} as read")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "[CHAT] Failed to mark message as read: ${e.message}")
                    }
            }
        }
    }



    /**
     * Send a new message via Realtime Database
     */
    fun sendMessage() {
        val text = _messageText.value.trim()
        if (text.isEmpty() || _isSending.value) {
            return
        }

        val currentUserId = authRepository.currentUser?.uid
        val partnerId = _partner.value?.id

        if (currentUserId == null || partnerId == null) {
            Log.e(TAG, "[CHAT] ❌ Cannot send message: missing user or partner")
            return
        }

        _isSending.value = true
        Log.d(TAG, "[CHAT] 📤 Sending message from $currentUserId to $partnerId: $text")

        // Check Firebase Realtime Database connection
        val connectedRef = realtimeDatabase.getReference(".info/connected")
        connectedRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "[CHAT] Firebase connection status: $connected")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "[CHAT] Cannot check connection: ${error.message}")
            }
        })

        // Generate coupleId
        val coupleId = listOf(currentUserId, partnerId).sorted().joinToString("_")
        Log.d(TAG, "[CHAT] Generated coupleId: $coupleId")
        
        val messagesRef = realtimeDatabase.getReference("chats/$coupleId/messages")
        Log.d(TAG, "[CHAT] Firebase path: chats/$coupleId/messages")
        Log.d(TAG, "[CHAT] Database URL: ${realtimeDatabase.reference.toString()}")
        
        // Create new message with auto-generated key
        val newMessageRef = messagesRef.push()
        val messageId = newMessageRef.key
        Log.d(TAG, "[CHAT] Generated message ID: $messageId")
        
        val messageData = mapOf(
            "senderId" to currentUserId,
            "message" to text,
            "messageType" to "text",
            "timestamp" to System.currentTimeMillis(),
            "isRead" to false
        )
        Log.d(TAG, "[CHAT] Message data prepared: $messageData")
        Log.d(TAG, "[CHAT] Calling setValue()...")

        // Timeout handler in case Firebase never responds
        viewModelScope.launch {
            var callbackReceived = false
            val sentText = text // Capture text before clearing
            
            newMessageRef.setValue(messageData)
                .addOnSuccessListener {
                    callbackReceived = true
                    Log.d(TAG, "[CHAT] ✅ Message sent successfully to Firebase")
                    _messageText.value = ""
                    _isSending.value = false
                    
                    // Send push notification to partner (for when their app is closed)
                    viewModelScope.launch {
                        try {
                            // Create message preview (max 50 chars)
                            val preview = if (sentText.length > 50) {
                                sentText.take(47) + "..."
                            } else {
                                sentText
                            }
                            
                            val success = com.example.coupleapp.util.SyncTriggerHelper.notifyMessageSent(
                                context = appContext,
                                messagePreview = preview
                            )
                            
                            if (success) {
                                Log.d(TAG, "[CHAT] 📨 Push notification sent to partner")
                            } else {
                                Log.w(TAG, "[CHAT] ⚠️ Failed to send push notification to partner")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[CHAT] ❌ Error sending push notification", e)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    callbackReceived = true
                    Log.e(TAG, "[CHAT] ❌ Failed to send message: ${error.message}", error)
                    Log.e(TAG, "[CHAT] ❌ Error details: ${error.javaClass.name}")
                    _error.value = "Không thể gửi tin nhắn: ${error.message}"
                    _isSending.value = false
                }
            
            Log.d(TAG, "[CHAT] setValue() called, waiting for callback...")
            
            // Wait for timeout
            delay(FirebaseConstants.SEND_TIMEOUT_MS)
            
            if (!callbackReceived) {
                Log.e(TAG, "[CHAT] ⏱️ TIMEOUT: Firebase Realtime Database không phản hồi sau ${FirebaseConstants.SEND_TIMEOUT_MS}ms")
                Log.e(TAG, "[CHAT] ⚠️ Kiểm tra:")
                Log.e(TAG, "[CHAT] 1. Realtime Database đã được tạo trong Firebase Console chưa?")
                Log.e(TAG, "[CHAT] 2. Rules cho phép authenticated user ghi chưa?")
                Log.e(TAG, "[CHAT] 3. Database URL trong google-services.json đúng chưa?")
                _error.value = "Kết nối Firebase timeout. Kiểm tra cấu hình Realtime Database."
                _isSending.value = false
            }
        }
    }

    /**
     * Update message text
     */
    fun onMessageChanged(text: String) {
        _messageText.value = text
    }

    /**
     * Add emoji to message
     */
    fun onEmojiClick(emoji: String) {
        _messageText.value = _messageText.value + emoji
    }

    /**
     * Send emoji as message via Realtime Database
     */
    fun sendEmoji(emoji: String) {
        if (_isSending.value) return

        val currentUserId = authRepository.currentUser?.uid
        val partnerId = _partner.value?.id

        if (currentUserId == null || partnerId == null) {
            Log.e(TAG, "[CHAT] ❌ Cannot send emoji: missing user or partner")
            return
        }

        _isSending.value = true
        Log.d(TAG, "[CHAT] 📤 Sending emoji from $currentUserId to $partnerId: $emoji")

        val coupleId = listOf(currentUserId, partnerId).sorted().joinToString("_")
        Log.d(TAG, "[CHAT] Generated coupleId for emoji: $coupleId")
        
        val messagesRef = realtimeDatabase.getReference("chats/$coupleId/messages")
        Log.d(TAG, "[CHAT] Firebase path for emoji: chats/$coupleId/messages")
        
        // Create new emoji message
        val newMessageRef = messagesRef.push()
        val messageId = newMessageRef.key
        Log.d(TAG, "[CHAT] Generated emoji message ID: $messageId")
        
        val messageData = mapOf(
            "senderId" to currentUserId,
            "message" to emoji,
            "messageType" to "emoji",
            "timestamp" to System.currentTimeMillis(),
            "isRead" to false
        )
        Log.d(TAG, "[CHAT] Emoji data prepared: $messageData")
        Log.d(TAG, "[CHAT] Calling setValue() for emoji...")

        viewModelScope.launch {
            var callbackReceived = false
            
            newMessageRef.setValue(messageData)
                .addOnSuccessListener {
                    callbackReceived = true
                    Log.d(TAG, "[CHAT] ✅ Emoji sent successfully to Firebase")
                    _isSending.value = false
                }
                .addOnFailureListener { error ->
                    callbackReceived = true
                    Log.e(TAG, "[CHAT] ❌ Failed to send emoji: ${error.message}", error)
                    Log.e(TAG, "[CHAT] ❌ Error details: ${error.javaClass.name}")
                    _error.value = "Không thể gửi emoji: ${error.message}"
                    _isSending.value = false
                }
            
            Log.d(TAG, "[CHAT] setValue() called for emoji, waiting for callback...")
            
            delay(FirebaseConstants.SEND_TIMEOUT_MS)
            
            if (!callbackReceived) {
                Log.e(TAG, "[CHAT] ⏱️ TIMEOUT: Emoji sending timeout after ${FirebaseConstants.SEND_TIMEOUT_MS}ms")
                _error.value = "Kết nối Firebase timeout. Kiểm tra cấu hình Realtime Database."
                _isSending.value = false
            }
        }
    }
    
    /**
     * Send image message via Realtime Database
     * Image is compressed to Base64 to reduce database storage
     * Max size: 500KB after compression
     */
    fun sendImage(imageUri: Uri) {
        if (_isUploadingImage.value) return
        
        val currentUserId = authRepository.currentUser?.uid
        val partnerId = _partner.value?.id
        
        if (currentUserId == null || partnerId == null) {
            Log.e(TAG, "[CHAT] ❌ Cannot send image: missing user or partner")
            _error.value = "Không thể gửi ảnh: chưa kết nối partner"
            return
        }
        
        _isUploadingImage.value = true
        Log.d(TAG, "[CHAT] 📤 Sending image from $currentUserId to $partnerId")
        
        viewModelScope.launch {
            try {
                // Compress image to Base64
                val compressedImageBase64 = withContext(Dispatchers.IO) {
                    compressImageToBase64(imageUri)
                }
                
                if (compressedImageBase64 == null) {
                    _error.value = "Không thể xử lý ảnh. Vui lòng thử ảnh khác."
                    _isUploadingImage.value = false
                    return@launch
                }
                
                // Check size - must be under 500KB to save DB storage
                val sizeKB = compressedImageBase64.length / 1024
                Log.d(TAG, "[CHAT] Compressed image size: ${sizeKB}KB")
                
                if (sizeKB > 500) {
                    _error.value = "Ảnh quá lớn (${sizeKB}KB). Vui lòng chọn ảnh nhỏ hơn."
                    _isUploadingImage.value = false
                    return@launch
                }
                
                val coupleId = listOf(currentUserId, partnerId).sorted().joinToString("_")
                val messagesRef = realtimeDatabase.getReference("${FirebaseConstants.CHATS_PATH}/$coupleId/${FirebaseConstants.MESSAGES_PATH}")
                val newMessageRef = messagesRef.push()
                
                val messageData = mapOf(
                    "senderId" to currentUserId,
                    "message" to compressedImageBase64,
                    "messageType" to FirebaseConstants.MESSAGE_TYPE_IMAGE,
                    "timestamp" to System.currentTimeMillis(),
                    "isRead" to false
                )
                
                var callbackReceived = false
                
                newMessageRef.setValue(messageData)
                    .addOnSuccessListener {
                        callbackReceived = true
                        Log.d(TAG, "[CHAT] ✅ Image sent successfully")
                        _isUploadingImage.value = false
                    }
                    .addOnFailureListener { error ->
                        callbackReceived = true
                        Log.e(TAG, "[CHAT] ❌ Failed to send image: ${error.message}", error)
                        _error.value = "Không thể gửi ảnh: ${error.message}"
                        _isUploadingImage.value = false
                    }
                
                delay(FirebaseConstants.SEND_TIMEOUT_MS)
                
                if (!callbackReceived) {
                    Log.e(TAG, "[CHAT] ⏱️ TIMEOUT: Image sending timeout")
                    _error.value = "Kết nối Firebase timeout"
                    _isUploadingImage.value = false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[CHAT] ❌ Error processing image", e)
                _error.value = "Lỗi xử lý ảnh: ${e.message}"
                _isUploadingImage.value = false
            }
        }
    }
    
    /**
     * Compress and convert image to Base64 string
     * This optimizes storage by reducing image size before storing in database
     */
    private fun compressImageToBase64(imageUri: Uri): String? {
        return try {
            val inputStream = appContext.contentResolver.openInputStream(imageUri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (originalBitmap == null) {
                Log.e(TAG, "[CHAT] Failed to decode bitmap from URI")
                return null
            }
            
            // Calculate scaled dimensions to fit within max size
            val maxWidth = FirebaseConstants.MAX_IMAGE_WIDTH
            val maxHeight = FirebaseConstants.MAX_IMAGE_HEIGHT
            
            val width = originalBitmap.width
            val height = originalBitmap.height
            
            val scaleFactor = minOf(
                maxWidth.toFloat() / width,
                maxHeight.toFloat() / height,
                1f // Don't upscale small images
            )
            
            val scaledWidth = (width * scaleFactor).toInt()
            val scaledHeight = (height * scaleFactor).toInt()
            
            val scaledBitmap = if (scaleFactor < 1f) {
                Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            } else {
                originalBitmap
            }
            
            // Compress to JPEG with quality adjustment
            val outputStream = ByteArrayOutputStream()
            var quality = FirebaseConstants.IMAGE_QUALITY
            
            // Try to get under max size by reducing quality
            do {
                outputStream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                quality -= 10
            } while (outputStream.size() > FirebaseConstants.MAX_IMAGE_SIZE_BYTES && quality > 20)
            
            val imageBytes = outputStream.toByteArray()
            
            // Clean up
            if (scaledBitmap != originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap.recycle()
            
            Log.d(TAG, "[CHAT] Image compressed: ${imageBytes.size / 1024}KB, quality: ${quality + 10}%")
            
            "data:image/jpeg;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            
        } catch (e: Exception) {
            Log.e(TAG, "[CHAT] Error compressing image", e)
            null
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Convert Firebase Timestamp to LocalDateTime
     */
    private fun Date.toLocalDateTime(): LocalDateTime {
        return Instant.ofEpochMilli(this.time)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
    }
}
