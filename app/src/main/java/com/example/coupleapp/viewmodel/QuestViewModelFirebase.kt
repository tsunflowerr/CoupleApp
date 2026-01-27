package com.example.coupleapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.CoupleApplication
import com.example.coupleapp.data.model.*
import com.example.coupleapp.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Firebase-integrated ViewModel for Quest Screen
 * Syncs with user_wallets collection for coin balance
 * Saves quest progress to Firebase
 * 
 * Uses Cache-First Strategy:
 * 1. On init: Load cached data immediately (instant UI)
 * 2. Background refresh: Load fresh data from Firebase
 * 3. Cache duration: 5 minutes (quest data changes frequently)
 */
class QuestViewModelFirebase : ViewModel() {

    // Initialize repositories inside class to avoid factory issues
    private val authRepository = FirebaseAuthRepository()
    private val firestoreRepository = FirebaseFirestoreRepository()
    private val questCache = QuestCacheRepository.getInstance()
    private val storeCache = StoreCacheRepository.getInstance() // For invalidating store cache on reward claim

    private val _uiState = MutableStateFlow(QuestUiState())
    val uiState: StateFlow<QuestUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateFormatDisplay = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private val TAG = "QuestViewModelFirebase"

    init {
        Log.d(TAG, "Initializing QuestViewModelFirebase")
        loadQuestDataWithCache()
    }
    
