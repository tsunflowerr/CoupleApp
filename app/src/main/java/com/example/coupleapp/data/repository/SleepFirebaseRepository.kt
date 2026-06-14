package com.example.coupleapp.data.repository

import android.content.Context
import android.util.Log
import com.example.coupleapp.data.health.HealthConnectManager
import com.example.coupleapp.data.health.HealthSleepSession
import com.example.coupleapp.data.model.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

/**
 * Firebase Repository for Sleep Tracker
 */
class SleepFirebaseRepository(
    private val context: Context? = null,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    
    private val healthConnectManager by lazy {
        context?.let { HealthConnectManager(it) }
    }
    
    companion object {
        private const val TAG = "SleepFirebaseRepo"
        private const val SLEEP_SETTINGS_COLLECTION = "sleep_settings"
        private const val SLEEP_RECORDS_COLLECTION = "sleep_records"
        private const val USERS_COLLECTION = "users"
    }
    
    /**
     * Get or create sleep settings for current user
     */
    suspend fun getSleepSettings(userId: String): Result<FirebaseSleepSettings> {
        return try {
            Log.d(TAG, "getSleepSettings: Loading for userId=$userId")
            val doc = firestore.collection(SLEEP_SETTINGS_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            if (doc.exists()) {
                val settings = doc.toObject(FirebaseSleepSettings::class.java)
                Log.d(TAG, "getSleepSettings: Found existing settings - bedTime=${settings?.idealBedTimeHour}:${settings?.idealBedTimeMinute}, duration=${settings?.targetSleepDurationMinutes}")
                Result.success(settings ?: createDefaultSettings(userId))
            } else {
                // Create default settings
                val defaultSettings = createDefaultSettings(userId)
                firestore.collection(SLEEP_SETTINGS_COLLECTION)
                    .document(userId)
                    .set(defaultSettings)
                    .await()
                Result.success(defaultSettings)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSleepSettings: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update sleep settings
     */
    suspend fun updateSleepSettings(settings: FirebaseSleepSettings): Result<Unit> {
        return try {
            Log.d(TAG, "updateSleepSettings: Saving to Firebase - bedTime=${settings.idealBedTimeHour}:${settings.idealBedTimeMinute}, duration=${settings.targetSleepDurationMinutes}")
            firestore.collection(SLEEP_SETTINGS_COLLECTION)
                .document(settings.userId)
                .set(settings)
                .await()
            Log.d(TAG, "updateSleepSettings: Success - saved to Firestore")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateSleepSettings: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get today's sleep record
     */
    suspend fun getTodaySleepRecord(userId: String): Result<FirebaseSleepRecord?> {
        return try {
            val today = LocalDate.now()
            val startOfDay = today.atStartOfDay()
            val endOfDay = today.plusDays(1).atStartOfDay()
            
            val startTimestamp = Timestamp(Date.from(startOfDay.atZone(ZoneId.systemDefault()).toInstant()))
            val endTimestamp = Timestamp(Date.from(endOfDay.atZone(ZoneId.systemDefault()).toInstant()))
            
            Log.d(TAG, "getTodaySleepRecord: Querying for userId=$userId, date range: $startTimestamp to $endTimestamp")
            
            // Workaround: Get all user records and filter client-side until index is ready
            val snapshot = firestore.collection(SLEEP_RECORDS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            
            Log.d(TAG, "getTodaySleepRecord: Query returned ${snapshot.documents.size} total documents for user")
            
            // Log all documents for debugging
            snapshot.documents.forEach { doc ->
                val data = doc.data
                Log.d(TAG, "getTodaySleepRecord: Document - userId=${data?.get("userId")}, date=${data?.get("date")}, quality=${data?.get("quality")}")
            }
            
            // Filter client-side for today's date - skip invalid records
            val allRecords = snapshot.documents.mapNotNull { doc ->
                try {
                    doc.toObject(FirebaseSleepRecord::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "getTodaySleepRecord: Failed to parse document ${doc.id}: ${e.message}")
                    null
                }
            }
            Log.d(TAG, "getTodaySleepRecord: Parsed ${allRecords.size} valid records from ${snapshot.documents.size} documents")
            
            val record = allRecords.firstOrNull { record ->
                val recordDate = record.date ?: return@firstOrNull false
                val match = recordDate.seconds >= startTimestamp.seconds && recordDate.seconds < endTimestamp.seconds
                Log.d(TAG, "getTodaySleepRecord: Checking record - date=${recordDate.seconds} (${startTimestamp.seconds} to ${endTimestamp.seconds}), match=$match")
                match
            }
            
            Log.d(TAG, "getTodaySleepRecord: Found today's record = $record")
            Result.success(record)
        } catch (e: Exception) {
            Log.e(TAG, "getTodaySleepRecord: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get sleep history (last N days)
     */
    suspend fun getSleepHistory(userId: String, days: Int = 7): Result<List<FirebaseSleepRecord>> {
        return try {
            val startDate = LocalDate.now().minusDays(days.toLong())
            val startTimestamp = Timestamp(Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            
            Log.d(TAG, "getSleepHistory: Querying for userId=$userId, days=$days, startDate=$startTimestamp")
            
            // Workaround: Get all user records and filter client-side until index is ready
            val snapshot = firestore.collection(SLEEP_RECORDS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            
            Log.d(TAG, "getSleepHistory: Query returned ${snapshot.documents.size} total documents")
            
            // Log sample documents for debugging
            snapshot.documents.take(3).forEach { doc ->
                val data = doc.data
                Log.d(TAG, "getSleepHistory: Sample doc - userId=${data?.get("userId")}, date=${data?.get("date")}")
            }
            
            // Filter client-side for date range - skip invalid records
            val records = snapshot.documents
                .mapNotNull { doc -> 
                    try {
                        doc.toObject(FirebaseSleepRecord::class.java)
                    } catch (e: Exception) {
                        Log.w(TAG, "getSleepHistory: Failed to parse document ${doc.id}: ${e.message}")
                        null
                    }
                }
                .filter { record -> 
                    val recordDate = record.date ?: return@filter false
                    recordDate.seconds >= startTimestamp.seconds 
                }
                .sortedByDescending { it.date }
            
            Log.d(TAG, "getSleepHistory: Filtered to ${records.size} records in date range")
            records.take(3).forEach { record ->
                Log.d(TAG, "getSleepHistory: Record - userId=${record.userId}, date=${record.date}, quality=${record.quality}")
            }
            
            Result.success(records)
        } catch (e: Exception) {
            Log.e(TAG, "getSleepHistory: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save or update sleep record
     */
    suspend fun saveSleepRecord(record: FirebaseSleepRecord): Result<String> {
        return try {
            val docRef = if (record.id.isEmpty()) {
                firestore.collection(SLEEP_RECORDS_COLLECTION).document()
            } else {
                firestore.collection(SLEEP_RECORDS_COLLECTION).document(record.id)
            }
            
            val recordToSave = record.copy(id = docRef.id)
            docRef.set(recordToSave).await()
            
            Log.d(TAG, "saveSleepRecord: Success ${docRef.id}")
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e(TAG, "saveSleepRecord: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if it's time to sleep
     * Only show reminder if current time is after target bedtime
     */
    fun checkTimeToSleep(targetBedTime: LocalTime): Pair<Boolean, String> {
        val now = LocalTime.now()
        
        return when {
            // Show reminder only if current time is after bedtime
            now.isAfter(targetBedTime) -> {
                val minutesPast = java.time.Duration.between(targetBedTime, now).toMinutes()
                if (minutesPast < 30) {
                    Pair(true, "It's past your bedtime!")
                } else {
                    Pair(false, "")
                }
            }
            else -> {
                // Don't show reminder if it's before bedtime
                Pair(false, "")
            }
        }
    }
    
    /**
     * Calculate sleep quality and achievement
     */
    fun calculateSleepQuality(actualMinutes: Int, targetMinutes: Int): Pair<SleepQuality, Float> {
        val percentage = (actualMinutes.toFloat() / targetMinutes) * 100f
        val quality = when {
            percentage >= 90f -> SleepQuality.EXCELLENT
            percentage >= 75f -> SleepQuality.GOOD
            else -> SleepQuality.POOR
        }
        return Pair(quality, percentage)
    }
    
    /**
     * Get user profile info
     */
    suspend fun getUserProfile(userId: String): Result<UserProfile> {
        return try {
            val doc = firestore.collection(USERS_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            val name = doc.getString("displayName") ?: "User"
            val avatarUrl = doc.getString("profileImageUrl")
            
            Result.success(UserProfile(userId, name, avatarUrl))
        } catch (e: Exception) {
            Log.e(TAG, "getUserProfile: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get partner's user ID
     */
    suspend fun getPartnerId(): Result<String?> {
        return try {
            val currentUser = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            
            val doc = firestore.collection(USERS_COLLECTION)
                .document(currentUser.uid)
                .get()
                .await()
            
            val partnerId = doc.getString("partnerId")
            Result.success(partnerId)
        } catch (e: Exception) {
            Log.e(TAG, "getPartnerId: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Realtime listener for sleep records
     */
    fun getSleepRecordsFlow(userId: String): Flow<List<FirebaseSleepRecord>> = callbackFlow {
        val listener = firestore.collection(SLEEP_RECORDS_COLLECTION)
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "getSleepRecordsFlow: Error", error)
                    close(error)
                    return@addSnapshotListener
                }
                
                val records = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(FirebaseSleepRecord::class.java)
                }?.sortedByDescending { it.date } ?: emptyList()
                
                trySend(records)
            }
        
        awaitClose { listener.remove() }
    }
    
    /**
     * Create default settings
     */
    private fun createDefaultSettings(userId: String): FirebaseSleepSettings {
        return FirebaseSleepSettings(
            id = userId,
            userId = userId,
            targetSleepDurationMinutes = 480, // 8 hours
            idealBedTimeHour = 22,
            idealBedTimeMinute = 0,
            idealWakeUpTimeHour = 6,
            idealWakeUpTimeMinute = 0
        )
    }
    
    /**
     * Convert Firebase models to app models
     * 
     * IMPORTANT: Validates and recalculates duration if there's inconsistency
     * between stored duration and bed/wake times.
     */
    fun convertToSleepRecord(firebaseRecord: FirebaseSleepRecord): SleepRecord {
        val date = firebaseRecord.date?.toDate()?.toInstant()
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?: LocalDateTime.now()
        
        // Calculate expected duration from bed/wake times
        val calculatedDuration = calculateDurationFromTimes(
            firebaseRecord.bedTimeHour, firebaseRecord.bedTimeMinute,
            firebaseRecord.wakeUpTimeHour, firebaseRecord.wakeUpTimeMinute
        )
        
        // Use stored duration unless there's a significant mismatch (>30 min difference)
        // If mismatch, prefer calculated duration as it's more reliable
        val storedDuration = firebaseRecord.actualSleepDurationMinutes
        val actualDuration = if (kotlin.math.abs(storedDuration - calculatedDuration) > 30) {
            Log.w(TAG, "convertToSleepRecord: Duration mismatch detected! " +
                "Stored=${storedDuration}min, Calculated=${calculatedDuration}min. " +
                "Using calculated value for display.")
            calculatedDuration
        } else {
            storedDuration
        }
        
        // Same check for sleepDurationMinutes
        val storedSleepDuration = firebaseRecord.sleepDurationMinutes
        val sleepDuration = if (kotlin.math.abs(storedSleepDuration - calculatedDuration) > 30 && storedSleepDuration > 0) {
            calculatedDuration - firebaseRecord.awakeDurationMinutes
        } else if (storedSleepDuration == 0) {
            // If sleepDurationMinutes not set, calculate from actual duration
            actualDuration - firebaseRecord.awakeDurationMinutes
        } else {
            storedSleepDuration
        }
        
        return SleepRecord(
            id = firebaseRecord.id,
            date = date,
            bedTime = LocalTime.of(firebaseRecord.bedTimeHour, firebaseRecord.bedTimeMinute),
            wakeUpTime = LocalTime.of(firebaseRecord.wakeUpTimeHour, firebaseRecord.wakeUpTimeMinute),
            actualSleepDuration = actualDuration,
            targetSleepDuration = firebaseRecord.targetSleepDurationMinutes,
            sleepStages = SleepStage(
                awakeDurationMinutes = firebaseRecord.awakeDurationMinutes,
                sleepDurationMinutes = sleepDuration.coerceAtLeast(0)
            ),
            quality = when (firebaseRecord.quality) {
                "EXCELLENT" -> SleepQuality.EXCELLENT
                "GOOD" -> SleepQuality.GOOD
                else -> SleepQuality.POOR
            },
            achievementPercentage = firebaseRecord.achievementPercentage,
            userId = firebaseRecord.userId
        )
    }
    
    fun convertToSleepSettings(firebaseSettings: FirebaseSleepSettings): SleepSettings {
        return SleepSettings(
            targetSleepDuration = firebaseSettings.targetSleepDurationMinutes,
            idealBedTime = LocalTime.of(firebaseSettings.idealBedTimeHour, firebaseSettings.idealBedTimeMinute),
            idealWakeUpTime = LocalTime.of(firebaseSettings.idealWakeUpTimeHour, firebaseSettings.idealWakeUpTimeMinute),
            userId = firebaseSettings.userId
        )
    }
    
    /**
     * Insert mock sleep data for testing (both user and partner)
     */
    suspend fun insertMockSleepData(userId: String, partnerId: String?): Result<Unit> {
        return try {
            Log.d(TAG, "insertMockSleepData: Inserting for user=$userId, partner=$partnerId")
            
            val today = LocalDate.now()
            
            data class MockSleepData(
                val bedHour: Int, val bedMin: Int,
                val wakeHour: Int, val wakeMin: Int,
                val quality: String, val achievement: Float
            )
            
            val mockDataList = mutableMapOf<String, List<MockSleepData>>()
            
            // Mock data for current user (last 7 days)
            mockDataList[userId] = listOf(
                MockSleepData(22, 30, 6, 15, "EXCELLENT", 95f),  // Today
                MockSleepData(23, 0, 7, 0, "GOOD", 85f),          // Yesterday
                MockSleepData(22, 15, 6, 30, "EXCELLENT", 98f),   // 2 days ago
                MockSleepData(23, 30, 5, 45, "POOR", 65f),        // 3 days ago
                MockSleepData(22, 0, 6, 0, "EXCELLENT", 100f),    // 4 days ago
                MockSleepData(23, 15, 6, 45, "GOOD", 88f),        // 5 days ago
                MockSleepData(22, 45, 6, 30, "EXCELLENT", 92f)    // 6 days ago
            )
            
            // Mock data for partner if exists
            if (partnerId != null) {
                mockDataList[partnerId] = listOf(
                    MockSleepData(21, 30, 5, 30, "EXCELLENT", 100f),
                    MockSleepData(22, 0, 6, 0, "EXCELLENT", 100f),
                    MockSleepData(21, 45, 5, 45, "EXCELLENT", 100f),
                    MockSleepData(22, 30, 6, 15, "GOOD", 90f),
                    MockSleepData(23, 0, 6, 30, "GOOD", 88f),
                    MockSleepData(22, 15, 6, 0, "EXCELLENT", 95f),
                    MockSleepData(21, 30, 5, 30, "EXCELLENT", 100f)
                )
            }
            
            // Insert data for each user
            for ((targetUserId, dataList) in mockDataList) {
                dataList.forEachIndexed { index, mockData ->
                    val date = today.minusDays(index.toLong())
                    val dateTime = date.atStartOfDay()
                    
                    // Calculate sleep duration
                    val bedTime = LocalTime.of(mockData.bedHour, mockData.bedMin)
                    val wakeTime = LocalTime.of(mockData.wakeHour, mockData.wakeMin)
                    val duration = if (wakeTime.isAfter(bedTime)) {
                        java.time.Duration.between(bedTime, wakeTime).toMinutes().toInt()
                    } else {
                        // Overnight sleep
                        (java.time.Duration.between(bedTime, LocalTime.MAX).toMinutes() +
                         java.time.Duration.between(LocalTime.MIN, wakeTime).toMinutes()).toInt()
                    }
                    
                    val recordId = "${targetUserId}_${date}"
                    val timestamp = Timestamp(Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()))
                    val record = FirebaseSleepRecord(
                        id = recordId,
                        userId = targetUserId,
                        coupleId = "",
                        date = timestamp,
                        bedTimeHour = mockData.bedHour,
                        bedTimeMinute = mockData.bedMin,
                        wakeUpTimeHour = mockData.wakeHour,
                        wakeUpTimeMinute = mockData.wakeMin,
                        actualSleepDurationMinutes = duration,
                        targetSleepDurationMinutes = 480, // 8 hours
                        awakeDurationMinutes = (duration * 0.1).toInt(),
                        sleepDurationMinutes = (duration * 0.9).toInt(),
                        quality = mockData.quality,
                        achievementPercentage = mockData.achievement
                    )
                    
                    firestore.collection(SLEEP_RECORDS_COLLECTION)
                        .document(recordId)
                        .set(record)
                        .await()
                    
                    Log.d(TAG, "insertMockSleepData: Inserted record for $targetUserId on $date, timestamp=$timestamp, userId=${record.userId}")
                }
                
                // Create default settings if not exists
                val settingsDoc = firestore.collection(SLEEP_SETTINGS_COLLECTION)
                    .document(targetUserId)
                    .get()
                    .await()
                
                if (!settingsDoc.exists()) {
                    val settings = createDefaultSettings(targetUserId)
                    firestore.collection(SLEEP_SETTINGS_COLLECTION)
                        .document(targetUserId)
                        .set(settings)
                        .await()
                    Log.d(TAG, "insertMockSleepData: Created default settings for $targetUserId")
                }
            }
            
            Log.d(TAG, "insertMockSleepData: Successfully inserted all mock data")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "insertMockSleepData: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if Health Connect is available
     */
    suspend fun isHealthConnectAvailable(): Boolean {
        return healthConnectManager?.isAvailable() ?: false
    }
    
    /**
     * Check if Health Connect permissions are granted
     */
    suspend fun hasHealthConnectPermissions(): Boolean {
        return healthConnectManager?.hasAllPermissions() ?: false
    }
    
    /**
     * Get Health Connect permission contract
     */
    fun getHealthConnectPermissionContract(): androidx.activity.result.contract.ActivityResultContract<Set<String>, Set<String>> {
        return healthConnectManager?.createPermissionRequestContract() 
            ?: throw IllegalStateException("Health Connect not initialized")
    }
    
    /**
     * Get required Health Connect permissions
     */
    fun getRequiredHealthConnectPermissions(): Set<String> {
        return com.example.coupleapp.data.health.HealthConnectManager.PERMISSIONS
    }
    
    /**
     * Sync sleep data from Health Connect to Firebase
     */
    suspend fun syncSleepDataFromHealthConnect(userId: String): Result<Int> {
        return try {
            val manager = healthConnectManager 
                ?: return Result.failure(Exception("Health Connect not initialized"))
            
            if (!manager.hasAllPermissions()) {
                return Result.failure(SecurityException("Missing Health Connect permissions"))
            }
            
            Log.d(TAG, "syncSleepDataFromHealthConnect: Starting sync for user=$userId")
            
            // Read last 7 days of sleep data
            val result = manager.readRecentSleepSessions(7)
            
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Failed to read Health Connect data"))
            }
            
            val sessions = result.getOrNull() ?: emptyList()
            Log.d(TAG, "syncSleepDataFromHealthConnect: Found ${sessions.size} sessions from Health Connect")
            
            var syncCount = 0
            
            // Sync each session to Firebase
            for (session in sessions) {
                val recordId = "${userId}_${session.date}"
                
                // Check if record already exists
                val existingDoc = firestore.collection(SLEEP_RECORDS_COLLECTION)
                    .document(recordId)
                    .get()
                    .await()
                
                // Only sync if doesn't exist or is older
                if (!existingDoc.exists()) {
                    val targetDuration = 480 // 8 hours default
                    val achievement = (session.totalSleepMinutes.toFloat() / targetDuration) * 100f
                    
                    val record = FirebaseSleepRecord(
                        id = recordId,
                        userId = userId,
                        coupleId = "",
                        date = Timestamp(Date.from(session.startTime)),
                        bedTimeHour = session.bedTime.hour,
                        bedTimeMinute = session.bedTime.minute,
                        wakeUpTimeHour = session.wakeUpTime.hour,
                        wakeUpTimeMinute = session.wakeUpTime.minute,
                        actualSleepDurationMinutes = session.totalSleepMinutes,
                        targetSleepDurationMinutes = targetDuration,
                        awakeDurationMinutes = session.awakeDurationMinutes,
                        sleepDurationMinutes = session.sleepDurationMinutes,
                        quality = when (session.quality) {
                            SleepQuality.EXCELLENT -> "EXCELLENT"
                            SleepQuality.GOOD -> "GOOD"
                            SleepQuality.POOR -> "POOR"
                        },
                        achievementPercentage = achievement.coerceIn(0f, 150f)
                    )
                    
                    firestore.collection(SLEEP_RECORDS_COLLECTION)
                        .document(recordId)
                        .set(record)
                        .await()
                    
                    syncCount++
                    Log.d(TAG, "syncSleepDataFromHealthConnect: Synced record for ${session.date}")
                }
            }
            
            Log.d(TAG, "syncSleepDataFromHealthConnect: Successfully synced $syncCount/${ sessions.size} records")
            Result.success(syncCount)
        } catch (e: Exception) {
            Log.e(TAG, "syncSleepDataFromHealthConnect: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get today's sleep data from Health Connect
     */
    suspend fun getTodaySleepFromHealthConnect(): Result<HealthSleepSession?> {
        return try {
            val manager = healthConnectManager 
                ?: return Result.failure(Exception("Health Connect not initialized"))
            
            if (!manager.hasAllPermissions()) {
                return Result.failure(SecurityException("Missing Health Connect permissions"))
            }
            
            manager.readTodaySleepSession()
        } catch (e: Exception) {
            Log.e(TAG, "getTodaySleepFromHealthConnect: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Convert HealthSleepSession to FirebaseSleepRecord
     */
    fun convertHealthSessionToFirebaseRecord(
        session: HealthSleepSession,
        userId: String,
        targetDuration: Int = 480
    ): FirebaseSleepRecord {
        val achievement = (session.totalSleepMinutes.toFloat() / targetDuration) * 100f
        
        return FirebaseSleepRecord(
            id = "${userId}_${session.date}",
            userId = userId,
            coupleId = "",
            date = Timestamp(Date.from(session.startTime)),
            bedTimeHour = session.bedTime.hour,
            bedTimeMinute = session.bedTime.minute,
            wakeUpTimeHour = session.wakeUpTime.hour,
            wakeUpTimeMinute = session.wakeUpTime.minute,
            actualSleepDurationMinutes = session.totalSleepMinutes,
            targetSleepDurationMinutes = targetDuration,
            awakeDurationMinutes = session.awakeDurationMinutes,
            sleepDurationMinutes = session.sleepDurationMinutes,
            quality = when (session.quality) {
                SleepQuality.EXCELLENT -> "EXCELLENT"
                SleepQuality.GOOD -> "GOOD"
                SleepQuality.POOR -> "POOR"
            },
            achievementPercentage = achievement.coerceIn(0f, 150f)
        )
    }
    
    /**
     * Auto-sync yesterday's sleep data from Health Connect
     * Should be called daily to keep data up to date
     */
    suspend fun autoSyncYesterdaySleepData(userId: String, healthManager: HealthConnectManager): Result<Boolean> {
        return try {
            if (!healthManager.hasAllPermissions()) {
                Log.w(TAG, "autoSyncYesterdaySleepData: Missing Health Connect permissions")
                return Result.success(false)
            }
            
            val yesterday = LocalDate.now().minusDays(1)
            val yesterdayId = "${userId}_${yesterday}"
            
            // Check if yesterday's data already exists
            val existingDoc = firestore.collection(SLEEP_RECORDS_COLLECTION)
                .document(yesterdayId)
                .get()
                .await()
            
            if (existingDoc.exists()) {
                Log.d(TAG, "autoSyncYesterdaySleepData: Yesterday's data already exists")
                return Result.success(true)
            }
            
            // Get yesterday's sleep data from Health Connect
            val sessionsResult = healthManager.readSleepSessions(yesterday, yesterday)
            
            if (sessionsResult.isFailure) {
                Log.e(TAG, "autoSyncYesterdaySleepData: Failed to read from Health Connect")
                return Result.success(false)
            }
            
            val sessions = sessionsResult.getOrNull() ?: emptyList()
            
            if (sessions.isEmpty()) {
                Log.d(TAG, "autoSyncYesterdaySleepData: No sleep data found for yesterday")
                return Result.success(false)
            }
            
            // Get user's target sleep duration
            val settings = getSleepSettings(userId).getOrNull()
            val targetDuration = settings?.targetSleepDurationMinutes ?: 480
            
            // Use the longest session of the day
            val mainSession = sessions.maxByOrNull { it.totalSleepMinutes }
            if (mainSession != null) {
                val record = convertHealthSessionToFirebaseRecord(mainSession, userId, targetDuration)
                
                firestore.collection(SLEEP_RECORDS_COLLECTION)
                    .document(yesterdayId)
                    .set(record)
                    .await()
                
                Log.d(TAG, "autoSyncYesterdaySleepData: Successfully synced yesterday's data")
                Result.success(true)
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "autoSyncYesterdaySleepData: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if we should trigger auto-sync (once per day)
     * Returns true if last sync was more than 24 hours ago
     */
    suspend fun shouldAutoSync(userId: String): Boolean {
        return try {
            val settingsDoc = firestore.collection(SLEEP_SETTINGS_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            val lastSyncTimestamp = settingsDoc.getTimestamp("lastAutoSync")
            
            if (lastSyncTimestamp == null) {
                return true // Never synced before
            }
            
            val lastSyncTime = lastSyncTimestamp.toDate().toInstant()
            val hoursSinceLastSync = java.time.Duration.between(lastSyncTime, Instant.now()).toHours()
            
            hoursSinceLastSync >= 24
        } catch (e: Exception) {
            Log.e(TAG, "shouldAutoSync: Error", e)
            true // Sync on error to be safe
        }
    }
    
    /**
     * Update last auto-sync timestamp
     */
    suspend fun updateLastAutoSyncTime(userId: String) {
        try {
            firestore.collection(SLEEP_SETTINGS_COLLECTION)
                .document(userId)
                .update("lastAutoSync", FieldValue.serverTimestamp())
                .await()
            
            Log.d(TAG, "updateLastAutoSyncTime: Updated for user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "updateLastAutoSyncTime: Error", e)
        }
    }
    
    // ========== Manual Sleep Tracking ==========
    
    /**
     * Start manual sleep tracking
     */
    suspend fun startManualSleepTracking(userId: String): Result<String> {
        return try {
            Log.d(TAG, "startManualSleepTracking: Creating session for user $userId")
            val docRef = firestore.collection("active_sleep_sessions").document(userId)
            
            // Use explicit map to ensure field names are correct
            // NOTE: Do NOT include 'id' field - it conflicts with @DocumentId annotation
            val sessionData = hashMapOf(
                "userId" to userId,
                "startTime" to Timestamp.now(),
                "isActive" to true,
                "active" to true,  // Include both for backward compatibility
                "createdAt" to FieldValue.serverTimestamp()
            )
            
            Log.d(TAG, "startManualSleepTracking: Writing to Firestore with fields: ${sessionData.keys}")
            docRef.set(sessionData).await()
            
            Log.d(TAG, "startManualSleepTracking: Successfully written to Firestore - doc path: ${docRef.path}")
            Result.success(userId)
        } catch (e: Exception) {
            Log.e(TAG, "startManualSleepTracking: Error writing to Firestore", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get active sleep session
     */
    suspend fun getActiveSleepSession(userId: String): Result<FirebaseActiveSleepSession?> {
        return try {
            Log.d(TAG, "getActiveSleepSession: Querying for user $userId")
            val doc = firestore.collection("active_sleep_sessions")
                .document(userId)
                .get()
                .await()
            
            Log.d(TAG, "getActiveSleepSession: Query complete - doc.exists=${doc.exists()}, doc.id=${doc.id}")
            
            if (doc.exists()) {
                // Check both field names for compatibility (Firestore may have "active" or "isActive")
                val isActive = doc.getBoolean("isActive") ?: doc.getBoolean("active")
                val data = doc.data
                Log.d(TAG, "getActiveSleepSession: Found doc - isActive=$isActive, data keys=${data?.keys}")
                
                if (isActive == true) {
                    // Parse manually to avoid @DocumentId conflict with existing 'id' field
                    val startTime = doc.getTimestamp("startTime")
                    val session = FirebaseActiveSleepSession(
                        id = doc.id,
                        userId = doc.getString("userId") ?: userId,
                        startTime = startTime,
                        isActive = true
                    )
                    Log.d(TAG, "getActiveSleepSession: Returning active session - startTime=$startTime")
                    Result.success(session)
                } else {
                    Log.d(TAG, "getActiveSleepSession: Session exists but isActive=false")
                    Result.success(null)
                }
            } else {
                Log.d(TAG, "getActiveSleepSession: No document found")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveSleepSession: Error querying Firestore", e)
            Result.failure(e)
        }
    }
    
    /**
     * End manual sleep tracking and create record
     */
    suspend fun endManualSleepTracking(userId: String): Result<FirebaseSleepRecord> {
        return try {
            val sessionDoc = firestore.collection("active_sleep_sessions")
                .document(userId)
                .get()
                .await()
            
            if (!sessionDoc.exists()) {
                return Result.failure(Exception("No active sleep session found"))
            }
            
            val session = sessionDoc.toObject(FirebaseActiveSleepSession::class.java)
                ?: return Result.failure(Exception("Failed to parse sleep session"))
            
            val startTime = session.startTime?.toDate()?.toInstant()
                ?: return Result.failure(Exception("Invalid start time"))
            
            val endTime = Instant.now()
            val durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes().toInt()
            
            // Convert to LocalDateTime for bed/wake times
            val startDateTime = LocalDateTime.ofInstant(startTime, ZoneId.systemDefault())
            val endDateTime = LocalDateTime.ofInstant(endTime, ZoneId.systemDefault())
            
            // Get settings for target duration
            val settings = getSleepSettings(userId).getOrNull()
            val targetDuration = settings?.targetSleepDurationMinutes ?: 480
            
            // Calculate quality
            val (quality, achievement) = calculateSleepQuality(durationMinutes, targetDuration)
            
            // Get couple ID
            val coupleIdResult = getCoupleId(userId)
            val coupleId = coupleIdResult.getOrNull() ?: ""
            
            // Create sleep record
            val record = FirebaseSleepRecord(
                userId = userId,
                coupleId = coupleId,
                date = Timestamp(Date.from(startDateTime.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant())),
                bedTimeHour = startDateTime.hour,
                bedTimeMinute = startDateTime.minute,
                wakeUpTimeHour = endDateTime.hour,
                wakeUpTimeMinute = endDateTime.minute,
                actualSleepDurationMinutes = durationMinutes,
                targetSleepDurationMinutes = targetDuration,
                sleepDurationMinutes = durationMinutes,
                quality = quality.name,
                achievementPercentage = achievement,
                trackingMethod = "MANUAL",
                isManualTracking = false,
                manualSleepStartTime = session.startTime
            )
            
            // Save record
            val recordId = saveSleepRecord(record).getOrThrow()
            
            // Delete active session
            firestore.collection("active_sleep_sessions")
                .document(userId)
                .delete()
                .await()
            
            Log.d(TAG, "endManualSleepTracking: Created record $recordId, duration=$durationMinutes minutes")
            Result.success(record.copy(id = recordId))
        } catch (e: Exception) {
            Log.e(TAG, "endManualSleepTracking: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Calculate sleep duration from bed time to wake time in minutes.
     * Handles overnight sleep (e.g., 23:00 to 6:00).
     * 
     * @return Duration in minutes
     */
    private fun calculateDurationFromTimes(
        bedHour: Int, bedMinute: Int,
        wakeHour: Int, wakeMinute: Int
    ): Int {
        val bedTimeMinutes = bedHour * 60 + bedMinute
        var wakeTimeMinutes = wakeHour * 60 + wakeMinute
        
        // Handle overnight sleep (e.g., 23:00 -> 6:00)
        // If wake time is "before" bed time, add 24 hours
        if (wakeTimeMinutes <= bedTimeMinutes) {
            wakeTimeMinutes += 24 * 60 // Add 24 hours
        }
        
        val duration = wakeTimeMinutes - bedTimeMinutes
        
        // Sanity check: sleep should be between 30 min and 16 hours
        return when {
            duration < 30 -> {
                Log.w(TAG, "calculateDurationFromTimes: Suspiciously short sleep ($duration min)")
                duration
            }
            duration > 16 * 60 -> {
                Log.w(TAG, "calculateDurationFromTimes: Suspiciously long sleep ($duration min), capping at 16h")
                16 * 60
            }
            else -> duration
        }
    }
    
    // ========== Google Sleep API Integration ==========
    
    /**
     * Save sleep segment from Google API
     * 
     * @param trackingMethod The source of sleep data:
     *   - "GOOGLE_API": Official SleepSegmentEvent from Google (delivered after waking, may have delay)
     *   - "GOOGLE_API_PARTIAL": SleepSegmentEvent with STATUS_MISSING_DATA
     *   - "GOOGLE_API_CLASSIFY": Calculated from SleepClassifyEvents (more accurate timing, real-time)
     * 
     * IMPORTANT: sleepDurationMinutes is now calculated from bedTime to wakeUpTime,
     * NOT from Google's durationMillis (which can be inaccurate or represent something else)
     */
    suspend fun saveSleepSegmentFromGoogleApi(
        userId: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        durationMillis: Long,
        trackingMethod: String = "GOOGLE_API"
    ): Result<String> {
        return try {
            val startInstant = Instant.ofEpochMilli(startTimeMillis)
            val endInstant = Instant.ofEpochMilli(endTimeMillis)
            
            val startDateTime = LocalDateTime.ofInstant(startInstant, ZoneId.systemDefault())
            val endDateTime = LocalDateTime.ofInstant(endInstant, ZoneId.systemDefault())
            
            // CRITICAL FIX: Calculate duration from actual times, not from Google's durationMillis
            // Google's durationMillis can be inaccurate in some edge cases
            val calculatedDurationMs = endTimeMillis - startTimeMillis
            val durationMinutes = (calculatedDurationMs / 1000 / 60).toInt()
            
            // Log if there's a significant difference between Google's duration and calculated
            val googleDurationMinutes = (durationMillis / 1000 / 60).toInt()
            if (kotlin.math.abs(durationMinutes - googleDurationMinutes) > 30) {
                Log.w(TAG, "saveSleepSegmentFromGoogleApi: Duration mismatch! " +
                    "Calculated=${durationMinutes}min, Google=${googleDurationMinutes}min. " +
                    "Using calculated value.")
            }
            
            Log.d(TAG, "saveSleepSegmentFromGoogleApi: Processing sleep data")
            Log.d(TAG, "  Start: $startDateTime")
            Log.d(TAG, "  End: $endDateTime")
            Log.d(TAG, "  Duration (calculated): $durationMinutes minutes")
            Log.d(TAG, "  Duration (Google API): $googleDurationMinutes minutes")
            Log.d(TAG, "  Method: $trackingMethod")
            
            // Get settings
            val settings = getSleepSettings(userId).getOrNull()
            val targetDuration = settings?.targetSleepDurationMinutes ?: 480
            
            // Calculate quality
            val (quality, achievement) = calculateSleepQuality(durationMinutes, targetDuration)
            
            // Get couple ID
            val coupleIdResult = getCoupleId(userId)
            val coupleId = coupleIdResult.getOrNull() ?: ""
            
            // Determine the date for this sleep record
            // Use the date when sleep ENDED (wake up date) as the record date
            // This matches how most sleep apps work (e.g., sleep on Jan 24 night, wake up Jan 25 = Jan 25's sleep)
            val recordDate = endDateTime.toLocalDate()
            val recordTimestamp = Timestamp(Date.from(recordDate.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            
            // Create record
            val record = FirebaseSleepRecord(
                userId = userId,
                coupleId = coupleId,
                date = recordTimestamp,
                bedTimeHour = startDateTime.hour,
                bedTimeMinute = startDateTime.minute,
                wakeUpTimeHour = endDateTime.hour,
                wakeUpTimeMinute = endDateTime.minute,
                actualSleepDurationMinutes = durationMinutes,
                targetSleepDurationMinutes = targetDuration,
                sleepDurationMinutes = durationMinutes,
                quality = quality.name,
                achievementPercentage = achievement,
                trackingMethod = trackingMethod,
                isManualTracking = false
            )
            
            // Check if record already exists for this date
            val existingRecord = getSleepRecordForDate(userId, recordDate).getOrNull()
            
            if (existingRecord != null) {
                // Define tracking method priority (higher = more reliable)
                // Priority: MANUAL > GOOGLE_API_CLASSIFY > GOOGLE_API > GOOGLE_API_FORCE_SYNC > GOOGLE_API_PARTIAL
                val methodPriority = mapOf(
                    "MANUAL" to 100,
                    "GOOGLE_API_CLASSIFY" to 80,
                    "GOOGLE_API" to 60,
                    "GOOGLE_API_FORCE_SYNC" to 50, // NEW: Force sync is less reliable than real-time
                    "GOOGLE_API_PARTIAL" to 40
                )
                
                val existingPriority = methodPriority[existingRecord.trackingMethod] ?: 0
                val newPriority = methodPriority[trackingMethod] ?: 0
                
                Log.d(TAG, "saveSleepSegmentFromGoogleApi: Existing record found")
                Log.d(TAG, "  Existing: method=${existingRecord.trackingMethod}, priority=$existingPriority, duration=${existingRecord.actualSleepDurationMinutes}min")
                Log.d(TAG, "  New: method=$trackingMethod, priority=$newPriority, duration=${durationMinutes}min")
                
                when {
                    // Don't overwrite manual tracking
                    existingRecord.trackingMethod == "MANUAL" -> {
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Skipping - manual record exists")
                        return Result.success(existingRecord.id)
                    }
                    
                    // Check if this is a separate sleep segment (e.g., nap after main sleep, or split sleep)
                    // If bedtimes differ by more than 2 hours, this might be a separate segment to MERGE
                    kotlin.math.abs(existingRecord.bedTimeHour * 60 + existingRecord.bedTimeMinute - 
                                   (startDateTime.hour * 60 + startDateTime.minute)) > 120 -> {
                        // Different sleep segments in the same day - MERGE them
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Detected multiple sleep segments, MERGING")
                        
                        // Calculate merged values
                        // Use earliest bed time and latest wake time
                        val existingBedTimeMinutes = existingRecord.bedTimeHour * 60 + existingRecord.bedTimeMinute
                        val newBedTimeMinutes = startDateTime.hour * 60 + startDateTime.minute
                        val existingWakeTimeMinutes = existingRecord.wakeUpTimeHour * 60 + existingRecord.wakeUpTimeMinute
                        val newWakeTimeMinutes = endDateTime.hour * 60 + endDateTime.minute
                        
                        // Handle overnight bedtime comparison (e.g., 23:00 vs 02:00)
                        // Determine which bedtime is "earlier" in a sleep context
                        val earliestBedHour: Int
                        val earliestBedMinute: Int
                        
                        if (existingBedTimeMinutes > 12 * 60 && newBedTimeMinutes < 12 * 60) {
                            // Existing is PM (like 23:00), new is AM (like 02:00) - existing is earlier
                            earliestBedHour = existingRecord.bedTimeHour
                            earliestBedMinute = existingRecord.bedTimeMinute
                        } else if (newBedTimeMinutes > 12 * 60 && existingBedTimeMinutes < 12 * 60) {
                            // New is PM, existing is AM - new is earlier
                            earliestBedHour = startDateTime.hour
                            earliestBedMinute = startDateTime.minute
                        } else {
                            // Both same period - use simple comparison
                            if (existingBedTimeMinutes <= newBedTimeMinutes) {
                                earliestBedHour = existingRecord.bedTimeHour
                                earliestBedMinute = existingRecord.bedTimeMinute
                            } else {
                                earliestBedHour = startDateTime.hour
                                earliestBedMinute = startDateTime.minute
                            }
                        }
                        
                        // Determine latest wake time
                        val latestWakeHour: Int
                        val latestWakeMinute: Int
                        
                        if (newWakeTimeMinutes >= existingWakeTimeMinutes) {
                            latestWakeHour = endDateTime.hour
                            latestWakeMinute = endDateTime.minute
                        } else {
                            latestWakeHour = existingRecord.wakeUpTimeHour
                            latestWakeMinute = existingRecord.wakeUpTimeMinute
                        }
                        
                        // CRITICAL FIX: Calculate merged duration from times, not sum of durations
                        // Old logic: sum both durations (caused 18h sleep bug)
                        // New logic: Calculate from earliest bed to latest wake, accounting for overnight
                        val mergedDuration = calculateDurationFromTimes(
                            earliestBedHour, earliestBedMinute,
                            latestWakeHour, latestWakeMinute
                        )
                        
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Merge calculation - " +
                            "bed=${earliestBedHour}:${earliestBedMinute}, " +
                            "wake=${latestWakeHour}:${latestWakeMinute}, " +
                            "duration=${mergedDuration}min")
                        
                        // Recalculate quality with merged duration
                        val (mergedQuality, mergedAchievement) = calculateSleepQuality(mergedDuration, targetDuration)
                        
                        // Use higher priority tracking method
                        val mergedTrackingMethod = if (existingPriority >= newPriority) {
                            existingRecord.trackingMethod
                        } else {
                            trackingMethod
                        }
                        
                        val mergedRecord = existingRecord.copy(
                            bedTimeHour = earliestBedHour,
                            bedTimeMinute = earliestBedMinute,
                            wakeUpTimeHour = latestWakeHour,
                            wakeUpTimeMinute = latestWakeMinute,
                            actualSleepDurationMinutes = mergedDuration,
                            sleepDurationMinutes = mergedDuration,
                            quality = mergedQuality.name,
                            achievementPercentage = mergedAchievement,
                            trackingMethod = mergedTrackingMethod
                        )
                        
                        saveSleepRecord(mergedRecord).getOrThrow()
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Merged records - total duration=${mergedDuration}min")
                        return Result.success(existingRecord.id)
                    }
                    
                    // Same sleep segment - apply priority rules
                    newPriority > existingPriority -> {
                        // New record has higher priority - update
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Updating with higher priority method ($trackingMethod > ${existingRecord.trackingMethod})")
                        val updatedRecord = record.copy(id = existingRecord.id)
                        saveSleepRecord(updatedRecord).getOrThrow()
                        return Result.success(existingRecord.id)
                    }
                    
                    newPriority == existingPriority && durationMinutes > existingRecord.actualSleepDurationMinutes -> {
                        // Same priority but longer duration - update (captures more sleep data)
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Updating with longer duration ($durationMinutes > ${existingRecord.actualSleepDurationMinutes})")
                        val updatedRecord = record.copy(id = existingRecord.id)
                        saveSleepRecord(updatedRecord).getOrThrow()
                        return Result.success(existingRecord.id)
                    }
                    
                    else -> {
                        // Keep existing record (higher or equal priority with equal/more duration)
                        Log.d(TAG, "saveSleepSegmentFromGoogleApi: Keeping existing record")
                        return Result.success(existingRecord.id)
                    }
                }
            }
            
            val recordId = saveSleepRecord(record).getOrThrow()
            Log.d(TAG, "saveSleepSegmentFromGoogleApi: Saved NEW record $recordId")
            
            Result.success(recordId)
        } catch (e: Exception) {
            Log.e(TAG, "saveSleepSegmentFromGoogleApi: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get sleep record for a specific date
     */
    private suspend fun getSleepRecordForDate(userId: String, date: LocalDate): Result<FirebaseSleepRecord?> {
        return try {
            val startOfDay = date.atStartOfDay()
            val endOfDay = date.plusDays(1).atStartOfDay()
            
            val startTimestamp = Timestamp(Date.from(startOfDay.atZone(ZoneId.systemDefault()).toInstant()))
            val endTimestamp = Timestamp(Date.from(endOfDay.atZone(ZoneId.systemDefault()).toInstant()))
            
            val snapshot = firestore.collection(SLEEP_RECORDS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            
            val record = snapshot.documents
                .mapNotNull { doc ->
                    try {
                        doc.toObject(FirebaseSleepRecord::class.java)
                    } catch (e: Exception) {
                        null
                    }
                }
                .firstOrNull { record ->
                    val recordDate = record.date ?: return@firstOrNull false
                    recordDate.seconds >= startTimestamp.seconds && recordDate.seconds < endTimestamp.seconds
                }
            
            Result.success(record)
        } catch (e: Exception) {
            Log.e(TAG, "getSleepRecordForDate: Error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get couple ID for user
     */
    private suspend fun getCoupleId(userId: String): Result<String> {
        return try {
            val doc = firestore.collection(USERS_COLLECTION)
                .document(userId)
                .get()
                .await()
            
            val coupleId = doc.getString("coupleId") ?: ""
            Result.success(coupleId)
        } catch (e: Exception) {
            Log.e(TAG, "getCoupleId: Error", e)
            Result.success("") // Return empty string on error
        }
    }
}
