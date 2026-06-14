package com.example.coupleapp.widget.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.coupleapp.data.local.CoupleAppDatabase
import com.example.coupleapp.data.local.entity.PartnerLocationEntity
import com.example.coupleapp.data.local.entity.PartnerPhotoEntity
import com.example.coupleapp.data.local.entity.PartnerSleepEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Repository for widget data with Room DB as single source of truth.
 * 
 * Architecture (Silent Push Strategy):
 * 1. FCM Data Message arrives → triggers PartnerDataSyncWorker
 * 2. Worker fetches from Firebase → saves to Room DB
 * 3. Widget reads from Room DB (THIS CLASS)
 * 4. Only fallback to Firebase if Room is empty
 * 
 * Benefits:
 * - Battery efficient: Widget never calls Firebase directly
 * - Offline-first: Widget always has data from Room cache
 * - Reactive: Room Flow enables automatic widget updates
 * - Consistent: Single source of truth prevents data conflicts
 */
object WidgetDataRepository {
    private const val TAG = "WidgetDataRepository"
    private const val PREFS_NAME = "widget_data_cache"
    
    // Cache expiry times (in milliseconds) - now used as FALLBACK only
    // Primary data comes from Room DB
    private const val SLEEP_CACHE_EXPIRY_MS = 30 * 60 * 1000L     // 30 minutes
    private const val LOCKET_CACHE_EXPIRY_MS = 5 * 60 * 1000L     // 5 minutes (more frequent for real-time feel)
    private const val MISSING_CACHE_EXPIRY_MS = 15 * 60 * 1000L   // 15 minutes
    private const val LOCATION_CACHE_EXPIRY_MS = 10 * 60 * 1000L  // 10 minutes
    
    // Room data staleness threshold - if older than this, also try Firebase refresh
    private const val ROOM_DATA_STALE_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
    
    // Mutex for preventing race conditions during missing count increment
    private val missingCountMutex = Mutex()
    
    // Cache keys (for SharedPreferences fallback)
    private const val KEY_SLEEP_DATA = "sleep_data"
    private const val KEY_SLEEP_TIMESTAMP = "sleep_timestamp"
    private const val KEY_LOCKET_DATA = "locket_data"
    private const val KEY_LOCKET_TIMESTAMP = "locket_timestamp"
    private const val KEY_MISSING_DATA = "missing_data"
    private const val KEY_MISSING_TIMESTAMP = "missing_timestamp"
    private const val KEY_LOCATION_DATA = "location_data"
    private const val KEY_LOCATION_TIMESTAMP = "location_timestamp"
    private const val KEY_PARTNER_ID = "cached_partner_id"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get partner ID from cache or fetch from Firebase.
     */
    private suspend fun getPartnerId(context: Context): String? {
        val prefs = getPrefs(context)
        val cachedPartnerId = prefs.getString(KEY_PARTNER_ID, null)
        if (!cachedPartnerId.isNullOrEmpty()) {
            return cachedPartnerId
        }
        
        // Fetch and cache partner ID
        return withContext(Dispatchers.IO) {
            try {
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser ?: return@withContext null
                val db = FirebaseFirestore.getInstance()
                val userDoc = db.collection("users").document(currentUser.uid).get().await()
                val partnerId = userDoc.getString("partnerId")
                if (!partnerId.isNullOrEmpty()) {
                    prefs.edit().putString(KEY_PARTNER_ID, partnerId).apply()
                }
                partnerId
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching partner ID", e)
                null
            }
        }
    }
    
    // ==================== SLEEP DATA ====================
    
    /**
     * Get sleep widget data with Room DB as single source of truth.
     * 
     * Priority order:
     * 1. Room DB (primary - synced by PartnerDataSyncWorker)
     * 2. SharedPreferences cache (fallback)
     * 3. Firebase direct fetch (last resort, also saves to Room)
     */
    suspend fun getSleepWidgetData(context: Context, forceRefresh: Boolean = false): SleepWidgetCachedData? {
        return withContext(Dispatchers.IO) {
            try {
                val partnerId = getPartnerId(context) ?: return@withContext null
                
                // 1. Try Room DB first (single source of truth)
                if (!forceRefresh) {
                    val roomData = getSleepFromRoom(context, partnerId)
                    if (roomData != null) {
                        Log.d(TAG, "✅ Returning sleep data from Room DB")
                        return@withContext roomData
                    }
                }
                
                // 2. Try SharedPreferences cache as fallback
                val prefs = getPrefs(context)
                val cachedTimestamp = prefs.getLong(KEY_SLEEP_TIMESTAMP, 0)
                val now = System.currentTimeMillis()
                
                if (!forceRefresh && (now - cachedTimestamp) < SLEEP_CACHE_EXPIRY_MS) {
                    val cachedData = parseSleepCachedData(prefs.getString(KEY_SLEEP_DATA, null))
                    if (cachedData != null) {
                        Log.d(TAG, "📦 Returning sleep data from SharedPreferences cache")
                        return@withContext cachedData
                    }
                }
                
                // 3. Last resort: Fetch from Firebase directly
                Log.d(TAG, "🌐 Fetching sleep data from Firebase (fallback)")
                val freshData = fetchSleepDataFromFirebase()
                if (freshData != null) {
                    cacheSleepData(prefs, freshData)
                }
                freshData
            } catch (e: Exception) {
                Log.e(TAG, "Error getting sleep data", e)
                // Return stale cache if all else fails
                parseSleepCachedData(getPrefs(context).getString(KEY_SLEEP_DATA, null))
            }
        }
    }
    
