package com.example.coupleapp.data.model

import androidx.annotation.DrawableRes
import com.example.coupleapp.R

/**
 * Plant growth stages - 6 stages from seed to blooming
 * Each stage has a specific growth time before transitioning to the next
 */
enum class PlantStage(
    val displayName: String,
    val vietnameseName: String,
    @DrawableRes val imageRes: Int,
    val growthTimeHours: Int // Time to reach next stage
) {
    SEED("Seed", "Hạt giống", R.drawable.seed, 6),           // 6 hours to sprout
    SPROUT("Sprout", "Mầm", R.drawable.sprout, 7),           // 7 hours to seedling
    SEEDLING("Seedling", "Cây con", R.drawable.seedling, 8), // 8 hours to growing
    GROWING("Growing", "Đang lớn", R.drawable.growing, 10),   // 10 hours to mature
    MATURE("Mature", "Trưởng thành", R.drawable.mature, 12),  // 12 hours to blooming
    BLOOMING("Blooming", "Nở hoa", R.drawable.blooming, 0)    // Final stage - ready to harvest
}

/**
 * Seed rarity types - determines which plant rarity pool to draw from
 */
enum class SeedRarity(
    val displayName: String,
    val vietnameseName: String
) {
    NORMAL("Normal", "Thường"),
    RARE("Rare", "Hiếm"),
    SUPER_RARE("Super Rare", "Siêu hiếm")
}

/**
 * Plant rarity levels - determines the collection value
 */
enum class PlantRarity(
    val displayName: String,
    val vietnameseName: String,
    val colorHue: Float,
    val dropRate: Float,
    val starsCount: Int // Visual indicator of rarity
) {
    COMMON("Common", "Thường", 0f, 0.6f, 1),
    UNCOMMON("Uncommon", "Không phổ biến", 30f, 0.25f, 2),
    RARE("Rare", "Hiếm", 180f, 0.12f, 3),
    SUPER_RARE("Super Rare", "Siêu hiếm", 280f, 0.03f, 4)
}

/**
 * Plant types - different species of plants that can be grown
 * Each type has unique appearance and is unlocked through collection
 */
enum class PlantType(
    val displayName: String,
    val vietnameseName: String,
    @DrawableRes val unlockedImageRes: Int,
    @DrawableRes val lockedImageRes: Int
) {
    ROSE("Rose", "Hoa hồng", R.drawable.blooming, R.drawable.seed),
    TULIP("Tulip", "Hoa tulip", R.drawable.blooming, R.drawable.seed),
    SUNFLOWER("Sunflower", "Hoa hướng dương", R.drawable.blooming, R.drawable.seed),
    LILY("Lily", "Hoa lily", R.drawable.blooming, R.drawable.seed),
    ORCHID("Orchid", "Hoa lan", R.drawable.blooming, R.drawable.seed),
    DAISY("Daisy", "Hoa cúc", R.drawable.blooming, R.drawable.seed),
    LAVENDER("Lavender", "Hoa oải hương", R.drawable.blooming, R.drawable.seed),
    CHERRY_BLOSSOM("Cherry Blossom", "Hoa anh đào", R.drawable.blooming, R.drawable.seed),
    LOTUS("Lotus", "Hoa sen", R.drawable.blooming, R.drawable.seed)
}

/**
 * Plant flower colors for gallery collection
 */
enum class PlantFlowerColor(
    val displayName: String,
    val vietnameseName: String,
    val colorHue: Float,
    val hexColor: Long,
    @DrawableRes val bloomingRes: Int
) {
    PINK("Pink", "Hồng", 330f, 0xFFFFB6C1, R.drawable.blooming),
    RED("Red", "Đỏ", 0f, 0xFFFF4444, R.drawable.blooming),
    ORANGE("Orange", "Cam", 30f, 0xFFFF9933, R.drawable.blooming),
    YELLOW("Yellow", "Vàng", 60f, 0xFFFFD700, R.drawable.blooming),
    GREEN("Green", "Xanh lá", 120f, 0xFF4CAF50, R.drawable.blooming),
    CYAN("Cyan", "Xanh ngọc", 180f, 0xFF00CED1, R.drawable.blooming),
    BLUE("Blue", "Xanh dương", 210f, 0xFF4169E1, R.drawable.blooming),
    PURPLE("Purple", "Tím", 270f, 0xFF9370DB, R.drawable.blooming),
    MAGENTA("Magenta", "Hồng tím", 300f, 0xFFFF00FF, R.drawable.blooming),
    WHITE("White", "Trắng", 0f, 0xFFFFFAFA, R.drawable.blooming),
    BLACK("Black", "Đen", 0f, 0xFF1A1A1A, R.drawable.blooming),
    RAINBOW("Rainbow", "Cầu vồng", 0f, 0xFFFFFFFF, R.drawable.blooming) // Special rare color
}