    /**
     * Load quest data with cache-first strategy
     */
    private fun loadQuestDataWithCache() {
        viewModelScope.launch {
            try {
                val userId = authRepository.currentUser?.uid
                if (userId == null) {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Vui lòng đăng nhập") }
                    return@launch
                }
                
                // Try to load from cache first
                val hasCached = questCache.hasCachedData(userId)
                val isCacheFresh = questCache.isQuestCacheFresh(userId)
                
                if (hasCached) {
                    Log.d(TAG, "📦 Cache found! Loading from cache first...")
                    val cachedData = questCache.getCachedQuestData(userId)
                    val cachedCoins = questCache.getCachedCoins(userId)
                    val cachedStreak = questCache.getCachedStreakInfo(userId)
                    
                    if (cachedData != null) {
                        // Show cached data immediately
                        val today = Date()
                        val todayDisplay = dateFormatDisplay.format(today)
                        
                        // Convert cached quests back to domain models
                        val quests = cachedData.quests.map { it.toQuest() }
                        val specialQuest = cachedData.specialQuest?.toQuest()
                        val summary = cachedData.dailySummary.toDailySummary()
                        
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                quests = quests,
                                specialQuest = specialQuest,
                                dailySummary = summary,
                                userCoins = cachedCoins ?: 0,
                                currentStreak = cachedStreak?.currentStreak ?: 0,
                                longestStreak = cachedStreak?.longestStreak ?: 0,
                                missedDays = cachedStreak?.missedDays ?: 0,
                                lastClaimDate = cachedStreak?.lastClaimDate,
                                todayDate = todayDisplay,
                                isLinkedWithPartner = cachedData.isLinkedWithPartner
                            )
                        }
                        Log.d(TAG, "✅ UI updated from cache")
                        
                        // Refresh in background if cache is stale
                        if (!isCacheFresh) {
                            Log.d(TAG, "🔄 Cache is stale, refreshing in background...")
                            loadQuestDataFromFirebase(showLoading = false)
                        }
                    } else {
                        // Cache parsing failed, load from Firebase
                        _uiState.update { it.copy(isLoading = true) }
                        loadQuestData()
                    }
                } else {
                    Log.d(TAG, "🌐 No cache, loading from Firebase...")
                    _uiState.update { it.copy(isLoading = true) }
                    loadQuestData()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadQuestDataWithCache", e)
                _uiState.update { it.copy(isLoading = true) }
                loadQuestData()
            }
        }
    }
    
    /**
     * Load quest data from Firebase (background refresh)
     */
    private fun loadQuestDataFromFirebase(showLoading: Boolean) {
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }
        loadQuestData()
    }

    /**
     * Load quest data including daily quests and user progress from Firebase
     */
    private fun loadQuestData() {
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val userId = authRepository.currentUser?.uid
                if (userId == null) {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Vui lòng đăng nhập"
                        )
                    }
                    return@launch
                }

                Log.d(TAG, "Loading quest data for userId: $userId")

                // Load user wallet from Firebase
                val userCoins = loadUserCoinsFromFirebase(userId)
                Log.d(TAG, "Loaded user coins: $userCoins")

                // Load quest progress from Firebase
                val questProgress = loadQuestProgressFromFirebase(userId)
                Log.d(TAG, "Loaded quest progress: ${questProgress.size} quests")

                // Generate today's quests
                val today = Date()
                val todayString = dateFormat.format(today)
                val todayDisplay = dateFormatDisplay.format(today)

                // Use today's date as seed for consistent daily quests
                val seed = todayString.replace("-", "").toLong()

                // Select 5 random quests from pool with saved progress
                var dailyQuests = selectDailyQuestsWithProgress(seed, questProgress)
                Log.d(TAG, "Generated ${dailyQuests.size} daily quests")
                
                // Check if daily reward can be claimed (prevent exploit)
                val canClaimToday = canClaimDailyReward(userId)
                Log.d(TAG, "[DAILY CHECK] Can claim daily reward today: $canClaimToday")
                
                // Handle login quest status carefully to prevent exploit
                val loginQuest = dailyQuests.find { it.type == QuestType.DAILY_LOGIN }
                val savedLoginProgress = questProgress[loginQuest?.id]
                
                Log.d(TAG, "[DAILY] Login quest saved progress: status=${savedLoginProgress?.status}, canClaimToday=$canClaimToday")
                
                if (loginQuest != null) {
                    // PRIORITY 1: If saved progress shows CLAIMED, ALWAYS respect it (prevents exploit)
                    if (savedLoginProgress?.status == QuestStatus.CLAIMED) {
                        Log.d(TAG, "[DAILY] Login quest: saved as CLAIMED - respecting saved status")
                        dailyQuests = dailyQuests.map { quest ->
                            if (quest.type == QuestType.DAILY_LOGIN) {
                                quest.copy(currentProgress = 1, status = QuestStatus.CLAIMED)
                            } else {
                                quest
                            }
                        }
                    }
                    // PRIORITY 2: If wallet says already claimed today, mark as CLAIMED
                    else if (!canClaimToday) {
                        Log.d(TAG, "[DAILY] Login quest: wallet shows already claimed today - marking as CLAIMED")
                        dailyQuests = dailyQuests.map { quest ->
                            if (quest.type == QuestType.DAILY_LOGIN) {
                                quest.copy(currentProgress = 1, status = QuestStatus.CLAIMED)
                            } else {
                                quest
                            }
                        }
                        // Also save to Firebase to fix any missing quest_progress records
                        val loginToSave = loginQuest.copy(currentProgress = 1, status = QuestStatus.CLAIMED)
                        saveQuestProgressToFirebase(loginToSave)
                    }
                    // PRIORITY 3: Not claimed today - mark as COMPLETED so user can claim
                    else {
                        Log.d(TAG, "[DAILY] Login quest: not claimed yet - marking as COMPLETED")
                        dailyQuests = dailyQuests.map { quest ->
                            if (quest.type == QuestType.DAILY_LOGIN) {
                                quest.copy(currentProgress = 1, status = QuestStatus.COMPLETED)
                            } else {
                                quest
                            }
                        }
                    }
                }

                // Check partner link status
                val isLinked = checkPartnerLinkStatus(userId)
                Log.d(TAG, "[QUEST] Partner link status: $isLinked")

                // Get special quest - show if not linked OR if linked but not claimed yet
                val specialProgress = questProgress["link_partner"]
                Log.d(TAG, "[QUEST] Special quest progress from Firebase: currentProgress=${specialProgress?.currentProgress}, status=${specialProgress?.status}")
                
                val specialQuest = if (!isLinked) {
                    // Not linked yet - show quest with saved progress
                    Log.d(TAG, "[QUEST] User not linked - showing special quest as NOT_STARTED or saved status")
                    QuestPool.getSpecialLinkPartnerQuest().copy(
                        currentProgress = specialProgress?.currentProgress ?: 0,
                        status = specialProgress?.status ?: QuestStatus.NOT_STARTED
                    )
                } else {
                    // Already linked - check if already claimed
                    if (specialProgress?.status == QuestStatus.CLAIMED) {
                        // Already claimed - hide quest
                        Log.d(TAG, "[QUEST] User linked and quest already claimed - hiding quest")
                        null
                    } else {
                        // Linked but not claimed yet - show as completed so user can claim
                        Log.d(TAG, "[QUEST] User linked but quest not claimed - showing as COMPLETED")
                        QuestPool.getSpecialLinkPartnerQuest().copy(
                            currentProgress = 1,
                            status = QuestStatus.COMPLETED
                        )
                    }
                }
                
                Log.d(TAG, "[QUEST] Final special quest: ${if (specialQuest == null) "null (hidden)" else "visible with status ${specialQuest.status}"}")

                // Check if bonus can be claimed today (prevent re-claiming)
                val canClaimBonus = canClaimBonusToday(userId)
                Log.d(TAG, "[BONUS CHECK] Can claim bonus today: $canClaimBonus")

                // Calculate summary with bonus claim status
                val summary = calculateDailySummary(dailyQuests, canClaimBonus)
                Log.d(TAG, "Daily summary: ${summary.completedQuests}/${summary.totalQuests} completed, bonusUnlocked=${summary.bonusRewardUnlocked}")

                // Load streak info (includes current streak, longest streak, missed days)
                val streakInfo = loadStreakInfoFromFirebase(userId)
                Log.d(TAG, "Streak info: current=${streakInfo.currentStreak}, longest=${streakInfo.longestStreak}, missed=${streakInfo.missedDays}")

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        quests = dailyQuests,
                        specialQuest = specialQuest,
                        dailySummary = summary,
                        userCoins = userCoins,
                        currentStreak = streakInfo.currentStreak,
                        longestStreak = streakInfo.longestStreak,
                        missedDays = streakInfo.missedDays,
                        lastClaimDate = streakInfo.lastClaimDate,
                        todayDate = todayDisplay,
                        isLinkedWithPartner = isLinked
                    )
                }
                
                // Cache the loaded data
                try {
                    val todayString = dateFormat.format(Date())
                    val cachedQuestData = CachedQuestData(
                        cacheDate = todayString,
                        quests = dailyQuests.map { it.toCachedQuest() },
                        specialQuest = specialQuest?.toCachedQuest(),
                        dailySummary = summary.toCachedDailySummary(),
                        isLinkedWithPartner = isLinked
                    )
                    questCache.cacheQuestData(userId, cachedQuestData)
                    questCache.cacheCoins(userId, userCoins)
                    val cachedStreakInfo = CachedStreakInfo(
                        currentStreak = streakInfo.currentStreak,
                        longestStreak = streakInfo.longestStreak,
                        missedDays = streakInfo.missedDays,
                        lastClaimDate = streakInfo.lastClaimDate
                    )
                    questCache.cacheStreakInfo(userId, cachedStreakInfo)
                    Log.d(TAG, "💾 Quest data cached successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cache quest data", e)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load quest data", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Không thể tải dữ liệu nhiệm vụ: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Load user coins from Firebase user_wallets collection
     */
    private suspend fun loadUserCoinsFromFirebase(userId: String): Int {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            result.fold(
                onSuccess = { wallet ->
                    if (wallet == null) {
                        Log.d(TAG, "[QUEST] No wallet found at document: user_wallets/$userId")
                        Log.d(TAG, "[QUEST] Creating default wallet with 1000 coins")
                        // Create default wallet with 1000 coins
                        createDefaultWallet(userId)
                        1000
                    } else {
                        Log.d(TAG, "[QUEST] Wallet loaded from user_wallets/$userId")
                        Log.d(TAG, "[QUEST] Wallet has ${wallet.coins} coins")
                        
                        // Migrate old wallets: add streak fields if missing
                        if (wallet.lastQuestClaimDate == null && wallet.currentStreak == 0 && wallet.longestStreak == 0) {
                            Log.d(TAG, "[QUEST] Old wallet detected, will initialize streak fields on first claim")
                        }
                        
                        wallet.coins
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading wallet: ${e.message}")
                    // Try to create default wallet
                    createDefaultWallet(userId)
                    1000
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading user coins", e)
            1000 // Default value
        }
    }

    /**
     * Create default wallet with 1000 coins
     */
    private suspend fun createDefaultWallet(userId: String): Boolean {
        return try {
            val wallet = FirebaseUserWallet(
                id = userId,
                userId = userId,
                coins = 1000,
                freeCoins = 0,
                lastFreeGiftDate = null,
                lastQuestClaimDate = null,
                currentStreak = 0,
                longestStreak = 0,
                updatedAt = Date()
            )
            
            val result = firestoreRepository.setDocument(
                collection = "user_wallets",
                documentId = userId,
                data = wallet,
                merge = false
            )

            result.fold(
                onSuccess = {
                    Log.d(TAG, "Created default wallet for user: $userId")
                    true
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to create default wallet: ${e.message}")
                    false
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating default wallet", e)
            false
        }
    }

    /**
     * Load quest progress from Firebase
     * NOTE: Special quests (like link_partner) are loaded from ANY date since they should only be claimable once ever
     * Daily quests are filtered to today's progress only
     */
    private suspend fun loadQuestProgressFromFirebase(userId: String): Map<String, SavedQuestProgress> {
        return try {
            val todayString = dateFormat.format(Date())
            
            val result = firestoreRepository.queryDocuments(
                collection = "quest_progress",
                field = "userId",
                value = userId,
                clazz = FirebaseQuestProgress::class.java
            )

            result.fold(
                onSuccess = { allProgress ->
                    // Special quests IDs that should only be claimable ONCE ever (not reset daily)
                    val oneTimeQuestIds = setOf("link_partner")
                    
                    // Filter: today's progress for daily quests, ANY date for special quests
                    val relevantProgress = allProgress.filter { progress ->
                        if (oneTimeQuestIds.contains(progress.questId)) {
                            // Special quest: include from any date (most recent status wins)
                            true
                        } else {
                            // Daily quest: only include today's progress
                            progress.date == todayString
                        }
                    }
                    
                    // For special quests, we might have multiple entries from different dates
                    // We need to check if ANY of them is CLAIMED
                    val progressMap = mutableMapOf<String, SavedQuestProgress>()
                    
                    for (progress in relevantProgress) {
                        val existingProgress = progressMap[progress.questId]
                        val currentStatus = try { QuestStatus.valueOf(progress.status) } catch (e: Exception) { QuestStatus.NOT_STARTED }
                        
                        if (existingProgress == null) {
                            progressMap[progress.questId] = SavedQuestProgress(
                                currentProgress = progress.currentProgress,
                                status = currentStatus
                            )
                        } else {
                            // For special quests: if any record shows CLAIMED, mark as CLAIMED
                            if (oneTimeQuestIds.contains(progress.questId) && currentStatus == QuestStatus.CLAIMED) {
                                progressMap[progress.questId] = SavedQuestProgress(
                                    currentProgress = progress.currentProgress,
                                    status = QuestStatus.CLAIMED
                                )
                            }
                        }
                    }
                    
                    Log.d(TAG, "Loaded ${progressMap.size} quest progress (including special quests from any date)")
                    progressMap
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading quest progress: ${e.message}")
                    emptyMap()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading quest progress", e)
            emptyMap()
        }
    }

    /**
     * Save quest progress to Firebase
     * NOTE: Special quests (like link_partner) use a date-independent document ID
     * so their CLAIMED status persists forever and is not reset daily
     */
    private suspend fun saveQuestProgressToFirebase(quest: Quest) {
        val userId = authRepository.currentUser?.uid ?: return
        val todayString = dateFormat.format(Date())
        
        // Special quests IDs that should only be claimable ONCE ever (not reset daily)
        val oneTimeQuestIds = setOf("link_partner")
        val isOneTimeQuest = oneTimeQuestIds.contains(quest.id)
        
        try {
            val progressData = FirebaseQuestProgress(
                userId = userId,
                questId = quest.id,
                questType = quest.type.name,
                date = if (isOneTimeQuest) "permanent" else todayString, // Use "permanent" for one-time quests
                currentProgress = quest.currentProgress,
                targetProgress = quest.targetProgress,
                status = quest.status.name
            )

            // For one-time quests, use a date-independent document ID so it doesn't reset daily
            val documentId = if (isOneTimeQuest) {
                "${userId}_${quest.id}_permanent"
            } else {
                "${userId}_${quest.id}_$todayString"
            }
            
            val result = firestoreRepository.setDocument(
                collection = "quest_progress",
                documentId = documentId,
                data = progressData,
                merge = true
            )

            result.fold(
                onSuccess = {
                    Log.d(TAG, "Saved quest progress for ${quest.id} (oneTime=$isOneTimeQuest)")
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to save quest progress: ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving quest progress", e)
        }
    }

    /**
     * Streak information loaded from Firebase
     */
    private data class StreakInfo(
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val missedDays: Int = 0,
        val lastClaimDate: String? = null
    )

    /**
     * Load streak info from Firebase wallet
     * Returns StreakInfo with current streak, longest streak, missed days, and last claim date
     */
    private suspend fun loadStreakInfoFromFirebase(userId: String): StreakInfo {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            result.fold(
                onSuccess = { wallet ->
                    if (wallet == null) {
                        Log.d(TAG, "[STREAK] No wallet found, returning default StreakInfo")
                        return@fold StreakInfo()
                    }
                    
                    val todayString = dateFormat.format(Date())
                    val lastClaimDate = wallet.lastQuestClaimDate
                    val savedCurrentStreak = wallet.getCurrentStreakSafe()
                    val savedLongestStreak = wallet.getLongestStreakSafe()
                    
                    Log.d(TAG, "[STREAK] Today: $todayString, LastClaim: $lastClaimDate, CurrentStreak: $savedCurrentStreak, LongestStreak: $savedLongestStreak")
                    
                    if (lastClaimDate == null) {
                        // First time user - no streak yet
                        return@fold StreakInfo(
                            currentStreak = 0,
                            longestStreak = savedLongestStreak,
                            missedDays = 0,
                            lastClaimDate = null
                        )
                    }
                    
                    // Calculate streak status and missed days
                    try {
                        val lastDate = java.time.LocalDate.parse(lastClaimDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        val today = java.time.LocalDate.now()
                        val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(lastDate, today)
                        
                        Log.d(TAG, "[STREAK] Days since last claim: $daysDiff")
                        
                        when {
                            daysDiff == 0L -> {
                                // Same day - streak is current
                                StreakInfo(
                                    currentStreak = savedCurrentStreak,
                                    longestStreak = savedLongestStreak,
                                    missedDays = 0,
                                    lastClaimDate = lastClaimDate
                                )
                            }
                            daysDiff == 1L -> {
                                // Consecutive day - streak continues
                                StreakInfo(
                                    currentStreak = savedCurrentStreak,
                                    longestStreak = savedLongestStreak,
                                    missedDays = 0,
                                    lastClaimDate = lastClaimDate
                                )
                            }
                            else -> {
                                // Missed days - streak broken
                                val missedDays = (daysDiff - 1).toInt().coerceAtLeast(0)
                                Log.d(TAG, "[STREAK] Streak broken! Missed $missedDays days")
                                StreakInfo(
                                    currentStreak = 0,  // Streak reset
                                    longestStreak = savedLongestStreak,
                                    missedDays = missedDays,
                                    lastClaimDate = lastClaimDate
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[STREAK] Error parsing date", e)
                        StreakInfo(currentStreak = 0, longestStreak = savedLongestStreak, lastClaimDate = lastClaimDate)
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "[STREAK] Error loading wallet: ${e.message}")
                    StreakInfo()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "[STREAK] Exception loading streak", e)
            StreakInfo()
        }
    }

    /**
     * Load current streak from Firebase wallet (legacy function for compatibility)
     * Also updates streak based on last claim date
     */
    private suspend fun loadStreakFromFirebase(userId: String): Int {
        return loadStreakInfoFromFirebase(userId).currentStreak
    }

    /**
     * Result from updating streak on claim
     */
    private data class StreakUpdateResult(
        val newStreak: Int,
        val newLongestStreak: Int,
        val isNewDayClaim: Boolean
    )

    /**
     * Update streak when claiming daily reward
     * Returns StreakUpdateResult with new streak, longest streak, and whether this is a new day claim
     */
    private suspend fun updateStreakOnClaim(userId: String): StreakUpdateResult {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            result.fold(
                onSuccess = { wallet ->
                    if (wallet == null) {
                        return@fold StreakUpdateResult(1, 1, true) // First claim
                    }
                    
                    val todayString = dateFormat.format(Date())
                    val lastClaimDate = wallet.lastQuestClaimDate
                    
                    // Check if already claimed today
                    if (lastClaimDate == todayString) {
                        Log.d(TAG, "[STREAK] Already claimed today, no streak update")
                        return@fold StreakUpdateResult(
                            wallet.getCurrentStreakSafe(),
                            wallet.getLongestStreakSafe(),
                            false
                        )
                    }
                    
                    var newStreak = 1 // Default for first claim or reset
                    
                    if (lastClaimDate != null) {
                        try {
                            val lastDate = java.time.LocalDate.parse(lastClaimDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                            val today = java.time.LocalDate.now()
                            val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(lastDate, today)
                            
                            newStreak = if (daysDiff == 1L) {
                                // Consecutive day - increment streak
                                wallet.getCurrentStreakSafe() + 1  // Use helper
                            } else {
                                // Missed days - reset to 1
                                1
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[STREAK] Error calculating streak", e)
                        }
                    }
                    
                    val newLongestStreak = maxOf(wallet.getLongestStreakSafe(), newStreak)  // Use helper
                    
                    // Update wallet with new streak
                    val updates = mapOf(
                        "lastQuestClaimDate" to todayString,
                        "currentStreak" to newStreak,
                        "longestStreak" to newLongestStreak,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                    
                    val updateResult = firestoreRepository.updateDocument(
                        collection = "user_wallets",
                        documentId = userId,
                        updates = updates
                    )
                    
                    updateResult.fold(
                        onSuccess = {
                            Log.d(TAG, "[STREAK] ✓ Updated streak to $newStreak (longest: $newLongestStreak)")
                        },
                        onFailure = { e ->
                            Log.e(TAG, "[STREAK] ✗ Failed to update wallet: ${e.message}")
                        }
                    )
                    
                    StreakUpdateResult(newStreak, newLongestStreak, true)
                },
                onFailure = { e ->
                    Log.e(TAG, "[STREAK] Error: ${e.message}")
                    StreakUpdateResult(1, 1, true)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "[STREAK] Exception", e)
            StreakUpdateResult(1, 1, true)
        }
    }

    /**
     * Calculate streak multiplier for reward based on milestone tiers
     * Higher streak = higher multiplier
     * 
     * Milestones:
     * - 3+ days: 1.1x
     * - 7+ days: 1.2x
     * - 14+ days: 1.3x
     * - 30+ days: 1.5x
     * - 50+ days: 1.7x
     * - 100+ days: 2.0x
     * - 200+ days: 2.5x
     * - 365+ days: 3.0x
     */
    private fun calculateStreakMultiplier(streak: Int): Float {
        return when {
            streak >= 365 -> 3.0f  // 365+ days: 3x (1 year!)
            streak >= 200 -> 2.5f  // 200+ days: 2.5x
            streak >= 100 -> 2.0f  // 100+ days: 2x
            streak >= 50 -> 1.7f   // 50+ days: 1.7x
            streak >= 30 -> 1.5f   // 30+ days: 1.5x
            streak >= 14 -> 1.3f   // 14+ days: 1.3x
            streak >= 7 -> 1.2f    // 7+ days: 1.2x
            streak >= 3 -> 1.1f    // 3+ days: 1.1x
            else -> 1.0f           // Default: 1x (streak reset to 0 means no bonus)
        }
    }
    
    /**
     * Get the next milestone streak level and its multiplier
     */
    fun getNextStreakMilestone(currentStreak: Int): Pair<Int, Float>? {
        val milestones = listOf(
            3 to 1.1f,
            7 to 1.2f,
            14 to 1.3f,
            30 to 1.5f,
            50 to 1.7f,
            100 to 2.0f,
            200 to 2.5f,
            365 to 3.0f
        )
        return milestones.firstOrNull { it.first > currentStreak }
    }

    /**
     * Check if user can claim daily reward (haven't claimed today)
     */
    private suspend fun canClaimDailyReward(userId: String): Boolean {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            result.fold(
                onSuccess = { wallet ->
                    if (wallet == null) return@fold true
                    
                    val todayString = dateFormat.format(Date())
                    // Null-safe check: if lastQuestClaimDate is null (old wallet), allow claim
                    val canClaim = wallet.lastQuestClaimDate != todayString
                    
                    Log.d(TAG, "[DAILY] Today: $todayString, LastClaim: ${wallet.lastQuestClaimDate ?: "null (first time)"}, CanClaim: $canClaim")
                    canClaim
                },
                onFailure = { e ->
                    Log.e(TAG, "[DAILY] Error: ${e.message}")
                    true
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "[DAILY] Exception", e)
            true
        }
    }
    
    /**
     * Check if user can claim daily bonus (haven't claimed bonus today)
     */
    private suspend fun canClaimBonusToday(userId: String): Boolean {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            result.fold(
                onSuccess = { wallet ->
                    if (wallet == null) return@fold true
                    
                    val todayString = dateFormat.format(Date())
                    // Null-safe check: if lastBonusClaimDate is null (old wallet), allow claim
                    val canClaim = wallet.lastBonusClaimDate != todayString
                    
                    Log.d(TAG, "[BONUS CHECK] Today: $todayString, LastBonus: ${wallet.lastBonusClaimDate ?: "null (first time)"}, CanClaim: $canClaim")
                    canClaim
                },
                onFailure = { e ->
                    Log.e(TAG, "[BONUS CHECK] Error: ${e.message}")
                    true
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "[BONUS CHECK] Exception", e)
            true
        }
    }

    /**
     * Check if user has linked with partner
     */
    private suspend fun checkPartnerLinkStatus(userId: String): Boolean {
        return try {
            val result = firestoreRepository.getDocument(
                collection = "users",
                documentId = userId,
                clazz = FirebaseUser::class.java
            )

            result.fold(
                onSuccess = { user ->
                    if (user == null) {
                        Log.w(TAG, "User document not found for userId: $userId")
                        return@fold false
                    }
                    
                    // Check both partnerId and coupleId (partnerId is set when link request is accepted)
                    val hasPartner = !user.partnerId.isNullOrEmpty() || !user.coupleId.isNullOrEmpty()
                    Log.d(TAG, "[QUEST] Partner link check for user $userId:")
                    Log.d(TAG, "[QUEST] - partnerId: ${user.partnerId}")
                    Log.d(TAG, "[QUEST] - coupleId: ${user.coupleId}")
                    Log.d(TAG, "[QUEST] - hasPartner: $hasPartner")
                    hasPartner
                },
                onFailure = { e ->
                    Log.e(TAG, "Error checking partner link: ${e.message}")
                    false
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking partner link", e)
            false
        }
    }

    /**
     * Select daily quests from pool with user progress
     * NOTE: Login quest is NOT auto-completed here anymore - it's handled after checking claim status
     */
    private fun selectDailyQuestsWithProgress(seed: Long, savedProgress: Map<String, SavedQuestProgress>): List<Quest> {
        val selectedQuests = QuestPool.selectDailyQuests(count = 5, seed = seed)

        return selectedQuests.map { quest ->
            val saved = savedProgress[quest.id]
            if (saved != null) {
                quest.copy(
                    currentProgress = saved.currentProgress,
                    status = saved.status
                )
            } else {
                // DON'T auto-complete login quest here - will be handled after claim check
                quest
            }
        }
    }

    /**
     * Calculate daily summary from quests
     * @param quests The list of daily quests
     * @param canClaimBonus Whether the user can claim the bonus (hasn't claimed today)
     */
    private fun calculateDailySummary(quests: List<Quest>, canClaimBonus: Boolean = true): DailyQuestSummary {
        val total = quests.size
        val completed = quests.count { it.status == QuestStatus.COMPLETED || it.status == QuestStatus.CLAIMED }
        val claimed = quests.count { it.status == QuestStatus.CLAIMED }
        val coinsEarned = quests.filter { it.status == QuestStatus.CLAIMED }.sumOf { it.reward.coins }
        val totalAvailable = quests.sumOf { it.reward.coins }
        // Bonus is only unlocked if all quests are completed AND user hasn't claimed bonus today
        val bonusUnlocked = completed == total && total > 0 && canClaimBonus

        return DailyQuestSummary(
            totalQuests = total,
            completedQuests = completed,
            claimedQuests = claimed,
            totalCoinsEarned = coinsEarned,
            totalCoinsAvailable = totalAvailable,
            bonusRewardUnlocked = bonusUnlocked
        )
    }

    /**
     * Update quest progress (called from other features)
     */
    fun updateQuestProgress(questType: QuestType, progressAmount: Int = 1) {
        viewModelScope.launch {
            Log.d(TAG, "Updating quest progress: $questType by $progressAmount")
            
            val updatedQuests = _uiState.value.quests.map { quest ->
                if (quest.type == questType && quest.status != QuestStatus.CLAIMED) {
                    val newProgress = (quest.currentProgress + progressAmount).coerceAtMost(quest.targetProgress)
                    val newStatus = when {
                        newProgress >= quest.targetProgress -> QuestStatus.COMPLETED
                        newProgress > 0 -> QuestStatus.IN_PROGRESS
                        else -> QuestStatus.NOT_STARTED
                    }
                    val updatedQuest = quest.copy(currentProgress = newProgress, status = newStatus)
                    
                    // Save to Firebase
                    saveQuestProgressToFirebase(updatedQuest)
                    
                    updatedQuest
                } else {
                    quest
                }
            }

            // Preserve the current bonus state - if it was already claimed, keep it false
            val currentBonusState = _uiState.value.dailySummary.bonusRewardUnlocked
            val newSummary = calculateDailySummary(updatedQuests, currentBonusState)

            _uiState.update {
                it.copy(
                    quests = updatedQuests,
                    dailySummary = newSummary
                )
            }
            
            Log.d(TAG, "Quest progress updated, summary: ${newSummary.completedQuests}/${newSummary.totalQuests}")
        }
    }

    /**
     * Claim reward for completed quest and update Firebase wallet
     * Applies streak multiplier to daily quests
     */
    fun claimReward(quest: Quest) {
        if (quest.status != QuestStatus.COMPLETED) return
        // Prevent multiple clicks - check if already claiming
        if (_uiState.value.isClaimingReward) {
            Log.d(TAG, "Already claiming reward, ignoring duplicate click")
            return
        }

        viewModelScope.launch {
            // Set claiming state immediately to prevent duplicate clicks
            _uiState.update { it.copy(isClaimingReward = true) }
            
            try {
                val userId = authRepository.currentUser?.uid
                if (userId == null) {
                    Log.e(TAG, "Cannot claim reward: no authenticated user")
                    _uiState.update { it.copy(isClaimingReward = false) }
                    return@launch
                }

            Log.d(TAG, "Claiming reward for quest: ${quest.id}, reward: ${quest.reward.coins} coins")

            // Check if it's the special quest
            if (quest.isSpecial) {
                val success = addCoinsToWallet(userId, quest.reward.coins)
                if (success) {
                    val newCoins = _uiState.value.userCoins + quest.reward.coins
                    // Save special quest progress as CLAIMED before hiding
                    saveQuestProgressToFirebase(quest.copy(status = QuestStatus.CLAIMED))
                    
                    // Hide special quest after claiming by setting it to null
                    _uiState.update {
                        it.copy(
                            specialQuest = null,
                            userCoins = newCoins,
                            showRewardDialog = true,
                            claimedReward = quest.reward,
                            isClaimingReward = false
                        )
                    }
                    Log.d(TAG, "Special quest reward claimed successfully, new balance: $newCoins, quest hidden")
                } else {
                    _uiState.update { it.copy(isClaimingReward = false) }
                }
                return@launch
            }

            // For daily login quest, update streak and apply multiplier
            // Streak is only updated when ALL daily quests are claimed
            val updatedQuestsForStreakCheck = _uiState.value.quests.map {
                if (it.id == quest.id) quest.copy(status = QuestStatus.CLAIMED) else it
            }
            val allQuestsClaimed = updatedQuestsForStreakCheck.all { it.status == QuestStatus.CLAIMED }
            
            val streakResult = if (allQuestsClaimed) {
                // All quests claimed - update streak!
                Log.d(TAG, "[STREAK] All quests claimed! Updating streak...")
                updateStreakOnClaim(userId)
            } else {
                // Not all quests claimed yet - keep current streak
                Log.d(TAG, "[STREAK] Not all quests claimed yet (${updatedQuestsForStreakCheck.count { it.status == QuestStatus.CLAIMED }}/${updatedQuestsForStreakCheck.size})")
                StreakUpdateResult(_uiState.value.currentStreak, _uiState.value.longestStreak, false)
            }
            val newStreak = streakResult.newStreak
            val newLongestStreak = streakResult.newLongestStreak
            
            // Calculate reward with streak multiplier
            val multiplier = calculateStreakMultiplier(newStreak)
            val baseReward = quest.reward.coins
            val finalReward = (baseReward * multiplier).toInt()
            
            Log.d(TAG, "[REWARD] Base: $baseReward, Streak: $newStreak, Multiplier: $multiplier, Final: $finalReward")

            // Regular quest reward - prepare updated quest first
            val claimedQuest = quest.copy(status = QuestStatus.CLAIMED)
            
            // IMPORTANT: Save quest progress to Firebase FIRST before updating UI
            // This prevents the exploit where user can claim again if they navigate away
            // AWAIT the save to ensure it completes before proceeding
            saveQuestProgressToFirebase(claimedQuest)
            Log.d(TAG, "[REWARD] Quest progress saved for ${quest.id}")
            
            val updatedQuests = _uiState.value.quests.map {
                if (it.id == quest.id) {
                    claimedQuest
                } else {
                    it
                }
            }

            val success = addCoinsToWallet(userId, finalReward)
            if (success) {
                val newCoins = _uiState.value.userCoins + finalReward
                // Preserve the current bonus state - if bonus was already claimed, keep it false
                val currentBonusState = _uiState.value.dailySummary.bonusRewardUnlocked
                val newSummary = calculateDailySummary(updatedQuests, currentBonusState)
                val todayString = dateFormat.format(Date())

                _uiState.update {
                    it.copy(
                        quests = updatedQuests,
                        userCoins = newCoins,
                        currentStreak = newStreak,
                        longestStreak = newLongestStreak,
                        missedDays = 0,  // Reset missed days on successful claim
                        lastClaimDate = todayString,
                        dailySummary = newSummary,
                        showRewardDialog = true,
                        claimedReward = if (multiplier > 1.0f) {
                            quest.reward.copy(
                                coins = finalReward,
                                bonusItem = "Chuỗi ${newStreak} ngày! (x${String.format("%.1f", multiplier)})"
                            )
                        } else {
                            quest.reward.copy(coins = finalReward)
                        },
                        isClaimingReward = false
                    )
                }

                Log.d(TAG, "Quest reward claimed successfully, new balance: $newCoins, streak: $newStreak, longest: $newLongestStreak")
                
                // Invalidate cache to ensure fresh data on next load
                questCache.invalidateCache(userId)
            } else {
                _uiState.update { it.copy(isClaimingReward = false) }
            }
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming reward", e)
                _uiState.update { it.copy(isClaimingReward = false) }
            }
        }
    }

    /**
     * Add coins to user wallet in Firebase
     */
    private suspend fun addCoinsToWallet(userId: String, amount: Int): Boolean {
        return try {
            Log.d(TAG, "Adding $amount coins to wallet for user: $userId")
            
            // Load current wallet
            val result = firestoreRepository.getDocument(
                collection = "user_wallets",
                documentId = userId,
                clazz = FirebaseUserWallet::class.java
            )

            val currentWallet = result.fold(
                onSuccess = { wallet -> wallet },
                onFailure = { e ->
                    Log.e(TAG, "Failed to load wallet: ${e.message}")
                    null
                }
            )

            if (currentWallet == null) {
                Log.d(TAG, "No wallet found, creating new wallet")
                createDefaultWallet(userId)
                return false
            }

            // Update wallet
            val updates = mapOf(
                "coins" to (currentWallet.coins + amount),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            val updateResult = firestoreRepository.updateDocument(
                collection = "user_wallets",
                documentId = userId,
                updates = updates
            )

            updateResult.fold(
                onSuccess = {
                    Log.d(TAG, "Wallet updated successfully, added $amount coins")
                    // Invalidate store cache so Store screen will reload fresh data
                    storeCache.invalidateCache(userId)
                    Log.d(TAG, "📦 Store cache invalidated after coin update")
                    true
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to update wallet: ${e.message}")
                    false
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception adding coins to wallet", e)
            false
        }
    }

    /**
     * Claim all completed rewards at once
     * Applies streak multiplier to total rewards
     */
    fun claimAllRewards() {
        // Prevent multiple clicks - check if already claiming
        if (_uiState.value.isClaimingReward) {
            Log.d(TAG, "Already claiming rewards, ignoring duplicate click")
            return
        }

        viewModelScope.launch {
            // Set claiming state immediately to prevent duplicate clicks
            _uiState.update { it.copy(isClaimingReward = true) }
            
            try {
                val userId = authRepository.currentUser?.uid
                if (userId == null) {
                    Log.e(TAG, "Cannot claim rewards: no authenticated user")
                    _uiState.update { it.copy(isClaimingReward = false) }
                    return@launch
                }

                val completedQuests = _uiState.value.quests.filter { it.status == QuestStatus.COMPLETED }
                if (completedQuests.isEmpty()) {
                    Log.d(TAG, "No completed quests to claim")
                    _uiState.update { it.copy(isClaimingReward = false) }
                    return@launch
                }

            // Check if claiming all completed quests will result in all quests being claimed
            // Streak only increases when ALL daily quests are claimed
            val allQuests = _uiState.value.quests
            val questsAfterClaim = allQuests.map { quest ->
                if (quest.status == QuestStatus.COMPLETED) {
                    quest.copy(status = QuestStatus.CLAIMED)
                } else {
                    quest
                }
            }
            val allQuestsClaimed = questsAfterClaim.all { it.status == QuestStatus.CLAIMED }
            
            val streakResult = if (allQuestsClaimed) {
                // All quests will be claimed after this - update streak!
                Log.d(TAG, "[CLAIM ALL] All quests will be claimed! Updating streak...")
                updateStreakOnClaim(userId)
            } else {
                // Not all quests will be claimed - keep current streak
                Log.d(TAG, "[CLAIM ALL] Not all quests will be claimed (${questsAfterClaim.count { it.status == QuestStatus.CLAIMED }}/${questsAfterClaim.size})")
                StreakUpdateResult(_uiState.value.currentStreak, _uiState.value.longestStreak, false)
            }
            val newStreak = streakResult.newStreak
            val newLongestStreak = streakResult.newLongestStreak
            
            // Calculate total with streak multiplier
            val multiplier = calculateStreakMultiplier(newStreak)
            val baseCoins = completedQuests.sumOf { it.reward.coins }
            val totalCoins = (baseCoins * multiplier).toInt()
            
            Log.d(TAG, "[CLAIM ALL] Base: $baseCoins, Streak: $newStreak, Multiplier: $multiplier, Total: $totalCoins")

            val success = addCoinsToWallet(userId, totalCoins)
            if (success) {
                // IMPORTANT: Save quest progress to Firebase FIRST before updating UI
                // This prevents the exploit where user can claim again if they navigate away
                val updatedQuests = _uiState.value.quests.map {
                    if (it.status == QuestStatus.COMPLETED) {
                        it.copy(status = QuestStatus.CLAIMED)
                    } else {
                        it
                    }
                }
                
                // Save all claimed quests to Firebase first - AWAIT each save
                val questsToSave = updatedQuests.filter { it.status == QuestStatus.CLAIMED && 
                    _uiState.value.quests.find { q -> q.id == it.id }?.status == QuestStatus.COMPLETED 
                }
                questsToSave.forEach { claimed ->
                    saveQuestProgressToFirebase(claimed)
                    Log.d(TAG, "[CLAIM ALL] Quest progress saved for ${claimed.id}")
                }

                val newCoins = _uiState.value.userCoins + totalCoins
                // Preserve the current bonus state - if bonus was already claimed, keep it false
                val currentBonusState = _uiState.value.dailySummary.bonusRewardUnlocked
                val newSummary = calculateDailySummary(updatedQuests, currentBonusState)
                val todayString = dateFormat.format(Date())

                _uiState.update {
                    it.copy(
                        quests = updatedQuests,
                        userCoins = newCoins,
                        currentStreak = newStreak,
                        longestStreak = newLongestStreak,
                        missedDays = 0,  // Reset missed days on successful claim
                        lastClaimDate = todayString,
                        dailySummary = newSummary,
                        showRewardDialog = true,
                        claimedReward = if (multiplier > 1.0f) {
                            QuestReward(
                                coins = totalCoins,
                                bonusItem = "Chuỗi ${newStreak} ngày! (x${String.format("%.1f", multiplier)})"
                            )
                        } else {
                            QuestReward(coins = totalCoins)
                        },
                        isClaimingReward = false
                    )
                }

                Log.d(TAG, "All rewards claimed successfully, new balance: $newCoins, streak: $newStreak, longest: $newLongestStreak")
                
                // Invalidate cache to ensure fresh data on next load
                questCache.invalidateCache(userId)
            } else {
                _uiState.update { it.copy(isClaimingReward = false) }
            }
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming all rewards", e)
                _uiState.update { it.copy(isClaimingReward = false) }
            }
        }
    }

    /**
     * Watch ad to complete ad quest
     */
    fun watchAd() {
        viewModelScope.launch {
            // TODO: Implement AdMob logic
            Log.d(TAG, "Watching ad...")
            updateQuestProgress(QuestType.WATCH_AD, 1)
        }
    }

    /**
     * Dismiss reward dialog
     */
    fun dismissRewardDialog() {
        _uiState.update {
            it.copy(
                showRewardDialog = false,
                claimedReward = null
            )
        }
    }

    /**
     * Show bonus reward for completing all quests
     * Applies streak multiplier to bonus reward
     * Only claimable once per day
     */
    fun showBonusReward() {
        if (!_uiState.value.dailySummary.bonusRewardUnlocked) return
        // Prevent multiple clicks - check if already claiming
        if (_uiState.value.isClaimingReward) {
            Log.d(TAG, "Already claiming bonus, ignoring duplicate click")
            return
        }

        viewModelScope.launch {
            // Set claiming state immediately to prevent duplicate clicks
            _uiState.update { it.copy(isClaimingReward = true) }
            
            try {
                val userId = authRepository.currentUser?.uid
                if (userId == null) {
                    Log.e(TAG, "Cannot show bonus: no authenticated user")
                    _uiState.update { it.copy(isClaimingReward = false) }
                    return@launch
                }

            // Calculate bonus based on streak with multiplier
            val currentStreak = _uiState.value.currentStreak
            val multiplier = calculateStreakMultiplier(currentStreak)
            val baseBonus = 50
            val streakBonus = currentStreak * 10
            val totalBonus = ((baseBonus + streakBonus) * multiplier).toInt()
            
            Log.d(TAG, "[BONUS] Base: $baseBonus, StreakBonus: $streakBonus, Multiplier: $multiplier, Total: $totalBonus")

            val success = addCoinsToWallet(userId, totalBonus)
            if (success) {
                val newCoins = _uiState.value.userCoins + totalBonus
                val todayString = dateFormat.format(Date())
                
                // Save bonus claim date to Firebase to prevent re-claiming
                val bonusUpdateResult = firestoreRepository.updateDocument(
                    collection = "user_wallets",
                    documentId = userId,
                    updates = mapOf(
                        "lastBonusClaimDate" to todayString,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                )
                
                bonusUpdateResult.fold(
                    onSuccess = {
                        Log.d(TAG, "[BONUS] ✓ Bonus claim date saved: $todayString")
                    },
                    onFailure = { e ->
                        Log.e(TAG, "[BONUS] ✗ Failed to save bonus claim date: ${e.message}")
                    }
                )

                _uiState.update {
                    it.copy(
                        userCoins = newCoins,
                        showRewardDialog = true,
                        claimedReward = QuestReward(
                            coins = totalBonus,
                            bonusItem = if (multiplier > 1.0f) {
                                "Thưởng hoàn thành! Chuỗi ${currentStreak} ngày (x${String.format("%.1f", multiplier)})"
                            } else {
                                "Thưởng hoàn thành tất cả!"
                            }
                        ),
                        dailySummary = it.dailySummary.copy(bonusRewardUnlocked = false),
                        isClaimingReward = false
                    )
                }

                Log.d(TAG, "Bonus reward claimed successfully, new balance: $newCoins")
                
                // Invalidate cache to ensure fresh data on next load
                questCache.invalidateCache(userId)
            } else {
                _uiState.update { it.copy(isClaimingReward = false) }
            }
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming bonus reward", e)
                _uiState.update { it.copy(isClaimingReward = false) }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Refresh quest data (force refresh from Firebase)
     */
    fun refreshQuests() {
        viewModelScope.launch {
            val userId = authRepository.currentUser?.uid
            if (userId != null) {
                // Invalidate cache first to force fresh data from Firebase
                questCache.invalidateCache(userId)
                Log.d(TAG, "🗑️ Quest cache invalidated, refreshing from Firebase...")
            }
            loadQuestData()
        }
    }

    /**
     * Insert mock quest progress data for testing
     */
    fun insertMockQuestData() {
        viewModelScope.launch {
            try {
                val currentUser = authRepository.currentUser
                if (currentUser == null) {
                    Log.e(TAG, "No authenticated user found")
                    _uiState.update {
                        it.copy(errorMessage = "Bạn cần đăng nhập để thêm dữ liệu mẫu")
                    }
                    return@launch
                }

                val userId = currentUser.uid
                val todayString = dateFormat.format(Date())
                
                Log.d(TAG, "Inserting mock quest data for user: $userId, date: $todayString")

                // Get today's selected quests
                val seed = todayString.replace("-", "").toLong()
                val selectedQuests = QuestPool.selectDailyQuests(count = 5, seed = seed)
                
                // Mark first 3 quests as completed (but not claimed)
                selectedQuests.take(3).forEach { quest ->
                    val docId = "${userId}_${todayString}_${quest.id}"
                    val progressData = FirebaseQuestProgress(
                        id = docId,
                        userId = userId,
                        questId = quest.id,
                        questType = quest.type.name,
                        date = todayString,
                        currentProgress = quest.targetProgress,
                        targetProgress = quest.targetProgress,
                        status = "COMPLETED",
                        updatedAt = Date()
                    )
                    
                    firestoreRepository.setDocument(
                        collection = "quest_progress",
                        documentId = docId,
                        data = progressData
                    ).fold(
                        onSuccess = {
                            Log.d(TAG, "Mock quest ${quest.id} saved successfully")
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to save mock quest ${quest.id}", e)
                        }
                    )
                }
                
                // Mark quest 4 as in progress
                if (selectedQuests.size >= 4) {
                    val quest = selectedQuests[3]
                    val halfProgress = (quest.targetProgress / 2).coerceAtLeast(1)
                    val docId = "${userId}_${todayString}_${quest.id}"
                    val progressData = FirebaseQuestProgress(
                        id = docId,
                        userId = userId,
                        questId = quest.id,
                        questType = quest.type.name,
                        date = todayString,
                        currentProgress = halfProgress,
                        targetProgress = quest.targetProgress,
                        status = "IN_PROGRESS",
                        updatedAt = Date()
                    )
                    
                    firestoreRepository.setDocument(
                        collection = "quest_progress",
                        documentId = docId,
                        data = progressData
                    ).fold(
                        onSuccess = {
                            Log.d(TAG, "Mock quest ${quest.id} (in progress) saved successfully")
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to save mock quest ${quest.id}", e)
                        }
                    )
                }
                
                Log.d(TAG, "Mock quest data inserted successfully")
                
                // Reload quest data to show the changes
                kotlinx.coroutines.delay(500)
                loadQuestData()
                
                _uiState.update {
                    it.copy(errorMessage = "Đã thêm dữ liệu mẫu nhiệm vụ thành công!")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert mock quest data", e)
                _uiState.update {
                    it.copy(errorMessage = "Lỗi khi thêm dữ liệu mẫu: ${e.message}")
                }
            }
        }
    }
}

/**
 * Saved quest progress for restoration
 */
data class SavedQuestProgress(
    val currentProgress: Int,
    val status: QuestStatus
)

// ============ Cache Conversion Extension Functions ============

/**
 * Convert CachedQuest to domain Quest
 */
private fun CachedQuest.toQuest(): Quest {
    return Quest(
        id = this.id,
        type = try { QuestType.valueOf(this.type) } catch (e: Exception) { QuestType.DAILY_LOGIN },
        title = this.title,
        vietnameseTitle = this.vietnameseTitle,
        description = this.description,
        vietnameseDescription = this.vietnameseDescription,
        iconRes = this.iconRes,
        reward = QuestReward(coins = this.rewardCoins),
        currentProgress = this.currentProgress,
        targetProgress = this.targetProgress,
        status = try { QuestStatus.valueOf(this.status) } catch (e: Exception) { QuestStatus.NOT_STARTED },
        isSpecial = this.isSpecial,
        navigationRoute = this.navigationRoute
    )
}

/**
 * Convert domain Quest to CachedQuest
 */
private fun Quest.toCachedQuest(): CachedQuest {
    return CachedQuest(
        id = this.id,
        title = this.title,
        vietnameseTitle = this.vietnameseTitle,
        description = this.description,
        vietnameseDescription = this.vietnameseDescription,
        iconRes = this.iconRes,
        rewardCoins = this.reward.coins,
        targetProgress = this.targetProgress,
        currentProgress = this.currentProgress,
        status = this.status.name,
        type = this.type.name,
        isSpecial = this.isSpecial,
        navigationRoute = this.navigationRoute
    )
}

/**
 * Convert CachedDailySummary to domain DailyQuestSummary
 */
private fun CachedDailySummary.toDailySummary(): DailyQuestSummary {
    return DailyQuestSummary(
        totalQuests = this.totalQuests,
        completedQuests = this.completedQuests,
        claimedQuests = this.claimedQuests,
        totalCoinsEarned = this.totalCoinsEarned,
        totalCoinsAvailable = this.totalCoinsAvailable,
        bonusRewardUnlocked = this.bonusRewardUnlocked
    )
}

/**
 * Convert domain DailyQuestSummary to CachedDailySummary
 */
private fun DailyQuestSummary.toCachedDailySummary(): CachedDailySummary {
    return CachedDailySummary(
        totalQuests = this.totalQuests,
        completedQuests = this.completedQuests,
        claimedQuests = this.claimedQuests,
        totalCoinsEarned = this.totalCoinsEarned,
        totalCoinsAvailable = this.totalCoinsAvailable,
        bonusRewardUnlocked = this.bonusRewardUnlocked
    )
}
