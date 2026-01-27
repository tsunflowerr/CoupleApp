package com.example.coupleapp.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.data.model.*
import com.example.coupleapp.R
import com.example.coupleapp.data.repository.FirebaseStorageRepository
import com.example.coupleapp.data.repository.LocationRepository
import com.example.coupleapp.service.LocationTrackingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * ViewModel for the Distance/Location feature
 * Handles real-time location tracking, location history, and shared places
 */
class DistanceViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context: Context = application.applicationContext
    private val locationRepository = LocationRepository(context)
    private val storageRepository = FirebaseStorageRepository()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private val _uiState = MutableStateFlow(DistanceUiState())
    val uiState: StateFlow<DistanceUiState> = _uiState.asStateFlow()
    
    private val _photosState = MutableStateFlow(SharedPlacePhotosState())
    val photosState: StateFlow<SharedPlacePhotosState> = _photosState.asStateFlow()
    
    // User info
    private var userId: String = ""
    private var userName: String = ""
    private var avatarUrl: String = ""
    private var coupleId: String = ""
    private var partnerId: String = ""
    
    init {
        loadUserInfo()
        // Fix any photosCount inconsistencies in background
        viewModelScope.launch {
            delay(2000) // Wait 2 seconds to avoid blocking initial load
            fixPhotosCountForAllPlaces()
        }
        
        // ★ PERIODIC REFRESH: Force refresh partner data every 20 minutes
        // This is a safety net for when real-time listener WebSocket gets stuck
        // (half-open connection that doesn't receive updates)
        // 20 minutes = matches BackgroundLocationWorker interval, minimal battery impact
        startPeriodicPartnerRefresh()
    }
    
    /**
     * Periodic refresh of partner data from SERVER every 20 minutes.
     * This ensures partner location/history stays fresh even if WebSocket listener is stuck.
     * 
     * Battery impact: ~0.1% per day (very minimal)
     * - Only 72 requests/day vs 288 with 5-minute interval
     * - Each request is ~2KB
     * 
     * Worst case delay: 20 min (partner update) + 20 min (your refresh) = 40 min
     * But real-time listener usually works, so delay is typically near-zero.
     */
    private fun startPeriodicPartnerRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(20 * 60 * 1000L) // 20 minutes - matches BackgroundLocationWorker interval
                
                // Only refresh if we have partner info
                if (partnerId.isNotEmpty() && coupleId.isNotEmpty()) {
                    try {
                        android.util.Log.d("DistanceViewModel", "⏰ Periodic force refresh of partner data from SERVER (every 20 min)")
                        
                        // Force refresh partner location from server
                        locationRepository.forceRefreshPartnerLocation(partnerId, coupleId)?.let { partnerLoc ->
                            updatePartnerLocation(partnerLoc)
                        }
                        
                        // Force refresh partner history from server
                        val partnerHistory = locationRepository.forceRefreshPartnerHistory(partnerId, coupleId)
                        if (partnerHistory.isNotEmpty()) {
                            _uiState.update { it.copy(partnerLocationHistory = partnerHistory) }
                            android.util.Log.d("DistanceViewModel", "⏰ Periodic refresh got ${partnerHistory.size} partner history entries")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DistanceViewModel", "Error in periodic partner refresh", e)
                    }
                }
            }
        }
        
        // ★ NETWORK CHANGE DETECTION: Force refresh when network changes
        // This fixes the "stuck WebSocket" issue - when WiFi changes, we force refresh
        registerNetworkChangeListener()
    }
    
    // Network callback for detecting network changes
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkId: String? = null
    
    /**
     * Register network change listener to detect WiFi switches.
     * When network changes, force refresh partner data from server.
     */
    private fun registerNetworkChangeListener() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkId = network.toString()
                    
                    // Only trigger refresh if network actually changed (not just reconnected)
                    if (lastNetworkId != null && lastNetworkId != networkId) {
                        android.util.Log.d("DistanceViewModel", "🌐 Network CHANGED: $lastNetworkId → $networkId - Force refreshing partner data")
                        
                        // Force refresh partner data on network change
                        viewModelScope.launch {
                            delay(2000) // Wait for network to stabilize
                            forceRefreshPartnerData()
                        }
                    }
                    lastNetworkId = networkId
                }
                
                override fun onLost(network: Network) {
                    android.util.Log.d("DistanceViewModel", "🌐 Network LOST: ${network}")
                }
            }
            
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            android.util.Log.d("DistanceViewModel", "✅ Network change listener registered")
            
        } catch (e: Exception) {
            android.util.Log.e("DistanceViewModel", "Error registering network callback", e)
        }
    }
    
    /**
     * Force refresh partner data from SERVER.
     * Called when network changes or manual refresh.
     */
    private fun forceRefreshPartnerData() {
        if (partnerId.isEmpty() || coupleId.isEmpty()) return
        
        viewModelScope.launch {
            try {
                android.util.Log.d("DistanceViewModel", "🔄 Force refreshing ALL partner data from SERVER")
                
                // Force refresh partner location
                locationRepository.forceRefreshPartnerLocation(partnerId, coupleId)?.let { partnerLoc ->
                    updatePartnerLocation(partnerLoc)
                }
                
                // Force refresh partner history
                val partnerHistory = locationRepository.forceRefreshPartnerHistory(partnerId, coupleId)
                if (partnerHistory.isNotEmpty()) {
                    _uiState.update { it.copy(partnerLocationHistory = partnerHistory) }
                }
                
                android.util.Log.d("DistanceViewModel", "✅ Force refresh completed")
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error force refreshing partner data", e)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Unregister network callback to prevent leaks
        try {
            networkCallback?.let {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
                android.util.Log.d("DistanceViewModel", "Network callback unregistered")
            }
        } catch (e: Exception) {
            android.util.Log.e("DistanceViewModel", "Error unregistering network callback", e)
        }
    }
    
    /**
     * Load current user info and couple info from Firebase
     */
    private fun loadUserInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val currentUser = auth.currentUser
                if (currentUser != null) {
                    userId = currentUser.uid
                    
                    // Get user data from Firestore
                    val userDoc = db.collection("users").document(userId).get().await()
                    userName = userDoc.getString("displayName") ?: "Me"
                    avatarUrl = userDoc.getString("profileImageUrl") ?: ""
                    coupleId = userDoc.getString("coupleId") ?: ""
                    partnerId = userDoc.getString("partnerId") ?: ""
                    
                    // If partnerId exists but coupleId is empty, generate coupleId
                    // This handles cases where users paired before coupleId was being set
                    if (partnerId.isNotEmpty() && coupleId.isEmpty()) {
                        val sortedIds = listOf(userId, partnerId).sorted()
                        coupleId = "${sortedIds[0]}_${sortedIds[1]}"
                        android.util.Log.d("DistanceViewModel", "Generated coupleId from partnerId: $coupleId")
                        
                        // Update Firestore with the generated coupleId for BOTH users
                        try {
                            // Update current user
                            db.collection("users").document(userId)
                                .update("coupleId", coupleId)
                                .await()
                            android.util.Log.d("DistanceViewModel", "Updated user's coupleId in Firestore")
                            
                            // Also update partner's coupleId so they use the same ID
                            db.collection("users").document(partnerId)
                                .update("coupleId", coupleId)
                                .await()
                            android.util.Log.d("DistanceViewModel", "Updated partner's coupleId in Firestore")
                        } catch (e: Exception) {
                            android.util.Log.e("DistanceViewModel", "Failed to update coupleId in Firestore", e)
                        }
                    }
                    
                    android.util.Log.d("DistanceViewModel", "User loaded - userId: $userId, partnerId: $partnerId, coupleId: $coupleId")
                    
                    // Always load real data - no more mock data fallback
                    loadRealData()
                    startLocationTracking()
                } else {
                    // Not logged in - show empty state
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            error = "Please log in to use location features"
                        ) 
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = "Failed to load user info: ${e.message}"
                    ) 
                }
            }
        }
    }
    
    /**
     * Load real location data from Firebase with Pub/Sub pattern
     * Both users can see each other's location in real-time
     */
    private fun loadRealData() {
        android.util.Log.d("DistanceViewModel", ">>> loadRealData() called - coupleId: $coupleId, partnerId: $partnerId")
        
        // Also load shared places directly as a backup
        if (coupleId.isNotEmpty()) {
            loadSharedPlacesDirectly()
        }
        
        viewModelScope.launch {
            try {
                // Load my current location and upload to Firebase if paired
                val myLocationResult = locationRepository.getCurrentLocation(
                    userId = userId, 
                    userName = userName, 
                    avatarUrl = avatarUrl,
                    coupleId = coupleId.ifEmpty { null }
                )
                val myLocation = myLocationResult.getOrNull()
                
                // Check if user is paired with someone
                if (coupleId.isEmpty()) {
                    android.util.Log.d("DistanceViewModel", "User is not paired - showing only own location")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            myLocation = myLocation,
                            partnerLocation = null,
                            distanceInMeters = 0.0,
                            distanceText = context.getString(R.string.not_paired_yet),
                            lastSyncTime = formatLastSync(LocalDateTime.now()),
                            currentUserId = userId,
                            error = if (myLocation == null) "Could not get your location" else null
                        )
                    }
                    return@launch
                }
                
                // Set currentUserId in state for avatar comparison
                _uiState.update { it.copy(currentUserId = userId) }
                
                // ===== PUB/SUB PATTERN: Listen to both locations in real-time =====
                
                // Listen to MY location from Firebase (so I see my own synced location)
                launch {
                    try {
                        android.util.Log.d("DistanceViewModel", "Setting up MY location listener (Pub/Sub) - userId: $userId, coupleId: $coupleId")
                        locationRepository.listenToMyLocation(userId, coupleId).collect { myLocationUpdate ->
                            android.util.Log.d("DistanceViewModel", "My location update received from Firebase: $myLocationUpdate")
                            myLocationUpdate?.let { updateMyLocation(it) }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DistanceViewModel", "Error listening to my location", e)
                    }
                }
                
                // Listen to PARTNER's location from Firebase (so I see partner's synced location)
                if (partnerId.isNotEmpty()) {
                    android.util.Log.d("DistanceViewModel", "Setting up PARTNER location listener (Pub/Sub) - partnerId: $partnerId, coupleId: $coupleId")
                    android.util.Log.d("DistanceViewModel", "Expected document path: ${coupleId}_${partnerId}")
                    
                    launch {
                        try {
                            locationRepository.listenToPartnerLocation(partnerId, coupleId).collect { partnerLocation ->
                                android.util.Log.d("DistanceViewModel", "Partner location update received from Firebase: $partnerLocation")
                                updatePartnerLocation(partnerLocation)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DistanceViewModel", "Error listening to partner location", e)
                        }
                    }
                    
                    // Load location histories with REAL-TIME LISTENERS
                    // This ensures both users see each other's history updates immediately
                    launch {
                        try {
                            android.util.Log.d("DistanceViewModel", ">>> Starting real-time listener for MY location history")
                            locationRepository.listenToLocationHistory(userId, coupleId, isCurrentUser = true).collect { history ->
                                android.util.Log.d("DistanceViewModel", "My location history updated: ${history.size} entries")
                                _uiState.update { it.copy(myLocationHistory = history) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DistanceViewModel", "Error listening to my location history", e)
                        }
                    }
                    
                    launch {
                        try {
                            android.util.Log.d("DistanceViewModel", ">>> Starting real-time listener for PARTNER location history")
                            locationRepository.listenToLocationHistory(partnerId, coupleId, isCurrentUser = false).collect { history ->
                                android.util.Log.d("DistanceViewModel", "Partner location history updated: ${history.size} entries")
                                _uiState.update { it.copy(partnerLocationHistory = history) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DistanceViewModel", "Error listening to partner location history", e)
                        }
                    }
                    
                    // Load shared places (real-time listener)
                    launch {
                        try {
                            android.util.Log.d("DistanceViewModel", ">>> Starting shared places listener for coupleId: $coupleId")
                            locationRepository.loadSharedPlaces(coupleId).collect { places ->
                                android.util.Log.d("DistanceViewModel", ">>> Received ${places.size} shared places")
                                _uiState.update { it.copy(sharedPlaces = places) }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DistanceViewModel", "Error loading shared places", e)
                            // On error, try to load directly without listener
                            loadSharedPlacesDirectly()
                        }
                    }
                } else {
                    android.util.Log.w("DistanceViewModel", "Couple ID exists but partnerId is empty")
                }
                
                // Update UI with initial data
                val partnerLocation = locationRepository.partnerCurrentLocation.value
                
                if (myLocation != null) {
                    val distance = if (partnerLocation != null) {
                        calculateDistance(myLocation.coordinate, partnerLocation.coordinate)
                    } else {
                        0.0
                    }
                    
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            myLocation = myLocation,
                            partnerLocation = partnerLocation,
                            distanceInMeters = distance,
                            distanceText = formatDistance(distance),
                            lastSyncTime = formatLastSync(LocalDateTime.now())
                        )
                    }
                } else {
                    // Could not get location - show error
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Could not get your current location. Please enable location services."
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load location data: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Start the location tracking service
     */
    private fun startLocationTracking() {
        android.util.Log.d("DistanceViewModel", ">>> startLocationTracking called")
        android.util.Log.d("DistanceViewModel", "   userId: $userId")
        android.util.Log.d("DistanceViewModel", "   coupleId: $coupleId") 
        android.util.Log.d("DistanceViewModel", "   partnerId: $partnerId")
        android.util.Log.d("DistanceViewModel", "   hasLocationPermission: ${locationRepository.hasLocationPermission()}")
        
        // Validate that we have the necessary data before starting the service
        if (!locationRepository.hasLocationPermission()) {
            android.util.Log.w("DistanceViewModel", "Cannot start location tracking: no location permission")
            return
        }
        
        if (userId.isEmpty()) {
            android.util.Log.w("DistanceViewModel", "Cannot start location tracking: userId is empty")
            return
        }
        
        if (coupleId.isEmpty()) {
            android.util.Log.w("DistanceViewModel", "Cannot start location tracking: user is not paired yet")
            _uiState.update { 
                it.copy(
                    error = null // Don't show error, just don't start tracking
                )
            }
            return
        }
        
        android.util.Log.d("DistanceViewModel", ">>> Starting LocationTrackingService...")
        
        val serviceStarted = LocationTrackingService.startService(
            context = context,
            userId = userId,
            userName = userName,
            avatarUrl = avatarUrl,
            coupleId = coupleId,
            partnerId = partnerId
        )
        
        android.util.Log.d("DistanceViewModel", ">>> Service start result: $serviceStarted")
        
        if (!serviceStarted) {
            android.util.Log.w("DistanceViewModel", "Location tracking service failed to start")
            return
        }
        
        // Note: BackgroundLocationWorker is already scheduled in CoupleApplication.kt
        // We don't need to schedule it again here to avoid duplicate work
        // The worker uses ExistingPeriodicWorkPolicy.KEEP so duplicates are ignored anyway
        android.util.Log.d("DistanceViewModel", "LocationTrackingService started, background worker already scheduled from Application")
        
        // Listen to my location updates from the service (via StateFlow)
        // The service uploads to Firebase, we just observe the local state
        viewModelScope.launch {
            LocationTrackingService.lastKnownLocation.collect { coordinate ->
                coordinate?.let {
                    val location = UserLocation(
                        userId = userId,
                        userName = userName,
                        avatarUrl = avatarUrl,
                        coordinate = it,
                        address = "",
                        lastUpdated = LocalDateTime.now(),
                        batteryLevel = 100,
                        isOnline = true
                    )
                    updateMyLocation(location)
                }
            }
        }
        
        // Track colocation status from service
        viewModelScope.launch {
            LocationTrackingService.isColocationActive.collect { isActive ->
                val startTime = LocationTrackingService.colocationStartTime.value
                val durationMinutes = if (isActive && startTime != null) {
                    ((System.currentTimeMillis() - startTime) / 60_000).toInt()
                } else {
                    0
                }
                
                _uiState.update {
                    it.copy(
                        isColocationActive = isActive,
                        colocationDurationMinutes = durationMinutes
                    )
                }
            }
        }
        
        // Track colocation session from repository
        viewModelScope.launch {
            locationRepository.activeColocationSession.collect { session ->
                if (session != null) {
                    val durationMinutes = java.time.Duration.between(
                        session.startTime,
                        LocalDateTime.now()
                    ).toMinutes().toInt()
                    
                    _uiState.update {
                        it.copy(
                            isColocationActive = true,
                            colocationStartTime = session.startTime,
                            colocationDurationMinutes = durationMinutes
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isColocationActive = false,
                            colocationStartTime = null,
                            colocationDurationMinutes = 0
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Stop location tracking service
     */
    fun stopLocationTracking() {
        LocationTrackingService.stopService(context)
    }
    
    /**
     * Update my location and recalculate distance
     * Also checks if users are within colocation range (200m)
     */
    private fun updateMyLocation(location: UserLocation) {
        val partnerLocation = _uiState.value.partnerLocation
        val distance = if (partnerLocation != null) {
            calculateDistance(location.coordinate, partnerLocation.coordinate)
        } else {
            0.0
        }
        
        // Check if within colocation range (300 meters for same location)
        val isNearPartner = partnerLocation != null && distance <= 300.0
        
        // Format distance text based on whether partner location is available
        val distanceText = when {
            partnerLocation == null -> "Waiting for partner..."
            isNearPartner -> "Together 💕"
            else -> formatDistance(distance)
        }
        
        _uiState.update {
            it.copy(
                myLocation = location,
                distanceInMeters = distance,
                distanceText = distanceText,
                lastSyncTime = formatLastSync(LocalDateTime.now())
            )
        }
    }
    
    /**
     * Update partner location and recalculate distance
     * Also checks if users are within colocation range (200m)
     */
    private fun updatePartnerLocation(location: UserLocation?) {
        val myLocation = _uiState.value.myLocation
        val distance = if (myLocation != null && location != null) {
            calculateDistance(myLocation.coordinate, location.coordinate)
        } else {
            0.0
        }
        
        // Check if within colocation range (300 meters for same location)
        val isNearPartner = myLocation != null && location != null && distance <= 300.0
        
        // Format distance text based on whether both locations are available
        val distanceText = when {
            location == null -> "Waiting for partner..."
            myLocation == null -> "Getting your location..."
            isNearPartner -> "Together 💕"
            else -> formatDistance(distance)
        }
        
        _uiState.update {
            it.copy(
                partnerLocation = location,
                distanceInMeters = distance,
                distanceText = distanceText,
                lastSyncTime = formatLastSync(LocalDateTime.now())
            )
        }
    }
    
    /**
     * Refresh locations manually.
     * Forces fetch from SERVER to bypass any stale cache.
     */
    fun refreshLocations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                if (userId.isNotEmpty()) {
                    // Get fresh location and upload to Firebase if paired
                    val result = locationRepository.getCurrentLocation(
                        userId = userId, 
                        userName = userName, 
                        avatarUrl = avatarUrl,
                        coupleId = coupleId.ifEmpty { null }
                    )
                    result.getOrNull()?.let { location ->
                        updateMyLocation(location)
                    }
                    
                    // Reload histories if coupleId is available
                    if (coupleId.isNotEmpty()) {
                        locationRepository.loadLocationHistory(userId, coupleId, isCurrentUser = true)
                        if (partnerId.isNotEmpty()) {
                            // ★ Force refresh from SERVER to bypass stale cache
                            android.util.Log.d("DistanceViewModel", "Force refreshing partner data from SERVER...")
                            locationRepository.forceRefreshPartnerLocation(partnerId, coupleId)?.let { partnerLoc ->
                                updatePartnerLocation(partnerLoc)
                            }
                            val partnerHistory = locationRepository.forceRefreshPartnerHistory(partnerId, coupleId)
                            _uiState.update { it.copy(partnerLocationHistory = partnerHistory) }
                        }
                    }
                    
                    _uiState.update { it.copy(isLoading = false) }
                } else {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            error = "Please log in to refresh locations"
                        ) 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message
                    ) 
                }
            }
        }
    }
    
    fun selectUser(user: UserLocation?) {
        _uiState.update {
            it.copy(
                selectedUser = user,
                showUserInfoSheet = user != null
            )
        }
        
        // Force refresh location history when user opens the bottom sheet
        // This ensures we always have the latest data from server
        if (user != null) {
            forceRefreshAllHistory()
        }
    }
    
    /**
     * Load shared places directly from Firestore (fallback when Flow fails)
     */
    private fun loadSharedPlacesDirectly() {
        viewModelScope.launch {
            try {
                android.util.Log.d("DistanceViewModel", ">>> Loading shared places directly (fallback)")
                
                val snapshot = db.collection("shared_places")
                    .whereEqualTo("coupleId", coupleId)
                    .get()
                    .await()
                
                android.util.Log.d("DistanceViewModel", ">>> Direct query returned ${snapshot.documents.size} documents")
                
                val places = snapshot.documents.mapNotNull { doc ->
                    try {
                        val data = doc.data
                        android.util.Log.d("DistanceViewModel", ">>> Place doc: ${doc.id}, data: $data")
                        
                        SharedPlace(
                            id = doc.id,
                            placeName = doc.getString("placeName") ?: "Unknown",
                            address = doc.getString("address") ?: "",
                            coordinate = LocationCoordinate(
                                doc.getDouble("latitude") ?: 0.0,
                                doc.getDouble("longitude") ?: 0.0
                            ),
                            representativePhotoUrl = doc.getString("representativePhotoUrl") ?: "",
                            visitDate = doc.getDate("visitDate")?.toInstant()
                                ?.atZone(java.time.ZoneId.systemDefault())?.toLocalDateTime() 
                                ?: LocalDateTime.now(),
                            durationMinutes = doc.getLong("durationMinutes")?.toInt() ?: 0,
                            photosCount = doc.getLong("photosCount")?.toInt() ?: 0,
                            locationType = try { 
                                LocationType.valueOf(doc.getString("locationType") ?: "OTHER") 
                            } catch (e: Exception) { 
                                LocationType.OTHER 
                            }
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("DistanceViewModel", "Error parsing place ${doc.id}", e)
                        null
                    }
                }.sortedByDescending { it.visitDate }
                
                android.util.Log.d("DistanceViewModel", ">>> Parsed ${places.size} shared places")
                _uiState.update { it.copy(sharedPlaces = places) }
                
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error loading shared places directly", e)
            }
        }
    }
    
    /**
     * Manually refresh shared places
     */
    fun refreshSharedPlaces() {
        loadSharedPlacesDirectly()
    }
    
    /**
     * Force refresh all location history from SERVER (bypass cache).
     * Call this when data seems stale or after deleting entries directly from Firebase Console.
     */
    fun forceRefreshAllHistory() {
        viewModelScope.launch {
            try {
                android.util.Log.d("DistanceViewModel", "🔄 Force refreshing ALL location history from SERVER")
                
                if (userId.isNotEmpty() && coupleId.isNotEmpty()) {
                    // Force refresh MY history
                    val myHistory = locationRepository.forceRefreshMyHistory(userId, coupleId)
                    _uiState.update { it.copy(myLocationHistory = myHistory) }
                    android.util.Log.d("DistanceViewModel", "✅ Force refreshed MY history: ${myHistory.size} entries")
                }
                
                if (partnerId.isNotEmpty() && coupleId.isNotEmpty()) {
                    // Force refresh PARTNER history
                    val partnerHistory = locationRepository.forceRefreshPartnerHistory(partnerId, coupleId)
                    _uiState.update { it.copy(partnerLocationHistory = partnerHistory) }
                    android.util.Log.d("DistanceViewModel", "✅ Force refreshed PARTNER history: ${partnerHistory.size} entries")
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error force refreshing location history", e)
            }
        }
    }

    fun dismissUserInfoSheet() {
        _uiState.update {
            it.copy(
                showUserInfoSheet = false,
                selectedUser = null
            )
        }
    }
    
    /**
     * Load photos for a shared place
     */
    fun loadPlacePhotos(placeId: String) {
        viewModelScope.launch {
            android.util.Log.d("DistanceViewModel", "=== loadPlacePhotos called for placeId: $placeId ===")
            _photosState.update { it.copy(isLoading = true) }
            
            try {
                val place = _uiState.value.sharedPlaces.find { it.id == placeId }
                android.util.Log.d("DistanceViewModel", "Found place: ${place?.placeName}")
                
                android.util.Log.d("DistanceViewModel", "Calling getSharedPlacePhotos...")
                val photosResult = locationRepository.getSharedPlacePhotos(placeId)
                
                val photos = photosResult.getOrElse { 
                    android.util.Log.e("DistanceViewModel", "Error getting photos: ${photosResult.exceptionOrNull()}")
                    emptyList() 
                }
                
                android.util.Log.d("DistanceViewModel", "Loaded ${photos.size} photos")
                photos.forEachIndexed { index, photo ->
                    android.util.Log.d("DistanceViewModel", "Photo $index: id=${photo.id}, url=${photo.photoUrl}")
                }
                
                _photosState.update {
                    it.copy(
                        isLoading = false,
                        place = place,
                        photos = photos
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Exception loading photos", e)
                _photosState.update {
                    it.copy(
                        isLoading = false,
                        photos = emptyList(),
                        error = "Failed to load photos: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Add a photo to a shared place
     * Flow: Upload to Firebase Storage -> Save metadata to Firestore -> Refresh UI
     * Limit: Maximum 30 photos per place
     */
    fun addPhotoToPlace(placeId: String, photoUriString: String) {
        viewModelScope.launch {
            _photosState.update { it.copy(isAddingPhoto = true, error = null) }
            
            try {
                if (userId.isEmpty()) {
                    throw Exception("User not logged in")
                }
                
                // Check photo count limit (30 photos max)
                val currentPhotoCount = _photosState.value.photos.size
                if (currentPhotoCount >= 30) {
                    throw Exception("Đã đạt giới hạn 30 ảnh cho địa điểm này. Vui lòng xóa một số ảnh cũ trước khi thêm ảnh mới.")
                }
                
                android.util.Log.d("DistanceViewModel", "Adding photo to place: $placeId (current count: $currentPhotoCount/30)")
                android.util.Log.d("DistanceViewModel", "Photo URI string: $photoUriString")
                
                // Parse the URI
                val photoUri = Uri.parse(photoUriString)
                android.util.Log.d("DistanceViewModel", "Parsed URI: $photoUri, scheme: ${photoUri.scheme}")
                
                // Step 1: Upload photo to Firebase Storage using context for content:// URIs
                val filename = "place_${placeId}_${userId}_${System.currentTimeMillis()}.jpg"
                android.util.Log.d("DistanceViewModel", "Uploading photo to Storage: $filename")
                
                // Use uploadImageWithContext for content:// URIs (from gallery/camera)
                val uploadResult = storageRepository.uploadImageWithContext(
                    context = context,
                    uri = photoUri,
                    path = FirebaseStorageRepository.PLACE_IMAGES_PATH,
                    filename = filename
                )
                
                if (uploadResult.isSuccess) {
                    val downloadUrl = uploadResult.getOrNull()!!
                    android.util.Log.d("DistanceViewModel", "Photo uploaded successfully: $downloadUrl")
                    
                    // Step 2: Save photo metadata to Firestore
                    val result = locationRepository.addPhotoToSharedPlace(
                        placeId = placeId,
                        photoUrl = downloadUrl,
                        userId = userId,
                        caption = null
                    )
                    
                    if (result.isSuccess) {
                        android.util.Log.d("DistanceViewModel", "Photo metadata saved to Firestore")
                        
                        // Step 3: Update shared place's photo count and representative photo
                        updateSharedPlacePhotoInfo(placeId, downloadUrl)
                        
                        // Step 4: Reload photos to show the new one
                        loadPlacePhotos(placeId)
                    } else {
                        throw result.exceptionOrNull() ?: Exception("Failed to save photo metadata")
                    }
                } else {
                    throw uploadResult.exceptionOrNull() ?: Exception("Failed to upload photo")
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error adding photo", e)
                _photosState.update { 
                    it.copy(error = "Failed to add photo: ${e.message}") 
                }
            } finally {
                _photosState.update { it.copy(isAddingPhoto = false) }
            }
        }
    }
    
    /**
     * Update shared place's photo count and representative photo
     */
    private suspend fun updateSharedPlacePhotoInfo(placeId: String, newPhotoUrl: String) {
        try {
            val placeRef = db.collection("shared_places").document(placeId)
            db.runTransaction { transaction ->
                val placeSnapshot = transaction.get(placeRef)
                val currentCount = placeSnapshot.getLong("photosCount") ?: 0
                val currentRepPhoto = placeSnapshot.getString("representativePhotoUrl") ?: ""
                
                val updates = mutableMapOf<String, Any>(
                    "photosCount" to (currentCount + 1)
                )
                
                // Set representative photo if empty
                if (currentRepPhoto.isEmpty()) {
                    updates["representativePhotoUrl"] = newPhotoUrl
                }
                
                transaction.update(placeRef, updates)
            }.await()
        } catch (e: Exception) {
            android.util.Log.e("DistanceViewModel", "Error updating shared place info", e)
        }
    }
    
    /**
     * Delete a photo from a shared place
     */
    fun deletePhotoFromPlace(photoId: String, placeId: String) {
        viewModelScope.launch {
            _photosState.update { it.copy(isAddingPhoto = true) } // Reuse loading state
            
            try {
                android.util.Log.d("DistanceViewModel", "Deleting photo: $photoId from place: $placeId")
                
                val result = locationRepository.deletePhotoFromSharedPlace(photoId, placeId)
                
                if (result.isSuccess) {
                    android.util.Log.d("DistanceViewModel", "Photo deleted successfully")
                    // Reload photos to update the UI
                    loadPlacePhotos(placeId)
                } else {
                    throw result.exceptionOrNull() ?: Exception("Failed to delete photo")
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error deleting photo", e)
                _photosState.update { 
                    it.copy(error = "Failed to delete photo: ${e.message}") 
                }
            } finally {
                _photosState.update { it.copy(isAddingPhoto = false) }
            }
        }
    }
    
    /**
     * Delete multiple photos from a shared place at once
     */
    fun deleteMultiplePhotosFromPlace(photoIds: List<String>, placeId: String) {
        viewModelScope.launch {
            _photosState.update { it.copy(isAddingPhoto = true, error = null) }
            
            try {
                android.util.Log.d("DistanceViewModel", "Deleting ${photoIds.size} photos from place: $placeId")
                
                var successCount = 0
                var failCount = 0
                
                // Delete each photo
                photoIds.forEach { photoId ->
                    val result = locationRepository.deletePhotoFromSharedPlace(photoId, placeId)
                    if (result.isSuccess) {
                        successCount++
                    } else {
                        failCount++
                    }
                }
                
                android.util.Log.d("DistanceViewModel", "Deleted $successCount photos, $failCount failed")
                
                // Reload photos to update the UI
                loadPlacePhotos(placeId)
                
                if (failCount > 0) {
                    _photosState.update { 
                        it.copy(error = "Đã xóa $successCount ảnh, $failCount ảnh không thể xóa") 
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error deleting multiple photos", e)
                _photosState.update { 
                    it.copy(error = "Lỗi khi xóa ảnh: ${e.message}") 
                }
            } finally {
                _photosState.update { it.copy(isAddingPhoto = false) }
            }
        }
    }
    
    /**
     * Add photo to current colocation session (if both users are together)
     */
    fun addPhotoToCurrentColocation(photoUrl: String) {
        locationRepository.addPhotoToColocationSession(photoUrl)
    }
    
    companion object {
        const val DUPLICATE_PLACE_DISTANCE_METERS = 500.0 // Don't create new place within 500m of existing one
    }
    
    /**
     * Manually create a shared place at the current location
     * Useful when users want to mark a place without waiting for the 10-minute timer
     * Checks for existing places within 500m to avoid duplicates
     */
    fun createSharedPlaceManually(placeName: String, photoUri: Uri?) {
        viewModelScope.launch {
            try {
                if (userId.isEmpty() || coupleId.isEmpty()) {
                    _uiState.update { it.copy(error = "User not logged in or not paired") }
                    return@launch
                }
                
                val myLocation = _uiState.value.myLocation ?: run {
                    _uiState.update { it.copy(error = "Could not get your location") }
                    return@launch
                }
                
                android.util.Log.d("DistanceViewModel", "Creating shared place manually: $placeName")
                
                // Check for existing place within 500m
                val existingPlaceId = checkForExistingPlaceNearby(
                    myLocation.coordinate.latitude,
                    myLocation.coordinate.longitude
                )
                
                if (existingPlaceId != null) {
                    // Place already exists nearby - show error or navigate to existing place
                    android.util.Log.d("DistanceViewModel", "Found existing place within 500m: $existingPlaceId")
                    _uiState.update { 
                        it.copy(error = "A shared place already exists nearby. Adding photos to existing place.") 
                    }
                    
                    // Upload photo and add to existing place
                    if (photoUri != null) {
                        val filename = "place_${existingPlaceId}_${userId}_${System.currentTimeMillis()}.jpg"
                        val uploadResult = storageRepository.uploadImageWithContext(
                            context = context,
                            uri = photoUri,
                            path = FirebaseStorageRepository.PLACE_IMAGES_PATH,
                            filename = filename
                        )
                        uploadResult.getOrNull()?.let { url ->
                            locationRepository.addPhotoToSharedPlace(existingPlaceId, url, userId)
                        }
                    }
                    return@launch
                }
                
                // No existing place nearby - create new one
                // Upload photo if provided
                var photoUrl = ""
                if (photoUri != null) {
                    val filename = "place_manual_${coupleId}_${System.currentTimeMillis()}.jpg"
                    val uploadResult = storageRepository.uploadImageWithContext(
                        context = context,
                        uri = photoUri,
                        path = FirebaseStorageRepository.PLACE_IMAGES_PATH,
                        filename = filename
                    )
                    photoUrl = uploadResult.getOrDefault("")
                }
                
                // Create shared place
                val sharedPlaceData = mapOf(
                    "coupleId" to coupleId,
                    "placeName" to placeName,
                    "address" to myLocation.address,
                    "latitude" to myLocation.coordinate.latitude,
                    "longitude" to myLocation.coordinate.longitude,
                    "representativePhotoUrl" to photoUrl,
                    "visitDate" to java.util.Date(),
                    "durationMinutes" to 0,
                    "photosCount" to if (photoUrl.isNotEmpty()) 1 else 0,
                    "locationType" to "OTHER",
                    "photoUrls" to if (photoUrl.isNotEmpty()) listOf(photoUrl) else emptyList<String>()
                )
                
                db.collection("shared_places")
                    .add(sharedPlaceData)
                    .addOnSuccessListener { docRef ->
                        android.util.Log.d("DistanceViewModel", "Created manual shared place: ${docRef.id}")
                        
                        // Add photo to shared_place_photos if uploaded
                        if (photoUrl.isNotEmpty()) {
                            val photoData = mapOf(
                                "placeId" to docRef.id,
                                "photoUrl" to photoUrl,
                                "takenAt" to java.util.Date(),
                                "takenByUserId" to userId,
                                "caption" to null
                            )
                            db.collection("shared_place_photos").add(photoData)
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("DistanceViewModel", "Failed to create shared place", e)
                        _uiState.update { it.copy(error = "Failed to create place: ${e.message}") }
                    }
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error creating shared place", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    /**
     * Delete a shared place
     */
    fun deleteSharedPlace(placeId: String) {
        viewModelScope.launch {
            try {
                // Delete photos from the place first
                val photosQuery = db.collection("shared_place_photos")
                    .whereEqualTo("placeId", placeId)
                    .get()
                    .await()
                
                photosQuery.documents.forEach { doc ->
                    // Delete from Storage if it's a Firebase URL
                    val photoUrl = doc.getString("photoUrl") ?: ""
                    if (photoUrl.contains("firebasestorage")) {
                        try {
                            storageRepository.deleteFile(photoUrl)
                        } catch (e: Exception) {
                            android.util.Log.w("DistanceViewModel", "Failed to delete photo from storage: ${e.message}")
                        }
                    }
                    // Delete photo document
                    doc.reference.delete()
                }
                
                // Delete the shared place
                db.collection("shared_places").document(placeId).delete().await()
                android.util.Log.d("DistanceViewModel", "Deleted shared place: $placeId")
                
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error deleting shared place", e)
                _uiState.update { it.copy(error = "Failed to delete place: ${e.message}") }
            }
        }
    }
    
    // Helper functions
    private fun calculateDistance(coord1: LocationCoordinate, coord2: LocationCoordinate): Double {
        val earthRadius = 6371000.0 // meters
        
        val lat1Rad = Math.toRadians(coord1.latitude)
        val lat2Rad = Math.toRadians(coord2.latitude)
        val deltaLat = Math.toRadians(coord2.latitude - coord1.latitude)
        val deltaLon = Math.toRadians(coord2.longitude - coord1.longitude)
        
        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Check if there's an existing shared place within 500m of the given location
     * Returns the place ID if found, null otherwise
     */
    private suspend fun checkForExistingPlaceNearby(latitude: Double, longitude: Double): String? {
        return try {
            val snapshot = db.collection("shared_places")
                .whereEqualTo("coupleId", coupleId)
                .get()
                .await()
            
            var nearestPlaceId: String? = null
            var nearestDistance = Double.MAX_VALUE
            
            for (doc in snapshot.documents) {
                val placeLat = doc.getDouble("latitude") ?: continue
                val placeLng = doc.getDouble("longitude") ?: continue
                
                val distance = calculateDistance(
                    LocationCoordinate(latitude, longitude),
                    LocationCoordinate(placeLat, placeLng)
                )
                
                if (distance <= DUPLICATE_PLACE_DISTANCE_METERS && distance < nearestDistance) {
                    nearestDistance = distance
                    nearestPlaceId = doc.id
                }
            }
            
            nearestPlaceId
        } catch (e: Exception) {
            android.util.Log.e("DistanceViewModel", "Error checking nearby places: ${e.message}")
            null
        }
    }
    
    private fun formatDistance(meters: Double): String {
        return when {
            meters < 1000 -> "${meters.toInt()} m"
            meters < 10000 -> String.format("%.1f km", meters / 1000)
            else -> String.format("%.0f km", meters / 1000)
        }
    }
    
    private fun formatLastSync(dateTime: LocalDateTime): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val isVietnamese = java.util.Locale.getDefault().language == "vi"
        val prefix = if (isVietnamese) "Cập nhật" else "Updated"
        return "$prefix ${dateTime.format(formatter)}"
    }
    
    /**
     * Fix photosCount for all shared places by counting actual photos in shared_place_photos
     * Call this once to fix any inconsistencies in existing data
     */
    fun fixPhotosCountForAllPlaces() {
        viewModelScope.launch {
            try {
                android.util.Log.d("DistanceViewModel", "Starting photosCount fix for all places")
                
                // Get all shared places
                val placesSnapshot = db.collection("shared_places")
                    .whereEqualTo("coupleId", coupleId)
                    .get()
                    .await()
                
                for (placeDoc in placesSnapshot.documents) {
                    val placeId = placeDoc.id
                    
                    // Count actual photos for this place
                    val photosSnapshot = db.collection("shared_place_photos")
                        .whereEqualTo("placeId", placeId)
                        .get()
                        .await()
                    
                    val actualCount = photosSnapshot.size()
                    val storedCount = placeDoc.getLong("photosCount")?.toInt() ?: 0
                    
                    if (actualCount != storedCount) {
                        android.util.Log.d("DistanceViewModel", 
                            "Fixing place $placeId: stored=$storedCount, actual=$actualCount")
                        
                        // Update the photosCount
                        db.collection("shared_places").document(placeId)
                            .update("photosCount", actualCount)
                            .await()
                    }
                }
                
                android.util.Log.d("DistanceViewModel", "Finished fixing photosCount")
                
                // Reload shared places to reflect changes
                loadSharedPlacesDirectly()
            } catch (e: Exception) {
                android.util.Log.e("DistanceViewModel", "Error fixing photosCount", e)
            }
        }
    }
    
    /**
     * Factory for creating DistanceViewModel with Application context
     */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DistanceViewModel::class.java)) {
                return DistanceViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