/**
 * Status bar types for plant care
 */
enum class PlantStatusType(
    val displayName: String,
    val vietnameseName: String,
    @DrawableRes val iconRes: Int
) {
    SUNLIGHT("Sunlight", "Sunlight", R.drawable.sun),
    WATER("Water", "Water", R.drawable.xoa),
    HEALTH("Health", "Health", R.drawable.xit) // Represents pest/weed status
}

/**
 * Care item types
 */
enum class CareItemType {
    WATER,           // Watering can
    SUNLIGHT,        // Sun lamp
    PESTICIDE,       // Bug spray
    SCISSORS,        // Pruning scissors
    FERTILIZER_4H,   // 4 hour boost
    FERTILIZER_8H,   // 8 hour boost
    FERTILIZER_24H,  // 24 hour boost
    SEED_NORMAL,     // Normal seed
    SEED_RARE,       // Rare seed
    SEED_SUPER_RARE  // Super rare seed
}

/**
 * Care item for inventory
 */
data class CareItem(
    val id: String,
    val type: CareItemType,
    val name: String,
    val vietnameseName: String,
    val description: String,
    @DrawableRes val iconRes: Int,
    val quantity: Int = 0,
    val effectValue: Float = 25f, // How much it affects the status bar
    val boostHours: Int = 0 // For fertilizers
)

/**
 * Plant status values
 */
data class PlantStatus(
    val sunlight: Float = 100f,   // 0-100
    val water: Float = 100f,      // 0-100
    val health: Float = 100f,     // 0-100
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    val isAlive: Boolean
        get() = sunlight > 0 && water > 0 && health > 0
    
    val isDead: Boolean
        get() = sunlight <= 0 || water <= 0 || health <= 0
    
    val needsSunlight: Boolean
        get() = sunlight < 30
    
    val needsWater: Boolean
        get() = water < 30
    
    val needsHealth: Boolean
        get() = health < 30
    
    val mostNeededStatus: PlantStatusType?
        get() = when {
            isDead -> null // Plant is dead if any stat is 0
            sunlight <= water && sunlight <= health -> PlantStatusType.SUNLIGHT
            water <= sunlight && water <= health -> PlantStatusType.WATER
            else -> PlantStatusType.HEALTH
        }
    
    /**
     * Calculate overall plant health percentage
     */
    val overallHealth: Float
        get() = (sunlight + water + health) / 3f
}

/**
 * Main Plant data class - shared between couple
 */
data class Plant(
    val id: String,
    val name: String,
    val stage: PlantStage = PlantStage.SEED,
    val status: PlantStatus = PlantStatus(),
    val rarity: PlantRarity = PlantRarity.COMMON,
    val plantType: PlantType = PlantType.ROSE,
    val flowerColor: PlantFlowerColor = PlantFlowerColor.PINK,
    val plantedAt: Long = System.currentTimeMillis(),
    val stageStartedAt: Long = System.currentTimeMillis(),
    val fertilizerBoostHours: Int = 0, // Total hours skipped by fertilizer
    val growthProgress: Float = 0f, // 0-1 progress to next stage
    val isInGreenhouse: Boolean = false,
    val greenhouseBoost: Float = 1.5f, // 150% growth speed
    val coupleId: String = "", // For sync between couple - SHARED PLANT
    val plantedByUserId: String = "", // Who planted the seed
    val lastCaredByUserId: String = "", // Last person who cared for plant
    val lastSyncTime: Long = System.currentTimeMillis()
) {
    /**
     * Calculate time remaining to next growth stage
     */
    val timeToNextStage: Long
        get() {
            if (stage == PlantStage.BLOOMING) return 0
            val baseTimeMs = stage.growthTimeHours * 60 * 60 * 1000L
            val fertilizerBoostMs = fertilizerBoostHours * 60 * 60 * 1000L
            val adjustedTime = if (isInGreenhouse) (baseTimeMs / greenhouseBoost).toLong() else baseTimeMs
            val effectiveTime = maxOf(0, adjustedTime - fertilizerBoostMs)
            val elapsed = System.currentTimeMillis() - stageStartedAt
            return maxOf(0, effectiveTime - elapsed)
        }
    
    /**
     * Calculate current growth progress (0.0 to 1.0)
     */
    val currentGrowthProgress: Float
        get() {
            if (stage == PlantStage.BLOOMING) return 1f
            val baseTimeMs = stage.growthTimeHours * 60 * 60 * 1000L
            if (baseTimeMs <= 0) return 1f
            val elapsed = System.currentTimeMillis() - stageStartedAt
            val fertilizerBoostMs = fertilizerBoostHours * 60 * 60 * 1000L
            val effectiveElapsed = elapsed + fertilizerBoostMs
            val adjustedTotal = if (isInGreenhouse) (baseTimeMs / greenhouseBoost).toLong() else baseTimeMs
            return (effectiveElapsed.toFloat() / adjustedTotal).coerceIn(0f, 1f)
        }
    
    /**
     * Check if plant can evolve to next stage
     */
    val canEvolve: Boolean
        get() = stage != PlantStage.BLOOMING && timeToNextStage <= 0 && status.isAlive
    
    /**
     * Check if plant is ready to harvest (blooming stage)
     */
    val isReadyToHarvest: Boolean
        get() = stage == PlantStage.BLOOMING && status.isAlive
    
    /**
     * Get total growth time remaining for all stages
     */
    val totalTimeRemaining: Long
        get() {
            if (stage == PlantStage.BLOOMING) return 0
            var total = timeToNextStage
            val stagesRemaining = PlantStage.values().drop(stage.ordinal + 1).dropLast(1) // Exclude BLOOMING
            stagesRemaining.forEach { nextStage ->
                total += nextStage.growthTimeHours * 60 * 60 * 1000L
            }
            return total
        }
}