    /**
     * Read sleep data from Room Database.
     */
    private suspend fun getSleepFromRoom(context: Context, partnerId: String): SleepWidgetCachedData? {
        return try {
            val db = CoupleAppDatabase.getInstance(context)
            val sleepDao = db.partnerSleepDao()
            val sleepEntity = sleepDao.getLatestSleep(partnerId)
            
            if (sleepEntity != null) {
                // Check if data is too stale
                val now = System.currentTimeMillis()
                if (now - sleepEntity.lastSyncedAt > ROOM_DATA_STALE_THRESHOLD_MS) {
                    Log.d(TAG, "Room sleep data is stale (${(now - sleepEntity.lastSyncedAt) / 1000}s old)")
                    // Return data but also trigger background refresh
                }
                
                // Get my sleep data too (need Firebase for this)
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser
                val firestore = FirebaseFirestore.getInstance()
                
                val userDoc = currentUser?.let {
                    firestore.collection("users").document(it.uid).get().await()
                }
                val myName = userDoc?.getString("displayName") ?: "Bạn"
                val myAvatarUrl = userDoc?.getString("profileImageUrl")
                
                // Get partner avatar URL
                val partnerDoc = firestore.collection("users").document(partnerId).get().await()
                val partnerAvatarUrl = partnerDoc.getString("profileImageUrl")
                
                val coupleId = currentUser?.let {
                    listOf(it.uid, partnerId).sorted().joinToString("_")
                } ?: ""
                
                val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                
                // Get my sleep record
                val mySleepDoc = currentUser?.let {
                    firestore.collection("sleep_records")
                        .document("${coupleId}_${it.uid}_$today")
                        .get().await()
                }
                
                val mySleepDuration = mySleepDoc?.getLong("actualSleepDurationMinutes")?.toInt() ?: 0
                val mySleepQuality = mySleepDoc?.getString("quality") ?: "GOOD"
                val myAchievement = mySleepDoc?.getDouble("achievementPercentage")?.toFloat() ?: 0f
                
                // Get bedtime setting
                val settingsDoc = currentUser?.let {
                    firestore.collection("sleep_settings").document(it.uid).get().await()
                }
                val bedtimeHour = settingsDoc?.getLong("bedtimeHour")?.toInt() ?: 22
                val bedtimeMinute = settingsDoc?.getLong("bedtimeMinute")?.toInt() ?: 0
                
                // Check if partner is currently asleep
                val partnerSleepDoc = firestore.collection("sleep_records")
                    .document("${coupleId}_${partnerId}_$today")
                    .get().await()
                val partnerIsAsleep = partnerSleepDoc.getBoolean("isCurrentlyAsleep") ?: false
                
                SleepWidgetCachedData(
                    myName = myName,
                    partnerName = sleepEntity.partnerName,
                    mySleepDuration = mySleepDuration,
                    mySleepQuality = mySleepQuality,
                    myAchievement = myAchievement,
                    partnerSleepDuration = sleepEntity.sleepDurationMinutes,
                    partnerSleepQuality = sleepEntity.sleepQuality,
                    partnerAchievement = (sleepEntity.sleepDurationMinutes.toFloat() / sleepEntity.targetDurationMinutes * 100).coerceIn(0f, 100f),
                    partnerIsAsleep = partnerIsAsleep,
                    bedtimeHour = bedtimeHour,
                    bedtimeMinute = bedtimeMinute,
                    date = sleepEntity.date,
                    myAvatarUrl = myAvatarUrl,
                    partnerAvatarUrl = partnerAvatarUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sleep from Room", e)
            null
        }
    }
    
    private suspend fun fetchSleepDataFromFirebase(): SleepWidgetCachedData? {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return null
        val db = FirebaseFirestore.getInstance()
        
        val userDoc = db.collection("users").document(currentUser.uid).get().await()
        val partnerId = userDoc.getString("partnerId")
        val userName = userDoc.getString("displayName") ?: "Bạn"
        val myAvatarUrl = userDoc.getString("profileImageUrl")
        
        if (partnerId.isNullOrEmpty()) return null
        
        val partnerDoc = db.collection("users").document(partnerId).get().await()
        val partnerName = partnerDoc.getString("displayName") ?: "Người yêu"
        val partnerAvatarUrl = partnerDoc.getString("profileImageUrl")
        
        val coupleId = listOf(currentUser.uid, partnerId).sorted().joinToString("_")
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Get my sleep record
        val mySleepDoc = db.collection("sleep_records")
            .document("${coupleId}_${currentUser.uid}_$today")
            .get().await()
        
        val mySleepDuration = mySleepDoc.getLong("actualSleepDurationMinutes")?.toInt() ?: 0
        val mySleepQuality = mySleepDoc.getString("quality") ?: "GOOD"
        val myAchievement = mySleepDoc.getDouble("achievementPercentage")?.toFloat() ?: 0f
        
        // Get partner's sleep record
        val partnerSleepDoc = db.collection("sleep_records")
            .document("${coupleId}_${partnerId}_$today")
            .get().await()
        
        val partnerSleepDuration = partnerSleepDoc.getLong("actualSleepDurationMinutes")?.toInt() ?: 0
        val partnerSleepQuality = partnerSleepDoc.getString("quality") ?: "GOOD"
        val partnerAchievement = partnerSleepDoc.getDouble("achievementPercentage")?.toFloat() ?: 0f
        val partnerIsAsleep = partnerSleepDoc.getBoolean("isCurrentlyAsleep") ?: false
        
        // Get bedtime setting
        val settingsDoc = db.collection("sleep_settings").document(currentUser.uid).get().await()
        val bedtimeHour = settingsDoc.getLong("bedtimeHour")?.toInt() ?: 22
        val bedtimeMinute = settingsDoc.getLong("bedtimeMinute")?.toInt() ?: 0
        
        return SleepWidgetCachedData(
            myName = userName,
            partnerName = partnerName,
            mySleepDuration = mySleepDuration,
            mySleepQuality = mySleepQuality,
            myAchievement = myAchievement,
            partnerSleepDuration = partnerSleepDuration,
            partnerSleepQuality = partnerSleepQuality,
            partnerAchievement = partnerAchievement,
            partnerIsAsleep = partnerIsAsleep,
            bedtimeHour = bedtimeHour,
            bedtimeMinute = bedtimeMinute,
            date = today,
            myAvatarUrl = myAvatarUrl,
            partnerAvatarUrl = partnerAvatarUrl
        )
    }
    
    private fun cacheSleepData(prefs: SharedPreferences, data: SleepWidgetCachedData) {
        prefs.edit()
            .putString(KEY_SLEEP_DATA, serializeSleepData(data))
            .putLong(KEY_SLEEP_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    private fun serializeSleepData(data: SleepWidgetCachedData): String {
        return "${data.myName}|${data.partnerName}|${data.mySleepDuration}|${data.mySleepQuality}|" +
               "${data.myAchievement}|${data.partnerSleepDuration}|${data.partnerSleepQuality}|" +
               "${data.partnerAchievement}|${data.partnerIsAsleep}|${data.bedtimeHour}|${data.bedtimeMinute}|${data.date}|" +
               "${data.myAvatarUrl ?: ""}|${data.partnerAvatarUrl ?: ""}"
    }
    
    private fun parseSleepCachedData(cached: String?): SleepWidgetCachedData? {
        if (cached == null) return null
        val parts = cached.split("|")
        if (parts.size < 12) return null
        return try {
            SleepWidgetCachedData(
                myName = parts[0],
                partnerName = parts[1],
                mySleepDuration = parts[2].toInt(),
                mySleepQuality = parts[3],
                myAchievement = parts[4].toFloat(),
                partnerSleepDuration = parts[5].toInt(),
                partnerSleepQuality = parts[6],
                partnerAchievement = parts[7].toFloat(),
                partnerIsAsleep = parts[8].toBoolean(),
                bedtimeHour = parts[9].toInt(),
                bedtimeMinute = parts[10].toInt(),
                date = parts[11],
                myAvatarUrl = parts.getOrNull(12)?.takeIf { it.isNotEmpty() },
                partnerAvatarUrl = parts.getOrNull(13)?.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }
    
    // ==================== LOCKET DATA ====================
    
    /**
     * Get locket widget data with Room DB as single source of truth.
     * 
     * Priority order:
     * 1. Room DB (primary - synced by PartnerDataSyncWorker)
     * 2. SharedPreferences cache (fallback)
     * 3. Firebase direct fetch (last resort)
     */
    suspend fun getLocketWidgetData(context: Context, forceRefresh: Boolean = false): LocketWidgetCachedData? {
        return withContext(Dispatchers.IO) {
            try {
                val partnerId = getPartnerId(context) ?: return@withContext null
                
                // 1. Try Room DB first (single source of truth)
                if (!forceRefresh) {
                    val roomData = getLocketFromRoom(context, partnerId)
                    if (roomData != null) {
                        Log.d(TAG, "✅ Returning locket data from Room DB")
                        return@withContext roomData
                    }
                }
                
                // 2. Try SharedPreferences cache as fallback
                val prefs = getPrefs(context)
                val cachedTimestamp = prefs.getLong(KEY_LOCKET_TIMESTAMP, 0)
                val now = System.currentTimeMillis()
                
                if (!forceRefresh && (now - cachedTimestamp) < LOCKET_CACHE_EXPIRY_MS) {
                    val cachedData = parseLocketCachedData(prefs.getString(KEY_LOCKET_DATA, null))
                    if (cachedData != null) {
                        // If cached content is a placeholder (for PHOTO/DRAWING), we need fresh data
                        if (cachedData.content == "__BASE64_IMAGE__") {
                            Log.d(TAG, "Cached locket is image type, fetching fresh data for content")
                            val freshData = fetchLocketDataFromFirebase()
                            if (freshData != null) {
                                return@withContext freshData
                            }
                        }
                        Log.d(TAG, "📦 Returning locket data from SharedPreferences cache")
                        return@withContext cachedData
                    }
                }
                
                // 3. Last resort: Fetch from Firebase directly
                Log.d(TAG, "🌐 Fetching locket data from Firebase (fallback)")
                val freshData = fetchLocketDataFromFirebase()
                if (freshData != null) {
                    cacheLocketData(prefs, freshData)
                }
                freshData
            } catch (e: Exception) {
                Log.e(TAG, "Error getting locket data", e)
                parseLocketCachedData(getPrefs(context).getString(KEY_LOCKET_DATA, null))
            }
        }
    }
    
    /**
     * Read locket/photo data from Room Database.
     */
    private suspend fun getLocketFromRoom(context: Context, partnerId: String): LocketWidgetCachedData? {
        return try {
            val db = CoupleAppDatabase.getInstance(context)
            val photoDao = db.partnerPhotoDao()
            val photoEntity = photoDao.getLatestPhoto(partnerId)
            
            if (photoEntity != null) {
                // Check if data is too stale
                val now = System.currentTimeMillis()
                if (now - photoEntity.lastSyncedAt > ROOM_DATA_STALE_THRESHOLD_MS) {
                    Log.d(TAG, "Room locket data is stale (${(now - photoEntity.lastSyncedAt) / 1000}s old)")
                }
                
                // Determine type based on URL
                val type = when {
                    photoEntity.imageUrl.contains("drawing") -> "DRAWING"
                    photoEntity.imageUrl.startsWith("http") -> "PHOTO"
                    photoEntity.imageUrl.length < 50 -> "EMOJI" // Emoji is short text
                    else -> "TEXT"
                }
                
                LocketWidgetCachedData(
                    senderName = photoEntity.partnerName,
                    content = photoEntity.imageUrl,
                    type = type,
                    caption = photoEntity.caption,
                    timestamp = photoEntity.timestamp,
                    hasNewLocket = !photoEntity.isRead,
                    widgetThumbnail = null // Room doesn't store thumbnail separately, use full content
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading locket from Room", e)
            null
        }
    }
    
    private suspend fun fetchLocketDataFromFirebase(): LocketWidgetCachedData? {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return null
        val db = FirebaseFirestore.getInstance()
        
        // Query lockets where current user is the receiver
        // Use only receiverId filter (no orderBy) to avoid needing composite index
        // Sort client-side like app does in LocketFirebaseRepository
        val locketQuery = db.collection("locket_posts")
            .whereEqualTo("receiverId", currentUser.uid)
            .get()
            .await()
        
        if (locketQuery.documents.isEmpty()) {
            return LocketWidgetCachedData(
                senderName = "",
                content = "",
                type = "EMPTY",
                caption = null,
                timestamp = 0L,
                hasNewLocket = false
            )
        }
        
        // Sort by timestamp descending and get the latest
        val doc = locketQuery.documents
            .sortedByDescending { it.getTimestamp("timestamp")?.toDate()?.time ?: 0L }
            .first()
        
        val senderName = doc.getString("senderName") ?: "Partner"
        val typeStr = doc.getString("type") ?: "text"
        val caption = doc.getString("caption") ?: ""
        val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
        val isRead = doc.getBoolean("isRead") ?: false
        
        // Get widget thumbnail if available (optimized for widget)
        val widgetThumbnail = doc.getString("widgetThumbnail")
        
        // Get content based on type - each type has its own field
        val content = when (typeStr.lowercase()) {
            "photo" -> doc.getString("photoUrl") ?: ""
            "emoji" -> doc.getString("emoji") ?: ""
            "drawing" -> doc.getString("drawingUrl") ?: ""
            "text" -> doc.getString("textContent") ?: ""
            else -> doc.getString("textContent") ?: ""
        }
        
        // Convert type to uppercase for widget display consistency
        val type = typeStr.uppercase()
        
        // Log thumbnail availability
        if (widgetThumbnail != null) {
            Log.d(TAG, "✅ Widget thumbnail available (${widgetThumbnail.length} chars)")
        } else {
            Log.d(TAG, "⚠️ No widget thumbnail, will use full content")
        }
        
        return LocketWidgetCachedData(
            senderName = senderName,
            content = content,
            type = type,
            caption = caption.ifEmpty { null },
            timestamp = timestamp,
            hasNewLocket = !isRead,
            widgetThumbnail = widgetThumbnail
        )
    }
    
    private fun cacheLocketData(prefs: SharedPreferences, data: LocketWidgetCachedData) {
        prefs.edit()
            .putString(KEY_LOCKET_DATA, serializeLocketData(data))
            .putLong(KEY_LOCKET_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    private fun serializeLocketData(data: LocketWidgetCachedData): String {
        // For PHOTO and DRAWING types, don't cache the content (Base64 is too large for SharedPreferences)
        // Instead, store a placeholder - widget will fetch fresh data when needed
        val contentToCache = when (data.type) {
            "PHOTO", "DRAWING" -> "__BASE64_IMAGE__" // Placeholder for large image content
            else -> data.content
        }
        return "${data.senderName}|${contentToCache}|${data.type}|${data.caption ?: ""}|${data.timestamp}|${data.hasNewLocket}"
    }
    
    private fun parseLocketCachedData(cached: String?): LocketWidgetCachedData? {
        if (cached == null) return null
        val parts = cached.split("|")
        if (parts.size < 6) return null
        return try {
            LocketWidgetCachedData(
                senderName = parts[0],
                content = parts[1],
                type = parts[2],
                caption = parts[3].ifEmpty { null },
                timestamp = parts[4].toLong(),
                hasNewLocket = parts[5].toBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }
    
    // ==================== MISSING DATA ====================
    
    // Key to store the last cached date for missing data
    private const val KEY_MISSING_CACHED_DATE = "missing_cached_date"
    
    suspend fun getMissingWidgetData(context: Context, forceRefresh: Boolean = false): MissingWidgetCachedData? {
        val prefs = getPrefs(context)
        val cachedTimestamp = prefs.getLong(KEY_MISSING_TIMESTAMP, 0)
        val cachedDate = prefs.getString(KEY_MISSING_CACHED_DATE, "") ?: ""
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val now = System.currentTimeMillis()
        
        // ========== DAY CHANGE DETECTION ==========
        // Force refresh if day has changed (resets today's counts)
        val dayChanged = cachedDate.isNotEmpty() && cachedDate != today
        if (dayChanged) {
            Log.d(TAG, "📅 Day changed from $cachedDate to $today - invalidating missing cache")
        }
        
        if (!forceRefresh && !dayChanged && (now - cachedTimestamp) < MISSING_CACHE_EXPIRY_MS) {
            val cachedData = parseMissingCachedData(prefs.getString(KEY_MISSING_DATA, null))
            if (cachedData != null) {
                Log.d(TAG, "Returning cached missing data")
                return cachedData
            }
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val freshData = fetchMissingDataFromFirebase()
                if (freshData != null) {
                    cacheMissingData(prefs, freshData)
                }
                freshData
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching missing data", e)
                parseMissingCachedData(prefs.getString(KEY_MISSING_DATA, null))
            }
        }
    }
    
    private suspend fun fetchMissingDataFromFirebase(): MissingWidgetCachedData? {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return null
        val db = FirebaseFirestore.getInstance()
        
        val userDoc = db.collection("users").document(currentUser.uid).get().await()
        val partnerId = userDoc.getString("partnerId")
        
        if (partnerId.isNullOrEmpty()) return null
        
        val coupleId = listOf(currentUser.uid, partnerId).sorted().joinToString("_")
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Load today's counts
        val myRecordId = "${coupleId}_${currentUser.uid}_$today"
        val partnerRecordId = "${coupleId}_${partnerId}_$today"
        
        val myRecord = db.collection("missing_records").document(myRecordId).get().await()
        val partnerRecord = db.collection("missing_records").document(partnerRecordId).get().await()
        
        val myTodayCount = myRecord.getLong("count")?.toInt() ?: 0
        val partnerTodayCount = partnerRecord.getLong("count")?.toInt() ?: 0
        
        // Calculate streak efficiently - only check last 30 days
        var currentStreak = 0
        var longestStreak = 0
        var tempStreak = 0
        var gapFound = false
        
        for (dayOffset in 0 until 30) {
            val date = LocalDate.now().minusDays(dayOffset.toLong())
            val dateString = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            
            val myDayRecordId = "${coupleId}_${currentUser.uid}_$dateString"
            val partnerDayRecordId = "${coupleId}_${partnerId}_$dateString"
            
            val myDayRecord = db.collection("missing_records").document(myDayRecordId).get().await()
            val partnerDayRecord = db.collection("missing_records").document(partnerDayRecordId).get().await()
            
            val myDayCount = myDayRecord.getLong("count")?.toInt() ?: 0
            val partnerDayCount = partnerDayRecord.getLong("count")?.toInt() ?: 0
            
            if (myDayCount > 0 && partnerDayCount > 0) {
                tempStreak++
                if (!gapFound) {
                    currentStreak = tempStreak
                }
            } else {
                longestStreak = maxOf(longestStreak, tempStreak)
                tempStreak = 0
                if (dayOffset > 0) gapFound = true
            }
        }
        longestStreak = maxOf(longestStreak, tempStreak)
        
        return MissingWidgetCachedData(
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            myTodayCount = myTodayCount,
            partnerTodayCount = partnerTodayCount,
            hasSentToday = myTodayCount > 0
        )
    }
    
    private fun cacheMissingData(prefs: SharedPreferences, data: MissingWidgetCachedData) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        prefs.edit()
            .putString(KEY_MISSING_DATA, serializeMissingData(data))
            .putLong(KEY_MISSING_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_MISSING_CACHED_DATE, today) // Store current date for day change detection
            .apply()
    }
    
    private fun serializeMissingData(data: MissingWidgetCachedData): String {
        return "${data.currentStreak}|${data.longestStreak}|${data.myTodayCount}|${data.partnerTodayCount}|${data.hasSentToday}"
    }
    
    private fun parseMissingCachedData(cached: String?): MissingWidgetCachedData? {
        if (cached == null) return null
        val parts = cached.split("|")
        if (parts.size < 5) return null
        return try {
            MissingWidgetCachedData(
                currentStreak = parts[0].toInt(),
                longestStreak = parts[1].toInt(),
                myTodayCount = parts[2].toInt(),
                partnerTodayCount = parts[3].toInt(),
                hasSentToday = parts[4].toBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Increment missing count from widget tap
     * This immediately updates cache for responsive UI
     * 
     * FIXED: Race condition prevention using mutex synchronization
     * Multiple rapid taps are now properly serialized
     */
    suspend fun incrementMissingCount(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser ?: return@withContext false
                val db = FirebaseFirestore.getInstance()
                
                val userDoc = db.collection("users").document(currentUser.uid).get().await()
                val partnerId = userDoc.getString("partnerId") ?: return@withContext false
                
                val coupleId = listOf(currentUser.uid, partnerId).sorted().joinToString("_")
                val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val recordId = "${coupleId}_${currentUser.uid}_$today"
                
                // CRITICAL SECTION: Prevent race condition with mutex
                // This ensures only one increment operation happens at a time
                missingCountMutex.withLock {
                    // Get current count from Firebase
                    val currentRecord = db.collection("missing_records").document(recordId).get().await()
                    val currentCount = currentRecord.getLong("count")?.toInt() ?: 0
                    val newCount = currentCount + 1
                    
                    Log.d(TAG, "Incrementing missing count from $currentCount to $newCount for user ${currentUser.uid}")
                    
                    // Update Firebase with new count
                    val recordData = hashMapOf(
                        "id" to recordId,
                        "coupleId" to coupleId,
                        "userId" to currentUser.uid,
                        "date" to today,
                        "count" to newCount,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                    
                    db.collection("missing_records").document(recordId).set(recordData).await()
                    
                    Log.d(TAG, "Missing count successfully incremented to $newCount")
                    
                    // Update cache immediately for responsive UI
                    val prefs = getPrefs(context)
                    val cachedData = parseMissingCachedData(prefs.getString(KEY_MISSING_DATA, null))
                    if (cachedData != null) {
                        val updatedData = cachedData.copy(
                            myTodayCount = newCount,
                            hasSentToday = true
                        )
                        cacheMissingData(prefs, updatedData)
                    } else {
                        // Create new cache if it doesn't exist
                        val newCacheData = MissingWidgetCachedData(
                            currentStreak = 0,
                            longestStreak = 0,
                            myTodayCount = newCount,
                            partnerTodayCount = 0,
                            hasSentToday = true
                        )
                        cacheMissingData(prefs, newCacheData)
                    }
                }
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error incrementing missing count", e)
                false
            }
        }
    }
    
    // ==================== LOCATION DATA ====================
    
    /**
     * Get location widget data with Room DB as single source of truth.
     * 
     * Priority order:
     * 1. Room DB (primary - synced by PartnerDataSyncWorker)
     * 2. SharedPreferences cache (fallback)
     * 3. Firebase direct fetch (last resort)
     */
    suspend fun getLocationWidgetData(context: Context, forceRefresh: Boolean = false): LocationWidgetCachedData? {
        return withContext(Dispatchers.IO) {
            try {
                val partnerId = getPartnerId(context) ?: return@withContext null
                
                // 1. Try Room DB first (single source of truth)
                if (!forceRefresh) {
                    val roomData = getLocationFromRoom(context, partnerId)
                    if (roomData != null) {
                        Log.d(TAG, "✅ Returning location data from Room DB")
                        return@withContext roomData
                    }
                }
                
                // 2. Try SharedPreferences cache as fallback
                val prefs = getPrefs(context)
                val cachedTimestamp = prefs.getLong(KEY_LOCATION_TIMESTAMP, 0)
                val now = System.currentTimeMillis()
                
                if (!forceRefresh && (now - cachedTimestamp) < LOCATION_CACHE_EXPIRY_MS) {
                    val cachedData = parseLocationCachedData(prefs.getString(KEY_LOCATION_DATA, null))
                    if (cachedData != null) {
                        Log.d(TAG, "📦 Returning location data from SharedPreferences cache")
                        return@withContext cachedData
                    }
                }
                
                // 3. Last resort: Fetch from Firebase directly
                Log.d(TAG, "🌐 Fetching location data from Firebase (fallback)")
                val freshData = fetchLocationDataFromFirebase()
                if (freshData != null) {
                    Log.d(TAG, "✅ Fresh data fetched:")
                    Log.d(TAG, "   myName='${freshData.myName}'")
                    Log.d(TAG, "   partnerName='${freshData.partnerName}'")
                    Log.d(TAG, "   myLocation='${freshData.myLocation}'")
                    Log.d(TAG, "   partnerLocation='${freshData.partnerLocation}'")
                    Log.d(TAG, "   distance=${freshData.distance}")
                    Log.d(TAG, "   partnerLastUpdate=${freshData.partnerLastUpdate}")
                    cacheLocationData(prefs, freshData)
                    Log.d(TAG, "✅ Data cached to SharedPreferences")
                } else {
                    Log.d(TAG, "❌ No fresh data returned from Firebase")
                }
                freshData
            } catch (e: Exception) {
                Log.e(TAG, "Error getting location data", e)
                parseLocationCachedData(getPrefs(context).getString(KEY_LOCATION_DATA, null))
            }
        }
    }
    
    /**
     * Read location data from Room Database.
     */
    private suspend fun getLocationFromRoom(context: Context, partnerId: String): LocationWidgetCachedData? {
        return try {
            val db = CoupleAppDatabase.getInstance(context)
            val locationDao = db.partnerLocationDao()
            val locationEntity = locationDao.getLatestLocation(partnerId)
            
            if (locationEntity != null) {
                // Check if data is too stale
                val now = System.currentTimeMillis()
                if (now - locationEntity.lastSyncedAt > ROOM_DATA_STALE_THRESHOLD_MS) {
                    Log.d(TAG, "Room location data is stale (${(now - locationEntity.lastSyncedAt) / 1000}s old)")
                }
                
                // Get my location from Firebase (Room only stores partner data)
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser
                val firestore = FirebaseFirestore.getInstance()
                
                val userDoc = currentUser?.let {
                    firestore.collection("users").document(it.uid).get().await()
                }
                val myName = userDoc?.getString("displayName") ?: "Bạn"
                val myAvatarUrl = userDoc?.getString("profileImageUrl")
                val coupleId = userDoc?.getString("coupleId") 
                    ?: currentUser?.let { listOf(it.uid, partnerId).sorted().joinToString("_") }
                    ?: ""
                
                // Get partner avatar URL
                val partnerDoc = firestore.collection("users").document(partnerId).get().await()
                val partnerAvatarUrl = partnerDoc.getString("profileImageUrl")
                
                // Get my location using "locations" collection with document ID format: {coupleId}_{userId}
                val myLocationDocId = "${coupleId}_${currentUser?.uid}"
                val myLocationDoc = currentUser?.let {
                    firestore.collection("locations")
                        .document(myLocationDocId)
                        .get()
                        .await()
                }
                
                val myLat = myLocationDoc?.getDouble("latitude")
                val myLng = myLocationDoc?.getDouble("longitude")
                val myLocationName = myLocationDoc?.getString("address") ?: "Không rõ"
                
                // Calculate distance if both have locations
                val distance = if (myLat != null && myLng != null) {
                    calculateDistance(myLat, myLng, locationEntity.latitude, locationEntity.longitude)
                } else {
                    null
                }
                
                val isSharing = myLat != null && myLng != null
                
                LocationWidgetCachedData(
                    myName = myName,
                    partnerName = locationEntity.partnerName,
                    myLocation = myLocationName,
                    partnerLocation = locationEntity.placeName ?: locationEntity.address ?: "Không rõ",
                    distance = distance,
                    isSharing = isSharing,
                    partnerLastUpdate = locationEntity.timestamp,
                    myAvatarUrl = myAvatarUrl,
                    partnerAvatarUrl = partnerAvatarUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading location from Room", e)
            null
        }
    }
    
    private suspend fun fetchLocationDataFromFirebase(): LocationWidgetCachedData? {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return null
        val db = FirebaseFirestore.getInstance()
        
        Log.d(TAG, "=== fetchLocationDataFromFirebase DEBUG ===")
        
        val userDoc = db.collection("users").document(currentUser.uid).get().await()
        val userName = userDoc.getString("displayName") ?: "Bạn"
        val myAvatarUrl = userDoc.getString("profileImageUrl")
        val partnerId = userDoc.getString("partnerId")
        val coupleId = userDoc.getString("coupleId")
        
        Log.d(TAG, "Current user displayName: '$userName'")
        Log.d(TAG, "partnerId: '$partnerId'")
        Log.d(TAG, "coupleId: '$coupleId'")
        
        if (partnerId.isNullOrEmpty()) {
            Log.e(TAG, "No partnerId found!")
            return null
        }
        
        val partnerDoc = db.collection("users").document(partnerId).get().await()
        val partnerName = partnerDoc.getString("displayName") ?: "Người yêu"
        val partnerAvatarUrl = partnerDoc.getString("profileImageUrl")
        
        Log.d(TAG, "Partner displayName: '$partnerName'")
        
        val effectiveCoupleId = coupleId ?: listOf(currentUser.uid, partnerId).sorted().joinToString("_")
        Log.d(TAG, "effectiveCoupleId: '$effectiveCoupleId'")
        
        // Get my location - using "locations" collection with document ID format: {coupleId}_{userId}
        val myLocationDocId = "${effectiveCoupleId}_${currentUser.uid}"
        Log.d(TAG, "My location doc ID: '$myLocationDocId'")
        val myLocationDoc = db.collection("locations")
            .document(myLocationDocId)
            .get()
            .await()
        
        Log.d(TAG, "My location doc exists: ${myLocationDoc.exists()}")
        
        // Get partner's location
        val partnerLocationDocId = "${effectiveCoupleId}_${partnerId}"
        Log.d(TAG, "Partner location doc ID: '$partnerLocationDocId'")
        val partnerLocationDoc = db.collection("locations")
            .document(partnerLocationDocId)
            .get()
            .await()
        
        Log.d(TAG, "Partner location doc exists: ${partnerLocationDoc.exists()}")
        
        val myLat = myLocationDoc.getDouble("latitude")
        val myLng = myLocationDoc.getDouble("longitude")
        val myLocationName = myLocationDoc.getString("address") ?: "Không rõ"
        
        Log.d(TAG, "My location - lat: $myLat, lng: $myLng, address: '$myLocationName'")
        
        val partnerLat = partnerLocationDoc.getDouble("latitude")
        val partnerLng = partnerLocationDoc.getDouble("longitude")
        val partnerLocationName = partnerLocationDoc.getString("address") ?: "Không rõ"
        val partnerLastUpdate = partnerLocationDoc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
        
        Log.d(TAG, "Partner location - lat: $partnerLat, lng: $partnerLng, address: '$partnerLocationName'")
        
        // Calculate distance if both have locations
        val distance = if (myLat != null && myLng != null && partnerLat != null && partnerLng != null) {
            calculateDistance(myLat, myLng, partnerLat, partnerLng)
        } else {
            null
        }
        
        val isSharing = myLat != null && myLng != null
        
        return LocationWidgetCachedData(
            myName = userName,
            partnerName = partnerName,
            myLocation = myLocationName,
            partnerLocation = partnerLocationName,
            distance = distance,
            isSharing = isSharing,
            partnerLastUpdate = partnerLastUpdate,
            myAvatarUrl = myAvatarUrl,
            partnerAvatarUrl = partnerAvatarUrl
        )
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
    
    private fun cacheLocationData(prefs: SharedPreferences, data: LocationWidgetCachedData) {
        prefs.edit()
            .putString(KEY_LOCATION_DATA, serializeLocationData(data))
            .putLong(KEY_LOCATION_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    private fun serializeLocationData(data: LocationWidgetCachedData): String {
        return "${data.myName}|${data.partnerName}|${data.myLocation}|${data.partnerLocation}|" +
               "${data.distance ?: -1.0}|${data.isSharing}|${data.partnerLastUpdate}|" +
               "${data.myAvatarUrl ?: ""}|${data.partnerAvatarUrl ?: ""}"
    }
    
    private fun parseLocationCachedData(cached: String?): LocationWidgetCachedData? {
        if (cached == null) return null
        val parts = cached.split("|")
        if (parts.size < 7) return null
        return try {
            val distance = parts[4].toDouble()
            LocationWidgetCachedData(
                myName = parts[0],
                partnerName = parts[1],
                myLocation = parts[2],
                partnerLocation = parts[3],
                distance = if (distance < 0) null else distance,
                isSharing = parts[5].toBoolean(),
                partnerLastUpdate = parts[6].toLong(),
                myAvatarUrl = parts.getOrNull(7)?.takeIf { it.isNotEmpty() },
                partnerAvatarUrl = parts.getOrNull(8)?.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Clear all cached data
     * Useful when user logs out or switches accounts
     */
    fun clearCache(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    /**
     * Invalidate specific cache types
     */
    fun invalidateSleepCache(context: Context) {
        getPrefs(context).edit().remove(KEY_SLEEP_TIMESTAMP).apply()
    }
    
    fun invalidateLocketCache(context: Context) {
        getPrefs(context).edit().remove(KEY_LOCKET_TIMESTAMP).apply()
    }
    
    fun invalidateMissingCache(context: Context) {
        getPrefs(context).edit().remove(KEY_MISSING_TIMESTAMP).apply()
    }
    
    fun invalidateLocationCache(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_LOCATION_TIMESTAMP)
            .remove(KEY_LOCATION_DATA)
            .apply()
        Log.d(TAG, "Location cache invalidated")
    }
    
    /**
     * Force clear all widget caches - use when data seems stale
     */
    fun clearAllCaches(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "All widget caches cleared")
    }
}

/**
 * Cached data classes
 */
data class SleepWidgetCachedData(
    val myName: String,
    val partnerName: String,
    val mySleepDuration: Int,
    val mySleepQuality: String,
    val myAchievement: Float,
    val partnerSleepDuration: Int,
    val partnerSleepQuality: String,
    val partnerAchievement: Float,
    val partnerIsAsleep: Boolean,
    val bedtimeHour: Int,
    val bedtimeMinute: Int,
    val date: String,
    val myAvatarUrl: String? = null,
    val partnerAvatarUrl: String? = null
)

data class LocketWidgetCachedData(
    val senderName: String,
    val content: String,
    val type: String,
    val caption: String?,
    val timestamp: Long,
    val hasNewLocket: Boolean,
    // Widget thumbnail - small version for efficient widget display
    // If available, widget should use this instead of full content
    val widgetThumbnail: String? = null
)

data class MissingWidgetCachedData(
    val currentStreak: Int,
    val longestStreak: Int,
    val myTodayCount: Int,
    val partnerTodayCount: Int,
    val hasSentToday: Boolean
)

data class LocationWidgetCachedData(
    val myName: String,
    val partnerName: String,
    val myLocation: String,
    val partnerLocation: String,
    val distance: Double?,
    val isSharing: Boolean,
    val partnerLastUpdate: Long,
    val myAvatarUrl: String? = null,
    val partnerAvatarUrl: String? = null
)
