package com.example.coupleapp.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.coupleapp.data.model.*
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

/**
 * Repository for managing location data and tracking
 * Handles real-time location updates, location history, and shared places detection
 */
class LocationRepository(
    private val context: Context
) {
    private val db = FirebaseFirestore.getInstance()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    
    // Collections
    companion object {
        const val LOCATIONS_COLLECTION = "locations"
        const val LOCATION_HISTORY_COLLECTION = "location_history"
        const val SHARED_PLACES_COLLECTION = "shared_places"
        const val SHARED_PLACE_PHOTOS_COLLECTION = "shared_place_photos"
        const val COLOCATION_SESSIONS_COLLECTION = "colocation_sessions"
        
        // Constants for shared place detection
        const val COLOCATION_RADIUS_METERS = 300.0 // Distance to consider "same location" (300m)
        const val COLOCATION_TIME_MINUTES = 5 // Time together to create shared place (5 minutes for easier testing)
        const val LOCATION_UPDATE_INTERVAL_MS = 15_000L // 15 seconds for more responsive updates
        const val LOCATION_FASTEST_INTERVAL_MS = 10_000L // 10 seconds fastest interval
        const val LOCATION_STALE_THRESHOLD_MINUTES = 5 // Consider location stale after 5 minutes
        const val HISTORY_MINIMUM_DURATION_MINUTES = 5 // Minimum time to record in history (5 min for easier testing)
    }
    
    // Current location state
    private val _myCurrentLocation = MutableStateFlow<UserLocation?>(null)
    val myCurrentLocation: StateFlow<UserLocation?> = _myCurrentLocation.asStateFlow()
    
    private val _partnerCurrentLocation = MutableStateFlow<UserLocation?>(null)
    val partnerCurrentLocation: StateFlow<UserLocation?> = _partnerCurrentLocation.asStateFlow()
    
    // Location history state
    private val _myLocationHistory = MutableStateFlow<List<LocationHistory>>(emptyList())
    val myLocationHistory: StateFlow<List<LocationHistory>> = _myLocationHistory.asStateFlow()
    
    private val _partnerLocationHistory = MutableStateFlow<List<LocationHistory>>(emptyList())
    val partnerLocationHistory: StateFlow<List<LocationHistory>> = _partnerLocationHistory.asStateFlow()
    
    // Shared places state
    private val _sharedPlaces = MutableStateFlow<List<SharedPlace>>(emptyList())
    val sharedPlaces: StateFlow<List<SharedPlace>> = _sharedPlaces.asStateFlow()
    
    // Colocation tracking
    private val _activeColocationSession = MutableStateFlow<ColocationSession?>(null)
    val activeColocationSession: StateFlow<ColocationSession?> = _activeColocationSession.asStateFlow()
    
    // Location request configuration
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        LOCATION_UPDATE_INTERVAL_MS
    ).apply {
        setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
        setWaitForAccurateLocation(false)
    }.build()
    
    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start real-time location updates for current user
     */
    fun startLocationUpdates(
        userId: String,
        userName: String,
        avatarUrl: String,
        coupleId: String
    ): Flow<UserLocation> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission not granted"))
            return@callbackFlow
        }
        
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val address = getAddressFromLocation(location.latitude, location.longitude)
                    val userLocation = UserLocation(
                        userId = userId,
                        userName = userName,
                        avatarUrl = avatarUrl,
                        coordinate = LocationCoordinate(
                            latitude = location.latitude,
                            longitude = location.longitude
                        ),
                        address = address,
                        lastUpdated = LocalDateTime.now(),
                        batteryLevel = getBatteryLevel(),
                        isOnline = true
                    )
                    
                    _myCurrentLocation.value = userLocation
                    trySend(userLocation)
                    
                    // Upload to Firebase
                    uploadLocationToFirebase(userId, coupleId, userLocation)
                    
                    // Check colocation with partner
                    checkAndUpdateColocation(coupleId, userLocation)
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e)
        }
        
        awaitClose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
    
    /**
     * Get current location once (not continuous updates)
     * Optionally uploads to Firebase if coupleId is provided
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun getCurrentLocation(
        userId: String,
        userName: String,
        avatarUrl: String,
        coupleId: String? = null
    ): Result<UserLocation> {
        return try {
            if (!hasLocationPermission()) {
                return Result.failure(SecurityException("Location permission not granted"))
            }
            
            val location = fusedLocationClient.lastLocation.await()
            if (location != null) {
                val address = getAddressFromLocation(location.latitude, location.longitude)
                val userLocation = UserLocation(
                    userId = userId,
                    userName = userName,
                    avatarUrl = avatarUrl,
                    coordinate = LocationCoordinate(
                        latitude = location.latitude,
                        longitude = location.longitude
                    ),
                    address = address,
                    lastUpdated = LocalDateTime.now(),
                    batteryLevel = getBatteryLevel(),
                    isOnline = true
                )
                _myCurrentLocation.value = userLocation
                
                // Also upload to Firebase if coupleId is available
                if (!coupleId.isNullOrEmpty()) {
                    uploadLocationToFirebase(userId, coupleId, userLocation)
                }
                
                Result.success(userLocation)
            } else {
                Result.failure(Exception("Could not get current location"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Listen to my own location updates in real-time from Firebase (pub/sub for both users)
     * This allows the user to see their own location updates being synced
     */
    fun listenToMyLocation(userId: String, coupleId: String): Flow<UserLocation?> = callbackFlow {
        // Validate inputs
        if (userId.isEmpty() || coupleId.isEmpty()) {
            android.util.Log.w("LocationRepository", "Cannot listen to my location: userId or coupleId is empty")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        
        val documentId = "${coupleId}_${userId}"
        android.util.Log.d("LocationRepository", "Starting to listen for my location (pub/sub): $documentId")
        
        val subscription = db.collection(LOCATIONS_COLLECTION)
            .document(documentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("LocationRepository", "Error listening to my location", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    try {
                        val firebaseLocation = snapshot.toObject(FirebaseLocationData::class.java)
                        if (firebaseLocation != null) {
                            val userLocation = firebaseLocation.toUserLocation()
                            android.util.Log.d("LocationRepository", "My location synced from Firebase: ${userLocation.coordinate}")
                            _myCurrentLocation.value = userLocation
                            trySend(userLocation)
                        } else {
                            trySend(null)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LocationRepository", "Error parsing my location", e)
                        trySend(null)
                    }
                } else {
                    android.util.Log.d("LocationRepository", "My location document does not exist yet: $documentId")
                    trySend(null)
                }
            }
        
        awaitClose { 
            android.util.Log.d("LocationRepository", "Stopped listening to my location")
            subscription.remove() 
        }
    }
    
    /**
     * Listen to partner's location in real-time from Firebase
     */
    fun listenToPartnerLocation(partnerId: String, coupleId: String): Flow<UserLocation?> = callbackFlow {
        // Validate inputs
        if (partnerId.isEmpty() || coupleId.isEmpty()) {
            android.util.Log.w("LocationRepository", "Cannot listen to partner location: partnerId or coupleId is empty")
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        
        val documentId = "${coupleId}_${partnerId}"
        android.util.Log.d("LocationRepository", "Starting to listen for partner location: $documentId")
        
        val subscription = db.collection(LOCATIONS_COLLECTION)
            .document(documentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("LocationRepository", "Error listening to partner location", error)
                    // Don't close the flow on transient errors, just send null
                    trySend(null)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    // ★ Check if data is from cache or server
                    val source = if (snapshot.metadata.isFromCache) "LOCAL_CACHE" else "SERVER"
                    android.util.Log.d("LocationRepository", "Partner location snapshot [source=$source]")
                    
                    try {
                        val firebaseLocation = snapshot.toObject(FirebaseLocationData::class.java)
                        if (firebaseLocation != null) {
                            val userLocation = firebaseLocation.toUserLocation()
                            android.util.Log.d("LocationRepository", "Partner location received: ${userLocation.coordinate} [source=$source]")
                            _partnerCurrentLocation.value = userLocation
                            trySend(userLocation)
                        } else {
                            android.util.Log.w("LocationRepository", "Could not parse partner location data")
                            trySend(null)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LocationRepository", "Error parsing partner location", e)
                        trySend(null)
                    }
                } else {
                    android.util.Log.d("LocationRepository", "Partner location document does not exist yet: $documentId")
                    // Document doesn't exist yet - partner hasn't uploaded location
                    _partnerCurrentLocation.value = null
                    trySend(null)
                }
            }
        
        awaitClose { 
            android.util.Log.d("LocationRepository", "Stopped listening to partner location")
            subscription.remove() 
        }
    }
    
    /**
     * Upload current location to Firebase
     */
    private fun uploadLocationToFirebase(userId: String, coupleId: String, location: UserLocation) {
        val documentId = "${coupleId}_${userId}"
        android.util.Log.d("LocationRepository", "Uploading location to Firebase: $documentId")
        
        val firebaseLocation = FirebaseLocationData(
            id = documentId,
            userId = userId,
            coupleId = coupleId,
            userName = location.userName,
            avatarUrl = location.avatarUrl,
            latitude = location.coordinate.latitude,
            longitude = location.coordinate.longitude,
            address = location.address,
            batteryLevel = location.batteryLevel,
            isOnline = location.isOnline,
            timestamp = Date()
        )
        
        db.collection(LOCATIONS_COLLECTION)
            .document(firebaseLocation.id)
            .set(firebaseLocation)
            .addOnSuccessListener {
                android.util.Log.d("LocationRepository", "Location uploaded successfully: $documentId")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("LocationRepository", "Failed to upload location: $documentId", e)
            }
    }
    
    /**
     * Update user online status
     */
    suspend fun updateOnlineStatus(userId: String, coupleId: String, isOnline: Boolean): Result<Unit> {
        return try {
            db.collection(LOCATIONS_COLLECTION)
                .document("${coupleId}_${userId}")
                .update("isOnline", isOnline)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete location history entries older than specified number of days.
     * This helps save database storage.
     * 
     * @param userId The user ID to delete history for
     * @param coupleId The couple ID
     * @param daysToKeep Number of days of history to keep (default 3)
     * @return Number of deleted entries
     */
    suspend fun deleteOldLocationHistory(userId: String, coupleId: String, daysToKeep: Int = 3): Result<Int> {
        return try {
            val cutoffDate = Date(System.currentTimeMillis() - daysToKeep * 24 * 60 * 60 * 1000L)
            android.util.Log.d("LocationRepository", "Deleting location history older than $daysToKeep days (before $cutoffDate)")
            
            val oldEntries = db.collection(LOCATION_HISTORY_COLLECTION)
                .whereEqualTo("userId", userId)
                .whereEqualTo("coupleId", coupleId)
                .get()
                .await()
            
            var deletedCount = 0
            for (doc in oldEntries.documents) {
                val arrivalTime = doc.getDate("arrivalTime")
                if (arrivalTime != null && arrivalTime.before(cutoffDate)) {
                    doc.reference.delete().await()
                    deletedCount++
                }
            }
            
            android.util.Log.d("LocationRepository", "Deleted $deletedCount old location history entries")
            Result.success(deletedCount)
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error deleting old location history", e)
            Result.failure(e)
        }
    }
    
    /**
     * Sync active entry's duration when app is opened.
     * Finds the entry with departureTime = null and updates its durationMinutes to reflect 
     * the actual time from arrivalTime to NOW.
     * 
     * This ensures the "currently here" entry shows accurate duration even if 
     * BackgroundLocationWorker hasn't run recently.
     */
    private suspend fun syncActiveEntryDuration(documents: List<com.google.firebase.firestore.DocumentSnapshot>) {
        try {
            val now = System.currentTimeMillis()
            val today = java.time.LocalDate.now()
            
            // Find entry with departureTime = null (active entry)
            for (doc in documents) {
                val departureTime = doc.getDate("departureTime")
                val arrivalTime = doc.getDate("arrivalTime") ?: continue
                
                // Skip if not active (has departureTime)
                if (departureTime != null) continue
                
                // Only sync entries from today
                val arrivalDate = arrivalTime.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                if (arrivalDate != today) continue
                
                // Validate arrivalTime is not in the future
                if (arrivalTime.time > now) {
                    android.util.Log.w("LocationRepository", "Active entry has future arrivalTime, skipping sync")
                    continue
                }
                
                // Calculate accurate duration from arrivalTime to now
                val durationMinutes = ((now - arrivalTime.time) / 60_000).toInt().coerceAtLeast(0)
                val currentDuration = doc.getLong("durationMinutes")?.toInt() ?: 0
                
                // Only update if there's a significant difference (> 5 minutes)
                if (kotlin.math.abs(durationMinutes - currentDuration) > 5) {
                    doc.reference.update("durationMinutes", durationMinutes).await()
                    android.util.Log.d("LocationRepository", 
                        "★ Synced active entry duration: ${doc.getString("locationName")} - ${currentDuration}min → ${durationMinutes}min")
                }
                
                // Only one active entry should exist, so we can break after finding it
                break
            }
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error syncing active entry duration", e)
            // Don't throw - this is a best-effort sync
        }
    }
    
    /**
     * Force refresh partner location from SERVER (bypass cache).
     * Call this when app resumes or when user suspects stale data.
     * 
     * This is needed because Firestore's real-time listener may return cached data
     * if the WebSocket connection is stale but SDK hasn't detected it yet.
     */
    suspend fun forceRefreshPartnerLocation(partnerId: String, coupleId: String): UserLocation? {
        if (partnerId.isEmpty() || coupleId.isEmpty()) return null
        
        return try {
            val documentId = "${coupleId}_${partnerId}"
            android.util.Log.d("LocationRepository", "Force refreshing partner location from SERVER: $documentId")
            
            // Use Source.SERVER to bypass cache
            val snapshot = db.collection(LOCATIONS_COLLECTION)
                .document(documentId)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            
            if (snapshot.exists()) {
                val firebaseLocation = snapshot.toObject(FirebaseLocationData::class.java)
                val userLocation = firebaseLocation?.toUserLocation()
                if (userLocation != null) {
                    _partnerCurrentLocation.value = userLocation
                    android.util.Log.d("LocationRepository", "✅ Force refresh SUCCESS: ${userLocation.coordinate}")
                }
                userLocation
            } else {
                android.util.Log.d("LocationRepository", "Partner location document doesn't exist on server")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error force refreshing partner location", e)
            null
        }
    }
    
    /**
     * Force refresh MY location history from SERVER (bypass cache).
     * Call this when user manually refreshes or when data seems stale.
     */
    suspend fun forceRefreshMyHistory(userId: String, coupleId: String): List<LocationHistory> {
        if (userId.isEmpty() || coupleId.isEmpty()) return emptyList()
        
        return try {
            android.util.Log.d("LocationRepository", "Force refreshing MY history from SERVER")
            
            val threeDaysAgo = java.time.LocalDate.now().minusDays(3)
            
            // Use Source.SERVER to bypass cache
            val snapshot = db.collection(LOCATION_HISTORY_COLLECTION)
                .whereEqualTo("userId", userId)
                .whereEqualTo("coupleId", coupleId)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            
            android.util.Log.d("LocationRepository", "Force refresh got ${snapshot.documents.size} documents from SERVER for MY history")
            
            val rawHistory = snapshot.documents.mapNotNull { doc ->
                try {
                    val history = doc.toObject(FirebaseLocationHistory::class.java)?.toLocationHistory()
                    if (history != null && history.arrivalTime.toLocalDate() >= threeDaysAgo) {
                        history
                    } else null
                } catch (e: Exception) { null }
            }.sortedByDescending { it.arrivalTime }
            
            val cleanedHistory = processLocationHistory(rawHistory)
            _myLocationHistory.value = cleanedHistory
            
            android.util.Log.d("LocationRepository", "✅ Force refresh MY history SUCCESS: ${cleanedHistory.size} entries")
            cleanedHistory
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error force refreshing MY history", e)
            emptyList()
        }
    }
    
    /**
     * Force refresh partner location history from SERVER (bypass cache).
     */
    suspend fun forceRefreshPartnerHistory(partnerId: String, coupleId: String): List<LocationHistory> {
        if (partnerId.isEmpty() || coupleId.isEmpty()) return emptyList()
        
        return try {
            android.util.Log.d("LocationRepository", "Force refreshing partner history from SERVER")
            
            val threeDaysAgo = java.time.LocalDate.now().minusDays(3)
            
            // Use Source.SERVER to bypass cache
            val snapshot = db.collection(LOCATION_HISTORY_COLLECTION)
                .whereEqualTo("userId", partnerId)
                .whereEqualTo("coupleId", coupleId)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            
            android.util.Log.d("LocationRepository", "Force refresh got ${snapshot.documents.size} documents from SERVER")
            
            val rawHistory = snapshot.documents.mapNotNull { doc ->
                try {
                    val history = doc.toObject(FirebaseLocationHistory::class.java)?.toLocationHistory()
                    if (history != null && history.arrivalTime.toLocalDate() >= threeDaysAgo) {
                        history
                    } else null
                } catch (e: Exception) { null }
            }.sortedByDescending { it.arrivalTime }
            
            val cleanedHistory = processLocationHistory(rawHistory)
            _partnerLocationHistory.value = cleanedHistory
            
            android.util.Log.d("LocationRepository", "✅ Force refresh partner history SUCCESS: ${cleanedHistory.size} entries")
            cleanedHistory
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error force refreshing partner history", e)
            emptyList()
        }
    }
    
    /**
     * Load location history for a user - only last 3 days
     * Includes logic to:
     * 1. Filter to only last 3 days (older entries are cleaned up automatically)
     * 2. Merge consecutive entries at the same location
     * 3. Fix "departureTime = null" for entries from past days
     * 4. Ensure only the most recent entry can show "currently here"
     * 
     * @param userId The user ID to load history for
     * @param coupleId The couple ID
     * @param isCurrentUser TRUE if loading for current user (updates myLocationHistory), FALSE for partner (updates partnerLocationHistory)
     */
    suspend fun loadLocationHistory(userId: String, coupleId: String, isCurrentUser: Boolean = true): Result<List<LocationHistory>> {
        return try {
            android.util.Log.d("LocationRepository", "Loading location history for userId=$userId, coupleId=$coupleId, isCurrentUser=$isCurrentUser")
            
            // Clean up old entries (older than 3 days) in background - only for current user to avoid duplicate cleanup
            if (isCurrentUser) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        deleteOldLocationHistory(userId, coupleId, daysToKeep = 3)
                    } catch (e: Exception) {
                        android.util.Log.e("LocationRepository", "Error cleaning up old history", e)
                    }
                }
            }
            
            // Calculate cutoff date for filtering (3 days ago)
            val threeDaysAgo = java.time.LocalDate.now().minusDays(3)
            
            // Query with orderBy to ensure consistent ordering (index already exists in firestore.indexes.json)
            val snapshot = db.collection(LOCATION_HISTORY_COLLECTION)
                .whereEqualTo("userId", userId)
                .whereEqualTo("coupleId", coupleId)
                .orderBy("arrivalTime", Query.Direction.DESCENDING)
                .limit(100)
                .get()
                .await()
            
            android.util.Log.d("LocationRepository", "Found ${snapshot.documents.size} history documents for ${if (isCurrentUser) "current user" else "partner"}")
            
            // ★ SYNC: Update active entry's duration to current time in Firebase
            // This ensures when user opens app, the "currently here" entry has correct duration
            if (isCurrentUser) {
                syncActiveEntryDuration(snapshot.documents)
            }
            
            val rawHistory = snapshot.documents.mapNotNull { doc ->
                try {
                    val history = doc.toObject(FirebaseLocationHistory::class.java)?.toLocationHistory()
                    // Filter to only last 3 days
                    if (history != null && history.arrivalTime.toLocalDate() >= threeDaysAgo) {
                        android.util.Log.d("LocationRepository", "Parsed history: ${history.locationName} at ${history.arrivalTime}")
                        history
                    } else {
                        null // Skip entries older than 3 days
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LocationRepository", "Error parsing history doc: ${doc.id}", e)
                    null
                }
            }.sortedByDescending { it.arrivalTime }
            
            // Process and clean the history data
            val cleanedHistory = processLocationHistory(rawHistory)
            
            android.util.Log.d("LocationRepository", "Final history list size after processing: ${cleanedHistory.size}")
            
            // Use explicit parameter instead of comparing with potentially unset _myCurrentLocation
            if (isCurrentUser) {
                android.util.Log.d("LocationRepository", "Updating _myLocationHistory with ${cleanedHistory.size} entries")
                _myLocationHistory.value = cleanedHistory
            } else {
                android.util.Log.d("LocationRepository", "Updating _partnerLocationHistory with ${cleanedHistory.size} entries")
                _partnerLocationHistory.value = cleanedHistory
            }
            
            Result.success(cleanedHistory)
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error loading location history", e)
            Result.failure(e)
        }
    }
    
    /**
     * Listen to location history changes in real-time for a user.
     * This allows both users in a couple to see each other's history updates immediately.
     * 
     * @param userId The user ID to listen history for
     * @param coupleId The couple ID
     * @param isCurrentUser TRUE if listening for current user (updates myLocationHistory), FALSE for partner
     */
    fun listenToLocationHistory(userId: String, coupleId: String, isCurrentUser: Boolean = true): Flow<List<LocationHistory>> = callbackFlow {
        // Validate inputs
        if (userId.isEmpty() || coupleId.isEmpty()) {
            android.util.Log.w("LocationRepository", "Cannot listen to location history: userId or coupleId is empty")
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        
        android.util.Log.d("LocationRepository", "Starting real-time listener for location history - userId=$userId, coupleId=$coupleId, isCurrentUser=$isCurrentUser")
        
        // Calculate cutoff date for filtering (3 days ago)
        val threeDaysAgo = java.time.LocalDate.now().minusDays(3)
        
        val subscription = db.collection(LOCATION_HISTORY_COLLECTION)
            .whereEqualTo("userId", userId)
            .whereEqualTo("coupleId", coupleId)
            .orderBy("arrivalTime", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("LocationRepository", "Error listening to location history for ${if (isCurrentUser) "current user" else "partner"}", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null) {
                    // ★ IMPORTANT: Check if data is from cache or server
                    val source = if (snapshot.metadata.isFromCache) "LOCAL_CACHE" else "SERVER"
                    val hasPending = snapshot.metadata.hasPendingWrites()
                    android.util.Log.d("LocationRepository", "Location history snapshot received: ${snapshot.documents.size} documents for ${if (isCurrentUser) "current user" else "partner"} [source=$source, pendingWrites=$hasPending]")
                    
                    val rawHistory = snapshot.documents.mapNotNull { doc ->
                        try {
                            val history = doc.toObject(FirebaseLocationHistory::class.java)?.toLocationHistory()
                            // Filter to only last 3 days
                            if (history != null && history.arrivalTime.toLocalDate() >= threeDaysAgo) {
                                history
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LocationRepository", "Error parsing history doc: ${doc.id}", e)
                            null
                        }
                    }.sortedByDescending { it.arrivalTime }
                    
                    // Process and clean the history data
                    val cleanedHistory = processLocationHistory(rawHistory)
                    
                    android.util.Log.d("LocationRepository", "Processed ${cleanedHistory.size} history entries for ${if (isCurrentUser) "current user" else "partner"}")
                    
                    // Update the appropriate StateFlow
                    if (isCurrentUser) {
                        _myLocationHistory.value = cleanedHistory
                    } else {
                        _partnerLocationHistory.value = cleanedHistory
                    }
                    
                    trySend(cleanedHistory)
                }
            }
        
        awaitClose {
            android.util.Log.d("LocationRepository", "Stopped listening to location history for ${if (isCurrentUser) "current user" else "partner"}")
            subscription.remove()
        }
    }
    
    /**
     * Process location history for display:
     * 1. Sort by arrival time (most recent first)
     * 2. Fix swapped times (departureTime < arrivalTime)
     * 3. For current location (today, no departure): show real-time duration
     * 4. For past entries without departure: infer from next entry's arrival
     * 
     * SIMPLE: No merging - each entry is displayed separately
     */
    private fun processLocationHistory(rawHistory: List<LocationHistory>): List<LocationHistory> {
        if (rawHistory.isEmpty()) return emptyList()
        
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        
        // Sort by arrival time descending (most recent first)
        val sorted = rawHistory.sortedByDescending { it.arrivalTime }
        
        // Step 1: Basic fixes (swap invalid times)
        val basicFixed = sorted.map { entry ->
            if (entry.departureTime != null && entry.departureTime.isBefore(entry.arrivalTime)) {
                android.util.Log.w("LocationRepository", "Swapping invalid times for ${entry.locationName}")
                entry.copy(
                    arrivalTime = entry.departureTime,
                    departureTime = entry.arrivalTime,
                    durationMinutes = java.time.Duration.between(entry.departureTime, entry.arrivalTime).toMinutes().toInt().coerceAtLeast(0)
                )
            } else {
                entry
            }
        }
        
        // Step 2: Fix time issues for each entry
        var foundCurrentLocation = false
        val result = basicFixed.mapIndexed { index, entry ->
            val entryDate = entry.arrivalTime.toLocalDate()
            
            when {
                // Case 1: Entry has departureTime - cap at now if in the future
                entry.departureTime != null -> {
                    if (entryDate == today && entry.departureTime.isAfter(now)) {
                        entry.copy(
                            departureTime = now,
                            durationMinutes = java.time.Duration.between(entry.arrivalTime, now).toMinutes().toInt().coerceAtLeast(0)
                        )
                    } else {
                        entry
                    }
                }
                
                // Case 2: Today's entry without departure - "currently here"
                !foundCurrentLocation && entryDate == today -> {
                    foundCurrentLocation = true
                    val realTimeDuration = java.time.Duration.between(entry.arrivalTime, now).toMinutes().toInt().coerceAtLeast(0)
                    entry.copy(durationMinutes = realTimeDuration)
                }
                
                // Case 3: Old entry without departure - infer from next entry or use end of day
                else -> {
                    val prevEntry = basicFixed.getOrNull(index + 1)
                    val inferredDeparture = when {
                        prevEntry != null && prevEntry.arrivalTime.toLocalDate() == entryDate -> prevEntry.arrivalTime
                        entry.durationMinutes > 0 -> entry.arrivalTime.plusMinutes(entry.durationMinutes.toLong())
                        else -> entryDate.atTime(23, 59)
                    }
                    val duration = java.time.Duration.between(entry.arrivalTime, inferredDeparture).toMinutes().toInt().coerceAtLeast(0)
                    entry.copy(departureTime = inferredDeparture, durationMinutes = duration)
                }
            }
        }
        
        android.util.Log.d("LocationRepository", "Processed ${rawHistory.size} entries")
        return result.take(20)
    }
    
    /**
     * Add a location to history (when user stays at a place for minimum duration)
     */
    suspend fun addLocationToHistory(
        userId: String,
        coupleId: String,
        location: LocationHistory
    ): Result<String> {
        return try {
            val firebaseHistory = FirebaseLocationHistory(
                id = "",
                userId = userId,
                coupleId = coupleId,
                locationName = location.locationName,
                address = location.address,
                latitude = location.coordinate.latitude,
                longitude = location.coordinate.longitude,
                arrivalTime = Date.from(location.arrivalTime.atZone(ZoneId.systemDefault()).toInstant()),
                departureTime = location.departureTime?.let { 
                    Date.from(it.atZone(ZoneId.systemDefault()).toInstant()) 
                },
                durationMinutes = location.durationMinutes,
                locationType = location.locationType.name
            )
            
            val docRef = db.collection(LOCATION_HISTORY_COLLECTION).add(firebaseHistory).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Check if both users are at the same location and manage colocation session
     */
    private fun checkAndUpdateColocation(coupleId: String, myLocation: UserLocation) {
        val partnerLocation = _partnerCurrentLocation.value ?: return
        
        val distance = calculateDistance(
            myLocation.coordinate,
            partnerLocation.coordinate
        )
        
        if (distance <= COLOCATION_RADIUS_METERS) {
            // Users are together
            val currentSession = _activeColocationSession.value
            
            if (currentSession == null) {
                // Start new colocation session
                val centerCoordinate = LocationCoordinate(
                    latitude = (myLocation.coordinate.latitude + partnerLocation.coordinate.latitude) / 2,
                    longitude = (myLocation.coordinate.longitude + partnerLocation.coordinate.longitude) / 2
                )
                
                val newSession = ColocationSession(
                    id = UUID.randomUUID().toString(),
                    coupleId = coupleId,
                    coordinate = centerCoordinate,
                    address = myLocation.address,
                    startTime = LocalDateTime.now(),
                    photosCollected = emptyList()
                )
                
                _activeColocationSession.value = newSession
                saveColocationSession(newSession)
            } else {
                // Update existing session duration
                val duration = java.time.Duration.between(
                    currentSession.startTime, 
                    LocalDateTime.now()
                ).toMinutes()
                
                // If together for 5+ minutes and not yet converted to shared place
                if (duration >= COLOCATION_TIME_MINUTES && !currentSession.convertedToSharedPlace) {
                    createSharedPlaceFromColocation(currentSession)
                }
            }
        } else {
            // Users are apart - end colocation session if exists
            _activeColocationSession.value?.let { session ->
                endColocationSession(session)
            }
            _activeColocationSession.value = null
        }
    }
    
    /**
     * Create a shared place from a colocation session
     */
    private fun createSharedPlaceFromColocation(session: ColocationSession) {
        val sharedPlace = FirebaseSharedPlaceData(
            id = "",
            coupleId = session.coupleId,
            placeName = detectPlaceName(session.address),
            address = session.address,
            latitude = session.coordinate.latitude,
            longitude = session.coordinate.longitude,
            representativePhotoUrl = session.photosCollected.firstOrNull() ?: "",
            visitDate = Date(),
            durationMinutes = java.time.Duration.between(session.startTime, LocalDateTime.now()).toMinutes().toInt(),
            photosCount = session.photosCollected.size,
            locationType = detectLocationType(session.address).name,
            photoUrls = session.photosCollected
        )
        
        db.collection(SHARED_PLACES_COLLECTION)
            .add(sharedPlace)
            .addOnSuccessListener { docRef ->
                // Update session as converted
                _activeColocationSession.value = session.copy(
                    convertedToSharedPlace = true,
                    sharedPlaceId = docRef.id
                )
                
                // Reload shared places
                loadSharedPlaces(session.coupleId)
            }
    }
    
    /**
     * Load all shared places for a couple
     * Uses real-time listener to sync across all devices
     */
    fun loadSharedPlaces(coupleId: String): Flow<List<SharedPlace>> = callbackFlow {
        android.util.Log.d("LocationRepository", "Starting shared places listener for coupleId: $coupleId")
        
        // First try with orderBy (requires compound index)
        var subscription = db.collection(SHARED_PLACES_COLLECTION)
            .whereEqualTo("coupleId", coupleId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Log the error but continue - could be permission or network issue
                    android.util.Log.e("LocationRepository", "Error listening to shared places: ${error.message}")
                    android.util.Log.e("LocationRepository", "Full error: ", error)
                    
                    // If it's an index error, the message will contain info about creating the index
                    if (error.message?.contains("index") == true) {
                        android.util.Log.w("LocationRepository", ">>> Firestore requires a compound index! Check the error message for the link to create it.")
                    }
                    return@addSnapshotListener
                }
                
                android.util.Log.d("LocationRepository", 
                    "Shared places snapshot received: ${snapshot?.documents?.size ?: 0} documents")
                
                val places = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        android.util.Log.d("LocationRepository", "Parsing place doc: ${doc.id}, data: ${doc.data}")
                        doc.toObject(FirebaseSharedPlaceData::class.java)?.copy(id = doc.id)?.toSharedPlace()
                    } catch (e: Exception) {
                        android.util.Log.e("LocationRepository", "Error parsing place ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()
                
                // Sort locally by visitDate descending
                val sortedPlaces = places.sortedByDescending { it.visitDate }
                
                android.util.Log.d("LocationRepository", "Parsed and sorted ${sortedPlaces.size} shared places")
                _sharedPlaces.value = sortedPlaces
                trySend(sortedPlaces)
            }
        
        awaitClose { 
            android.util.Log.d("LocationRepository", "Closing shared places listener")
            subscription.remove() 
        }
    }
    
    /**
     * Get photos for a shared place
     */
    suspend fun getSharedPlacePhotos(placeId: String): Result<List<SharedPlacePhoto>> {
        return try {
            android.util.Log.d("LocationRepository", "=== getSharedPlacePhotos for placeId: $placeId ===")
            
            // First, check without orderBy to avoid index issues
            val snapshot = db.collection(SHARED_PLACE_PHOTOS_COLLECTION)
                .whereEqualTo("placeId", placeId)
                .get()
                .await()
            
            android.util.Log.d("LocationRepository", "Found ${snapshot.documents.size} photo documents")
            
            val photos = snapshot.documents.mapNotNull { doc ->
                android.util.Log.d("LocationRepository", "Document ID: ${doc.id}, data: ${doc.data}")
                try {
                    // Parse manually to handle document ID
                    val data = doc.data ?: return@mapNotNull null
                    
                    // Handle Firestore Timestamp conversion to Date
                    val takenAtTimestamp = data["takenAt"]
                    val takenAtDate: Date? = when (takenAtTimestamp) {
                        is com.google.firebase.Timestamp -> takenAtTimestamp.toDate()
                        is Date -> takenAtTimestamp
                        else -> null
                    }
                    
                    android.util.Log.d("LocationRepository", "Photo ${doc.id}: takenAt raw=$takenAtTimestamp, converted=$takenAtDate")
                    
                    val firebasePhoto = FirebaseSharedPlacePhoto(
                        id = doc.id,
                        placeId = data["placeId"] as? String ?: "",
                        photoUrl = data["photoUrl"] as? String ?: "",
                        takenAt = takenAtDate,
                        takenByUserId = data["takenByUserId"] as? String ?: "",
                        caption = data["caption"] as? String
                    )
                    firebasePhoto.toSharedPlacePhoto().also {
                        android.util.Log.d("LocationRepository", "Parsed photo: id=${it.id}, url=${it.photoUrl}, takenAt=${it.takenAt}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LocationRepository", "Error parsing photo doc: ${doc.id}", e)
                    null
                }
            }.sortedByDescending { it.takenAt }
            
            android.util.Log.d("LocationRepository", "Returning ${photos.size} parsed photos")
            Result.success(photos)
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error getting shared place photos", e)
            Result.failure(e)
        }
    }
    
    /**
     * Add a photo to a shared place
     */
    suspend fun addPhotoToSharedPlace(
        placeId: String,
        photoUrl: String,
        userId: String,
        caption: String? = null
    ): Result<String> {
        return try {
            // Use a Map instead of data class to avoid Firestore serialization issues
            val photoData = hashMapOf<String, Any?>(
                "placeId" to placeId,
                "photoUrl" to photoUrl,
                "takenAt" to Date(),
                "takenByUserId" to userId,
                "caption" to caption
            )
            
            val docRef = db.collection(SHARED_PLACE_PHOTOS_COLLECTION).add(photoData).await()
            
            // Update photo count in shared place
            val placeRef = db.collection(SHARED_PLACES_COLLECTION).document(placeId)
            db.runTransaction { transaction ->
                val placeSnapshot = transaction.get(placeRef)
                val currentCount = placeSnapshot.getLong("photosCount") ?: 0
                transaction.update(placeRef, "photosCount", currentCount + 1)
            }.await()
            
            Result.success(docRef.id)
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error adding photo to place: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Delete a photo from a shared place
     */
    suspend fun deletePhotoFromSharedPlace(
        photoId: String,
        placeId: String
    ): Result<Unit> {
        return try {
            android.util.Log.d("LocationRepository", "Deleting photo: $photoId from place: $placeId")
            
            // Delete the photo document
            db.collection(SHARED_PLACE_PHOTOS_COLLECTION).document(photoId).delete().await()
            
            // Update photo count in shared place
            val placeRef = db.collection(SHARED_PLACES_COLLECTION).document(placeId)
            db.runTransaction { transaction ->
                val placeSnapshot = transaction.get(placeRef)
                val currentCount = placeSnapshot.getLong("photosCount") ?: 0
                if (currentCount > 0) {
                    transaction.update(placeRef, "photosCount", currentCount - 1)
                }
            }.await()
            
            android.util.Log.d("LocationRepository", "Photo deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("LocationRepository", "Error deleting photo: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Add photos taken during a colocation session
     */
    fun addPhotoToColocationSession(photoUrl: String) {
        _activeColocationSession.value?.let { session ->
            _activeColocationSession.value = session.copy(
                photosCollected = session.photosCollected + photoUrl
            )
            
            // If session already converted to shared place, add photo directly
            session.sharedPlaceId?.let { placeId ->
                // Use Map instead of data class to avoid serialization issues
                val photoData = hashMapOf<String, Any?>(
                    "placeId" to placeId,
                    "photoUrl" to photoUrl,
                    "takenAt" to Date(),
                    "takenByUserId" to (_myCurrentLocation.value?.userId ?: ""),
                    "caption" to null
                )
                db.collection(SHARED_PLACE_PHOTOS_COLLECTION).add(photoData)
            }
        }
    }
    
    /**
     * Save colocation session to Firebase
     */
    private fun saveColocationSession(session: ColocationSession) {
        val firebaseSession = mapOf(
            "coupleId" to session.coupleId,
            "latitude" to session.coordinate.latitude,
            "longitude" to session.coordinate.longitude,
            "address" to session.address,
            "startTime" to Date.from(session.startTime.atZone(ZoneId.systemDefault()).toInstant()),
            "isActive" to true
        )
        
        db.collection(COLOCATION_SESSIONS_COLLECTION)
            .document(session.id)
            .set(firebaseSession)
    }
    
    /**
     * End a colocation session
     */
    private fun endColocationSession(session: ColocationSession) {
        db.collection(COLOCATION_SESSIONS_COLLECTION)
            .document(session.id)
            .update(
                mapOf(
                    "isActive" to false,
                    "endTime" to Date()
                )
            )
    }
    
    // Helper functions
    
    private fun getAddressFromLocation(latitude: Double, longitude: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { address ->
                buildString {
                    address.thoroughfare?.let { append(it) }
                    address.subLocality?.let { 
                        if (isNotEmpty()) append(", ")
                        append(it) 
                    }
                    address.locality?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                }
            } ?: "Unknown location"
        } catch (e: Exception) {
            "Unknown location"
        }
    }
    
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            100
        }
    }
    
    private fun calculateDistance(coord1: LocationCoordinate, coord2: LocationCoordinate): Double {
        val earthRadius = 6371000.0 // meters
        
        val lat1Rad = Math.toRadians(coord1.latitude)
        val lat2Rad = Math.toRadians(coord2.latitude)
        val deltaLat = Math.toRadians(coord2.latitude - coord1.latitude)
        val deltaLon = Math.toRadians(coord2.longitude - coord1.longitude)
        
        val a = kotlin.math.sin(deltaLat / 2).let { it * it } +
                kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
                kotlin.math.sin(deltaLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        
        return earthRadius * c
    }
    
    private fun detectPlaceName(address: String): String {
        // Try to extract a meaningful place name from address
        return address.split(",").firstOrNull()?.trim() ?: address
    }
    
    private fun detectLocationType(address: String): LocationType {
        val lowerAddress = address.lowercase()
        return when {
            lowerAddress.contains("cafe") || lowerAddress.contains("coffee") -> LocationType.CAFE
            lowerAddress.contains("restaurant") || lowerAddress.contains("nhà hàng") -> LocationType.RESTAURANT
            lowerAddress.contains("mall") || lowerAddress.contains("vincom") || 
                lowerAddress.contains("center") || lowerAddress.contains("trung tâm") -> LocationType.SHOPPING
            lowerAddress.contains("park") || lowerAddress.contains("công viên") || 
                lowerAddress.contains("hồ") -> LocationType.PARK
            lowerAddress.contains("cinema") || lowerAddress.contains("cgv") || 
                lowerAddress.contains("lotte") -> LocationType.ENTERTAINMENT
            lowerAddress.contains("hospital") || lowerAddress.contains("bệnh viện") -> LocationType.HOSPITAL
            lowerAddress.contains("gym") || lowerAddress.contains("fitness") -> LocationType.GYM
            lowerAddress.contains("school") || lowerAddress.contains("university") || 
                lowerAddress.contains("đại học") || lowerAddress.contains("trường") -> LocationType.SCHOOL
            else -> LocationType.OTHER
        }
    }
}

/**
 * Firebase data classes for location storage
 */
data class FirebaseLocationData(
    val id: String = "",
    val userId: String = "",
    val coupleId: String = "",
    val userName: String = "",
    val avatarUrl: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String = "",
    val batteryLevel: Int = 100,
    val isOnline: Boolean = true,
    val timestamp: Date? = null
) {
    /**
     * Convert Firebase data to UserLocation.
     * IMPORTANT: isOnline is calculated based on timestamp, not stored value.
     * User is considered OFFLINE if last update was more than 5 minutes ago.
     */
    fun toUserLocation(): UserLocation {
        val lastUpdatedTime = timestamp?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime() 
            ?: LocalDateTime.now()
        
        // Calculate if user is actually online based on timestamp
        // User is considered OFFLINE if last location update was more than 5 minutes ago
        val minutesSinceUpdate = java.time.Duration.between(lastUpdatedTime, LocalDateTime.now()).toMinutes()
        val isActuallyOnline = minutesSinceUpdate <= LocationRepository.LOCATION_STALE_THRESHOLD_MINUTES
        
        return UserLocation(
            userId = userId,
            userName = userName,
            avatarUrl = avatarUrl,
            coordinate = LocationCoordinate(latitude, longitude),
            address = address,
            lastUpdated = lastUpdatedTime,
            batteryLevel = batteryLevel,
            isOnline = isActuallyOnline // Use calculated value instead of stored value
        )
    }
}

data class FirebaseLocationHistory(
    val id: String = "",
    val userId: String = "",
    val coupleId: String = "",
    val locationName: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val arrivalTime: Date? = null,
    val departureTime: Date? = null,
    val durationMinutes: Int = 0,
    val locationType: String = "OTHER"
) {
    fun toLocationHistory(): LocationHistory {
        return LocationHistory(
            id = id,
            locationName = locationName,
            address = address,
            coordinate = LocationCoordinate(latitude, longitude),
            arrivalTime = arrivalTime?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime()
                ?: LocalDateTime.now(),
            departureTime = departureTime?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime(),
            durationMinutes = durationMinutes,
            locationType = try { LocationType.valueOf(locationType) } catch (e: Exception) { LocationType.OTHER }
        )
    }
}

data class FirebaseSharedPlaceData(
    val id: String = "",
    val coupleId: String = "",
    val placeName: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val representativePhotoUrl: String = "",
    val visitDate: Date? = null,
    val durationMinutes: Int = 0,
    val photosCount: Int = 0,
    val locationType: String = "OTHER",
    val photoUrls: List<String> = emptyList()
) {
    fun toSharedPlace(): SharedPlace {
        return SharedPlace(
            id = id,
            placeName = placeName,
            address = address,
            coordinate = LocationCoordinate(latitude, longitude),
            representativePhotoUrl = representativePhotoUrl,
            visitDate = visitDate?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime()
                ?: LocalDateTime.now(),
            durationMinutes = durationMinutes,
            photosCount = photosCount,
            locationType = try { LocationType.valueOf(locationType) } catch (e: Exception) { LocationType.OTHER }
        )
    }
}

data class FirebaseSharedPlacePhoto(
    val id: String = "",
    val placeId: String = "",
    val photoUrl: String = "",
    val takenAt: Date? = null,
    val takenByUserId: String = "",
    val caption: String? = null
) {
    fun toSharedPlacePhoto(): SharedPlacePhoto {
        return SharedPlacePhoto(
            id = id,
            photoUrl = photoUrl,
            takenAt = takenAt?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime()
                ?: LocalDateTime.now(),
            takenByUserId = takenByUserId,
            caption = caption
        )
    }
}

/**
 * Represents an active colocation session when both users are together
 */
data class ColocationSession(
    val id: String,
    val coupleId: String,
    val coordinate: LocationCoordinate,
    val address: String,
    val startTime: LocalDateTime,
    val photosCollected: List<String> = emptyList(),
    val convertedToSharedPlace: Boolean = false,
    val sharedPlaceId: String? = null
)