/**
 * Collected plant for gallery - shared between couple
 */
data class CollectedPlant(
    val id: String,
    val plantType: PlantType,
    val flowerColor: PlantFlowerColor,
    val rarity: PlantRarity,
    val customColorHue: Float? = null, // Custom color adjustment
    val customSaturation: Float? = null,
    val customBrightness: Float? = null,
    val unlockedAt: Long = System.currentTimeMillis(),
    val unlockedByCoupleId: String = "",
    val harvestedByUserId: String = "",
    val partnerContributedCare: Boolean = false // Did partner help care for plant?
)

/**
 * Gallery collection item
 */
data class GalleryPlant(
    val id: String,
    val flowerColor: PlantFlowerColor,
    val rarity: PlantRarity,
    val plantType: PlantType = PlantType.ROSE,
    val unlockedAt: Long? = null,
    val isUnlocked: Boolean = false,
    val customColorHue: Float? = null
)

/**
 * User's garden inventory
 */
data class GardenInventory(
    val items: Map<CareItemType, CareItem> = emptyMap(),
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun getItem(type: CareItemType): CareItem? = items[type]
    
    fun hasItem(type: CareItemType): Boolean = (items[type]?.quantity ?: 0) > 0
    
    fun useItem(type: CareItemType): GardenInventory {
        val item = items[type] ?: return this
        if (item.quantity <= 0) return this
        return copy(
            items = items + (type to item.copy(quantity = item.quantity - 1)),
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    fun addItem(type: CareItemType, quantity: Int = 1): GardenInventory {
        val item = items[type] ?: createDefaultItem(type)
        return copy(
            items = items + (type to item.copy(quantity = item.quantity + quantity)),
            lastUpdated = System.currentTimeMillis()
        )
    }
}

/**
 * Create default care items
 */
fun createDefaultItem(type: CareItemType): CareItem {
    return when (type) {
        CareItemType.WATER -> CareItem(
            id = "water",
            type = type,
            name = "Watering Can",
            vietnameseName = "Bình tưới",
            description = "Water the plant",
            iconRes = R.drawable.xoa,
            effectValue = 30f
        )
        CareItemType.SUNLIGHT -> CareItem(
            id = "sunlight",
            type = type,
            name = "Sunlight",
            vietnameseName = "Ánh sáng",
            description = "Provide sunlight for the plant",
            iconRes = R.drawable.sun,
            effectValue = 30f
        )
        CareItemType.PESTICIDE -> CareItem(
            id = "pesticide",
            type = type,
            name = "Pesticide",
            vietnameseName = "Thuốc trừ sâu",
            description = "Protect plant from pests",
            iconRes = R.drawable.xit,
            effectValue = 35f
        )
        CareItemType.SCISSORS -> CareItem(
            id = "scissors",
            type = type,
            name = "Scissors",
            vietnameseName = "Kéo cắt tỉa",
            description = "Trim branches and leaves",
            iconRes = R.drawable.keo,
            effectValue = 20f
        )
        CareItemType.FERTILIZER_4H -> CareItem(
            id = "fertilizer_4h",
            type = type,
            name = "4h Fertilizer",
            vietnameseName = "Phân bón 4h",
            description = "Speed up 4 hours",
            iconRes = R.drawable.phan4h,
            boostHours = 4
        )
        CareItemType.FERTILIZER_8H -> CareItem(
            id = "fertilizer_8h",
            type = type,
            name = "8h Fertilizer",
            vietnameseName = "Phân bón 8h",
            description = "Speed up 8 hours",
            iconRes = R.drawable.phan8h,
            boostHours = 8
        )
        CareItemType.FERTILIZER_24H -> CareItem(
            id = "fertilizer_24h",
            type = type,
            name = "24h Fertilizer",
            vietnameseName = "Phân bón 24h",
            description = "Speed up 24 hours",
            iconRes = R.drawable.phan24h,
            boostHours = 24
        )
        CareItemType.SEED_NORMAL -> CareItem(
            id = "seed_normal",
            type = type,
            name = "Normal Seed",
            vietnameseName = "Hạt giống thường",
            description = "Basic seed",
            iconRes = R.drawable.normal_seed
        )
        CareItemType.SEED_RARE -> CareItem(
            id = "seed_rare",
            type = type,
            name = "Rare Seed",
            vietnameseName = "Hạt giống hiếm",
            description = "Rare seed",
            iconRes = R.drawable.rare_seed
        )
        CareItemType.SEED_SUPER_RARE -> CareItem(
            id = "seed_super_rare",
            type = type,
            name = "Super Rare Seed",
            vietnameseName = "Hạt giống siêu hiếm",
            description = "Super rare seed",
            iconRes = R.drawable.super_rare_seed
        )
    }
}

/**
 * Default inventory with initial items
 */
fun createDefaultInventory(): GardenInventory {
    return GardenInventory(
        items = mapOf(
            CareItemType.WATER to createDefaultItem(CareItemType.WATER).copy(quantity = 5),
            CareItemType.SUNLIGHT to createDefaultItem(CareItemType.SUNLIGHT).copy(quantity = 3),
            CareItemType.PESTICIDE to createDefaultItem(CareItemType.PESTICIDE).copy(quantity = 2),
            CareItemType.SCISSORS to createDefaultItem(CareItemType.SCISSORS).copy(quantity = 2),
            CareItemType.FERTILIZER_4H to createDefaultItem(CareItemType.FERTILIZER_4H).copy(quantity = 1),
            CareItemType.FERTILIZER_8H to createDefaultItem(CareItemType.FERTILIZER_8H).copy(quantity = 1),
            CareItemType.FERTILIZER_24H to createDefaultItem(CareItemType.FERTILIZER_24H).copy(quantity = 0),
            CareItemType.SEED_NORMAL to createDefaultItem(CareItemType.SEED_NORMAL).copy(quantity = 3),
            CareItemType.SEED_RARE to createDefaultItem(CareItemType.SEED_RARE).copy(quantity = 1),
            CareItemType.SEED_SUPER_RARE to createDefaultItem(CareItemType.SEED_SUPER_RARE).copy(quantity = 0)
        )
    )
}

/**
 * Garden UI State
 */
data class GardenUiState(
    val isLoading: Boolean = true,
    val plant: Plant? = null,
    val inventory: GardenInventory = createDefaultInventory(),
    val gallery: List<GalleryPlant> = emptyList(),
    val collection: List<CollectedPlant> = emptyList(), // Shared collection between couple
    val selectedTab: GardenTab = GardenTab.CARE,
    val showRenameDialog: Boolean = false,
    val showSettingsMenu: Boolean = false,
    val showItemAnimation: Boolean = false,
    val showHarvestDialog: Boolean = false,
    val showColorEditor: Boolean = false,
    val harvestedPlant: CollectedPlant? = null, // Recently harvested plant
    val animatingItem: CareItem? = null,
    val plantThought: PlantStatusType? = null, // For thought bubble
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val coupleId: String = "",
    val partnerId: String = "",
    val partnerName: String = "",
    val isConnected: Boolean = true,
    val lastPartnerCareTime: Long? = null // When partner last cared for plant
)

/**
 * Garden tabs
 */
enum class GardenTab(val displayName: String, val vietnameseName: String) {
    CARE("Care", "Care"),
    BOOST("Boost", "Boost"),
    SEEDS("Seeds", "Seeds")
}

/**
 * Color customization options for harvested plants
 */
data class PlantColorCustomization(
    val hueAdjustment: Float = 0f, // -180 to 180
    val saturationAdjustment: Float = 0f, // -1 to 1
    val brightnessAdjustment: Float = 0f // -1 to 1
)

/**
 * Item use animation state
 */
data class ItemAnimationState(
    val item: CareItem,
    val isAnimating: Boolean = false,
    val progress: Float = 0f, // 0-1 animation progress
    val startPosition: Pair<Float, Float> = 0f to 0f,
    val targetPosition: Pair<Float, Float> = 0f to 0f
)

/**
 * Harvest result when plant reaches blooming stage
 */
data class HarvestResult(
    val collectedPlant: CollectedPlant,
    val isNewUnlock: Boolean, // First time getting this type
    val bonusCoins: Int = 0,
    val partnerBonus: Boolean = false // Extra bonus if partner helped
)
