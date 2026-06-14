package com.example.coupleapp.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupleapp.R
import com.example.coupleapp.data.model.*
import com.example.coupleapp.data.repository.FirebaseAuthRepository
import com.example.coupleapp.data.repository.FirebaseFirestoreRepository
import com.example.coupleapp.data.repository.GardenCacheRepository
import com.example.coupleapp.data.repository.StoreCacheRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Firebase-backed ViewModel for Store Screen
 * Manages store items, purchases, and user wallet from Firestore
 * 
 * Uses LAZY LOADING strategy with StoreCacheRepository:
 * 1. Show cached wallet data immediately (no loading spinner if cache exists)
 * 2. Background refresh if cache is stale (> 15 minutes)
 * 3. Force refresh on purchase or pull-to-refresh
 */
class StoreViewModelFirebase(
    private val authRepository: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val firestoreRepository: FirebaseFirestoreRepository = FirebaseFirestoreRepository(),
    private val storeCache: StoreCacheRepository = StoreCacheRepository.getInstance(),
    private val gardenCache: GardenCacheRepository = GardenCacheRepository.getInstance()
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreUiState())
    val uiState: StateFlow<StoreUiState> = _uiState.asStateFlow()
    
    private val TAG = "StoreViewModelFirebase"

    init {
        loadStoreData()
    }

    /**
     * Load store data including categories and user wallet
     * @param forceRefresh If true, skip cache and load from network
     */
    fun loadStoreData(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentUserId = authRepository.currentUser?.uid
                if (currentUserId == null) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            errorMessage = "Not authenticated"
                        ) 
                    }
                    return@launch
                }
                
                // ========== LAZY LOADING: Try cache first ==========
                if (!forceRefresh) {
                    val (cachedWallet, needsRefresh) = storeCache.loadWallet(currentUserId)
                    
                    if (cachedWallet != null) {
                        Log.d(TAG, "✅ Cache hit! Showing cached wallet immediately")
                        
                        // Create store categories (static data - no caching needed)
                        val categories = createStoreCategories()
                        
                        // Check free gift availability
                        val canClaimFree = checkFreeGiftAvailability(cachedWallet)
                        val cooldownDays = calculateCooldownDays(cachedWallet)
                        
                        // Show cached data immediately (no loading spinner!)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                categories = categories,
                                userWallet = cachedWallet,
                                canClaimFreeGift = canClaimFree,
                                freeGiftCooldownDays = cooldownDays
                            )
                        }
                        
                        // Background refresh if cache is stale
                        if (needsRefresh) {
                            Log.d(TAG, "📦 Cache stale, background refresh...")
                            refreshWalletInBackground(currentUserId)
                        }
                        return@launch
                    }
                }
                
                // ========== No cache or force refresh: Load from network ==========
                _uiState.update { it.copy(isLoading = true) }
                
                // Load user wallet from network
                val wallet = loadUserWalletFromNetwork(currentUserId)
                
                // Check free gift availability
                val canClaimFree = checkFreeGiftAvailability(wallet)
                val cooldownDays = calculateCooldownDays(wallet)
                
                // Create store categories (static data)
                val categories = createStoreCategories()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = categories,
                        userWallet = wallet,
                        canClaimFreeGift = canClaimFree,
                        freeGiftCooldownDays = cooldownDays
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading store data", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Unable to load store data: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Refresh wallet in background without showing loading spinner
     */
    private fun refreshWalletInBackground(userId: String) {
        viewModelScope.launch {
            Log.d(TAG, "🔄 Background wallet refresh started")
            val wallet = storeCache.loadWalletFromNetwork(userId)
            if (wallet != null) {
                val canClaimFree = checkFreeGiftAvailability(wallet)
                val cooldownDays = calculateCooldownDays(wallet)
                _uiState.update { 
                    it.copy(
                        userWallet = wallet,
                        canClaimFreeGift = canClaimFree,
                        freeGiftCooldownDays = cooldownDays
                    ) 
                }
                Log.d(TAG, "✅ Background wallet refresh completed")
            }
        }
    }

    /**
     * Load user wallet from Firestore (network call)
     */
    private suspend fun loadUserWalletFromNetwork(userId: String): UserWallet {
        return try {
            val result = firestoreRepository.getDocument(
                "user_wallets", 
                userId,
                FirebaseUserWallet::class.java
            )
            
            result.fold(
                onSuccess = { walletDoc ->
                    if (walletDoc != null) {
                        Log.d(TAG, "[STORE] Wallet loaded from user_wallets/$userId")
                        Log.d(TAG, "[STORE] Wallet has ${walletDoc.coins} coins")
                        val wallet = UserWallet(
                            coins = walletDoc.coins,
                            lastFreeClaimTime = walletDoc.lastFreeGiftDate?.let { 
                                LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE)
                                    .atStartOfDay()
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            }
                        )
                        // Cache the wallet
                        storeCache.cacheWallet(userId, wallet)
                        wallet
                    } else {
                        Log.d(TAG, "[STORE] No wallet found at document: user_wallets/$userId")
                        Log.d(TAG, "[STORE] Creating default wallet with 1000 coins")
                        // Create default wallet with 1000 coins if not exists
                        val defaultWallet = FirebaseUserWallet(
                            id = userId,
                            userId = userId,
                            coins = 1000, // Starting coins
                            freeCoins = 0
                        )
                        firestoreRepository.setDocument("user_wallets", userId, defaultWallet)
                        
                        val wallet = UserWallet(coins = 1000)
                        storeCache.cacheWallet(userId, wallet)
                        wallet
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading wallet", e)
                    UserWallet(coins = 0)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading wallet", e)
            UserWallet(coins = 0)
        }
    }

    /**
     * Check if free gift can be claimed
     */
    private suspend fun checkFreeGiftAvailability(wallet: UserWallet): Boolean {
        return try {
            if (wallet.lastFreeClaimTime == null) {
                return true
            }
            
            val lastClaimDate = java.time.Instant.ofEpochMilli(wallet.lastFreeClaimTime)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            
            val today = LocalDate.now()
            val daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastClaimDate, today)
            
            daysSince >= 3
        } catch (e: Exception) {
            Log.e(TAG, "Error checking free gift availability", e)
            false
        }
    }

    /**
     * Calculate cooldown days for free gift
     */
    private suspend fun calculateCooldownDays(wallet: UserWallet): Int {
        return try {
            if (wallet.lastFreeClaimTime == null) {
                return 0
            }
            
            val lastClaimDate = java.time.Instant.ofEpochMilli(wallet.lastFreeClaimTime)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            
            val today = LocalDate.now()
            val daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastClaimDate, today).toInt()
            
            maxOf(0, 3 - daysSince)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cooldown", e)
            0
        }
    }

    /**
     * Create store categories with items (static data)
     */
    private fun createStoreCategories(): List<StoreCategory> {
        return listOf(
            // Combo Packages
            StoreCategory(
                id = "combos",
                name = "Plant Care Packages",
                items = listOf(
                    StoreItem(
                        id = "combo_free",
                        name = "Free Package",
                        vietnameseName = "Gói miễn phí",
                        description = "Claim free every 3 days",
                        vietnameseDescription = "Nhận miễn phí mỗi 3 ngày",
                        iconRes = R.drawable.combo1,
                        type = StoreItemType.CARE_PACKAGE,
                        purchaseType = PurchaseType.FREE_DAILY,
                        cooldownDays = 3
                    ),
                    StoreItem(
                        id = "combo_ad",
                        name = "Ad Package",
                        vietnameseName = "Gói quảng cáo",
                        description = "Watch ad to claim",
                        vietnameseDescription = "Xem quảng cáo để nhận",
                        iconRes = R.drawable.combo2,
                        type = StoreItemType.CARE_PACKAGE,
                        purchaseType = PurchaseType.WATCH_AD
                    ),
                    StoreItem(
                        id = "combo_premium",
                        name = "Premium Package",
                        vietnameseName = "Gói cao cấp",
                        description = "Premium plant care bundle",
                        vietnameseDescription = "Bộ chăm sóc cây cao cấp",
                        iconRes = R.drawable.combo3,
                        type = StoreItemType.CARE_PACKAGE,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 800
                    )
                )
            ),

            // Plant Seeds
            StoreCategory(
                id = "seeds",
                name = "Plant Seeds",
                items = listOf(
                    StoreItem(
                        id = "seed_normal",
                        name = "Normal Seed",
                        vietnameseName = "Hạt giống thường",
                        description = "Basic plant seed",
                        vietnameseDescription = "Hạt giống cơ bản",
                        iconRes = R.drawable.normal_seed,
                        type = StoreItemType.SEED,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100,
                        rarity = SeedRarity.NORMAL
                    ),
                    StoreItem(
                        id = "seed_rare",
                        name = "Rare Seed",
                        vietnameseName = "Hạt giống hiếm",
                        description = "Rare plant seed",
                        vietnameseDescription = "Hạt giống hiếm",
                        iconRes = R.drawable.rare_seed,
                        type = StoreItemType.SEED,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 300,
                        rarity = SeedRarity.RARE
                    ),
                    StoreItem(
                        id = "seed_super_rare",
                        name = "Super Rare Seed",
                        vietnameseName = "Hạt giống siêu hiếm",
                        description = "Special plant seed",
                        vietnameseDescription = "Hạt giống đặc biệt",
                        iconRes = R.drawable.super_rare_seed,
                        type = StoreItemType.SEED,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 500,
                        rarity = SeedRarity.SUPER_RARE
                    )
                )
            ),

            // Fertilizers
            StoreCategory(
                id = "fertilizers",
                name = "Fertilizers",
                items = listOf(
                    StoreItem(
                        id = "fertilizer_4h",
                        name = "4h Fertilizer",
                        vietnameseName = "Phân bón 4h",
                        description = "Speed up growth by 4 hours",
                        vietnameseDescription = "Tăng tốc 4 giờ",
                        iconRes = R.drawable.phan4h,
                        type = StoreItemType.FERTILIZER,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100,
                        durationHours = 4
                    ),
                    StoreItem(
                        id = "fertilizer_8h",
                        name = "8h Fertilizer",
                        vietnameseName = "Phân bón 8h",
                        description = "Speed up growth by 8 hours",
                        vietnameseDescription = "Tăng tốc 8 giờ",
                        iconRes = R.drawable.phan8h,
                        type = StoreItemType.FERTILIZER,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 150,
                        durationHours = 8
                    ),
                    StoreItem(
                        id = "fertilizer_24h",
                        name = "24h Fertilizer",
                        vietnameseName = "Phân bón 24h",
                        description = "Speed up growth by 24 hours",
                        vietnameseDescription = "Tăng tốc 24 giờ",
                        iconRes = R.drawable.phan24h,
                        type = StoreItemType.FERTILIZER,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 300,
                        durationHours = 24
                    )
                )
            ),

            // Gardening Tools
            StoreCategory(
                id = "tools",
                name = "Gardening Tools",
                items = listOf(
                    StoreItem(
                        id = "tool_sun",
                        name = "Sun Lamp",
                        vietnameseName = "Đèn mặt trời",
                        description = "Provide light for plants",
                        vietnameseDescription = "Cung cấp ánh sáng cho cây",
                        iconRes = R.drawable.sun,
                        type = StoreItemType.TOOL,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100
                    ),
                    StoreItem(
                        id = "tool_xoa",
                        name = "Watering Can",
                        vietnameseName = "Bình tưới",
                        description = "Water your plants",
                        vietnameseDescription = "Tưới nước cho cây",
                        iconRes = R.drawable.xoa,
                        type = StoreItemType.TOOL,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100
                    ),
                    StoreItem(
                        id = "tool_xit",
                        name = "Pesticide",
                        vietnameseName = "Thuốc trừ sâu",
                        description = "Protect plants from pests",
                        vietnameseDescription = "Bảo vệ cây khỏi sâu bệnh",
                        iconRes = R.drawable.xit,
                        type = StoreItemType.TOOL,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100
                    ),
                    StoreItem(
                        id = "tool_keo",
                        name = "Scissors",
                        vietnameseName = "Kéo cắt tỉa",
                        description = "Trim branches and leaves",
                        vietnameseDescription = "Cắt tỉa cành lá",
                        iconRes = R.drawable.keo,
                        type = StoreItemType.TOOL,
                        purchaseType = PurchaseType.COIN,
                        coinPrice = 100
                    )
                )
            )
        )
    }

    /**
     * Purchase an item
     */
    fun purchaseItem(item: StoreItem) {
        // Prevent multiple clicks - check if already purchasing
        if (_uiState.value.isPurchasing) {
            Log.d(TAG, "Already processing purchase, ignoring duplicate click")
            return
        }

        viewModelScope.launch {
            // Set purchasing state immediately to prevent duplicate clicks
            _uiState.update { it.copy(isPurchasing = true) }
            
            try {
                val currentUserId = authRepository.currentUser?.uid
                if (currentUserId == null) {
                    _uiState.update { it.copy(isPurchasing = false) }
                    return@launch
                }
                val wallet = _uiState.value.userWallet
                
                when (item.purchaseType) {
                    PurchaseType.COIN -> {
                        if (wallet.coins < item.coinPrice) {
                            _uiState.update { 
                                it.copy(errorMessage = "Not enough coins", isPurchasing = false) 
                            }
                            return@launch
                        }
                        
                        // Deduct coins
                        val newCoins = wallet.coins - item.coinPrice
                        firestoreRepository.updateDocument(
                            "user_wallets",
                            currentUserId,
                            mapOf("coins" to newCoins)
                        )
                        
                        // Add to inventory - handle combo packages differently
                        if (item.type == StoreItemType.CARE_PACKAGE) {
                            addComboPackageToInventory(currentUserId, item)
                        } else {
                            addToInventory(currentUserId, item)
                        }
                        
                        // Record purchase
                        recordPurchase(currentUserId, item, "coin")
                        
                        // Invalidate cache after purchase
                        storeCache.invalidateCache(currentUserId)
                        gardenCache.invalidateCache(currentUserId)
                        Log.d(TAG, "[STORE→GARDEN] 🗑️ Garden cache invalidated after coin purchase")
                        
                        // CRITICAL FIX: Update store cache with new wallet data
                        val updatedWallet = wallet.copy(coins = newCoins)
                        storeCache.cacheWallet(currentUserId, updatedWallet)
                        Log.d(TAG, "[STORE] 💰 Updated wallet cache after coin purchase: $newCoins coins")
                        
                        // Update local state
                        _uiState.update { 
                            it.copy(
                                userWallet = updatedWallet,
                                purchaseResult = PurchaseResult.Success(item, newCoins),
                                errorMessage = null,
                                isPurchasing = false
                            ) 
                        }
                    }
                    
                    PurchaseType.FREE_DAILY -> {
                        if (!_uiState.value.canClaimFreeGift) {
                            _uiState.update { 
                                it.copy(
                                    errorMessage = "Free gift available in ${_uiState.value.freeGiftCooldownDays} days",
                                    isPurchasing = false
                                ) 
                            }
                            return@launch
                        }
                        
                        // Update last free gift date
                        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                        firestoreRepository.updateDocument(
                            "user_wallets",
                            currentUserId,
                            mapOf("lastFreeGiftDate" to today)
                        )
                        
                        // Add FREE PACKAGE items to inventory
                        addComboPackageToInventory(currentUserId, item)
                        
                        // Record purchase
                        recordPurchase(currentUserId, item, "free")
                        
                        // Invalidate cache after free gift claim
                        storeCache.invalidateCache(currentUserId)
                        gardenCache.invalidateCache(currentUserId)
                        Log.d(TAG, "[STORE→GARDEN] 🗑️ Garden cache invalidated after free gift")
                        
                        // CRITICAL FIX: Update store cache with updated last claim time
                        val updatedWallet = wallet.copy(
                            lastFreeClaimTime = System.currentTimeMillis()
                        )
                        storeCache.cacheWallet(currentUserId, updatedWallet)
                        Log.d(TAG, "[STORE] 🎁 Updated wallet cache after free gift claim")
                        
                        // Update local state
                        _uiState.update { 
                            it.copy(
                                userWallet = updatedWallet,
                                canClaimFreeGift = false,
                                freeGiftCooldownDays = 3,
                                purchaseResult = PurchaseResult.Success(item, wallet.coins),
                                errorMessage = null,
                                isPurchasing = false
                            ) 
                        }
                    }
                    
                    PurchaseType.WATCH_AD -> {
                        // Add AD PACKAGE items to inventory
                        addComboPackageToInventory(currentUserId, item)
                        recordPurchase(currentUserId, item, "ad")
                        
                        // Invalidate cache after ad reward
                        storeCache.invalidateCache(currentUserId)
                        gardenCache.invalidateCache(currentUserId)
                        Log.d(TAG, "[STORE→GARDEN] 🗑️ Garden cache invalidated after ad reward")
                        
                        // CRITICAL FIX: Update store cache (no wallet change for ad watch, but ensure cache consistency)
                        storeCache.cacheWallet(currentUserId, wallet)
                        Log.d(TAG, "[STORE] 📺 Refreshed wallet cache after ad watch")
                        
                        _uiState.update { 
                            it.copy(
                                purchaseResult = PurchaseResult.Success(item, wallet.coins),
                                errorMessage = null,
                                isPurchasing = false
                            ) 
                        }
                    }
                    
                    else -> {
                        _uiState.update { 
                            it.copy(errorMessage = "Purchase type not supported", isPurchasing = false) 
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error purchasing item", e)
                _uiState.update { 
                    it.copy(errorMessage = "Purchase failed: ${e.message}", isPurchasing = false) 
                }
            }
        }
    }

    /**
     * Add purchased item to user's garden inventory
     */
    private suspend fun addToInventory(userId: String, item: StoreItem, quantity: Int = 1) {
        Log.d(TAG, "[STORE→GARDEN] Adding to inventory: itemId=${item.id}, quantity=$quantity, userId=$userId")
        try {
            val result = firestoreRepository.getDocument(
                "garden_inventories",
                userId,
                FirebaseGardenInventory::class.java
            )
            
            result.fold(
                onSuccess = { inventoryDoc ->
                    val updates = mutableMapOf<String, Any>()
                    Log.d(TAG, "[STORE→GARDEN] Current inventory: seeds=${inventoryDoc?.seeds}, fert4h=${inventoryDoc?.fertilizer4h}, fert8h=${inventoryDoc?.fertilizer8h}, fert12h=${inventoryDoc?.fertilizer12h}, water=${inventoryDoc?.wateringCan}, sun=${inventoryDoc?.sunlightBottle}")
                    
                    when (item.id) {
                        "seed_normal" -> {
                            val currentSeeds = inventoryDoc?.seeds ?: 0
                            updates["seeds"] = currentSeeds + quantity
                            Log.d(TAG, "[STORE→GARDEN] Normal seed update: $currentSeeds → ${currentSeeds + quantity}")
                        }
                        "seed_rare" -> {
                            val currentRareSeeds = inventoryDoc?.rareSeeds ?: 0
                            updates["rareSeeds"] = currentRareSeeds + quantity
                            Log.d(TAG, "[STORE→GARDEN] Rare seed update: $currentRareSeeds → ${currentRareSeeds + quantity}")
                        }
                        "seed_super_rare" -> {
                            val currentSuperRareSeeds = inventoryDoc?.superRareSeeds ?: 0
                            updates["superRareSeeds"] = currentSuperRareSeeds + quantity
                            Log.d(TAG, "[STORE→GARDEN] Super rare seed update: $currentSuperRareSeeds → ${currentSuperRareSeeds + quantity}")
                        }
                        "fertilizer_4h" -> {
                            val current = inventoryDoc?.fertilizer4h ?: 0
                            updates["fertilizer4h"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Fertilizer 4h update: $current → ${current + quantity}")
                        }
                        "fertilizer_8h" -> {
                            val current = inventoryDoc?.fertilizer8h ?: 0
                            updates["fertilizer8h"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Fertilizer 8h update: $current → ${current + quantity}")
                        }
                        "fertilizer_24h" -> {
                            val current = inventoryDoc?.fertilizer12h ?: 0
                            updates["fertilizer12h"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Fertilizer 24h update (stored as fertilizer12h): $current → ${current + quantity}")
                        }
                        "tool_keo" -> {
                            val current = inventoryDoc?.scissors ?: 0
                            updates["scissors"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Scissors update: $current → ${current + quantity}")
                        }
                        "tool_sun", "sunlight_bottle" -> {
                            val current = inventoryDoc?.sunlightBottle ?: 0
                            updates["sunlightBottle"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Sunlight bottle update: $current → ${current + quantity}")
                        }
                        "tool_xit", "pesticide" -> {
                            val current = inventoryDoc?.pesticide ?: 0
                            updates["pesticide"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Pesticide update: $current → ${current + quantity}")
                        }
                        "tool_xoa", "watering_can" -> {
                            val current = inventoryDoc?.wateringCan ?: 0
                            updates["wateringCan"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Watering can update: $current → ${current + quantity}")
                        }
                        "scissors" -> {
                            val current = inventoryDoc?.scissors ?: 0
                            updates["scissors"] = current + quantity
                            Log.d(TAG, "[STORE→GARDEN] Scissors update: $current → ${current + quantity}")
                        }
                        else -> {
                            Log.w(TAG, "[STORE→GARDEN] ❌ Unknown item ID: ${item.id} - will not be added to garden inventory!")
                        }
                    }
                    
                    if (updates.isNotEmpty()) {
                        Log.d(TAG, "[STORE→GARDEN] Applying updates to Firebase: $updates")
                        if (inventoryDoc == null) {
                            // Create new inventory
                            Log.d(TAG, "[STORE→GARDEN] Creating new inventory document for user $userId")
                            val newInventory = FirebaseGardenInventory(
                                id = userId,
                                userId = userId
                            )
                            firestoreRepository.setDocument("garden_inventories", userId, newInventory)
                        }
                        
                        firestoreRepository.updateDocument(
                            "garden_inventories",
                            userId,
                            updates
                        )
                        Log.d(TAG, "[STORE→GARDEN] ✓ Inventory updated successfully")
                    } else {
                        Log.w(TAG, "[STORE→GARDEN] ⚠️ No updates generated for item ${item.id}")
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading inventory", e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to inventory", e)
        }
    }

    /**
     * Add combo package items to inventory
     * Handles all 3 combo packages with their specific contents:
     * - combo_free: 1 pesticide, 1 sunlight, 1 wateringCan, 1 normal seed
     * - combo_ad: 1 fertilizer4h, 1 scissors, 1 wateringCan, 1 rare seed
     * - combo_premium: 1 fertilizer8h, 1 super rare seed, 1 pesticide, 1 wateringCan
     * 
     * @param quantity Number of combo packages to add (default 1)
     */
    private suspend fun addComboPackageToInventory(userId: String, comboItem: StoreItem, quantity: Int = 1) {
        Log.d(TAG, "[STORE→GARDEN] Adding COMBO PACKAGE '${comboItem.id}' x$quantity to inventory for userId=$userId")
        try {
            val result = firestoreRepository.getDocument(
                "garden_inventories",
                userId,
                FirebaseGardenInventory::class.java
            )
            
            result.fold(
                onSuccess = { inventoryDoc ->
                    val updates = mutableMapOf<String, Any>()
                    
                    // Define what each combo contains (multiplied by quantity)
                    when (comboItem.id) {
                        "combo_free" -> {
                            // Free Package (mỗi 3 ngày): 1 bình xịt cỏ, 1 sun, 1 bình nước, 1 hạt thường
                            val currentPesticide = inventoryDoc?.pesticide ?: 0
                            updates["pesticide"] = currentPesticide + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Free - Pesticide: $currentPesticide → ${currentPesticide + quantity}")
                            
                            val currentSunlight = inventoryDoc?.sunlightBottle ?: 0
                            updates["sunlightBottle"] = currentSunlight + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Free - Sunlight: $currentSunlight → ${currentSunlight + quantity}")
                            
                            val currentWater = inventoryDoc?.wateringCan ?: 0
                            updates["wateringCan"] = currentWater + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Free - WateringCan: $currentWater → ${currentWater + quantity}")
                            
                            val currentSeeds = inventoryDoc?.seeds ?: 0
                            updates["seeds"] = currentSeeds + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Free - Normal Seeds: $currentSeeds → ${currentSeeds + quantity}")
                        }
                        
                        "combo_ad" -> {
                            // Ad Package: 1 phân 4h, 1 kéo cắt, 1 bình nước, 1 hạt hiếm
                            val currentFert4h = inventoryDoc?.fertilizer4h ?: 0
                            updates["fertilizer4h"] = currentFert4h + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Ad - Fertilizer4h: $currentFert4h → ${currentFert4h + quantity}")
                            
                            val currentScissors = inventoryDoc?.scissors ?: 0
                            updates["scissors"] = currentScissors + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Ad - Scissors: $currentScissors → ${currentScissors + quantity}")
                            
                            val currentWater = inventoryDoc?.wateringCan ?: 0
                            updates["wateringCan"] = currentWater + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Ad - WateringCan: $currentWater → ${currentWater + quantity}")
                            
                            val currentRareSeeds = inventoryDoc?.rareSeeds ?: 0
                            updates["rareSeeds"] = currentRareSeeds + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Ad - Rare Seeds: $currentRareSeeds → ${currentRareSeeds + quantity}")
                        }
                        
                        "combo_premium" -> {
                            // Premium Package (800 coins): 1 phân 8h, 1 hạt siêu hiếm, 1 thuốc xịt, 1 bình nước
                            val currentFert8h = inventoryDoc?.fertilizer8h ?: 0
                            updates["fertilizer8h"] = currentFert8h + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Premium - Fertilizer8h: $currentFert8h → ${currentFert8h + quantity}")
                            
                            val currentSuperRareSeeds = inventoryDoc?.superRareSeeds ?: 0
                            updates["superRareSeeds"] = currentSuperRareSeeds + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Premium - Super Rare Seeds: $currentSuperRareSeeds → ${currentSuperRareSeeds + quantity}")
                            
                            val currentPesticide = inventoryDoc?.pesticide ?: 0
                            updates["pesticide"] = currentPesticide + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Premium - Pesticide: $currentPesticide → ${currentPesticide + quantity}")
                            
                            val currentWater = inventoryDoc?.wateringCan ?: 0
                            updates["wateringCan"] = currentWater + (1 * quantity)
                            Log.d(TAG, "[STORE→GARDEN] Premium - WateringCan: $currentWater → ${currentWater + quantity}")
                        }
                        
                        else -> {
                            Log.w(TAG, "[STORE→GARDEN] Unknown combo package: ${comboItem.id}")
                            return@fold
                        }
                    }
                    
                    Log.d(TAG, "[STORE→GARDEN] Combo '${comboItem.id}' x$quantity - Applying updates to Firebase: $updates")
                    if (inventoryDoc == null) {
                        // Create new inventory
                        Log.d(TAG, "[STORE→GARDEN] Creating new inventory document for user $userId")
                        val newInventory = FirebaseGardenInventory(
                            id = userId,
                            userId = userId
                        )
                        firestoreRepository.setDocument("garden_inventories", userId, newInventory)
                    }
                    
                    firestoreRepository.updateDocument(
                        "garden_inventories",
                        userId,
                        updates
                    )
                    Log.d(TAG, "[STORE→GARDEN] ✓ Combo Package '${comboItem.id}' x$quantity added to inventory successfully")
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading inventory for combo package", e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error adding combo package to inventory", e)
        }
    }

    /**
     * Record purchase in history
     */
    private suspend fun recordPurchase(userId: String, item: StoreItem, purchaseType: String, quantity: Int = 1) {
        try {
            val purchase = FirebasePurchaseHistory(
                userId = userId,
                itemId = item.id,
                itemName = item.name,
                itemType = item.type.name,
                price = item.coinPrice * quantity,
                purchaseType = purchaseType,
                quantity = quantity
            )
            
            firestoreRepository.addDocument("purchase_history", purchase)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording purchase", e)
        }
    }

    /**
     * Dismiss purchase result
     */
    fun dismissPurchaseResult() {
        _uiState.update { it.copy(purchaseResult = null) }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Refresh store data
     */
    fun refresh() {
        loadStoreData()
    }
    
    /**
     * Select category
     */
    fun selectCategory(index: Int) {
        _uiState.update { it.copy(selectedCategory = index) }
    }
    
    /**
     * Select item for purchase
     */
    fun selectItem(item: StoreItem) {
        _uiState.update { 
            it.copy(
                selectedItem = item,
                showPurchaseDialog = true,
                purchaseQuantity = 1
            ) 
        }
    }
    
    /**
     * Dismiss purchase dialog
     */
    fun dismissPurchaseDialog() {
        _uiState.update { 
            it.copy(
                showPurchaseDialog = false,
                selectedItem = null,
                purchaseQuantity = 1
            ) 
        }
    }
    
    /**
     * Update purchase quantity
     */
    fun updatePurchaseQuantity(quantity: Int) {
        _uiState.update { it.copy(purchaseQuantity = quantity) }
    }
    
    /**
     * Purchase with coins
     */
    fun purchaseWithCoins(item: StoreItem, quantity: Int) {
        viewModelScope.launch {
            try {
                val currentUserId = authRepository.currentUser?.uid ?: return@launch
                val wallet = _uiState.value.userWallet
                
                // Calculate total cost
                val totalCost = item.coinPrice * quantity
                
                if (wallet.coins < totalCost) {
                    _uiState.update { 
                        it.copy(errorMessage = "Not enough coins. Need $totalCost coins") 
                    }
                    return@launch
                }
                
                // Deduct coins (total cost)
                val newCoins = wallet.coins - totalCost
                firestoreRepository.updateDocument(
                    "user_wallets",
                    currentUserId,
                    mapOf("coins" to newCoins)
                )
                
                // Add to inventory - handle combo packages differently
                if (item.type == StoreItemType.CARE_PACKAGE) {
                    // For combo packages, add the package contents with quantity
                    addComboPackageToInventory(currentUserId, item, quantity)
                } else {
                    // For regular items, add with quantity
                    addToInventory(currentUserId, item, quantity)
                }
                
                // Record purchase
                recordPurchase(currentUserId, item, "coin", quantity)
                
                // IMPORTANT: Invalidate Garden cache so Garden screen loads fresh data
                gardenCache.invalidateCache(currentUserId)
                Log.d(TAG, "[STORE→GARDEN] 🗑️ Garden cache invalidated after purchase")
                
                // CRITICAL FIX: Update store cache with new wallet data
                val updatedWallet = wallet.copy(coins = newCoins)
                storeCache.cacheWallet(currentUserId, updatedWallet)
                Log.d(TAG, "[STORE] 💰 Updated wallet cache with new balance: $newCoins coins")
                
                // Update local state
                _uiState.update { 
                    it.copy(
                        userWallet = updatedWallet,
                        purchaseResult = PurchaseResult.Success(item, newCoins, quantity),
                        errorMessage = null
                    ) 
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error purchasing items", e)
                _uiState.update { 
                    it.copy(errorMessage = "Purchase failed: ${e.message}") 
                }
            }
        }
    }
    
    /**
     * Claim free gift
     */
    fun claimFreeGift(item: StoreItem) {
        purchaseItem(item)
    }
    
    /**
     * Watch ad for reward
     */
    fun watchAdForReward(item: StoreItem) {
        purchaseItem(item)
    }
    
    /**
     * Purchase with real money (not implemented)
     */
    fun purchaseWithRealMoney(item: StoreItem) {
        _uiState.update { 
            it.copy(errorMessage = "Real money purchases not yet implemented") 
        }
    }
    
    /**
     * Clear purchase result
     */
    fun clearPurchaseResult() {
        _uiState.update { it.copy(purchaseResult = null) }
    }
    
    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "StoreViewModelFirebase cleared")
    }
}
