package com.example.coupleapp.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.data.model.*
import com.example.coupleapp.data.repository.ProfileCacheRepository
import com.example.coupleapp.data.repository.SleepCacheRepository
import com.example.coupleapp.data.repository.SleepFirebaseRepository
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.example.coupleapp.widget.SleepWidgetManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Calendar

/**
 * Sleep Tracker ViewModel with Firebase integration and local caching.
 * 
 * Cache-First Strategy:
 * 1. On init: Load cached data immediately for instant UI (including user profiles from ProfileCacheRepository)
 * 2. Background sync: Refresh from Firebase in background
 * 3. Result: User sees data instantly, no waiting for network
 */
class SleepTrackerViewModelFirebase(
    private val context: Context? = null
) : ViewModel() {
    private val sleepRepository = SleepFirebaseRepository(context)
    private val sleepCache = context?.let { SleepCacheRepository.getInstance(it) }
    private val profileCache = context?.let { ProfileCacheRepository.getInstance(it) }
    private val googleSleepApiManager = context?.let { GoogleSleepApiManager(it) }
    private val auth = FirebaseAuth.getInstance()
    private val prefs = context?.getSharedPreferences("sleep_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SleepTrackerViewModel"
        private const val PREF_GOOGLE_SLEEP_API_ENABLED = "google_sleep_api_enabled"
    }

    private val _uiState = MutableStateFlow(SleepTrackerUiState())
    val uiState: StateFlow<SleepTrackerUiState> = _uiState
        .debounce(50)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SleepTrackerUiState()
        )

    private var loadDataJob: Job? = null
    private var lastLoadDate: String? = null

    init {
        Log.d(TAG, "SleepTrackerViewModelFirebase initialized")
        loadInitialDataWithCache()
        checkAndAutoSync()
        checkActiveSleepSession()
        checkGoogleSleepApiStatus()
    }
    
    /**
     * Called when user navigates to Sleep screen.
     * Checks if data needs refresh (new day, or stale cache).
     * 
     * This fixes the issue where:
     * - User opens app in the morning
     * - Sleep data arrived at 11:34 AM
     * - But cache shows yesterday's data
     * - Now: Force refresh if new day or cache is stale
     */
    fun onScreenVisible() {
        val today = java.time.LocalDate.now().toString()
        val userId = auth.currentUser?.uid ?: return
        
        // Check if we need to refresh (new day or stale cache)
        val needsRefresh = lastLoadDate != today || 
                           sleepCache?.isHistoryCacheFresh(userId) != true ||
                           sleepCache?.isTodayRecordCacheFresh(userId) != true
        
        if (needsRefresh) {
            Log.d(TAG, "📅 Screen visible: refreshing data (lastLoad=$lastLoadDate, today=$today)")
            lastLoadDate = today
            
            // Show loading only briefly, then refresh
            viewModelScope.launch {
                // Invalidate stale today record cache
                if (sleepCache?.isTodayRecordCacheFresh(userId) != true) {
                    sleepCache?.clearTodayRecord(userId)
                }
                
                // Refresh from Firebase
                loadUserDataAndUpdateCache(userId, isInitialLoad = false, showLoading = false)
            }
        } else {
            Log.d(TAG, "📅 Screen visible: cache is fresh, no refresh needed")
        }
    }
    
    /**
     * Determine the sleep data status based on current time and available data.
     * 
     * Google Sleep API typically sends data 1-3 hours after waking up.
     * - Before 12 PM: Data might still be arriving (WAITING_TODAY)
     * - After 12 PM: If no data, it's likely delayed (DATA_DELAYED)
     * - If history exists but no today data: User tracked before, waiting for today
     * - If no history at all: User never tracked (NO_DATA_EVER)
     */
    private fun determineSleepDataStatus(
        sleepRecord: SleepRecord?,
        sleepHistory: List<SleepRecord>,
        isViewingPartner: Boolean = false
    ): SleepDataStatus {
        // Has data for today
        if (sleepRecord != null) {
            return SleepDataStatus.HAS_DATA
        }
        
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        
        // Check if history exists (user has tracked before)
        val hasHistory = sleepHistory.isNotEmpty()
        
        return when {
            // Morning (before 12 PM) - data is expected to arrive later
            currentHour < 12 -> {
                if (hasHistory || isViewingPartner) {
                    SleepDataStatus.WAITING_TODAY
                } else {
                    SleepDataStatus.NO_DATA_EVER
                }
            }
            // Afternoon/Evening - data should have arrived by now
            else -> {
                if (hasHistory) {
                    SleepDataStatus.DATA_DELAYED
                } else {
                    SleepDataStatus.NO_DATA_EVER
                }
            }
        }
    }

    /**
     * Load initial data with cache-first strategy:
     * 1. Show cached data immediately (instant UI)
     * 2. Load fresh data from Firebase in background
     * 3. Update UI when fresh data arrives
     */
    private fun loadInitialDataWithCache() {
        viewModelScope.launch {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    Log.e(TAG, "User not logged in")
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val userId = currentUser.uid
                Log.d(TAG, "Loading data with cache-first strategy for user: $userId")

                // Step 1: Try to load from cache first (instant UI)
                val hasCached = sleepCache?.hasCachedData(userId) == true
                
                if (hasCached) {
                    Log.d(TAG, "📦 Cache found! Loading from cache first...")
                    loadFromCacheAndSyncBackground(userId)
                } else {
                    Log.d(TAG, "🌐 No cache, loading from Firebase...")
                    _uiState.update { it.copy(isLoading = true) }
                    loadInitialData()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in loadInitialDataWithCache", e)
                // Fallback to Firebase loading
                loadInitialData()
            }
        }
    }
    
    /**
     * Load data from cache immediately, then sync in background
     * Uses ProfileCacheRepository to avoid network calls for user profiles
     */
    private suspend fun loadFromCacheAndSyncBackground(userId: String) {
        try {
            // Load cached settings
            val cachedSettings = sleepCache?.getCachedSettings(userId)
            val cachedHistory = sleepCache?.getCachedHistory(userId)
            val cachedTodayRecord = sleepCache?.getCachedTodayRecord(userId)
            
            Log.d(TAG, "📦 Cached data: settings=${cachedSettings != null}, history=${cachedHistory?.size ?: 0}, today=${cachedTodayRecord != null}")
            
            // Update UI with cached data immediately
            if (cachedSettings != null || cachedHistory != null) {
                val settings = cachedSettings?.let { sleepRepository.convertToSleepSettings(it) }
                    ?: SleepSettings(
                        targetSleepDuration = 480,
                        idealBedTime = LocalTime.of(22, 0),
                        idealWakeUpTime = LocalTime.of(6, 0),
                        userId = userId
                    )
                
                val sleepHistory = cachedHistory?.map { sleepRepository.convertToSleepRecord(it) } ?: emptyList()
                val sleepRecord = cachedTodayRecord?.let { sleepRepository.convertToSleepRecord(it) }
                
                // Don't use history as today's record - show empty state instead
                // This prevents confusing UX where yesterday's data is shown as today's
                
                // ========== INSTANT PROFILE LOAD FROM CACHE (No network!) ==========
                // Try ProfileCacheRepository first for instant UI
                val cachedCurrentUser = profileCache?.getCachedCurrentUser()
                val cachedPartner = profileCache?.getCachedPartner()
                
                val currentUserProfile = if (cachedCurrentUser != null && cachedCurrentUser.id == userId) {
                    Log.d(TAG, "📦 Using cached current user profile: ${cachedCurrentUser.displayName}")
                    UserProfile(
                        id = cachedCurrentUser.id,
                        name = cachedCurrentUser.displayName,
                        avatarUrl = cachedCurrentUser.profileImageUrl.takeIf { it.isNotEmpty() }
                    )
                } else {
                    // Fallback to default - will be refreshed in background
                    Log.d(TAG, "⚠️ No cached current user, using default")
                    UserProfile(userId, "User", null)
                }
                
                val partnerProfile = if (cachedPartner != null) {
                    Log.d(TAG, "📦 Using cached partner profile: ${cachedPartner.displayName}")
                    UserProfile(
                        id = cachedPartner.id,
                        name = cachedPartner.displayName,
                        avatarUrl = cachedPartner.profileImageUrl.takeIf { it.isNotEmpty() }
                    )
                } else {
                    // Fallback to default - will be refreshed in background
                    Log.d(TAG, "⚠️ No cached partner, using default")
                    UserProfile("", "Partner", null)
                }
                
                // ========== INSTANT UI UPDATE (No network wait!) ==========
                val dataStatus = determineSleepDataStatus(sleepRecord, sleepHistory, isViewingPartner = false)
                _uiState.update { currentState ->
                    currentState.copy(
                        currentUser = currentUserProfile,
                        partnerUser = partnerProfile,
                        isCurrentUser = true,
                        sleepRecord = sleepRecord,
                        sleepHistory = sleepHistory.take(3),
                        settings = settings,
                        isLoading = false,
                        sleepDataStatus = dataStatus
                    )
                }
                
                Log.d(TAG, "✅ UI updated INSTANTLY from cache, starting background sync...")
                
                // Check bedtime reminder
                val hasActiveSession = _uiState.value.activeSleepSession != null
                if (!hasActiveSession) {
                    checkBedtimeReminder(settings.idealBedTime)
                }
                
                // Sync in background if cache is stale
                if (sleepCache?.isSettingsCacheFresh(userId) != true || 
                    sleepCache?.isHistoryCacheFresh(userId) != true) {
                    Log.d(TAG, "🔄 Cache is stale, refreshing in background...")
                    loadUserDataAndUpdateCache(userId, isInitialLoad = true, showLoading = false)
                }
            } else {
                // No usable cache, load from Firebase
                loadInitialData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from cache", e)
            loadInitialData()
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    Log.e(TAG, "User not logged in")
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val userId = currentUser.uid
                Log.d(TAG, "Loading data for user: $userId")
                
                // ========== TRY PROFILE CACHE FIRST FOR INSTANT USER DISPLAY ==========
                val cachedCurrentUser = profileCache?.getCachedCurrentUser()
                val cachedPartner = profileCache?.getCachedPartner()
                
                // Show cached profiles immediately while loading sleep data
                if (cachedCurrentUser != null && cachedCurrentUser.id == userId) {
                    val cachedCurrentProfile = UserProfile(
                        id = cachedCurrentUser.id,
                        name = cachedCurrentUser.displayName,
                        avatarUrl = cachedCurrentUser.profileImageUrl.takeIf { it.isNotEmpty() }
                    )
                    val cachedPartnerProfile = cachedPartner?.let {
                        UserProfile(
                            id = it.id,
                            name = it.displayName,
                            avatarUrl = it.profileImageUrl.takeIf { url -> url.isNotEmpty() }
                        )
                    } ?: UserProfile("", "Partner", null)
                    
                    _uiState.update { currentState ->
                        currentState.copy(
                            currentUser = cachedCurrentProfile,
                            partnerUser = cachedPartnerProfile,
                            isCurrentUser = true
                            // NOTE: Keep isLoading = true until sleep data is loaded
                        )
                    }
                    Log.d(TAG, "📦 Showed cached user profiles while loading sleep data")
                }
                
                // Check and perform auto-sync if needed
                tryAutoSync(userId)

                // Load current user profile from Firebase (for refresh)
                val currentUserResult = sleepRepository.getUserProfile(userId)
                val currentUserProfile = currentUserResult.getOrElse {
                    UserProfile(userId, "User", null)
                }
                Log.d(TAG, "Loaded current user profile: name=${currentUserProfile.name}, avatarUrl=${currentUserProfile.avatarUrl?.take(50)}")

                // Load partner profile from Firebase
                val partnerIdResult = sleepRepository.getPartnerId()
                val partnerId = partnerIdResult.getOrNull()
                
                var partnerProfile = UserProfile("", "Partner", null)
                if (partnerId != null) {
                    val partnerResult = sleepRepository.getUserProfile(partnerId)
                    partnerProfile = partnerResult.getOrElse {
                        UserProfile(partnerId, "Partner", null)
                    }
                    Log.d(TAG, "Loaded partner profile: name=${partnerProfile.name}, avatarUrl=${partnerProfile.avatarUrl?.take(50)}")
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        currentUser = currentUserProfile,
                        partnerUser = partnerProfile,
                        isCurrentUser = true
                    )
                }

                // Load current user's sleep data
                loadUserData(userId, isInitialLoad = true)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial data", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleUser() {
        loadDataJob?.cancel()

        val newIsCurrentUser = !_uiState.value.isCurrentUser
        val userId = if (newIsCurrentUser) {
            _uiState.value.currentUser.id
        } else {
            _uiState.value.partnerUser.id
        }

        _uiState.update { it.copy(isCurrentUser = newIsCurrentUser) }
        
        // Try cache first for toggled user
        viewModelScope.launch {
            val hasCached = sleepCache?.hasCachedData(userId) == true
            if (hasCached) {
                loadFromCacheForUser(userId)
            } else {
                loadUserDataAndUpdateCache(userId, isInitialLoad = false, showLoading = true)
            }
        }
    }
    
    /**
     * Load cached data for a specific user (when toggling)
     */
    private suspend fun loadFromCacheForUser(userId: String) {
        try {
            val cachedSettings = sleepCache?.getCachedSettings(userId)
            val cachedHistory = sleepCache?.getCachedHistory(userId)
            val cachedTodayRecord = sleepCache?.getCachedTodayRecord(userId)
            
            if (cachedSettings != null || cachedHistory != null) {
                val settings = cachedSettings?.let { sleepRepository.convertToSleepSettings(it) }
                    ?: _uiState.value.settings
                
                val sleepHistory = cachedHistory?.map { sleepRepository.convertToSleepRecord(it) } 
                    ?: emptyList()
                val sleepRecord = cachedTodayRecord?.let { sleepRepository.convertToSleepRecord(it) }
                
                // Don't use history as today's record - show empty state instead
                
                val isViewingPartner = !_uiState.value.isCurrentUser
                val dataStatus = determineSleepDataStatus(sleepRecord, sleepHistory, isViewingPartner)
                _uiState.update { currentState ->
                    currentState.copy(
                        sleepRecord = sleepRecord,
                        sleepHistory = sleepHistory.take(3),
                        settings = settings,
                        isLoading = false,
                        sleepDataStatus = dataStatus
                    )
                }
                
                Log.d(TAG, "✅ Loaded cached data for toggled user: $userId")
                
                // Sync in background if stale
                if (sleepCache?.isSettingsCacheFresh(userId) != true) {
                    loadUserDataAndUpdateCache(userId, isInitialLoad = false, showLoading = false)
                }
            } else {
                loadUserDataAndUpdateCache(userId, isInitialLoad = false, showLoading = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache for user", e)
            loadUserDataAndUpdateCache(userId, isInitialLoad = false, showLoading = true)
        }
    }

    private fun loadUserData(userId: String, isInitialLoad: Boolean) {
        loadUserDataAndUpdateCache(userId, isInitialLoad, showLoading = true)
    }
    
    /**
     * Load user data from Firebase and update cache
     */
    private fun loadUserDataAndUpdateCache(userId: String, isInitialLoad: Boolean, showLoading: Boolean) {
        loadDataJob?.cancel()
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }

        loadDataJob = viewModelScope.launch {
            try {
                // Minimal delay before API calls for smooth transition
                kotlinx.coroutines.delay(50)

                Log.d(TAG, "Loading sleep data for user: $userId")

                // Load settings
                val settingsResult = sleepRepository.getSleepSettings(userId)
                val firebaseSettings = settingsResult.getOrNull()
                Log.d(TAG, "loadUserData: Firebase settings = $firebaseSettings")
                
                // Cache settings
                if (firebaseSettings != null) {
                    sleepCache?.cacheSettings(userId, firebaseSettings)
                }
                
                val settings = firebaseSettings?.let { sleepRepository.convertToSleepSettings(it) }
                    ?: SleepSettings(
                        targetSleepDuration = 480,
                        idealBedTime = LocalTime.of(22, 0),
                        idealWakeUpTime = LocalTime.of(6, 0),
                        userId = userId
                    )
                Log.d(TAG, "loadUserData: Converted settings - bedTime=${settings.idealBedTime}, wakeTime=${settings.idealWakeUpTime}, duration=${settings.targetSleepDuration}")

                // Load today's sleep record
                val todayRecordResult = sleepRepository.getTodaySleepRecord(userId)
                val firebaseRecord = todayRecordResult.getOrNull()
                var sleepRecord = firebaseRecord?.let { sleepRepository.convertToSleepRecord(it) }
                Log.d(TAG, "loadUserData: Today's record = ${sleepRecord?.id}")
                
                // Cache today's record
                if (firebaseRecord != null) {
                    sleepCache?.cacheTodayRecord(userId, firebaseRecord)
                }

                // Load sleep history
                val historyResult = sleepRepository.getSleepHistory(userId, 7)
                val firebaseHistory = historyResult.getOrElse { emptyList() }
                
                // Deduplicate by date - keep only the most recent record for each date
                val deduplicatedHistory = firebaseHistory
                    .groupBy { record ->
                        record.date?.toDate()?.toInstant()
                            ?.atZone(java.time.ZoneId.systemDefault())
                            ?.toLocalDate()
                    }
                    .mapValues { (_, records) ->
                        // Keep the most recent record (by createdAt timestamp)
                        records.maxByOrNull { it.createdAt ?: com.google.firebase.Timestamp.now() }
                    }
                    .values
                    .filterNotNull()
                    .sortedByDescending { it.date }
                
                // Cache history
                if (deduplicatedHistory.isNotEmpty()) {
                    sleepCache?.cacheHistory(userId, deduplicatedHistory)
                }
                
                val sleepHistory = deduplicatedHistory.map { sleepRepository.convertToSleepRecord(it) }
                
                Log.d(TAG, "loadUserData: Found ${sleepHistory.size} history records (after deduplication)")
                
                // Don't use history as today's record - show empty state instead
                // This prevents confusing UX where yesterday's data is shown as today's

                val isViewingPartner = !_uiState.value.isCurrentUser
                val dataStatus = determineSleepDataStatus(sleepRecord, sleepHistory, isViewingPartner)
                _uiState.update { currentState ->
                    currentState.copy(
                        sleepRecord = sleepRecord,
                        sleepHistory = sleepHistory.take(3),
                        settings = settings,
                        isLoading = false,
                        sleepDataStatus = dataStatus
                    )
                }

                // Check bedtime reminder (only if no active sleep session)
                val hasActiveSession = _uiState.value.activeSleepSession != null
                if (!hasActiveSession) {
                    checkBedtimeReminder(settings.idealBedTime)
                }

                Log.d(TAG, "Sleep data loaded and cached successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading sleep data", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Check if it's time for bedtime reminder
     * Shows dialog if current time is within 30 minutes before or after bedtime
     * Does NOT show if user already has a sleep record for today (already slept and woke up)
     */
    private fun checkBedtimeReminder(bedTime: LocalTime) {
        val now = LocalTime.now()
        val today = LocalDate.now()
        val minutesDiff = java.time.Duration.between(bedTime, now).toMinutes()
        
        Log.d(TAG, "checkBedtimeReminder: bedTime=$bedTime, now=$now, minutesDiff=$minutesDiff")
        
        // Check if user already has a sleep record for today
        val sleepRecord = _uiState.value.sleepRecord
        val recordDate = sleepRecord?.date?.toLocalDate()
        val hasTodayRecord = sleepRecord != null && recordDate == today
        
        Log.d(TAG, "checkBedtimeReminder: sleepRecord=${sleepRecord?.id}, recordDate=$recordDate, today=$today, hasTodayRecord=$hasTodayRecord")
        
        // Show reminder if:
        // 1. No active sleep session
        // 2. No sleep record for today (haven't slept yet today)
        // 3. Within time window
        val shouldShowReminder = when {
            _uiState.value.activeSleepSession != null -> {
                Log.d(TAG, "checkBedtimeReminder: Active session exists, not showing")
                false
            }
            hasTodayRecord -> {
                Log.d(TAG, "checkBedtimeReminder: Already have today's record (id=${sleepRecord?.id}), not showing")
                false
            }
            minutesDiff in -30..60 -> {
                Log.d(TAG, "checkBedtimeReminder: Within reminder window, showing")
                true
            }
            // Handle overnight case (e.g., bedtime at 23:00, current time 00:30)
            bedTime.hour >= 20 && now.hour < 4 -> {
                val adjustedMinutesDiff = minutesDiff + 24 * 60 // Adjust for day wrap
                Log.d(TAG, "checkBedtimeReminder: Overnight case, adjustedMinutesDiff=$adjustedMinutesDiff")
                adjustedMinutesDiff in -30..60
            }
            else -> {
                Log.d(TAG, "checkBedtimeReminder: Outside reminder window, not showing")
                false
            }
        }
        
        if (shouldShowReminder) {
            _uiState.update { it.copy(showBedtimeReminder = true) }
        }
    }

    /**
     * Update when to sleep (bedtime)
     */
    fun updateBedTime(newBedTime: LocalTime) {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                val currentSettings = _uiState.value.settings ?: return@launch
                
                Log.d(TAG, "updateBedTime: Old bedtime = ${currentSettings.idealBedTime}, New bedtime = $newBedTime")
                
                val firebaseSettings = FirebaseSleepSettings(
                    id = userId,
                    userId = userId,
                    targetSleepDurationMinutes = currentSettings.targetSleepDuration,
                    idealBedTimeHour = newBedTime.hour,
                    idealBedTimeMinute = newBedTime.minute,
                    idealWakeUpTimeHour = currentSettings.idealWakeUpTime.hour,
                    idealWakeUpTimeMinute = currentSettings.idealWakeUpTime.minute
                )
                
                val result = sleepRepository.updateSleepSettings(firebaseSettings)
                if (result.isSuccess) {
                    Log.d(TAG, "Bedtime updated successfully in Firebase")
                    // Update local state immediately
                    _uiState.update { currentState ->
                        currentState.copy(
                            settings = currentSettings.copy(idealBedTime = newBedTime)
                        )
                    }
                    Log.d(TAG, "Local state updated: idealBedTime = ${_uiState.value.settings?.idealBedTime}")
                    checkBedtimeReminder(newBedTime)
                } else {
                    Log.e(TAG, "Failed to update bedtime: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating bedtime", e)
            }
        }
    }

    /**
     * Update wake up time
     */
    fun updateWakeUpTime(newWakeUpTime: LocalTime) {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                val currentSettings = _uiState.value.settings ?: return@launch
                
                val firebaseSettings = FirebaseSleepSettings(
                    id = userId,
                    userId = userId,
                    targetSleepDurationMinutes = currentSettings.targetSleepDuration,
                    idealBedTimeHour = currentSettings.idealBedTime.hour,
                    idealBedTimeMinute = currentSettings.idealBedTime.minute,
                    idealWakeUpTimeHour = newWakeUpTime.hour,
                    idealWakeUpTimeMinute = newWakeUpTime.minute
                )
                
                val result = sleepRepository.updateSleepSettings(firebaseSettings)
                if (result.isSuccess) {
                    Log.d(TAG, "Wake up time updated successfully")
                    // Update local state immediately
                    _uiState.update { currentState ->
                        currentState.copy(
                            settings = currentSettings.copy(idealWakeUpTime = newWakeUpTime)
                        )
                    }
                } else {
                    Log.e(TAG, "Failed to update wake up time: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating wake up time", e)
            }
        }
    }

    /**
     * Update sleep goal (target duration)
     */
    fun updateSleepGoal(durationMinutes: Int) {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                val currentSettings = _uiState.value.settings ?: return@launch
                
                Log.d(TAG, "updateSleepGoal: Old duration = ${currentSettings.targetSleepDuration} mins, New duration = $durationMinutes mins")
                
                val firebaseSettings = FirebaseSleepSettings(
                    id = userId,
                    userId = userId,
                    targetSleepDurationMinutes = durationMinutes,
                    idealBedTimeHour = currentSettings.idealBedTime.hour,
                    idealBedTimeMinute = currentSettings.idealBedTime.minute,
                    idealWakeUpTimeHour = currentSettings.idealWakeUpTime.hour,
                    idealWakeUpTimeMinute = currentSettings.idealWakeUpTime.minute
                )
                
                val result = sleepRepository.updateSleepSettings(firebaseSettings)
                if (result.isSuccess) {
                    Log.d(TAG, "Sleep goal updated successfully in Firebase")
                    // Update local state immediately
                    _uiState.update { currentState ->
                        currentState.copy(
                            settings = currentSettings.copy(targetSleepDuration = durationMinutes)
                        )
                    }
                    Log.d(TAG, "Local state updated: targetSleepDuration = ${_uiState.value.settings?.targetSleepDuration} mins")
                } else {
                    Log.e(TAG, "Failed to update sleep goal: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating sleep goal", e)
            }
        }
    }

    /**
     * Start sleep tracking
     */
    fun startSleepTracking() {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                val currentSettings = _uiState.value.settings ?: return@launch
                val now = LocalTime.now()
                
                val firebaseRecord = FirebaseSleepRecord(
                    userId = userId,
                    coupleId = "", // Will be filled by repository if needed
                    date = Timestamp(Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant())),
                    bedTimeHour = now.hour,
                    bedTimeMinute = now.minute,
                    wakeUpTimeHour = 0,
                    wakeUpTimeMinute = 0,
                    actualSleepDurationMinutes = 0,
                    targetSleepDurationMinutes = currentSettings.targetSleepDuration,
                    awakeDurationMinutes = 0,
                    sleepDurationMinutes = 0,
                    quality = "GOOD",
                    achievementPercentage = 0f
                )
                
                val result = sleepRepository.saveSleepRecord(firebaseRecord)
                if (result.isSuccess) {
                    Log.d(TAG, "Sleep tracking started")
                    // Invalidate cache to ensure fresh data
                    sleepCache?.invalidateCache(userId)
                    loadUserData(userId, false)
                    
                    // Update widget
                    context?.let { 
                        com.example.coupleapp.widget.WidgetManager.onSleepDataUpdated(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting sleep tracking", e)
            }
        }
    }

    /**
     * End sleep tracking
     */
    fun endSleepTracking() {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                val currentRecord = _uiState.value.sleepRecord ?: return@launch
                val now = LocalTime.now()
                
                // Calculate duration
                val bedTime = currentRecord.bedTime
                val duration = java.time.Duration.between(bedTime, now).toMinutes().toInt()
                
                // Calculate quality
                val (quality, percentage) = sleepRepository.calculateSleepQuality(
                    duration,
                    currentRecord.targetSleepDuration
                )
                
                val updatedRecord = FirebaseSleepRecord(
                    id = currentRecord.id,
                    userId = userId,
                    coupleId = "",
                    date = Timestamp(Date.from(currentRecord.date.atZone(ZoneId.systemDefault()).toInstant())),
                    bedTimeHour = bedTime.hour,
                    bedTimeMinute = bedTime.minute,
                    wakeUpTimeHour = now.hour,
                    wakeUpTimeMinute = now.minute,
                    actualSleepDurationMinutes = duration,
                    targetSleepDurationMinutes = currentRecord.targetSleepDuration,
                    awakeDurationMinutes = currentRecord.sleepStages.awakeDurationMinutes,
                    sleepDurationMinutes = duration - currentRecord.sleepStages.awakeDurationMinutes,
                    quality = quality.name,
                    achievementPercentage = percentage
                )
                
                val result = sleepRepository.saveSleepRecord(updatedRecord)
                if (result.isSuccess) {
                    Log.d(TAG, "Sleep tracking ended")
                    // Invalidate cache to ensure fresh data
                    sleepCache?.invalidateCache(userId)
                    loadUserData(userId, false)
                    
                    // Update widget
                    context?.let { 
                        com.example.coupleapp.widget.WidgetManager.onSleepDataUpdated(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending sleep tracking", e)
            }
        }
    }

    fun getActiveUser(): UserProfile {
        return if (_uiState.value.isCurrentUser) {
            _uiState.value.currentUser
        } else {
            _uiState.value.partnerUser
        }
    }

    fun showBottomSheet(show: Boolean) {
        _uiState.update { it.copy(showBottomSheet = show) }
    }

    fun showTimeEditor(show: Boolean, type: TimeEditorType? = null) {
        _uiState.update { it.copy(
            showTimeEditor = show,
            timeEditorType = type ?: TimeEditorType.NONE
        )}
    }

    fun dismissBedtimeReminder() {
        _uiState.update { it.copy(showBedtimeReminder = false) }
    }

    fun updateWidgets(context: Context) {
        // TODO: Implement widget update if needed
        Log.d(TAG, "Widget update requested")
    }
    
    /**
     * Insert mock sleep data for testing (includes current user and partner)
     */
    fun insertMockSleepData() {
        viewModelScope.launch {
            try {
                val currentUserId = _uiState.value.currentUser.id
                val partnerId = _uiState.value.partnerUser.id.takeIf { it.isNotEmpty() }
                
                Log.d(TAG, "insertMockSleepData: Current user=$currentUserId, Partner=$partnerId")
                
                _uiState.update { it.copy(
                    isLoading = true,
                    healthConnectSyncStatus = "Inserting mock data..."
                ) }
                
                val result = sleepRepository.insertMockSleepData(currentUserId, partnerId)
                
                if (result.isSuccess) {
                    Log.d(TAG, "insertMockSleepData: Success! Reloading data...")
                    
                    val partnerText = if (partnerId != null) " and partner" else ""
                    _uiState.update { it.copy(
                        healthConnectSyncStatus = "Mock data inserted for you${partnerText}! (7 days)"
                    ) }
                    
                    // Reload data after insertion
                    loadUserData(getActiveUser().id, false)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "insertMockSleepData: Failed - $error")
                    _uiState.update { it.copy(
                        isLoading = false,
                        healthConnectSyncStatus = "Failed to insert mock data: $error"
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "insertMockSleepData: Error", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    healthConnectSyncStatus = "Error: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Check if Health Connect is available
     */
    suspend fun checkHealthConnectAvailability(): Boolean {
        return try {
            sleepRepository.isHealthConnectAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect availability", e)
            false
        }
    }
    
    /**
     * Get Health Connect permission contract for activity result launcher
     */
    fun getHealthConnectPermissionContract(): androidx.activity.result.contract.ActivityResultContract<Set<String>, Set<String>> {
        return sleepRepository.getHealthConnectPermissionContract()
    }
    
    /**
     * Get required Health Connect permissions
     */
    fun getRequiredHealthConnectPermissions(): Set<String> {
        return sleepRepository.getRequiredHealthConnectPermissions()
    }
    
    /**
     * Check if Health Connect permissions are granted
     */
    suspend fun hasHealthConnectPermissions(): Boolean {
        return try {
            sleepRepository.hasHealthConnectPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Health Connect permissions", e)
            false
        }
    }
    
    /**
     * Sync sleep data from Health Connect to Firebase
     */
    fun syncFromHealthConnect() {
        viewModelScope.launch {
            try {
                val userId = getActiveUser().id
                Log.d(TAG, "syncFromHealthConnect: Starting sync for user=$userId")
                
                _uiState.update { it.copy(isLoading = true, healthConnectSyncStatus = "Syncing...") }
                
                val result = sleepRepository.syncSleepDataFromHealthConnect(userId)
                
                if (result.isSuccess) {
                    val syncCount = result.getOrNull() ?: 0
                    Log.d(TAG, "syncFromHealthConnect: Successfully synced $syncCount records")
                    _uiState.update { it.copy(healthConnectSyncStatus = "Synced $syncCount records") }
                    
                    // Reload data after sync
                    loadUserData(userId, false)
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "syncFromHealthConnect: Failed - $error")
                    _uiState.update { it.copy(
                        isLoading = false,
                        healthConnectSyncStatus = "Sync failed: $error"
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncFromHealthConnect: Error", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    healthConnectSyncStatus = "Sync error: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Clear Health Connect sync status message
     */
    fun clearSyncStatus() {
        _uiState.update { it.copy(healthConnectSyncStatus = null) }
    }
    
    /**
     * Check and perform auto-sync daily
     */
    private fun checkAndAutoSync() {
        viewModelScope.launch {
            val currentUser = auth.currentUser ?: return@launch
            
            // Trigger Google Sleep API sync immediately (like Widgetable)
            context?.let { ctx ->
                com.example.coupleapp.worker.GoogleSleepSyncWorker.triggerImmediateSync(ctx)
            }
            
            tryAutoSync(currentUser.uid)
        }
    }

    /**
     * Try to auto-sync yesterday's sleep data if needed
     */
    private suspend fun tryAutoSync(userId: String) {
        try {
            val ctx = context ?: run {
                Log.w(TAG, "tryAutoSync: Context is null")
                return
            }
            
            val shouldSync = sleepRepository.shouldAutoSync(userId)
            
            if (shouldSync) {
                Log.d(TAG, "tryAutoSync: Attempting auto-sync for yesterday's data")
                
                val healthManager = com.example.coupleapp.data.health.HealthConnectManager(ctx)
                val result = sleepRepository.autoSyncYesterdaySleepData(userId, healthManager)
                
                if (result.isSuccess && result.getOrNull() == true) {
                    sleepRepository.updateLastAutoSyncTime(userId)
                    Log.d(TAG, "tryAutoSync: Auto-sync completed successfully")
                    
                    // Refresh UI to show new data
                    loadUserData(userId, false)
                } else {
                    Log.d(TAG, "tryAutoSync: No data to sync or sync skipped")
                }
            } else {
                Log.d(TAG, "tryAutoSync: Auto-sync not needed (already synced today)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryAutoSync: Error during auto-sync", e)
        }
    }
    
    // ========== Manual Sleep Tracking ==========
    
    /**
     * Check if user has an active sleep session
     * Called on init and when app resumes from background
     */
    private fun checkActiveSleepSession() {
        viewModelScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: run {
                    Log.w(TAG, "checkActiveSleepSession: User not logged in")
                    return@launch
                }
                
                Log.d(TAG, "checkActiveSleepSession: Checking for user $userId")
                
                val result = sleepRepository.getActiveSleepSession(userId)
                val session = result.getOrNull()
                
                if (session != null && session.isActive) {
                    val startTime = session.startTime?.toDate()
                    val sleepDurationMinutes = if (startTime != null) {
                        java.time.Duration.between(startTime.toInstant(), java.time.Instant.now()).toMinutes()
                    } else 0L
                    
                    Log.d(TAG, "checkActiveSleepSession: Found active session started at ${session.startTime}, duration=${sleepDurationMinutes} mins")
                    
                    // Only show WakeUpDialog if user has been sleeping for at least 2 hours (120 minutes)
                    val minimumSleepMinutes = 120L
                    val shouldShowWakeUp = sleepDurationMinutes >= minimumSleepMinutes
                    
                    Log.d(TAG, "checkActiveSleepSession: shouldShowWakeUp=$shouldShowWakeUp (need >= ${minimumSleepMinutes} mins, have $sleepDurationMinutes mins)")
                    
                    _uiState.update { it.copy(
                        activeSleepSession = session,
                        showWakeUpDialog = shouldShowWakeUp,
                        showBedtimeReminder = false // Don't show bedtime reminder if already sleeping
                    ) }
                } else {
                    Log.d(TAG, "checkActiveSleepSession: No active session found")
                    _uiState.update { it.copy(
                        activeSleepSession = null,
                        showWakeUpDialog = false
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkActiveSleepSession: Error", e)
            }
        }
    }
    
    /**
     * Start manual sleep tracking (called when user presses "Sleep now")
     */
    fun startManualSleepTracking() {
        viewModelScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: run {
                    Log.e(TAG, "startManualSleepTracking: User not logged in")
                    return@launch
                }
                
                Log.d(TAG, "startManualSleepTracking: Starting for user $userId at ${LocalTime.now()}")
                
                val result = sleepRepository.startManualSleepTracking(userId)
                
                if (result.isSuccess) {
                    Log.d(TAG, "startManualSleepTracking: Success - session created")
                    
                    // Create local session immediately (don't wait for Firestore read)
                    val localSession = FirebaseActiveSleepSession(
                        id = userId,
                        userId = userId,
                        startTime = com.google.firebase.Timestamp.now(),
                        isActive = true
                    )
                    
                    // Update UI immediately with the local session
                    _uiState.update { it.copy(
                        showBedtimeReminder = false,
                        activeSleepSession = localSession,
                        showWakeUpDialog = false, // Don't show wake up dialog immediately after starting
                        healthConnectSyncStatus = "Sleep tracking started! Good night 🌙"
                    ) }
                    
                    Log.d(TAG, "startManualSleepTracking: UI updated with local session, skipping Firestore re-read")
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "startManualSleepTracking: Failed - $errorMsg")
                    _uiState.update { it.copy(
                        healthConnectSyncStatus = "Failed to start tracking: $errorMsg"
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startManualSleepTracking: Error", e)
                _uiState.update { it.copy(
                    healthConnectSyncStatus = "Error: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * End manual sleep tracking (called when user presses "I'm awake!")
     */
    fun endManualSleepTracking() {
        viewModelScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: run {
                    Log.e(TAG, "endManualSleepTracking: User not logged in")
                    return@launch
                }
                
                Log.d(TAG, "endManualSleepTracking: Ending for user $userId at ${LocalTime.now()}")
                
                val result = sleepRepository.endManualSleepTracking(userId)
                
                if (result.isSuccess) {
                    val record = result.getOrNull()
                    val hours = (record?.actualSleepDurationMinutes ?: 0) / 60
                    val mins = (record?.actualSleepDurationMinutes ?: 0) % 60
                    Log.d(TAG, "endManualSleepTracking: Success - recorded ${hours}h ${mins}m, quality=${record?.quality}")
                    
                    // Clear active session
                    _uiState.update { it.copy(
                        activeSleepSession = null,
                        showWakeUpDialog = false,
                        healthConnectSyncStatus = "Sleep recorded: ${hours}h ${mins}m! ☀️"
                    ) }
                    
                    // Reload data to show new record
                    loadUserData(userId, false)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "endManualSleepTracking: Failed - $errorMsg")
                    _uiState.update { it.copy(
                        healthConnectSyncStatus = "Failed to record sleep: $errorMsg"
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "endManualSleepTracking: Error", e)
                _uiState.update { it.copy(
                    healthConnectSyncStatus = "Error: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Dismiss wake up dialog
     */
    fun dismissWakeUpDialog() {
        _uiState.update { it.copy(showWakeUpDialog = false) }
    }
    
    // ========== Google Sleep API ==========
    
    /**
     * Check Google Sleep API status from SharedPreferences
     * Also auto-enables Google Sleep API if user has Activity Recognition permission
     * but hasn't explicitly enabled it yet (as fallback for manual tracking)
     */
    private fun checkGoogleSleepApiStatus() {
        viewModelScope.launch {
            try {
                // Check saved preference first
                val savedEnabled = prefs?.getBoolean(PREF_GOOGLE_SLEEP_API_ENABLED, false) ?: false
                // Also verify PendingIntent exists
                val isRegistered = googleSleepApiManager?.isSleepTrackingRegistered() ?: false
                // Check if has permission
                val hasPermission = googleSleepApiManager?.hasActivityRecognitionPermission() ?: false
                
                Log.d(TAG, "checkGoogleSleepApiStatus: savedEnabled=$savedEnabled, isRegistered=$isRegistered, hasPermission=$hasPermission")
                
                when {
                    // Case 1: Saved as enabled but not registered -> re-register
                    savedEnabled && !isRegistered -> {
                        Log.d(TAG, "checkGoogleSleepApiStatus: Re-registering Google Sleep API")
                        val result = googleSleepApiManager?.registerSleepUpdates()
                        if (result?.isSuccess == true) {
                            _uiState.update { it.copy(isGoogleSleepApiEnabled = true) }
                            Log.d(TAG, "checkGoogleSleepApiStatus: Re-registration successful")
                        } else {
                            // Registration failed, update preference
                            prefs?.edit()?.putBoolean(PREF_GOOGLE_SLEEP_API_ENABLED, false)?.apply()
                            _uiState.update { it.copy(isGoogleSleepApiEnabled = false) }
                            Log.w(TAG, "checkGoogleSleepApiStatus: Re-registration failed")
                        }
                    }
                    
                    // Case 2: Has permission but not enabled -> auto-enable as fallback
                    hasPermission && !isRegistered && !savedEnabled -> {
                        Log.d(TAG, "checkGoogleSleepApiStatus: Auto-enabling Google Sleep API as fallback")
                        val result = googleSleepApiManager?.registerSleepUpdates()
                        if (result?.isSuccess == true) {
                            prefs?.edit()?.putBoolean(PREF_GOOGLE_SLEEP_API_ENABLED, true)?.apply()
                            _uiState.update { it.copy(isGoogleSleepApiEnabled = true) }
                            Log.d(TAG, "checkGoogleSleepApiStatus: Auto-enable successful")
                        } else {
                            Log.w(TAG, "checkGoogleSleepApiStatus: Auto-enable failed")
                        }
                    }
                    
                    // Case 3: Already registered
                    else -> {
                        _uiState.update { it.copy(isGoogleSleepApiEnabled = savedEnabled && isRegistered) }
                    }
                }
                
                Log.d(TAG, "checkGoogleSleepApiStatus: Google Sleep API enabled = ${_uiState.value.isGoogleSleepApiEnabled}")
            } catch (e: Exception) {
                Log.e(TAG, "checkGoogleSleepApiStatus: Error", e)
            }
        }
    }
    
    /**
     * Enable Google Sleep API
     */
    fun enableGoogleSleepApi() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "enableGoogleSleepApi: Registering...")
                
                if (googleSleepApiManager?.hasActivityRecognitionPermission() == false) {
                    Log.e(TAG, "enableGoogleSleepApi: Missing permission")
                    _uiState.update { it.copy(
                        healthConnectSyncStatus = "Activity recognition permission required",
                        needsActivityRecognitionPermission = true
                    ) }
                    return@launch
                }
                
                val result = googleSleepApiManager?.registerSleepUpdates()
                
                if (result?.isSuccess == true) {
                    Log.d(TAG, "enableGoogleSleepApi: Success")
                    // Save to SharedPreferences
                    prefs?.edit()?.putBoolean(PREF_GOOGLE_SLEEP_API_ENABLED, true)?.apply()
                    _uiState.update { it.copy(
                        isGoogleSleepApiEnabled = true,
                        healthConnectSyncStatus = "Google Sleep API enabled"
                    ) }
                } else {
                    val errorMsg = result?.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "enableGoogleSleepApi: Failed - $errorMsg")
                    _uiState.update { it.copy(
                        healthConnectSyncStatus = "Failed to enable Google Sleep API: $errorMsg"
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "enableGoogleSleepApi: Error", e)
                _uiState.update { it.copy(
                    healthConnectSyncStatus = "Error: ${e.message}"
                ) }
            }
        }
    }
    
    /**
     * Disable Google Sleep API
     */
    fun disableGoogleSleepApi() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "disableGoogleSleepApi: Unregistering...")
                
                val result = googleSleepApiManager?.unregisterSleepUpdates()
                
                if (result?.isSuccess == true) {
                    Log.d(TAG, "disableGoogleSleepApi: Success")
                    // Save to SharedPreferences
                    prefs?.edit()?.putBoolean(PREF_GOOGLE_SLEEP_API_ENABLED, false)?.apply()
                    _uiState.update { it.copy(
                        isGoogleSleepApiEnabled = false,
                        healthConnectSyncStatus = "Google Sleep API disabled"
                    ) }
                } else {
                    Log.e(TAG, "disableGoogleSleepApi: Failed - ${result?.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "disableGoogleSleepApi: Error", e)
            }
        }
    }
    
    /**
     * Refresh sleep session state (called when app comes to foreground)
     */
    fun refreshSleepState() {
        Log.d(TAG, "refreshSleepState: Checking active session and bedtime")
        checkActiveSleepSession()
        checkBedtimeOnResume()
    }
    
    /**
     * Check bedtime when app resumes
     */
    private fun checkBedtimeOnResume() {
        val bedTime = _uiState.value.settings?.idealBedTime ?: return
        checkBedtimeReminder(bedTime)
    }
}

enum class TimeEditorType {
    NONE,
    BED_TIME,
    WAKE_UP_TIME,
    SLEEP_GOAL
}

data class SleepTrackerUiState(
    val isLoading: Boolean = false,
    val currentUser: UserProfile = UserProfile("", "", null),
    val partnerUser: UserProfile = UserProfile("", "", null),
    val isCurrentUser: Boolean = true,
    val sleepRecord: SleepRecord? = null,
    val sleepHistory: List<SleepRecord> = emptyList(),
    val settings: SleepSettings? = null,
    val showBottomSheet: Boolean = false,
    val showTimeEditor: Boolean = false,
    val timeEditorType: TimeEditorType = TimeEditorType.NONE,
    val showBedtimeReminder: Boolean = false,
    val showWakeUpDialog: Boolean = false,
    val showWidgetInstructions: Boolean = false,
    val healthConnectSyncStatus: String? = null,
    val activeSleepSession: FirebaseActiveSleepSession? = null,
    val isGoogleSleepApiEnabled: Boolean = false,
    val needsActivityRecognitionPermission: Boolean = false,
    val sleepDataStatus: SleepDataStatus = SleepDataStatus.UNKNOWN
) {
    val isContentReady: Boolean
        get() = !isLoading && sleepRecord != null
}

/**
 * Status indicating the availability of sleep data
 */
enum class SleepDataStatus {
    UNKNOWN,           // Initial state
    HAS_DATA,          // Data is available
    NO_DATA_EVER,      // User has never tracked sleep
    WAITING_TODAY,     // Waiting for today's data (early morning)
    DATA_DELAYED       // Data should have arrived but is late (past noon)
}

fun SleepTrackerUiState.getActiveUserProfile(): UserProfile {
    return if (isCurrentUser) currentUser else partnerUser
}

fun SleepTrackerUiState.hasData(): Boolean {
    return sleepRecord != null && sleepHistory.isNotEmpty()
}
