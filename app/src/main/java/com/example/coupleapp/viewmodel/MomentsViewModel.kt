package com.example.coupleapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.data.model.*
import com.example.coupleapp.data.repository.FirebaseAuthRepository
import com.example.coupleapp.data.repository.FirebaseFirestoreRepository
import com.example.coupleapp.data.repository.MomentsCacheRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

/**
 * ViewModel for Moments screen - Load real data from Firebase with caching.
 * 
 * Cache-First Strategy:
 * 1. On init: Load cached data immediately (instant UI)
 * 2. Background refresh: Load fresh data from Firebase in background
 * 3. Cache duration: 15 minutes (aggregates multiple data sources)
 */
class MomentsViewModel : ViewModel() {
    private val authRepository = FirebaseAuthRepository()
    private val firestoreRepository = FirebaseFirestoreRepository()
    private val momentsCache = MomentsCacheRepository.getInstance()
    
    private val _uiState = MutableStateFlow(MomentsUiState())
    val uiState: StateFlow<MomentsUiState> = _uiState.asStateFlow()
    
    private var currentCoupleId: String? = null
    
    companion object {
        private const val TAG = "MomentsViewModel"
    }
    
    init {
        loadMomentsWithCache()
    }
    
    /**
     * Load moments with cache-first strategy
     */
    private fun loadMomentsWithCache() {
        viewModelScope.launch {
            try {
                val firebaseUser = authRepository.currentUser
                if (firebaseUser == null) {
                    Log.e(TAG, "User not logged in")
                    _uiState.update { it.copy(isLoading = false, error = "User not logged in") }
                    return@launch
                }
                
                val userId = firebaseUser.uid
                
                // Get coupleId first
                val userResult = firestoreRepository.getDocument("users", userId, FirebaseUser::class.java)
                val currentUser = userResult.getOrNull()
                val coupleId = currentUser?.coupleId
                
                if (coupleId.isNullOrEmpty()) {
                    Log.w(TAG, "User not linked to partner, showing empty moments")
                    _uiState.update { it.copy(isLoading = false, momentsGroups = emptyList()) }
                    return@launch
                }
                
                currentCoupleId = coupleId
                
                // Try to load from cache first
                val hasCached = momentsCache.hasCachedMoments(coupleId)
                val isCacheFresh = momentsCache.isMomentsCacheFresh(coupleId)
                
                if (hasCached) {
                    Log.d(TAG, "📦 Cache found! Loading from cache first...")
                    val cachedGroups = momentsCache.getCachedMomentsGroups(coupleId)
                    
                    if (cachedGroups != null && cachedGroups.isNotEmpty()) {
                        // Show cached data immediately
                        _uiState.update { 
                            it.copy(
                                isLoading = false,
                                momentsGroups = cachedGroups,
                                error = null
                            ) 
                        }
                        
                        // Refresh in background if cache is stale
                        if (!isCacheFresh) {
                            Log.d(TAG, "🔄 Cache is stale, refreshing in background...")
                            loadMomentsFromFirebase(showLoading = false)
                        } else {
                            Log.d(TAG, "✅ Cache is fresh, no refresh needed")
                        }
                    } else {
                        // Cache parsing failed, load from Firebase
                        _uiState.update { it.copy(isLoading = true) }
                        loadMomentsFromFirebase(showLoading = true)
                    }
                } else {
                    Log.d(TAG, "🌐 No cache, loading from Firebase...")
                    _uiState.update { it.copy(isLoading = true) }
                    loadMomentsFromFirebase(showLoading = true)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadMomentsWithCache", e)
                _uiState.update { it.copy(isLoading = true) }
                loadMoments()
            }
        }
    }
    
    /**
     * Load moments from Firebase and update cache
     */
    private suspend fun loadMomentsFromFirebase(showLoading: Boolean) {
        try {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            
            val firebaseUser = authRepository.currentUser ?: return
            val userId = firebaseUser.uid
            
            val userResult = firestoreRepository.getDocument("users", userId, FirebaseUser::class.java)
            val currentUser = userResult.getOrNull() ?: return
            val coupleId = currentUser.coupleId ?: return
            
            // Load partner info
            val partnerId = currentUser.partnerId
            val partnerResult = if (partnerId != null) {
                firestoreRepository.getDocument("users", partnerId, FirebaseUser::class.java)
            } else {
                Result.failure(Exception("No partner"))
            }
            val partner = partnerResult.getOrNull()
            
            // Collect all moments
            val allMoments = mutableListOf<MomentItem>()
            
            // Load all moment types
            loadSleepMoments(currentUser, partner)?.let { allMoments.addAll(it) }
            loadMissingMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
            loadLocketMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
            loadAnniversaryMoments(currentUser, partner)?.let { allMoments.add(it) }
            loadUpcomingEvents(coupleId)?.let { allMoments.addAll(it) }
            loadGardenMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
            loadMessageMoments(currentUser, partner)?.let { allMoments.addAll(it) }
            loadCalendarMemories(coupleId)?.let { allMoments.addAll(it) }
            
            // Group by date
            val groupedMoments = groupMomentsByDate(allMoments)
            
            // Cache the results
            momentsCache.cacheMomentsGroups(coupleId, groupedMoments)
            
            Log.d(TAG, "✅ Loaded and cached ${allMoments.size} moments in ${groupedMoments.size} groups")
            
            _uiState.update {
                it.copy(
                    isLoading = false,
                    momentsGroups = groupedMoments,
                    error = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading moments from Firebase", e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
    
    /**
     * Load all moments from Firebase data sources (fallback/force refresh)
     */
    fun loadMoments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val firebaseUser = authRepository.currentUser
                if (firebaseUser == null) {
                    Log.e(TAG, "User not logged in")
                    _uiState.update { it.copy(isLoading = false, error = "User not logged in") }
                    return@launch
                }
                
                val userId = firebaseUser.uid
                Log.d(TAG, "Loading moments for user: $userId")
                
                // Load current user
                val userResult = firestoreRepository.getDocument(
                    "users",
                    userId,
                    FirebaseUser::class.java
                )
                val currentUser = userResult.getOrNull()
                val coupleId = currentUser?.coupleId
                
                if (coupleId.isNullOrEmpty()) {
                    Log.w(TAG, "User not linked to partner, showing empty moments")
                    _uiState.update { it.copy(isLoading = false, momentsGroups = emptyList()) }
                    return@launch
                }
                
                currentCoupleId = coupleId
                
                // Load partner info
                val partnerId = currentUser.partnerId
                val partnerResult = if (partnerId != null) {
                    firestoreRepository.getDocument("users", partnerId, FirebaseUser::class.java)
                } else {
                    Result.failure(Exception("No partner"))
                }
                val partner = partnerResult.getOrNull()
                
                // Collect all moments
                val allMoments = mutableListOf<MomentItem>()
                
                // 1. Load Sleep moments (last 7 days) - including partner's sleep data
                loadSleepMoments(currentUser, partner)?.let { allMoments.addAll(it) }
                
                // 2. Load Missing moments (last 30 days)
                loadMissingMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
                
                // 3. Load Locket moments (last 30 days)
                loadLocketMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
                
                // 4. Load Anniversary moments
                loadAnniversaryMoments(currentUser, partner)?.let { allMoments.add(it) }
                
                // 5. Load Upcoming Events
                loadUpcomingEvents(coupleId)?.let { allMoments.addAll(it) }
                
                // 6. Load Garden moments (plant milestones)
                loadGardenMoments(coupleId, currentUser, partner)?.let { allMoments.addAll(it) }
                
                // 7. Load Message notifications
                loadMessageMoments(currentUser, partner)?.let { allMoments.addAll(it) }
                
                // 8. Load Calendar Memories (past events as memories)
                loadCalendarMemories(coupleId)?.let { allMoments.addAll(it) }
                
                // Group by date
                val groupedMoments = groupMomentsByDate(allMoments)
                
                // Cache the results
                momentsCache.cacheMomentsGroups(coupleId, groupedMoments)
                
                Log.d(TAG, "Loaded and cached ${allMoments.size} moments")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        momentsGroups = groupedMoments,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading moments", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            }
        }
    }
    
    /**
     * Refresh moments data (force refresh from network)
     */
    fun refreshMoments() {
        viewModelScope.launch {
            // Invalidate cache first
            currentCoupleId?.let { momentsCache.invalidateCache(it) }
            // Reload from Firebase
            loadMoments()
        }
    }
    
    /**
     * Load sleep moments from Firebase
     * Uses client-side filtering to avoid composite index requirements
     */
    private suspend fun loadSleepMoments(currentUser: FirebaseUser, partner: FirebaseUser?): List<SleepMoment>? {
        return try {
            val sevenDaysAgo = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            val userIds = listOfNotNull(currentUser.id, partner?.id)
            
            // Query sleep records from Firestore - load all and filter client-side
            // to avoid composite index issues with whereIn + whereGreaterThan
            val db = Firebase.firestore
            val allMoments = mutableListOf<SleepMoment>()
            
            for (userId in userIds) {
                try {
                    val sleepSnapshot = db.collection("sleep_records")
                        .whereEqualTo("userId", userId)
                        .get()
                        .await()
                    
                    sleepSnapshot.documents.mapNotNull { doc ->
                        try {
                            val docUserId = doc.getString("userId") ?: return@mapNotNull null
                            val userName = if (docUserId == currentUser.id) currentUser.displayName else partner?.displayName ?: "Partner"
                            val dateTimestamp = doc.getTimestamp("date") ?: return@mapNotNull null
                            
                            // Client-side filter for last 7 days
                            if (dateTimestamp.toDate().before(sevenDaysAgo)) {
                                return@mapNotNull null
                            }
                            
                            val bedTimeHour = doc.getLong("bedTimeHour")?.toInt() ?: return@mapNotNull null
                            val bedTimeMinute = doc.getLong("bedTimeMinute")?.toInt() ?: return@mapNotNull null
                            val wakeUpTimeHour = doc.getLong("wakeUpTimeHour")?.toInt() ?: return@mapNotNull null
                            val wakeUpTimeMinute = doc.getLong("wakeUpTimeMinute")?.toInt() ?: return@mapNotNull null
                            val durationMinutes = doc.getLong("sleepDurationMinutes")?.toInt() ?: return@mapNotNull null
                            val qualityStr = doc.getString("quality") ?: "GOOD"
                            val achievementPercentage = doc.getDouble("achievementPercentage")?.toFloat() ?: 0f
                            
                            val date = LocalDateTime.ofInstant(
                                dateTimestamp.toDate().toInstant(),
                                java.time.ZoneId.systemDefault()
                            )
                            
                            val bedTime = LocalTime.of(bedTimeHour, bedTimeMinute)
                            val wakeUpTime = LocalTime.of(wakeUpTimeHour, wakeUpTimeMinute)
                            
                            SleepMoment(
                                id = doc.id,
                                timestamp = date,
                                userName = userName,
                                userAvatar = if (docUserId == currentUser.id) "😊" else "💕",
                                bedTime = bedTime,
                                wakeUpTime = wakeUpTime,
                                sleepDuration = durationMinutes,
                                quality = SleepQuality.valueOf(qualityStr),
                                achievementPercentage = achievementPercentage
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing sleep record", e)
                            null
                        }
                    }.let { allMoments.addAll(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading sleep records for user $userId", e)
                }
            }
            
            allMoments.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sleep moments", e)
            null
        }
    }
    
    /**
     * Load missing moments from Firebase - limited to 7 days
     */
    private suspend fun loadMissingMoments(coupleId: String, currentUser: FirebaseUser, partner: FirebaseUser?): List<MissingMoment>? {
        return try {
            val db = Firebase.firestore
            
            // Get records from last 7 days only (to save DB)
            val sevenDaysAgo = LocalDate.now().minusDays(7)
            
            val missingSnapshot = db.collection("missing_records")
                .whereEqualTo("coupleId", coupleId)
                .orderBy("updatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
            
            missingSnapshot.documents.mapNotNull { doc ->
                try {
                    val userId = doc.getString("userId") ?: return@mapNotNull null
                    val dateStr = doc.getString("date") ?: return@mapNotNull null
                    val count = doc.getLong("count")?.toInt() ?: return@mapNotNull null
                    
                    // Only show records with count > 0
                    if (count == 0) return@mapNotNull null
                    
                    // Parse date
                    val date = LocalDate.parse(dateStr)
                    if (date.isBefore(sevenDaysAgo)) return@mapNotNull null
                    
                    val senderName = if (userId == currentUser.id) currentUser.displayName else partner?.displayName ?: "Partner"
                    val receiverName = if (userId == currentUser.id) partner?.displayName ?: "You" else currentUser.displayName
                    
                    // Use updatedAt timestamp for accurate display time, fallback to createdAt
                    val updatedAt = doc.getTimestamp("updatedAt") ?: doc.getTimestamp("createdAt")
                    val timestamp = if (updatedAt != null) {
                        LocalDateTime.ofInstant(
                            updatedAt.toDate().toInstant(),
                            java.time.ZoneId.systemDefault()
                        )
                    } else {
                        // Fallback to date at current time if no timestamp
                        date.atTime(LocalDateTime.now().hour, LocalDateTime.now().minute)
                    }
                    
                    MissingMoment(
                        id = doc.id,
                        timestamp = timestamp,
                        senderName = senderName,
                        senderAvatar = if (userId == currentUser.id) "😊" else "💕",
                        receiverName = receiverName,
                        receiverAvatar = if (userId == currentUser.id) "💕" else "😊",
                        missCount = count
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing missing record", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading missing moments", e)
            null
        }
    }
    
    /**
     * Load locket moments from Firebase - limited to 7 days
     */
    private suspend fun loadLocketMoments(coupleId: String, currentUser: FirebaseUser, partner: FirebaseUser?): List<LocketMoment>? {
        return try {
            val db = Firebase.firestore
            val sevenDaysAgo = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            
            // Use locket_posts collection - limit to 7 days
            val locketSnapshot = db.collection("locket_posts")
                .whereEqualTo("coupleId", coupleId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
            
            locketSnapshot.documents.mapNotNull { doc ->
                try {
                    val createdAt = doc.getTimestamp("timestamp") ?: return@mapNotNull null
                    
                    // Filter for 7 days only
                    if (createdAt.toDate().before(sevenDaysAgo)) return@mapNotNull null
                    
                    val senderId = doc.getString("senderId") ?: return@mapNotNull null
                    val typeStr = doc.getString("type") ?: "text"
                    val photoUrl = doc.getString("photoUrl")
                    val emoji = doc.getString("emoji")
                    val drawingUrl = doc.getString("drawingUrl")
                    val textContent = doc.getString("textContent")
                    val caption = doc.getString("caption")
                    
                    val senderName = if (senderId == currentUser.id) currentUser.displayName else partner?.displayName ?: "Partner"
                    
                    val timestamp = LocalDateTime.ofInstant(
                        createdAt.toDate().toInstant(),
                        java.time.ZoneId.systemDefault()
                    )
                    
                    val locketType = when (typeStr.lowercase()) {
                        "photo" -> LocketType.PHOTO
                        "emoji" -> LocketType.EMOJI
                        "drawing" -> LocketType.DRAWING
                        "text" -> LocketType.TEXT
                        else -> LocketType.TEXT
                    }
                    
                    // Get content based on type
                    val content = when (locketType) {
                        LocketType.PHOTO -> photoUrl ?: ""
                        LocketType.EMOJI -> emoji ?: ""
                        LocketType.DRAWING -> drawingUrl ?: ""
                        LocketType.TEXT -> textContent ?: ""
                    }
                    
                    LocketMoment(
                        id = doc.id,
                        timestamp = timestamp,
                        senderName = senderName,
                        senderAvatar = "💕",
                        locketType = locketType,
                        content = content,
                        caption = caption
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing locket record", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading locket moments", e)
            null
        }
    }
    
    /**
     * Load anniversary moment - always show relationship status
     * Also adds upcoming anniversary reminders
     */
    private suspend fun loadAnniversaryMoments(currentUser: FirebaseUser, partner: FirebaseUser?): AnniversaryMoment? {
        return try {
            val coupleId = currentUser.coupleId ?: return null
            val coupleResult = firestoreRepository.getDocument("couples", coupleId, FirebaseCouple::class.java)
            val couple = coupleResult.getOrNull() ?: return null
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val anniversaryDate = dateFormat.parse(couple.anniversaryDate) ?: return null
            val today = Date()
            
            val daysTogether = ((today.time - anniversaryDate.time) / (1000 * 60 * 60 * 24))
            val monthsTogether = (daysTogether / 30)
            val yearsTogether = (daysTogether / 365)
            
            // Always return an anniversary moment to show relationship status
            // This ensures users always see how long they've been together
            AnniversaryMoment(
                id = "anniversary_$daysTogether",
                timestamp = LocalDateTime.now(),
                daysTogether = daysTogether,
                monthsTogether = monthsTogether,
                yearsTogether = yearsTogether,
                user1Name = currentUser.displayName,
                user1Avatar = "😊",
                user2Name = partner?.displayName ?: "Partner",
                user2Avatar = "💕"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading anniversary", e)
            null
        }
    }
    
    /**
     * Load upcoming events - limited to 7 days ahead
     */
    private suspend fun loadUpcomingEvents(coupleId: String): List<EventMoment>? {
        return try {
            val db = Firebase.firestore
            val sevenDaysFromNow = Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)
            
            val eventsSnapshot = db.collection("calendar_events")
                .whereEqualTo("coupleId", coupleId)
                .whereGreaterThan("eventDate", Date())
                .orderBy("eventDate", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .limit(5)
                .get()
                .await()
            
            eventsSnapshot.documents.mapNotNull { doc ->
                try {
                    val eventDateMs = doc.getLong("eventDate") ?: return@mapNotNull null
                    
                    // Filter for 7 days only
                    if (eventDateMs > sevenDaysFromNow.time) return@mapNotNull null
                    
                    val title = doc.getString("title") ?: return@mapNotNull null
                    val description = doc.getString("description")
                    val typeStr = doc.getString("type") ?: "OTHER"
                    
                    val eventDate = LocalDate.ofInstant(
                        java.time.Instant.ofEpochMilli(eventDateMs),
                        java.time.ZoneId.systemDefault()
                    )
                    val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), eventDate)
                    
                    EventMoment(
                        id = doc.id,
                        timestamp = LocalDateTime.now().minusDays(daysUntil),
                        title = title,
                        description = description,
                        eventDate = eventDate,
                        eventType = MomentEventType.valueOf(typeStr),
                        daysUntil = daysUntil
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing event", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading events", e)
            null
        }
    }
    
    /**
     * Group moments by date for timeline display
     */
    private fun groupMomentsByDate(moments: List<MomentItem>): List<MomentsGroup> {
        val grouped = moments
            .sortedByDescending { it.timestamp }
            .groupBy { it.timestamp.toLocalDate() }
        
        return grouped.map { (date, items) ->
            MomentsGroup(
                section = TimelineSection.from(date),
                moments = items
            )
        }
    }
    
    /**
     * Load garden moments from Firebase (plant events)
     * Also checks plants collection for care needs
     */
    private suspend fun loadGardenMoments(coupleId: String, currentUser: FirebaseUser, partner: FirebaseUser?): List<GardenMoment>? {
        val allMoments = mutableListOf<GardenMoment>()
        
        try {
            val db = Firebase.firestore
            val sevenDaysAgo = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)
            
            // Load plant events from garden_events collection
            val gardenSnapshot = db.collection("garden_events")
                .whereEqualTo("coupleId", coupleId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
            
            gardenSnapshot.documents.mapNotNull { doc ->
                try {
                    val userId = doc.getString("userId") ?: return@mapNotNull null
                    val plantName = doc.getString("plantName") ?: "Plant"
                    val plantEmoji = doc.getString("plantEmoji") ?: "🌱"
                    val eventTypeStr = doc.getString("eventType") ?: "WATERED"
                    val growthStage = doc.getLong("growthStage")?.toInt() ?: 0
                    val message = doc.getString("message") ?: ""
                    val eventTimestamp = doc.getTimestamp("timestamp") ?: return@mapNotNull null
                    
                    val userName = if (userId == currentUser.id) currentUser.displayName else partner?.displayName ?: "Partner"
                    
                    val timestamp = LocalDateTime.ofInstant(
                        eventTimestamp.toDate().toInstant(),
                        java.time.ZoneId.systemDefault()
                    )
                    
                    val eventType = try {
                        GardenEventType.valueOf(eventTypeStr.uppercase())
                    } catch (e: Exception) {
                        GardenEventType.WATERED
                    }
                    
                    GardenMoment(
                        id = doc.id,
                        timestamp = timestamp,
                        userName = userName,
                        userAvatar = if (userId == currentUser.id) "😊" else "💕",
                        plantName = plantName,
                        plantEmoji = plantEmoji,
                        eventType = eventType,
                        growthStage = growthStage,
                        message = message
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing garden event", e)
                    null
                }
            }.let { allMoments.addAll(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading garden events", e)
        }
        
        // ALWAYS also check plants collection for care needs (water/sunlight)
        try {
            loadGardenMomentsFromPlants(coupleId, currentUser, partner)?.let { 
                allMoments.addAll(it) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading plant care moments", e)
        }
        
        return allMoments.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Fallback: Load garden moments from plants collection
     * Also checks for plants that need water or sunlight
     */
    private suspend fun loadGardenMomentsFromPlants(coupleId: String, currentUser: FirebaseUser, partner: FirebaseUser?): List<GardenMoment>? {
        return try {
            val db = Firebase.firestore
            
            // Get all plants for the couple (not just recently updated)
            val plantsSnapshot = db.collection("plants")
                .whereEqualTo("coupleId", coupleId)
                .get()
                .await()
            
            val moments = mutableListOf<GardenMoment>()
            
            plantsSnapshot.documents.forEach { doc ->
                try {
                    val plantId = doc.id
                    val userId = doc.getString("plantedByUserId") ?: doc.getString("userId") ?: currentUser.id
                    val plantName = doc.getString("name") ?: "Plant"
                    val plantEmoji = doc.getString("emoji") ?: "🌱"
                    val growthProgress = doc.getDouble("growthProgress")?.toFloat() ?: 0f
                    val lastWateredAt = doc.getTimestamp("lastWateredAt")
                    val plantedAt = doc.getTimestamp("plantedAt")
                    val isHarvested = doc.getBoolean("isHarvested") ?: false
                    
                    // Get plant status values
                    val statusMap = doc.get("status") as? Map<*, *>
                    val waterLevel = (statusMap?.get("water") as? Number)?.toFloat() ?: 100f
                    val sunlightLevel = (statusMap?.get("sunlight") as? Number)?.toFloat() ?: 100f
                    val healthLevel = (statusMap?.get("health") as? Number)?.toFloat() ?: 100f
                    
                    val userName = if (userId == currentUser.id) currentUser.displayName else partner?.displayName ?: "Partner"
                    val growthStage = (growthProgress * 100).toInt()
                    
                    // Priority 1: Check for plants that NEED CARE (water/sunlight below 30%)
                    if (!isHarvested && waterLevel < 30f) {
                        moments.add(GardenMoment(
                            id = "${plantId}_needs_water",
                            timestamp = LocalDateTime.now(),
                            userName = "",
                            userAvatar = "💧",
                            plantName = plantName,
                            plantEmoji = plantEmoji,
                            eventType = GardenEventType.NEEDS_WATER,
                            growthStage = growthStage,
                            message = "$plantName cần được tưới nước! (${waterLevel.toInt()}%)"
                        ))
                    }
                    
                    if (!isHarvested && sunlightLevel < 30f) {
                        moments.add(GardenMoment(
                            id = "${plantId}_needs_sun",
                            timestamp = LocalDateTime.now(),
                            userName = "",
                            userAvatar = "☀️",
                            plantName = plantName,
                            plantEmoji = plantEmoji,
                            eventType = GardenEventType.NEEDS_SUN,
                            growthStage = growthStage,
                            message = "$plantName cần ánh sáng mặt trời! (${sunlightLevel.toInt()}%)"
                        ))
                    }
                    
                    // Priority 2: Harvested plants
                    if (isHarvested) {
                        val timestamp = lastWateredAt?.let {
                            LocalDateTime.ofInstant(it.toDate().toInstant(), java.time.ZoneId.systemDefault())
                        } ?: LocalDateTime.now()
                        
                        moments.add(GardenMoment(
                            id = "${plantId}_harvested",
                            timestamp = timestamp,
                            userName = userName,
                            userAvatar = if (userId == currentUser.id) "😊" else "💕",
                            plantName = plantName,
                            plantEmoji = plantEmoji,
                            eventType = GardenEventType.HARVESTED,
                            growthStage = 100,
                            message = "$plantName đã phát triển hoàn chỉnh! 🎉"
                        ))
                    } else if (growthStage >= 80) {
                        // Priority 3: Almost fully grown plants
                        val timestamp = lastWateredAt?.let {
                            LocalDateTime.ofInstant(it.toDate().toInstant(), java.time.ZoneId.systemDefault())
                        } ?: LocalDateTime.now()
                        
                        moments.add(GardenMoment(
                            id = "${plantId}_evolved",
                            timestamp = timestamp,
                            userName = userName,
                            userAvatar = if (userId == currentUser.id) "😊" else "💕",
                            plantName = plantName,
                            plantEmoji = plantEmoji,
                            eventType = GardenEventType.EVOLVED,
                            growthStage = growthStage,
                            message = "$plantName sắp phát triển hoàn chỉnh! ($growthStage%)"
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing plant for moment", e)
                }
            }
            
            moments.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading garden moments from plants", e)
            null
        }
    }
    
    /**
     * Load message notification moments from Firebase
     */
    private suspend fun loadMessageMoments(currentUser: FirebaseUser, partner: FirebaseUser?): List<MessageMoment>? {
        return try {
            val db = Firebase.firestore
            val userId = currentUser.id
            
            // Query messages where current user is receiver and not read
            val messagesSnapshot = db.collection("messages")
                .whereEqualTo("receiverId", userId)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
            
            // Group messages by sender and count unread
            val messagesBySender = messagesSnapshot.documents
                .groupBy { it.getString("senderId") }
            
            messagesBySender.mapNotNull { (senderId, docs) ->
                try {
                    if (senderId == null) return@mapNotNull null
                    
                    val unreadDocs = docs.filter { !(it.getBoolean("isRead") ?: true) }
                    if (unreadDocs.isEmpty()) return@mapNotNull null
                    
                    val latestDoc = docs.first()
                    val messagePreview = latestDoc.getString("content") ?: "New message"
                    val latestTimestamp = latestDoc.getTimestamp("timestamp") ?: return@mapNotNull null
                    
                    val senderName = if (senderId == partner?.id) {
                        partner.displayName
                    } else {
                        "Someone"
                    }
                    
                    val timestamp = LocalDateTime.ofInstant(
                        latestTimestamp.toDate().toInstant(),
                        java.time.ZoneId.systemDefault()
                    )
                    
                    MessageMoment(
                        id = "messages_$senderId",
                        timestamp = timestamp,
                        senderName = senderName,
                        senderAvatar = "💕",
                        messagePreview = if (messagePreview.length > 50) messagePreview.take(50) + "..." else messagePreview,
                        messageCount = unreadDocs.size,
                        isRead = false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message moment", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading message moments", e)
            null
        }
    }
    
    /**
     * Load calendar memories - past events from last 7 days only
     */
    private suspend fun loadCalendarMemories(coupleId: String): List<CalendarMemoryMoment>? {
        return try {
            val db = Firebase.firestore
            val today = LocalDate.now()
            val sevenDaysAgo = today.minusDays(7)
            
            // Get all calendar events
            val eventsSnapshot = db.collection("calendar_events")
                .whereEqualTo("coupleId", coupleId)
                .limit(20)
                .get()
                .await()
            
            eventsSnapshot.documents.mapNotNull { doc ->
                try {
                    val title = doc.getString("title") ?: return@mapNotNull null
                    val description = doc.getString("description")
                    val dateStr = doc.getString("date") ?: return@mapNotNull null
                    val typeStr = doc.getString("eventType") ?: "other"
                    
                    // Parse date
                    val eventDate = LocalDate.parse(dateStr)
                    
                    // Only include past events (memories)
                    if (!eventDate.isBefore(today)) return@mapNotNull null
                    
                    // Only show events from last 7 days
                    if (eventDate.isBefore(sevenDaysAgo)) return@mapNotNull null
                    
                    val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(eventDate, today)
                    
                    val eventType = when (typeStr.lowercase()) {
                        "birthday" -> MomentEventType.BIRTHDAY
                        "anniversary" -> MomentEventType.ANNIVERSARY
                        "special_day", "special" -> MomentEventType.SPECIAL_DAY
                        else -> MomentEventType.REMINDER
                    }
                    
                    // Use event date at noon as timestamp for grouping
                    val timestamp = eventDate.atTime(12, 0)
                    
                    CalendarMemoryMoment(
                        id = "memory_${doc.id}",
                        timestamp = timestamp,
                        title = title,
                        description = description,
                        eventDate = eventDate,
                        eventType = eventType,
                        daysAgo = daysAgo
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing calendar memory", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading calendar memories", e)
            null
        }
    }
    
    /**
     * Generate sample moments data - Fallback for testing
     */
    private fun generateSampleMoments(): List<MomentItem> {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        
        return listOf(
            // Today's sleep
            SleepMoment(
                id = "sleep1",
                timestamp = now.minusHours(6),
                userName = "Banana",
                userAvatar = "🍌",
                bedTime = LocalTime.of(3, 44),
                wakeUpTime = LocalTime.of(10, 31),
                sleepDuration = 406, // 6h 46min
                quality = SleepQuality.EXCELLENT,
                achievementPercentage = 84f
            ),
            
            // Missing sent
            MissingMoment(
                id = "missing1",
                timestamp = now.minusHours(8),
                senderName = "Banana",
                senderAvatar = "🍌",
                receiverName = "Broccoli",
                receiverAvatar = "🥦",
                missCount = 3
            ),
            
            // Locket posted
            LocketMoment(
                id = "locket1",
                timestamp = now.minusHours(10),
                senderName = "Broccoli",
                senderAvatar = "🥦",
                locketType = LocketType.EMOJI,
                content = "❤️",
                caption = "Thinking of you!"
            ),
            
            // Yesterday's sleep
            SleepMoment(
                id = "sleep2",
                timestamp = now.minusDays(1).minusHours(12),
                userName = "Broccoli",
                userAvatar = "🥦",
                bedTime = LocalTime.of(1, 9),
                wakeUpTime = LocalTime.of(7, 31),
                sleepDuration = 381, // 6h 21min
                quality = SleepQuality.GOOD,
                achievementPercentage = 79f
            ),
            
            // Upcoming event
            EventMoment(
                id = "event1",
                timestamp = now.minusDays(1).minusHours(15),
                title = "Valentine's Day",
                description = "Valentine's Day celebration",
                eventDate = today.plusDays(68),
                eventType = MomentEventType.SPECIAL_DAY,
                daysUntil = 68
            ),
            
            // Anniversary
            AnniversaryMoment(
                id = "anniversary1",
                timestamp = now.minusDays(2),
                daysTogether = 365,
                monthsTogether = 12,
                yearsTogether = 1,
                user1Name = "Banana",
                user1Avatar = "🍌",
                user2Name = "Broccoli",
                user2Avatar = "🥦"
            ),
            
            // More locket
            LocketMoment(
                id = "locket2",
                timestamp = now.minusDays(3).minusHours(5),
                senderName = "Banana",
                senderAvatar = "🍌",
                locketType = LocketType.TEXT,
                content = "Good morning! ☀️",
                caption = null
            ),
            
            // More missing
            MissingMoment(
                id = "missing2",
                timestamp = now.minusDays(4).minusHours(2),
                senderName = "Broccoli",
                senderAvatar = "🥦",
                receiverName = "Banana",
                receiverAvatar = "🍌",
                missCount = 5
            ),
            
            // Event
            EventMoment(
                id = "event2",
                timestamp = now.minusDays(5),
                title = "Banana's Birthday",
                description = null,
                eventDate = today.plusDays(30),
                eventType = MomentEventType.BIRTHDAY,
                daysUntil = 30
            ),
            
            // Old sleep
            SleepMoment(
                id = "sleep3",
                timestamp = now.minusDays(6).minusHours(8),
                userName = "Banana",
                userAvatar = "🍌",
                bedTime = LocalTime.of(2, 15),
                wakeUpTime = LocalTime.of(9, 0),
                sleepDuration = 405,
                quality = SleepQuality.GOOD,
                achievementPercentage = 82f
            )
        )
    }
    
    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        // Clear cached references to allow garbage collection
        currentCoupleId = null
        Log.d(TAG, "MomentsViewModel cleared")
    }
}

/**
 * UI state for Moments screen
 */
data class MomentsUiState(
    val isLoading: Boolean = true,
    val momentsGroups: List<MomentsGroup> = emptyList(),
    val error: String? = null
)
