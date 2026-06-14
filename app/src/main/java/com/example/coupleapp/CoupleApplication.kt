package com.example.coupleapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil.Coil
import com.example.coupleapp.data.sync.PartnerSyncRepository
import com.example.coupleapp.util.createImageLoaderWithBase64Support
import com.example.coupleapp.util.SyncTriggerHelper
import com.example.coupleapp.util.SyncTriggerListener
import com.example.coupleapp.service.SignificantLocationManager
import com.example.coupleapp.service.LocationTrackingService
import com.example.coupleapp.manager.MessageNotificationManager
import com.example.coupleapp.util.PartnerNotificationManager
import com.example.coupleapp.widget.WidgetManager
import com.example.coupleapp.widget.observer.RoomWidgetObserver
import com.example.coupleapp.widget.observer.WidgetFirestoreObserver
import com.example.coupleapp.worker.BackgroundLocationWorker
import com.example.coupleapp.worker.GoogleSleepSyncWorker
import com.example.coupleapp.worker.PartnerDataSyncWorker
import com.example.coupleapp.worker.SleepSyncWorker
import com.example.coupleapp.worker.SleepWakeUpReminderWorker
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Application class for initializing Firebase, background workers, widgets and tracking app lifecycle
 */
@HiltAndroidApp
class CoupleApplication : Application(), Configuration.Provider, LifecycleEventObserver {
    
