package com.example.coupleapp.viewmodel

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.data.EmojiHelper
import com.example.coupleapp.data.model.*
import com.example.coupleapp.data.repository.LocketFirebaseRepository
import com.example.coupleapp.widget.LocketWidgetManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(FlowPreview::class)
class LocketViewModelFirebase : ViewModel() {

    private val locketRepository = LocketFirebaseRepository()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    
    private val _uiState = MutableStateFlow(LocketUiState())

    val uiState: StateFlow<LocketUiState> = _uiState
        .debounce(50)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LocketUiState()
        )

    private var loadDataJob: Job? = null
    private var receivedLocketsJob: Job? = null
    private var sentLocketsJob: Job? = null

    init {
        loadInitialData()
        loadSettingsFromPreferences()
        observeReceivedLockets()
    }
    
    /**
     * Load Locket settings from SharedPreferences
     */
    private fun loadSettingsFromPreferences() {
        try {
            val prefs = CoupleApplication.instance.getSharedPreferences("couple_app_prefs", android.content.Context.MODE_PRIVATE)
            val notificationsEnabled = prefs.getBoolean("locket_notifications", true)
            val autoSaveEnabled = prefs.getBoolean("locket_auto_save", false)
            
            _uiState.update { currentState ->
                currentState.copy(
                    settings = currentState.settings.copy(
                        notificationsEnabled = notificationsEnabled,
                        autoSaveToGallery = autoSaveEnabled
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore if preferences cannot be loaded
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _uiState.update { it.copy(
                        isLoading = false,
                        error = "User not logged in"
                    )}
                    return@launch
                }
                
                // Load user info
                val userDoc = firestore.collection("users")
                    .document(currentUser.uid)
                    .get()
                    .await()
                
                val userName = userDoc.getString("displayName") ?: "You"
                val userAvatarUrl = userDoc.getString("profileImageUrl")
                val partnerId = userDoc.getString("partnerId")
                val coupleId = userDoc.getString("coupleId")
                
                // Load partner info
                var partnerName = "Partner"
                var partnerAvatarUrl: String? = null
                
                if (partnerId != null) {
                    val partnerDoc = firestore.collection("users")
                        .document(partnerId)
                        .get()
                        .await()
                    
                    partnerName = partnerDoc.getString("displayName") ?: "Partner"
                    partnerAvatarUrl = partnerDoc.getString("profileImageUrl")
                }
                
                // Load emojis
                val emojis = EmojiHelper.getEmojis()
                
                _uiState.update { currentState ->
                    currentState.copy(
                        currentUser = UserProfile(currentUser.uid, userName, userAvatarUrl),
                        partnerUser = UserProfile(partnerId ?: "", partnerName, partnerAvatarUrl),
                        emojis = emojis,
                        isLoading = false
                    )
                }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load data"
                )}
            }
        }
    }
    
    /**
     * Observe lockets received from partner (realtime)
     */
    private fun observeReceivedLockets() {
        receivedLocketsJob?.cancel()
        receivedLocketsJob = viewModelScope.launch {
            locketRepository.getReceivedLocketsFlow()
                .catch { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
                .collect { firebaseLockets ->
                    // Convert Firebase lockets to LocketPost
                    val locketPosts = firebaseLockets.map { firebaseLocket ->
                        convertFirebaseLocketToLocketPost(firebaseLocket)
                    }
                    
                    _uiState.update { it.copy(
                        locketHistory = locketPosts
                    )}
                }
        }
    }
    
    /**
     * Load sent lockets (for history view with both sent and received)
     */
    fun loadAllLockets() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                
                val userDoc = firestore.collection("users")
                    .document(currentUser.uid)
                    .get()
                    .await()
                
                val coupleId = userDoc.getString("coupleId")
                
                if (coupleId != null) {
                    locketRepository.getAllLocketsForCoupleFlow(coupleId)
                        .catch { e ->
                            _uiState.update { it.copy(
                                isLoading = false,
                                error = e.message
                            )}
                        }
                        .collect { firebaseLockets ->
                            val locketPosts = firebaseLockets.map { firebaseLocket ->
                                convertFirebaseLocketToLocketPost(firebaseLocket)
                            }
                            
                            _uiState.update { it.copy(
                                locketHistory = locketPosts,
                                isLoading = false
                            )}
                        }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    error = e.message
                )}
            }
        }
    }
    
    /**
     * Convert Firebase locket to app LocketPost model
     */
    private fun convertFirebaseLocketToLocketPost(firebaseLocket: com.example.coupleapp.data.model.FirebaseLocketPost): LocketPost {
        val type = when (firebaseLocket.type) {
            "photo" -> LocketType.PHOTO
            "emoji" -> LocketType.EMOJI
            "drawing" -> LocketType.DRAWING
            "text" -> LocketType.TEXT
            else -> LocketType.PHOTO
        }
        
        val content = when (firebaseLocket.type) {
            "photo" -> firebaseLocket.photoUrl
            "emoji" -> firebaseLocket.emoji
            "drawing" -> firebaseLocket.drawingUrl
            "text" -> firebaseLocket.textContent
            else -> ""
        }
        
        val timestamp = firebaseLocket.timestamp?.toInstant()
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?: LocalDateTime.now()
        
        return LocketPost(
            id = firebaseLocket.id,
            type = type,
            content = content,
            caption = firebaseLocket.caption,
            senderId = firebaseLocket.senderId,
            senderName = firebaseLocket.senderName,
            senderAvatar = firebaseLocket.senderAvatarUrl,
            receiverId = firebaseLocket.receiverId,
            receiverName = firebaseLocket.receiverName,
            timestamp = timestamp,
            isRead = firebaseLocket.isRead
        )
    }

    // Tab selection
    fun selectTab(tab: LocketTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    // Camera controls
    fun toggleFlash() {
        _uiState.update { currentState ->
            currentState.copy(
                cameraState = currentState.cameraState.copy(
                    isFlashOn = !currentState.cameraState.isFlashOn
                )
            )
        }
    }

    fun toggleCamera() {
        _uiState.update { currentState ->
            currentState.copy(
                cameraState = currentState.cameraState.copy(
                    isFrontCamera = !currentState.cameraState.isFrontCamera
                )
            )
        }
    }

    fun cycleZoom() {
        _uiState.update { currentState ->
            val currentZoom = currentState.cameraState.zoomLevel
            val newZoom = when (currentZoom) {
                1.0f -> 2.0f
                2.0f -> 3.0f
                else -> 1.0f
            }
            currentState.copy(
                cameraState = currentState.cameraState.copy(zoomLevel = newZoom)
            )
        }
    }

    // Photo handling
    fun onPhotoCaptured(bitmap: Bitmap) {
        _uiState.update { it.copy(
            capturedPhoto = bitmap,
            showPreview = true
        ) }
    }
    
    fun onGalleryImageSelected(bitmap: Bitmap) {
        _uiState.update { it.copy(
            capturedPhoto = bitmap,
            showPreview = true
        ) }
    }

    fun clearCapturedPhoto() {
        _uiState.update { it.copy(
            capturedPhoto = null,
            showPreview = false
        ) }
    }

    // Emoji handling
    fun selectEmoji(emoji: String) {
        _uiState.update { it.copy(selectedEmoji = emoji) }
    }

    fun showEmojiPicker(show: Boolean) {
        _uiState.update { it.copy(showEmojiPicker = show) }
    }

    // Text handling
    fun updateTextContent(text: String) {
        _uiState.update { it.copy(textContent = text) }
    }

    // Drawing handling
    fun updateDrawingPaths(paths: List<DrawingPath>) {
        _uiState.update { it.copy(drawingPaths = paths) }
    }

    fun setDrawingBitmap(bitmap: Bitmap?) {
        _uiState.update { it.copy(drawingBitmap = bitmap) }
    }

    // Send locket
    fun sendLocket() {
        // Guard: prevent duplicate sends when already sending
        if (_uiState.value.isSending) {
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            
            try {
                val state = _uiState.value
                val partnerId = state.partnerUser.id
                val partnerName = state.partnerUser.name
                
                if (partnerId.isEmpty()) {
                    _uiState.update { it.copy(
                        isSending = false,
                        error = "No partner connected"
                    )}
                    return@launch
                }
                
                val result = when (state.selectedTab) {
                    LocketTab.PHOTO -> {
                        val bitmap = state.capturedPhoto
                        if (bitmap != null) {
                            // Auto-save to gallery if enabled
                            if (state.settings.autoSaveToGallery) {
                                savePhotoToGallery(bitmap)
                            }
                            locketRepository.sendPhotoLocket(
                                bitmap = bitmap,
                                receiverId = partnerId,
                                receiverName = partnerName
                            )
                        } else {
                            Result.failure(Exception("No photo captured"))
                        }
                    }
                    
                    LocketTab.EMOJI -> {
                        val emoji = state.selectedEmoji
                        if (emoji != null) {
                            locketRepository.sendEmojiLocket(
                                emoji = emoji,
                                receiverId = partnerId,
                                receiverName = partnerName
                            )
                        } else {
                            Result.failure(Exception("No emoji selected"))
                        }
                    }
                    
                    LocketTab.DRAWING -> {
                        val bitmap = state.drawingBitmap
                        if (bitmap != null) {
                            locketRepository.sendDrawingLocket(
                                drawingBitmap = bitmap,
                                receiverId = partnerId,
                                receiverName = partnerName
                            )
                        } else {
                            Result.failure(Exception("No drawing created"))
                        }
                    }
                    
                    LocketTab.TEXT -> {
                        val text = state.textContent.trim()
                        if (text.isNotEmpty()) {
                            locketRepository.sendTextLocket(
                                text = text,
                                receiverId = partnerId,
                                receiverName = partnerName
                            )
                        } else {
                            Result.failure(Exception("Text is empty"))
                        }
                    }
                }
                
                if (result.isSuccess) {
                    // Reset state after sending
                    _uiState.update { currentState ->
                        currentState.copy(
                            isSending = false,
                            capturedPhoto = null,
                            showPreview = false,
                            selectedEmoji = null,
                            textContent = "",
                            drawingPaths = emptyList(),
                            drawingBitmap = null,
                            sendSuccess = true
                        )
                    }
                    
                    // Update widget immediately after sending locket
                    LocketWidgetManager.onLocketSent(CoupleApplication.instance)
                    
                    // Notify partner via sync trigger (will show notification on their device!)
                    val locketType = when (state.selectedTab) {
                        LocketTab.EMOJI -> "emoji"
                        LocketTab.DRAWING -> "drawing"
                        LocketTab.TEXT -> "text"
                        else -> "photo"
                    }
                    com.example.coupleapp.util.SyncTriggerHelper.sendTriggerToPartnerWithExtra(
                        context = CoupleApplication.instance,
                        dataType = com.example.coupleapp.util.SyncTriggerHelper.DataType.PHOTOS,
                        priority = "high",
                        extraData = locketType
                    )
                    
                    // Notify partner via sync trigger (no Cloud Functions needed!)
                    com.example.coupleapp.util.SyncTriggerHelper.notifyLocketUploaded(CoupleApplication.instance)
                    
                    // Reset success flag after showing
                    delay(2000)
                    _uiState.update { it.copy(sendSuccess = false) }
                } else {
                    _uiState.update { it.copy(
                        isSending = false,
                        error = result.exceptionOrNull()?.message ?: "Failed to send locket"
                    )}
                }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isSending = false,
                    error = e.message
                )}
            }
        }
    }
    
    /**
     * Mark a locket as read
     */
    fun markLocketAsRead(locketId: String) {
        viewModelScope.launch {
            locketRepository.markAsRead(locketId)
        }
    }
    
    /**
     * Delete a single locket (only allowed for sender)
     */
    fun deleteLocket(locketPost: LocketPost) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isDeleting = true) }
                
                val currentUserId = auth.currentUser?.uid
                if (currentUserId == null || locketPost.senderId != currentUserId) {
                    _uiState.update { it.copy(
                        isDeleting = false,
                        error = "Bạn chỉ có thể xóa Locket do mình đăng"
                    )}
                    return@launch
                }
                
                // Convert to FirebaseLocketPost for deletion
                val firebaseLocketPost = com.example.coupleapp.data.model.FirebaseLocketPost(
                    id = locketPost.id,
                    type = when (locketPost.type) {
                        LocketType.PHOTO -> "photo"
                        LocketType.EMOJI -> "emoji"
                        LocketType.DRAWING -> "drawing"
                        LocketType.TEXT -> "text"
                    },
                    photoUrl = if (locketPost.type == LocketType.PHOTO) locketPost.content else "",
                    drawingUrl = if (locketPost.type == LocketType.DRAWING) locketPost.content else "",
                    emoji = if (locketPost.type == LocketType.EMOJI) locketPost.content else "",
                    textContent = if (locketPost.type == LocketType.TEXT) locketPost.content else "",
                    senderId = locketPost.senderId,
                    receiverId = locketPost.receiverId
                )
                
                val result = locketRepository.deleteLocket(locketPost.id, firebaseLocketPost)
                
                if (result.isSuccess) {
                    // Remove from local state
                    _uiState.update { state ->
                        state.copy(
                            isDeleting = false,
                            locketHistory = state.locketHistory.filter { it.id != locketPost.id },
                            deleteSuccess = true
                        )
                    }
                    
                    // Update widget
                    LocketWidgetManager.onLocketSent(CoupleApplication.instance)
                    
                    // Reset success flag
                    delay(2000)
                    _uiState.update { it.copy(deleteSuccess = false) }
                } else {
                    _uiState.update { it.copy(
                        isDeleting = false,
                        error = "Không thể xóa Locket"
                    )}
                }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isDeleting = false,
                    error = e.message ?: "Lỗi khi xóa Locket"
                )}
            }
        }
    }
    
    /**
     * Delete multiple lockets at once (only allowed for sender)
     */
    fun deleteMultipleLockets(locketPosts: List<LocketPost>) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isDeleting = true) }
                
                val currentUserId = auth.currentUser?.uid
                if (currentUserId == null) {
                    _uiState.update { it.copy(
                        isDeleting = false,
                        error = "Vui lòng đăng nhập"
                    )}
                    return@launch
                }
                
                // Filter only lockets from current user
                val deletableLockets = locketPosts.filter { it.senderId == currentUserId }
                
                if (deletableLockets.isEmpty()) {
                    _uiState.update { it.copy(
                        isDeleting = false,
                        error = "Không có Locket nào có thể xóa"
                    )}
                    return@launch
                }
                
                var deletedCount = 0
                val deletedIds = mutableListOf<String>()
                
                for (locketPost in deletableLockets) {
                    val firebaseLocketPost = com.example.coupleapp.data.model.FirebaseLocketPost(
                        id = locketPost.id,
                        type = when (locketPost.type) {
                            LocketType.PHOTO -> "photo"
                            LocketType.EMOJI -> "emoji"
                            LocketType.DRAWING -> "drawing"
                            LocketType.TEXT -> "text"
                        },
                        photoUrl = if (locketPost.type == LocketType.PHOTO) locketPost.content else "",
                        drawingUrl = if (locketPost.type == LocketType.DRAWING) locketPost.content else "",
                        emoji = if (locketPost.type == LocketType.EMOJI) locketPost.content else "",
                        textContent = if (locketPost.type == LocketType.TEXT) locketPost.content else "",
                        senderId = locketPost.senderId,
                        receiverId = locketPost.receiverId
                    )
                    
                    val result = locketRepository.deleteLocket(locketPost.id, firebaseLocketPost)
                    if (result.isSuccess) {
                        deletedCount++
                        deletedIds.add(locketPost.id)
                    }
                }
                
                // Update local state
                _uiState.update { state ->
                    state.copy(
                        isDeleting = false,
                        locketHistory = state.locketHistory.filter { it.id !in deletedIds },
                        deleteSuccess = deletedCount > 0,
                        selectedForDeletion = emptySet()
                    )
                }
                
                // Update widget
                if (deletedCount > 0) {
                    LocketWidgetManager.onLocketSent(CoupleApplication.instance)
                }
                
                // Reset success flag
                delay(2000)
                _uiState.update { it.copy(deleteSuccess = false) }
                
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isDeleting = false,
                    error = e.message ?: "Lỗi khi xóa Locket"
                )}
            }
        }
    }
    
    /**
     * Toggle selection mode for batch deletion
     */
    fun toggleSelectionMode(enabled: Boolean) {
        _uiState.update { it.copy(
            isSelectionMode = enabled,
            selectedForDeletion = if (enabled) it.selectedForDeletion else emptySet()
        )}
    }
    
    /**
     * Toggle selection of a locket for deletion
     */
    fun toggleLocketSelection(locketId: String) {
        _uiState.update { state ->
            val newSelection = if (locketId in state.selectedForDeletion) {
                state.selectedForDeletion - locketId
            } else {
                state.selectedForDeletion + locketId
            }
            state.copy(selectedForDeletion = newSelection)
        }
    }
    
    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // Pin toggle
    fun togglePinMode(isPinMode: Boolean) {
        _uiState.update { it.copy(isPinMode = isPinMode) }
        
        // Load appropriate data based on mode
        if (!isPinMode) {
            loadAllLockets()
        }
    }

    // Settings
    fun showSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    fun showHistory(show: Boolean) {
        _uiState.update { it.copy(showHistory = show) }
    }
    
    /**
     * Update locket notification settings
     */
    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                settings = currentState.settings.copy(notificationsEnabled = enabled)
            )
        }
    }
    
    /**
     * Update auto-save to gallery setting
     */
    fun updateAutoSaveEnabled(enabled: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                settings = currentState.settings.copy(autoSaveToGallery = enabled)
            )
        }
    }
    
    /**
     * Save photo to device gallery (phone's Photos/Gallery app)
     */
    private suspend fun savePhotoToGallery(bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val context = CoupleApplication.instance
                val filename = "Locket_${System.currentTimeMillis()}"
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ - Use MediaStore (saves to phone gallery)
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CoupleApp")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        }
                        
                        // Mark as complete - now visible in gallery
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(it, contentValues, null, null)
                        android.util.Log.d("LocketViewModel", "Photo saved to gallery: $filename")
                    }
                } else {
                    // Android 9 and below - Save to external storage
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val coupleAppDir = File(picturesDir, "CoupleApp")
                    if (!coupleAppDir.exists()) {
                        coupleAppDir.mkdirs()
                    }
                    
                    val file = File(coupleAppDir, "$filename.jpg")
                    FileOutputStream(file).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    }
                    
                    // Notify gallery app to scan the new file
                    val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    mediaScanIntent.data = android.net.Uri.fromFile(file)
                    context.sendBroadcast(mediaScanIntent)
                    android.util.Log.d("LocketViewModel", "Photo saved to gallery (legacy): $filename")
                }
            } catch (e: Exception) {
                android.util.Log.e("LocketViewModel", "Error saving photo to gallery", e)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        receivedLocketsJob?.cancel()
        sentLocketsJob?.cancel()
        loadDataJob?.cancel()
    }
}
