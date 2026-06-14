package com.example.coupleapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.example.coupleapp.data.repository.SleepFirebaseRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.*

/**
 * BroadcastReceiver for Google Sleep API
 * Receives sleep segment and classification events from Google Play Services
 * 
 * IMPORTANT: Google Sleep API behavior:
 * 1. SleepSegmentEvent is delivered ONCE per day, AFTER the user wakes up (with delay of up to several hours)
 * 2. SleepClassifyEvent is delivered every ~10 minutes during the night
 * 3. For real-time sleep tracking, we use SleepClassifyEvent with confidence threshold
 * 
 * BATTERY OPTIMIZATION:
 * - All state is stored in SharedPreferences (no network calls during sleep)
 * - Only ONE Firebase sync when wake-up is detected
 * - Google handles all sensor processing efficiently
 */
class SleepReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SleepReceiver"
        // These must match the intent filter in AndroidManifest.xml
        const val ACTION_SLEEP_SEGMENT = "com.google.android.gms.location.activity.SLEEP_SEGMENT"
        const val ACTION_SLEEP_CLASSIFY = "com.google.android.gms.location.activity.SLEEP_CLASSIFY"
        
        // Sleep detection thresholds - STRICTER to reduce false positives
        // Problem: User watching TV/football without phone = false sleep detection
        // Solution: Higher thresholds + more consecutive readings + time-based rules
        // confidence: 0-100, higher = more likely sleeping
        private const val SLEEP_CONFIDENCE_THRESHOLD = 80 // Increased from 70 - need high confidence
        private const val AWAKE_CONFIDENCE_THRESHOLD = 25 // Lowered from 30 - be more sensitive to wake
        
        // Sleep score thresholds (combined from confidence + motion + light)
        private const val SLEEP_SCORE_THRESHOLD = 75 // Need high combined score to detect sleep
        private const val AWAKE_SCORE_THRESHOLD = 35 // Low score = likely awake
        
        // Time-based rules to reduce false positives
        // Don't start sleep tracking before this hour (to avoid TV watching detection)
        private const val EARLIEST_SLEEP_HOUR = 21 // 9 PM - earliest reasonable sleep time
        // Don't continue sleep tracking after this hour (assume day activities)
        private const val LATEST_WAKE_HOUR = 12 // 12 PM - latest reasonable wake time
        
        // Motion threshold - if motion is too high, not sleeping (even if other factors suggest sleep)
        private const val MAX_MOTION_FOR_SLEEP = 3 // motion 1-6, <=3 means relatively still
        
        // SharedPreferences keys for tracking sleep state
        private const val PREFS_NAME = "sleep_classify_prefs"
        private const val KEY_IS_SLEEPING = "is_sleeping"
        private const val KEY_SLEEP_START_TIME = "sleep_start_time"
        private const val KEY_LAST_EVENT_TIME = "last_event_time"
        private const val KEY_LAST_CONFIDENCE = "last_confidence"
        private const val KEY_CONSECUTIVE_SLEEP_COUNT = "consecutive_sleep_count"
        private const val KEY_CONSECUTIVE_AWAKE_COUNT = "consecutive_awake_count"
        private const val KEY_SLEEP_DATE = "sleep_date" // YYYYMMDD format to track which night
        private const val KEY_ALREADY_SYNCED_TODAY = "already_synced_today"
        private const val KEY_AVERAGE_SLEEP_SCORE = "average_sleep_score" // Track average score during sleep
        
        // Require MORE consecutive readings before changing state (to avoid false triggers)
        // More readings = more confident about state change
        private const val CONSECUTIVE_READINGS_TO_SLEEP = 4  // ~40 min to detect sleep (was 2)
        private const val CONSECUTIVE_READINGS_TO_WAKE = 3   // ~30 min to confirm wake (handles bathroom breaks)
        
        // Timeout: if no event for 2 hours while "sleeping", assume phone was off
        private const val SLEEP_STATE_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 hours
        
        // Minimum sleep duration to save (avoid false positives)
        private const val MIN_SLEEP_DURATION_MS = 60 * 60 * 1000L // Increased to 60 minutes (was 30)
        
        // Maximum gap in events before considering it a new sleep session
        private const val MAX_EVENT_GAP_MS = 3 * 60 * 60 * 1000L // 3 hours
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        
        // Use goAsync() to allow more time for coroutine processing
        // BroadcastReceiver normally has ~10s timeout
        val pendingResult = goAsync()
        
        // Create a scope that will be properly cancelled after work completes
        // This prevents memory leaks from long-lived coroutine scopes in receivers
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        scope.launch {
            try {
                // Check and reset stale state first
                checkAndResetStaleState(context)
                
                // Process Sleep Segment Events (official sleep duration from Google)
                // NOTE: This is delivered AFTER waking up, with potential delay of hours
                if (SleepSegmentEvent.hasEvents(intent)) {
                    processSleepSegmentEvents(context, intent)
                }
                
                // Process Sleep Classify Events (real-time sleep indicators)
                // This is delivered every ~10 minutes and is used for more accurate tracking
                if (SleepClassifyEvent.hasEvents(intent)) {
                    processSleepClassifyEvents(context, intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing sleep events", e)
            } finally {
                // Must call finish() when done to release the BroadcastReceiver
                pendingResult.finish()
                // Cancel scope to prevent memory leaks
                scope.cancel()
            }
        }
    }
    
    /**
     * Check and reset stale sleep state
     * Handles cases like: phone was off, new day started, etc.
     * 
     * ENHANCED: Better handling of new day detection to ensure daily syncs work
     */
    private fun checkAndResetStaleState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
        val sleepDate = prefs.getString(KEY_SLEEP_DATE, "") ?: ""
        val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        
        val now = System.currentTimeMillis()
        val todayDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // CRITICAL FIX: Reset alreadySynced flag if it's from a previous day
        // This ensures we can sync data for the new day
        if (alreadySynced && sleepDate.isNotEmpty() && sleepDate != todayDate) {
            // If it's after 4 AM, the synced flag from yesterday should be reset
            if (currentHour >= 4) {
                Log.d(TAG, "⚠️ Stale alreadySynced flag detected (from $sleepDate), resetting for new day")
                prefs.edit()
                    .putBoolean(KEY_ALREADY_SYNCED_TODAY, false)
                    .putString(KEY_SLEEP_DATE, "")
                    .apply()
            }
        }
        
        // Reset if it's a new day (after 12:00 PM - assume previous night's sleep is done)
        if (sleepDate.isNotEmpty() && sleepDate != todayDate && currentHour >= 12) {
            Log.d(TAG, "New day detected (after noon), resetting sleep state")
            resetSleepStateInternal(prefs)
            return
        }
        
        // Reset if sleeping but no event for too long (phone was off/airplane mode)
        if (isSleeping && lastEventTime > 0) {
            val timeSinceLastEvent = now - lastEventTime
            if (timeSinceLastEvent > SLEEP_STATE_TIMEOUT_MS) {
                Log.d(TAG, "Sleep state timeout (${timeSinceLastEvent / 1000 / 60} min since last event), resetting")
                resetSleepStateInternal(prefs)
            }
        }
    }
    
    private fun resetSleepStateInternal(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_IS_SLEEPING, false)
            .putLong(KEY_SLEEP_START_TIME, 0L)
            .putLong(KEY_LAST_EVENT_TIME, 0L)
            .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
            .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
            .putString(KEY_SLEEP_DATE, "")
            .putBoolean(KEY_ALREADY_SYNCED_TODAY, false)
            .apply()
    }
    
    /**
     * Process SleepSegmentEvent from Google Sleep API
     * This is the official sleep detection result, delivered after waking up
     * 
     * NOTE: We prefer our classify-based detection for timing accuracy,
     * but use this as a backup/validation
     */
    private suspend fun processSleepSegmentEvents(context: Context, intent: Intent) {
        val events = SleepSegmentEvent.extractEvents(intent)
        Log.d(TAG, "Received ${events.size} sleep segment events")
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        
        events.forEach { event ->
            val startTimeMillis = event.startTimeMillis
            val endTimeMillis = event.endTimeMillis
            val durationMillis = event.segmentDurationMillis
            val status = event.status
            
            val statusStr = when (status) {
                SleepSegmentEvent.STATUS_SUCCESSFUL -> "SUCCESSFUL"
                SleepSegmentEvent.STATUS_MISSING_DATA -> "MISSING_DATA"
                SleepSegmentEvent.STATUS_NOT_DETECTED -> "NOT_DETECTED"
                else -> "UNKNOWN($status)"
            }
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            Log.d(TAG, "Sleep Segment Event:")
            Log.d(TAG, "  Status: $statusStr")
            Log.d(TAG, "  Start: ${dateFormat.format(Date(startTimeMillis))} ($startTimeMillis)")
            Log.d(TAG, "  End: ${dateFormat.format(Date(endTimeMillis))} ($endTimeMillis)")
            Log.d(TAG, "  Duration: ${durationMillis / 1000 / 60} minutes (${durationMillis / 1000 / 3600.0} hours)")
            Log.d(TAG, "  Already synced from classify: $alreadySynced")
            
            // Only save if status is SUCCESSFUL or MISSING_DATA (partial data)
            // STATUS_NOT_DETECTED means no sleep was detected, don't save
            when (status) {
                SleepSegmentEvent.STATUS_SUCCESSFUL, 
                SleepSegmentEvent.STATUS_MISSING_DATA -> {
                    // Validate data before saving
                    if (startTimeMillis > 0 && endTimeMillis > startTimeMillis && durationMillis > 0) {
                        
                        // IMPROVED: Additional validation for MISSING_DATA segments
                        // These are often unreliable (e.g., phone on table while watching TV)
                        val durationMinutes = durationMillis / 1000 / 60
                        val minDurationMinutes = MIN_SLEEP_DURATION_MS / 1000 / 60
                        
                        // Check if duration meets minimum threshold
                        if (durationMinutes < minDurationMinutes) {
                            Log.w(TAG, "Sleep segment too short ($durationMinutes min < $minDurationMinutes min), skipping")
                            return@forEach
                        }
                        
                        // For MISSING_DATA, be extra cautious - require longer duration
                        // Google reports MISSING_DATA when it's not confident about the sleep
                        if (status == SleepSegmentEvent.STATUS_MISSING_DATA && durationMinutes < 90) {
                            Log.w(TAG, "MISSING_DATA segment too short ($durationMinutes min < 90 min), likely false positive, skipping")
                            return@forEach
                        }
                        
                        // Check if start time is within reasonable sleep hours
                        val startCalendar = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
                        val startHour = startCalendar.get(Calendar.HOUR_OF_DAY)
                        
                        // If "sleep" started during typical awake hours (12 PM - 9 PM), be suspicious
                        if (startHour in LATEST_WAKE_HOUR until EARLIEST_SLEEP_HOUR) {
                            if (status == SleepSegmentEvent.STATUS_MISSING_DATA) {
                                Log.w(TAG, "MISSING_DATA segment started at unusual hour ($startHour:00), likely false positive (e.g., watching TV), skipping")
                                return@forEach
                            } else {
                                // For SUCCESSFUL segments during unusual hours, still save but log warning
                                Log.w(TAG, "Sleep segment started at unusual hour ($startHour:00), might be a nap or false positive")
                            }
                        }
                        
                        // Only save if we haven't already synced from classify events
                        // This prevents duplicate records
                        if (!alreadySynced) {
                            try {
                                saveSleepSegmentToFirebase(
                                    context = context,
                                    startTimeMillis = startTimeMillis,
                                    endTimeMillis = endTimeMillis,
                                    durationMillis = durationMillis,
                                    isOfficialSegment = true,
                                    hasMissingData = (status == SleepSegmentEvent.STATUS_MISSING_DATA)
                                )
                                // Mark as synced to prevent classify-based sync later
                                prefs.edit().putBoolean(KEY_ALREADY_SYNCED_TODAY, true).apply()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving sleep segment to Firebase", e)
                            }
                        } else {
                            Log.d(TAG, "Skipping official segment - already synced from classify events")
                        }
                    } else {
                        Log.w(TAG, "Invalid sleep segment data: start=$startTimeMillis, end=$endTimeMillis, duration=$durationMillis")
                    }
                }
                SleepSegmentEvent.STATUS_NOT_DETECTED -> {
                    Log.d(TAG, "Sleep not detected - possible reasons: device moved too much, light was on, or device not used for tracking")
                }
            }
        }
        
        // DON'T reset state here - the official segment may arrive hours after waking up
        // State will be reset by:
        // 1. checkAndResetStaleState() when new day + after noon
        // 2. When classify-based wake detection triggers
        // 3. Large gap in events detected
        Log.d(TAG, "SleepSegmentEvent processed, keeping state for ongoing tracking")
    }
    
    /**
     * Process SleepClassifyEvent for real-time sleep tracking
     * This is delivered every ~10 minutes and allows us to detect sleep/wake in near real-time
     * 
     * Algorithm (similar to Widgetable):
     * - Track consecutive readings above/below threshold
     * - Only change state after multiple consecutive readings to avoid false triggers
     * - When state changes from sleeping -> awake, calculate and save sleep duration
     * 
     * BATTERY OPTIMIZATION:
     * - Only save to SharedPreferences (local, no battery drain)
     * - Only sync to Firebase ONCE when wake up is detected (not every 10 min)
     * - Classification data is NOT saved to Firebase to minimize network calls
     */
    private suspend fun processSleepClassifyEvents(context: Context, intent: Intent) {
        val events = SleepClassifyEvent.extractEvents(intent)
        Log.d(TAG, "Received ${events.size} sleep classify events")
        
        // Process events in chronological order
        val sortedEvents = events.sortedBy { it.timestampMillis }
        
        sortedEvents.forEach { event ->
            val confidence = event.confidence
            val motion = event.motion // 1-6, lower = less movement
            val light = event.light // 1-6, lower = darker
            val timestampMillis = event.timestampMillis
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            Log.d(TAG, "Sleep Classify Event:")
            Log.d(TAG, "  Time: ${dateFormat.format(Date(timestampMillis))}")
            Log.d(TAG, "  Confidence: $confidence% (${if (confidence >= SLEEP_CONFIDENCE_THRESHOLD) "SLEEPING" else if (confidence <= AWAKE_CONFIDENCE_THRESHOLD) "AWAKE" else "UNCERTAIN"})")
            Log.d(TAG, "  Motion: $motion/6 (${if (motion <= 2) "still" else if (motion >= 5) "moving" else "moderate"})")
            Log.d(TAG, "  Light: $light/6 (${if (light <= 2) "dark" else if (light >= 5) "bright" else "dim"})")
            
            // Update sleep state machine (saves to SharedPreferences only - battery efficient)
            updateSleepStateMachine(context, confidence, motion, light, timestampMillis)
            
            // NOTE: We intentionally DO NOT save each classification to Firebase
            // This saves battery by avoiding network calls every 10 minutes
            // Sleep record is only synced to Firebase when wake-up is detected
        }
    }
    
    /**
     * State machine for detecting sleep/wake transitions based on SleepClassifyEvent
     * This provides more accurate and timely sleep tracking than waiting for SleepSegmentEvent
     * 
     * IMPROVED ALGORITHM:
     * - Handles bathroom breaks (longer wake threshold)
     * - Handles uncertain readings (doesn't reset counters)
     * - Tracks sleep date to prevent cross-day issues
     * - Prevents duplicate syncs
     */
    private suspend fun updateSleepStateMachine(
        context: Context,
        confidence: Int,
        motion: Int,
        light: Int,
        timestampMillis: Long
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
        val sleepStartTime = prefs.getLong(KEY_SLEEP_START_TIME, 0L)
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
        var consecutiveSleepCount = prefs.getInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
        var consecutiveAwakeCount = prefs.getInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
        val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormatFull = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val todayDate = dateFormatFull.format(Date(timestampMillis))
        
        Log.d(TAG, "Sleep State Machine: isSleeping=$isSleeping, sleepStart=${if (sleepStartTime > 0) dateFormat.format(Date(sleepStartTime)) else "none"}")
        Log.d(TAG, "  Consecutive counts: sleep=$consecutiveSleepCount, awake=$consecutiveAwakeCount")
        Log.d(TAG, "  Already synced: $alreadySynced")
        
        // Check for large gap in events (phone was off, etc.)
        if (lastEventTime > 0 && timestampMillis - lastEventTime > MAX_EVENT_GAP_MS) {
            Log.d(TAG, "Large gap detected (${(timestampMillis - lastEventTime) / 1000 / 60} min), resetting state")
            resetSleepStateInternal(prefs)
            // Re-read values after reset
            consecutiveSleepCount = 0
            consecutiveAwakeCount = 0
        }
        
        // Determine if current reading indicates sleep or awake
        // Use combined heuristic for better accuracy
        val sleepScore = calculateSleepScore(confidence, motion, light)
        
        // IMPROVED: Use stricter thresholds and add time-based check
        val withinSleepHours = isWithinSleepHours()
        val isLikelySleeping = sleepScore >= SLEEP_SCORE_THRESHOLD && withinSleepHours && motion <= MAX_MOTION_FOR_SLEEP
        val isLikelyAwake = sleepScore <= AWAKE_SCORE_THRESHOLD || motion > MAX_MOTION_FOR_SLEEP + 1
        val isUncertain = !isLikelySleeping && !isLikelyAwake
        
        Log.d(TAG, "  Sleep score: $sleepScore (threshold=$SLEEP_SCORE_THRESHOLD)")
        Log.d(TAG, "  Within sleep hours: $withinSleepHours, Motion: $motion (max=$MAX_MOTION_FOR_SLEEP)")
        Log.d(TAG, "  Verdict: sleeping=$isLikelySleeping, awake=$isLikelyAwake, uncertain=$isUncertain")
        
        if (!isSleeping) {
            // Currently awake, check if user fell asleep
            if (isLikelySleeping) {
                consecutiveSleepCount++
                consecutiveAwakeCount = 0
                Log.d(TAG, "  Potential sleep detected, count=$consecutiveSleepCount/${CONSECUTIVE_READINGS_TO_SLEEP}")
                
                if (consecutiveSleepCount >= CONSECUTIVE_READINGS_TO_SLEEP) {
                    // User has fallen asleep
                    // Calculate sleep start time (go back based on consecutive readings)
                    val adjustedSleepStart = timestampMillis - ((consecutiveSleepCount - 1) * 10 * 60 * 1000)
                    
                    prefs.edit()
                        .putBoolean(KEY_IS_SLEEPING, true)
                        .putLong(KEY_SLEEP_START_TIME, adjustedSleepStart)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
                        .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
                        .putString(KEY_SLEEP_DATE, todayDate)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                    
                    Log.d(TAG, ">>> User FELL ASLEEP at ${dateFormat.format(Date(adjustedSleepStart))}")
                } else {
                    prefs.edit()
                        .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, consecutiveSleepCount)
                        .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                }
            } else if (isLikelyAwake) {
                // Clearly awake, reset sleep counter
                if (consecutiveSleepCount > 0) {
                    prefs.edit()
                        .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                } else {
                    prefs.edit()
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                }
            } else {
                // Uncertain - DON'T reset counters, just update timestamp
                // This prevents false resets during transitional periods
                prefs.edit()
                    .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                    .putInt(KEY_LAST_CONFIDENCE, confidence)
                    .apply()
                Log.d(TAG, "  Uncertain state, keeping counters")
            }
        } else {
            // Currently sleeping, check if user woke up
            if (isLikelyAwake) {
                consecutiveAwakeCount++
                consecutiveSleepCount = 0
                Log.d(TAG, "  Potential wake detected, count=$consecutiveAwakeCount/${CONSECUTIVE_READINGS_TO_WAKE}")
                
                if (consecutiveAwakeCount >= CONSECUTIVE_READINGS_TO_WAKE) {
                    // User has woken up for real (not just bathroom break)
                    // Calculate wake time (go back based on consecutive readings)
                    val adjustedWakeTime = timestampMillis - ((consecutiveAwakeCount - 1) * 10 * 60 * 1000)
                    val durationMillis = adjustedWakeTime - sleepStartTime
                    
                    if (durationMillis >= MIN_SLEEP_DURATION_MS && !alreadySynced) {
                        Log.d(TAG, ">>> User WOKE UP at ${dateFormat.format(Date(adjustedWakeTime))}")
                        Log.d(TAG, ">>> Sleep duration: ${durationMillis / 1000 / 60} minutes")
                        
                        // Save the sleep record from classify events (more accurate timing)
                        try {
                            saveSleepSegmentToFirebase(
                                context = context,
                                startTimeMillis = sleepStartTime,
                                endTimeMillis = adjustedWakeTime,
                                durationMillis = durationMillis,
                                isOfficialSegment = false,
                                hasMissingData = false
                            )
                            // Mark as synced to prevent duplicate from official segment
                            prefs.edit().putBoolean(KEY_ALREADY_SYNCED_TODAY, true).apply()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving classify-based sleep to Firebase", e)
                        }
                    } else if (durationMillis < MIN_SLEEP_DURATION_MS) {
                        Log.d(TAG, "Sleep duration too short (${durationMillis / 1000 / 60} min), ignoring")
                    } else {
                        Log.d(TAG, "Already synced today, skipping")
                    }
                    
                    // Reset state
                    prefs.edit()
                        .putBoolean(KEY_IS_SLEEPING, false)
                        .putLong(KEY_SLEEP_START_TIME, 0L)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
                        .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                } else {
                    prefs.edit()
                        .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, consecutiveAwakeCount)
                        .putInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                }
            } else if (isLikelySleeping) {
                // Still sleeping, reset awake counter (handles bathroom break return)
                if (consecutiveAwakeCount > 0) {
                    Log.d(TAG, "  User returned to sleep (bathroom break handled)")
                    prefs.edit()
                        .putInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                } else {
                    prefs.edit()
                        .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                        .putInt(KEY_LAST_CONFIDENCE, confidence)
                        .apply()
                }
            } else {
                // Uncertain while sleeping - could be light sleep or brief stirring
                // DON'T reset counters, just update timestamp
                prefs.edit()
                    .putLong(KEY_LAST_EVENT_TIME, timestampMillis)
                    .putInt(KEY_LAST_CONFIDENCE, confidence)
                    .apply()
                Log.d(TAG, "  Uncertain during sleep, keeping state")
            }
        }
    }
    
    /**
     * Calculate a combined sleep score from confidence, motion, and light
     * Returns 0-100, higher = more likely sleeping
     * 
     * IMPROVED: Add stricter motion check to avoid false positives
     * when user is just resting (watching TV, reading) without using phone
     */
    private fun calculateSleepScore(confidence: Int, motion: Int, light: Int): Int {
        // Confidence is already 0-100
        // Motion: 1-6, lower = more still (sleeping)
        // Light: 1-6, lower = darker (sleeping)
        
        // CRITICAL: If motion is too high, definitely not sleeping
        // This catches cases like: watching TV while phone is on table
        if (motion > MAX_MOTION_FOR_SLEEP + 1) {
            Log.d(TAG, "  High motion ($motion) detected - reducing sleep score")
            return kotlin.math.min(confidence / 2, 40) // Cap at 40 if moving
        }
        
        // Convert motion to 0-100 scale (inverted)
        val motionScore = ((6 - motion) / 5.0 * 100).toInt().coerceIn(0, 100)
        
        // Convert light to 0-100 scale (inverted)  
        val lightScore = ((6 - light) / 5.0 * 100).toInt().coerceIn(0, 100)
        
        // IMPROVED weights: Give MORE weight to motion (key differentiator)
        // Weights: confidence 50%, motion 35%, light 15%
        // Motion is crucial: sleeping people don't move, TV watchers might shift
        val baseScore = ((confidence * 0.50) + (motionScore * 0.35) + (lightScore * 0.15)).toInt()
        
        // Penalty if light is bright (light >= 4) - probably not sleeping
        val lightPenalty = if (light >= 4) 15 else 0
        
        return (baseScore - lightPenalty).coerceIn(0, 100)
    }
    
    /**
     * Check if current time is within reasonable sleep hours
     * Returns false if it's daytime (likely not sleeping, just resting)
     */
    private fun isWithinSleepHours(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // Sleep hours: 9 PM (21) to 12 PM (12) next day
        // Covers: 21, 22, 23, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
        return hour >= EARLIEST_SLEEP_HOUR || hour < LATEST_WAKE_HOUR
    }
    
    /**
     * Save sleep segment data to Firebase
     * @param isOfficialSegment true if from SleepSegmentEvent, false if calculated from SleepClassifyEvents
     * @param hasMissingData true if Google reported STATUS_MISSING_DATA
     * 
     * BATTERY NOTE: This is only called ONCE when sleep segment is complete (wake up detected)
     * Not called every 10 minutes like classification events
     */
    private suspend fun saveSleepSegmentToFirebase(
        context: Context,
        startTimeMillis: Long,
        endTimeMillis: Long,
        durationMillis: Long,
        isOfficialSegment: Boolean = true,
        hasMissingData: Boolean = false
    ) {
        val auth = FirebaseAuth.getInstance()
        val userId = auth.currentUser?.uid
        
        if (userId == null) {
            Log.w(TAG, "Cannot save sleep segment - user not logged in")
            return
        }
        
        val repository = SleepFirebaseRepository(context)
        
        // Determine tracking method
        val trackingMethod = when {
            isOfficialSegment && hasMissingData -> "GOOGLE_API_PARTIAL"
            isOfficialSegment -> "GOOGLE_API"
            else -> "GOOGLE_API_CLASSIFY" // More accurate real-time detection
        }
        
        // Convert to sleep record
        repository.saveSleepSegmentFromGoogleApi(
            userId = userId,
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            durationMillis = durationMillis,
            trackingMethod = trackingMethod
        )
        
        Log.d(TAG, "Sleep segment saved to Firebase successfully (method=$trackingMethod)")
        
        // Immediately update widgets so data appears fresh like Widgetable
        try {
            com.example.coupleapp.widget.WidgetManager.onSleepDataUpdated(context)
            Log.d(TAG, "Widget update triggered after sleep data sync")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update widgets: ${e.message}")
        }
    }
    
    // NOTE: saveSleepClassificationToFirebase has been removed for battery optimization
    // Classification events are now only stored locally in SharedPreferences
    // Only the final sleep record is synced to Firebase when wake-up is detected
    
    /**
     * Check if user is currently marked as sleeping (for external access)
     */
    fun isCurrentlySleeping(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_SLEEPING, false)
    }
    
    /**
     * Get current sleep start time (0 if not sleeping)
     */
    fun getCurrentSleepStartTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_IS_SLEEPING, false)) {
            prefs.getLong(KEY_SLEEP_START_TIME, 0L)
        } else {
            0L
        }
    }
    
    /**
     * Get current sleep duration in minutes (0 if not sleeping)
     */
    fun getCurrentSleepDurationMinutes(context: Context): Int {
        val startTime = getCurrentSleepStartTime(context)
        return if (startTime > 0) {
            ((System.currentTimeMillis() - startTime) / 1000 / 60).toInt()
        } else {
            0
        }
    }
    
    /**
     * Reset sleep tracking state (e.g., when user manually starts tracking)
     */
    fun resetSleepState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        resetSleepStateInternal(prefs)
        Log.d(TAG, "Sleep state reset by external call")
    }
    
    /**
     * Get debug info about current sleep state
     */
    fun getDebugInfo(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSleeping = prefs.getBoolean(KEY_IS_SLEEPING, false)
        val sleepStartTime = prefs.getLong(KEY_SLEEP_START_TIME, 0L)
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT_TIME, 0L)
        val lastConfidence = prefs.getInt(KEY_LAST_CONFIDENCE, -1)
        val sleepDate = prefs.getString(KEY_SLEEP_DATE, "") ?: ""
        val alreadySynced = prefs.getBoolean(KEY_ALREADY_SYNCED_TODAY, false)
        val consecutiveSleep = prefs.getInt(KEY_CONSECUTIVE_SLEEP_COUNT, 0)
        val consecutiveAwake = prefs.getInt(KEY_CONSECUTIVE_AWAKE_COUNT, 0)
        
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        return buildString {
            appendLine("=== Google Sleep API Debug ===")
            appendLine("Is Sleeping: $isSleeping")
            appendLine("Sleep Start: ${if (sleepStartTime > 0) dateFormat.format(Date(sleepStartTime)) else "N/A"}")
            appendLine("Current Duration: ${if (isSleeping) getCurrentSleepDurationMinutes(context) else 0} min")
            appendLine("Last Event: ${if (lastEventTime > 0) dateFormat.format(Date(lastEventTime)) else "N/A"}")
            appendLine("Last Confidence: $lastConfidence%")
            appendLine("Sleep Date: $sleepDate")
            appendLine("Already Synced: $alreadySynced")
            appendLine("Consecutive Sleep Count: $consecutiveSleep")
            appendLine("Consecutive Awake Count: $consecutiveAwake")
        }
    }
}