    companion object {
        var isAppInForeground = false
            private set
        
        // Track if user is currently in chat screen (to avoid duplicate notifications)
        var isUserInChatScreen = false
        
        // Application instance for global context access (used by widgets, etc.)
        lateinit var instance: CoupleApplication
            private set
    }
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        
        // Configure Firestore settings
        val firestore = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true) // Enable offline persistence
            .build()
        firestore.firestoreSettings = settings
        
        // Initialize custom Coil ImageLoader with base64 support
        val imageLoader = createImageLoaderWithBase64Support(this)
        Coil.setImageLoader(imageLoader)
        
        // Track app lifecycle for notifications
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Create notification channels for partner notifications
        PartnerNotificationManager.createNotificationChannels(this)
        
        // Initialize widget system (legacy + new)
        initializeWidgets()
        
        Log.d("CoupleApplication", "Firebase initialized successfully")
        Log.d("CoupleApplication", "Coil ImageLoader with base64 support initialized")
        
        // Schedule background workers if user is logged in
        scheduleBackgroundWorkersIfNeeded()
    }
    
    /**
     * Initialize widget system with periodic updates
     */
    private fun initializeWidgets() {
        Log.d("CoupleApplication", "Initializing widget system")
        
        // Initialize legacy widget system (WidgetDataRepository + Firestore Observer)
        WidgetManager.initialize(this)
        
        // Initialize new Room-based widget observer
        // This provides reactive widget updates when Room DB changes
        RoomWidgetObserver.startObserving(this)
        
        // Start Firestore sync trigger listener (alternative to Cloud Functions)
        // This listens for sync requests from partner without needing FCM
        SyncTriggerListener.startListening(this)
        
        // Cleanup old sync triggers on app startup to prevent Firestore bloat
        // This is especially important if BackgroundLocationWorker is not running
        applicationScope.launch {
            try {
                val deleted = SyncTriggerHelper.cleanupOldTriggers()
                if (deleted > 0) {
                    Log.d("CoupleApplication", "🧹 Cleaned up $deleted old sync triggers on startup")
                }
            } catch (e: Exception) {
                Log.w("CoupleApplication", "Failed to cleanup sync triggers on startup", e)
            }
        }
        
        Log.d("CoupleApplication", "Widget systems and sync trigger listener initialized")
    }
    
    /**
     * Schedule background workers if user is logged in and paired.
     * 
     * ================================================================
     * LOCATION TRACKING ARCHITECTURE (Android 11+)
     * ================================================================
     * 
     * 4 Layers hoạt động HÒA HỢP - mỗi layer có vai trò riêng:
     * 
     * LAYER 1: SignificantLocationManager (BONUS - Piggyback)
     * ├── Priority: PRIORITY_PASSIVE (không tự bật GPS)
     * ├── Trigger: Chỉ nhận location khi apps KHÁC dùng GPS
     * ├── Ví dụ: User mở Google Maps, Grab, Zalo → ta nhận được location
     * ├── Battery: ~0% (bonus - không phụ thuộc)
     * └── Vai trò: BONUS real-time updates khi có sẵn
     * 
     * LAYER 2: BackgroundLocationWorker (PRIMARY - HYBRID Strategy) ⭐
     * ├── Interval: 15-20 phút (WorkManager)
     * ├── Strategy: HYBRID (LOW_POWER first, upgrade to BALANCED if needed)
     * │   ├── Try LOW_POWER (Cell + WiFi): ~0.05% battery
     * │   ├── If accuracy > 100m → upgrade to BALANCED: ~0.15% battery
     * │   └── Result: Smart battery saving based on environment
     * ├── Accuracy: 50-100m (city) / 20-50m (rural, auto-upgrade)
     * ├── Battery: ~4-8%/ngày (50% less than always BALANCED)
     * ├── Vai trò: NGUỒN CHÍNH cho location updates
     * └── Cleanup: cancel() trong onUserLogout()
     * 
     * LAYER 3: LocationAlarmManager (BACKUP - Reliable)
     * ├── Interval: 25 phút (setAndAllowWhileIdle)
     * ├── Vai trò: Backup khi WorkManager bị delay bởi Doze
     * ├── Chạy được cả trong Doze mode (Android 6+)
     * └── Cleanup: cancelLocationAlarm() trong onUserLogout()
     * 
     * LAYER 4: LocationTrackingService (REAL-TIME - Foreground)
     * ├── Trigger: User mở DistanceScreen
     * ├── Priority: HIGH_ACCURACY (GPS chính xác nhất, 3-10m)
     * ├── Hiển thị GPS icon (expected behavior)
     * ├── Battery: ~5%/giờ (chỉ khi đang xem)
     * └── Cleanup: stopService() trong onUserLogout()
     * 
     * ================================================================
     * HYBRID STRATEGY (Layer 2):
     * ================================================================
     * 1. Thử LOW_POWER trước (Cell + WiFi, tiết kiệm pin)
     * 2. Kiểm tra accuracy của location trả về
     * 3. Nếu accuracy > 100m → upgrade lên BALANCED (GPS + Cell + WiFi)
     * 
     * Kết quả:
     * - Thành phố: Dùng LOW_POWER (50-100m, ~0.05% pin)
     * - Nông thôn: Auto upgrade BALANCED (20-50m, ~0.15% pin)
     * - Tiết kiệm ~50% pin so với luôn dùng BALANCED
     * 
     * ================================================================
     * TIMING ANALYSIS:
     * ================================================================
     * Normal case: 15-20 phút từ Layer 2 (HYBRID)
     * Bonus: Real-time từ Layer 1 nếu user dùng Maps/Grab
     * Worst case (Doze): ~25 phút từ Layer 3
     * 
     * Total battery: ~4-8%/ngày (optimized with HYBRID strategy)
     * ================================================================
     */
    private fun scheduleBackgroundWorkersIfNeeded() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            Log.d("CoupleApplication", "User logged in, scheduling background workers")
            
            // Layer 1: PASSIVE location (BONUS - piggyback from other apps)
            // Zero battery - only receives when other apps use GPS
            SignificantLocationManager.getInstance(this).startTracking()
            
            // Layer 2: PRIMARY WorkManager with HYBRID strategy ⭐
            // LOW_POWER first, auto-upgrade to BALANCED if accuracy > 100m
            BackgroundLocationWorker.schedule(this)
            
            // Layer 3: AlarmManager last resort (25 min)
            // Guaranteed to fire even in Doze mode (Android 6+)
            com.example.coupleapp.receiver.LocationAlarmManager.scheduleInitialAlarm(this)
            
            // Note: Layer 4 (LocationTrackingService) is started separately 
            // in DistanceViewModel when user enters the location screen
            
            // Sleep sync worker
            scheduleSleepSyncWorker()
            
            // Partner data sync worker (Silent Push fallback - no Cloud Functions needed)
            schedulePartnerDataSync()
        } else {
            Log.d("CoupleApplication", "No user logged in, skipping background workers")
        }
    }
    
    /**
     * Schedule sleep sync worker for accurate sleep tracking
     * 
     * ================================================================
     * MULTI-LAYER SLEEP TRACKING (5 Layers for maximum reliability)
     * ================================================================
     * 
     * Layer 0: DailySleepResetWorker (NEW - CRITICAL)
     * ├── Schedule: 00:05 AM và 4:00 AM hàng ngày
     * ├── Vai trò: Reset flag alreadySynced để sync được ngày mới
     * └── Battery: ~0%
     * 
     * Layer 1: SleepReceiver (Google Sleep API PendingIntent)
     * ├── Trigger: Google Play Services
     * ├── Vai trò: Nhận sleep events real-time
     * └── Issue: PendingIntent bị mất sau RAM clear
     * 
     * Layer 2: GoogleSleepSyncWorker (WorkManager morning syncs)
     * ├── Schedule: 5:30-11:30 AM mỗi giờ + periodic 2h
     * ├── Vai trò: Sync data sáng sớm như Widgetable
     * └── Issue: Có thể bị delay bởi Doze
     * 
     * Layer 3: SleepAlarmManager (AlarmManager)
     * ├── Schedule: 6:00, 8:00, 10:00 AM
     * ├── Vai trò: Backup reliable, chạy cả trong Doze
     * └── Battery: ~0.02%/day
     * 
     * Layer 4: ForceSleepSyncWorker (NEW - CRITICAL)
     * ├── Schedule: 1:00 PM và 6:00 PM
     * ├── Vai trò: Backup cuối cùng, đảm bảo có data hàng ngày
     * └── Battery: ~0%
     * 
     * ================================================================
     */
    private fun scheduleSleepSyncWorker() {
        Log.d("CoupleApplication", "Scheduling sleep sync workers (5 layers)")
        
        // LAYER 0 (NEW): Daily reset worker - CRITICAL for daily sync
        // This ensures alreadySynced flag resets each day
        com.example.coupleapp.worker.DailySleepResetWorker.scheduleDailyReset(this)
        Log.d("CoupleApplication", "✅ Layer 0: Daily reset worker scheduled")
        
        // CRITICAL: Re-register Google Sleep API on app startup (Layer 1 recovery)
        // This ensures PendingIntent is restored after RAM clear / reboot
        applicationScope.launch {
            try {
                val googleSleepManager = com.example.coupleapp.data.sleep.GoogleSleepApiManager(this@CoupleApplication)
                
                // Use new ensureRegistered with autoEnable=true
                if (googleSleepManager.hasActivityRecognitionPermission()) {
                    val registered = googleSleepManager.ensureRegistered(autoEnable = true)
                    if (registered) {
                        Log.d("CoupleApplication", "✅ Layer 1: Google Sleep API registered")
                    } else {
                        Log.d("CoupleApplication", "⚠️ Layer 1: Google Sleep API not registered (disabled or error)")
                    }
                    
                    // Log debug info
                    Log.d("CoupleApplication", googleSleepManager.getDebugInfo())
                }
            } catch (e: Exception) {
                Log.e("CoupleApplication", "Error registering Google Sleep API", e)
            }
        }
        
        // Health Connect sync (for devices with Health Connect)
        SleepSyncWorker.schedulePeriodicSync(this)
        SleepSyncWorker.scheduleMorningSync(this)
        
        // LAYER 2: Google Sleep API sync (aggressive morning syncs like Widgetable)
        GoogleSleepSyncWorker.schedulePeriodicSync(this)
        GoogleSleepSyncWorker.scheduleAggressiveMorningSync(this)
        GoogleSleepSyncWorker.triggerImmediateSync(this) // Sync now when app opens
        Log.d("CoupleApplication", "✅ Layer 2: Morning sync workers scheduled")
        
        // LAYER 3: Sleep AlarmManager (most reliable, survives Doze mode)
        com.example.coupleapp.receiver.SleepAlarmManager.scheduleAllMorningAlarms(this)
        Log.d("CoupleApplication", "✅ Layer 3: Sleep alarms scheduled")
        
        // LAYER 4 (NEW): Force sync worker - backup for late sleepers and missed syncs
        com.example.coupleapp.worker.ForceSleepSyncWorker.scheduleForceSync(this)
        Log.d("CoupleApplication", "✅ Layer 4: Force sync workers scheduled")
        
        // Schedule wake up reminder (checks active sleep sessions each morning)
        SleepWakeUpReminderWorker.scheduleMorningReminder(this)
        
        // Check if we need to reset sleep state (e.g., new day since last app open)
        applicationScope.launch {
            if (com.example.coupleapp.worker.DailySleepResetWorker.needsReset(this@CoupleApplication)) {
                Log.d("CoupleApplication", "New day detected on app open, triggering reset")
                com.example.coupleapp.worker.DailySleepResetWorker.triggerImmediateReset(this@CoupleApplication)
            }
        }
    }
    
    /**
     * Schedule periodic partner data sync as fallback for Silent Push.
     * This ensures data stays fresh even if FCM is delayed/blocked.
     */
    private fun schedulePartnerDataSync() {
        applicationScope.launch {
            try {
                val partnerId = PartnerSyncRepository.getInstance(this@CoupleApplication).getPartnerId()
                if (partnerId != null) {
                    Log.d("CoupleApplication", "Scheduling periodic partner data sync for: $partnerId")
                    PartnerDataSyncWorker.schedulePeriodicSync(this@CoupleApplication, partnerId)
                    
                    // Also trigger immediate sync on app start
                    PartnerDataSyncWorker.enqueueRegular(
                        context = this@CoupleApplication,
                        partnerId = partnerId,
                        syncTypes = null,
                        triggerSource = PartnerDataSyncWorker.TRIGGER_APP_OPEN
                    )
                }
            } catch (e: Exception) {
                Log.e("CoupleApplication", "Error scheduling partner data sync", e)
            }
        }
    }
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
    
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                // App moved to foreground
                isAppInForeground = true
                Log.d("CoupleApplication", "App in FOREGROUND")
                
                // Restart Room observer (in case it was stopped)
                RoomWidgetObserver.startObserving(this)
                
                // Periodic cache cleanup when app comes to foreground
                LocationTrackingService.cleanupCaches()
            }
            Lifecycle.Event.ON_STOP -> {
                // App moved to background
                isAppInForeground = false
                Log.d("CoupleApplication", "App in BACKGROUND")
                // Note: Don't stop RoomWidgetObserver here - it should keep running
                // to update widgets even when app is in background
            }
            else -> {}
        }
    }
    
    /**
     * Handle low memory situations from the system.
     * This is critical for preventing crashes on low-RAM devices like Xiaomi Redmi Note 5.
     * 
     * Android calls this method when the system is running low on memory.
     * We should release any non-critical resources here.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("CoupleApplication", "onTrimMemory called with level: $level")
        
        when (level) {
            // App is in background and system is running low on memory
            TRIM_MEMORY_RUNNING_MODERATE,
            TRIM_MEMORY_RUNNING_LOW -> {
                Log.d("CoupleApplication", "System running low on memory, cleaning non-critical caches")
                // Clean location caches
                LocationTrackingService.cleanupCaches()
            }
            
            // App is in background, release everything we can
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE -> {
                Log.w("CoupleApplication", "Memory pressure high, releasing caches aggressively")
                // Clear all caches
                LocationTrackingService.cleanupCaches()
                // Clear Coil image cache
                try {
                    Coil.imageLoader(this).memoryCache?.clear()
                } catch (e: Exception) {
                    Log.e("CoupleApplication", "Error clearing Coil cache", e)
                }
            }
            
            // App will be killed soon, release everything
            TRIM_MEMORY_COMPLETE -> {
                Log.w("CoupleApplication", "App may be killed, releasing all resources")
                LocationTrackingService.resetAllCaches()
                try {
                    Coil.imageLoader(this).memoryCache?.clear()
                } catch (e: Exception) {
                    Log.e("CoupleApplication", "Error clearing Coil cache", e)
                }
            }
            
            // UI is hidden, release UI-related resources
            TRIM_MEMORY_UI_HIDDEN -> {
                Log.d("CoupleApplication", "UI hidden, releasing UI resources")
                // This is a good time to release cached bitmaps
                LocationTrackingService.cleanupCaches()
            }
        }
    }
    
    /**
     * Handle low memory callback (legacy, but still called)
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("CoupleApplication", "onLowMemory called, releasing all caches")
        LocationTrackingService.resetAllCaches()
        try {
            Coil.imageLoader(this).memoryCache?.clear()
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error clearing Coil cache", e)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d("CoupleApplication", "Application terminating, cleaning up resources")
        
        // Clean up all observers and managers to prevent memory leaks
        RoomWidgetObserver.cleanup()
        WidgetFirestoreObserver.cleanup()
        SyncTriggerListener.cleanup()
        MessageNotificationManager.cleanup()
        LocationTrackingService.resetAllCaches()
        
        // Cancel application scope
        applicationScope.cancel()
    }
    
    /**
     * Call this when user logs out to cleanup user-specific resources.
     * 
     * CRITICAL: This properly cleans up ALL location tracking layers:
     * - Layer 1: SignificantLocationManager (PendingIntent-based passive tracking)
     * - Layer 2: BackgroundLocationWorker (WorkManager periodic)
     * - Layer 3: LocationAlarmManager (AlarmManager fallback)
     * - Layer 4: LocationTrackingService (Foreground service)
     * 
     * This fixes the issue where GPS icon stays on until clear RAM.
     */
    fun onUserLogout() {
        Log.d("CoupleApplication", "User logged out, cleaning up user-specific resources")
        
        // Stop all observers
        RoomWidgetObserver.stopObserving()
        WidgetFirestoreObserver.stopObserving()
        SyncTriggerListener.stopListening()
        MessageNotificationManager.cleanup()
        
        // ================================================================
        // CRITICAL: Stop ALL location tracking layers
        // This releases GPS resources and stops the GPS icon from showing
        // ================================================================
        
        // Layer 1: Stop SignificantLocationManager (PendingIntent-based)
        // This was the main cause of GPS icon staying on!
        try {
            SignificantLocationManager.getInstance(this).stopTracking()
            Log.d("CoupleApplication", "✅ SignificantLocationManager stopped")
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error stopping SignificantLocationManager", e)
        }
        
        // Layer 2: Cancel BackgroundLocationWorker (WorkManager)
        try {
            BackgroundLocationWorker.cancel(this)
            Log.d("CoupleApplication", "✅ BackgroundLocationWorker cancelled")
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error cancelling BackgroundLocationWorker", e)
        }
        
        // Layer 3: Cancel LocationAlarmManager (AlarmManager)
        try {
            com.example.coupleapp.receiver.LocationAlarmManager.cancelLocationAlarm(this)
            Log.d("CoupleApplication", "✅ LocationAlarmManager cancelled")
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error cancelling LocationAlarmManager", e)
        }
        
        // Cancel Sleep AlarmManager (AlarmManager for sleep sync)
        try {
            com.example.coupleapp.receiver.SleepAlarmManager.cancelAllAlarms(this)
            Log.d("CoupleApplication", "✅ SleepAlarmManager cancelled")
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error cancelling SleepAlarmManager", e)
        }
        
        // Cancel Google Sleep sync workers
        try {
            GoogleSleepSyncWorker.cancelAll(this)
            Log.d("CoupleApplication", "✅ GoogleSleepSyncWorker cancelled")
        } catch (e: Exception) {
            Log.e("CoupleApplication", "Error cancelling GoogleSleepSyncWorker", e)
        }
        
        // Layer 4: Stop LocationTrackingService (Foreground service)
        LocationTrackingService.resetAllCaches()
        LocationTrackingService.stopService(this)
        Log.d("CoupleApplication", "✅ LocationTrackingService stopped")
        
        // Reset flags
        isUserInChatScreen = false
        
        Log.d("CoupleApplication", "✅ All location tracking layers cleaned up")
    }
}

