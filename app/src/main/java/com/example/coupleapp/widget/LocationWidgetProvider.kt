package com.example.coupleapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import com.example.coupleapp.widget.data.LocationWidgetCachedData
import com.example.coupleapp.widget.data.WidgetDataRepository
import com.example.coupleapp.widget.worker.WidgetUpdateWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Location Widget Provider - Battery Optimized
 * Displays the distance between couple partners and their locations
 * 
 * Battery Optimization Features:
 * - Aggressive caching with 10-minute expiry
 * - WorkManager for periodic updates (respects Doze mode)
 * - Location updates are handled by LocationTrackingService
 * - Smart data invalidation when location changes
 */
class LocationWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "LocationWidgetProvider"
        private const val ACTION_WIDGET_CLICK = "com.example.coupleapp.LOCATION_WIDGET_CLICK"
        private const val ACTION_UPDATE_WIDGET = "com.example.coupleapp.UPDATE_LOCATION_WIDGET"
        private const val ACTION_SHARE_LOCATION = "com.example.coupleapp.SHARE_LOCATION_FROM_WIDGET"
        
        // Debounce: only update widget every 30 seconds max to save battery
        private const val WIDGET_UPDATE_DEBOUNCE_MS = 30_000L
        private var lastWidgetUpdateTime = 0L
        
        /**
         * Update all widgets using cached data (battery-efficient)
         */
        fun updateWidgets(context: Context) {
            Log.d(TAG, "Requesting Location widget update")
            val intent = Intent(context, LocationWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
        
        /**
         * Force update with fresh data
         */
        fun forceUpdateWidgets(context: Context) {
            Log.d(TAG, "Force updating Location widgets")
            WidgetDataRepository.invalidateLocationCache(context)
            WidgetUpdateWorker.requestImmediateUpdate(context, WidgetUpdateWorker.WIDGET_TYPE_LOCATION)
        }
        
        /**
         * Notify that location changed - update widget with debounce
         * Called by LocationTrackingService when location changes
         * Uses debounce to prevent excessive updates (max once per 30 seconds)
         */
        fun onLocationChanged(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastWidgetUpdateTime < WIDGET_UPDATE_DEBOUNCE_MS) {
                Log.d(TAG, "Location changed but debouncing (${(now - lastWidgetUpdateTime)/1000}s since last update)")
                return
            }
            
            Log.d(TAG, "Location changed, updating widget")
            lastWidgetUpdateTime = now
            // Don't invalidate cache - just request update with existing cache
            updateWidgets(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widgets")
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            ACTION_WIDGET_CLICK, ACTION_SHARE_LOCATION -> {
                // Open app and navigate to Distance
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "distance")
                }
                context.startActivity(mainIntent)
            }
            ACTION_UPDATE_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val widgetIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, LocationWidgetProvider::class.java)
                )
                onUpdate(context, appWidgetManager, widgetIds)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val views = RemoteViews(context.packageName, R.layout.widget_location)
            
            try {
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser
                
                if (currentUser == null) {
                    Log.d(TAG, "User not authenticated")
                    showNoDataState(views, "Đăng nhập để xem vị trí")
                } else {
                    Log.d(TAG, "Loading location data for user: ${currentUser.uid}")
                    
                    // Use cached data for battery efficiency (same as Locket widget)
                    // Cache expires after 10 minutes, then fetches fresh data
                    val cachedData = WidgetDataRepository.getLocationWidgetData(context)
                    
                    if (cachedData != null) {
                        Log.d(TAG, "Using cached location data - distance: ${cachedData.distance}")
                        showLocationDataCached(context, views, cachedData)
                    } else {
                        Log.d(TAG, "No cached data, trying direct Firebase query")
                        // Fallback to direct Firebase query
                        val data = loadLocationData(currentUser.uid)
                        if (data != null) {
                            Log.d(TAG, "Successfully loaded location data from Firebase")
                            showLocationData(views, data)
                        } else {
                            Log.d(TAG, "No location data found")
                            showNoDataState(views, "Chia sẻ vị trí để bắt đầu")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating location widget: ${e.message}", e)
                
                // Try to provide more specific error message
                val errorMessage = when {
                    e.message?.contains("permission", ignoreCase = true) == true -> "Cần quyền truy cập vị trí"
                    e.message?.contains("network", ignoreCase = true) == true -> "Không có kết nối mạng" 
                    e.message?.contains("auth", ignoreCase = true) == true -> "Lỗi xác thực - hãy đăng nhập lại"
                    e.message?.contains("timeout", ignoreCase = true) == true -> "Kết nối chậm, thử lại sau"
                    e.message?.contains("partner", ignoreCase = true) == true -> "Chưa có người yêu"
                    else -> "Không thể tải vị trí"
                }
                showNoDataState(views, errorMessage)
            }
            
            // Set click intent for container
            val clickIntent = Intent(context, LocationWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
            }
            val clickPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.location_widget_container, clickPendingIntent)
            
            // Set share button intent
            val shareIntent = Intent(context, LocationWidgetProvider::class.java).apply {
                action = ACTION_SHARE_LOCATION
            }
            val sharePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 1000,
                shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.location_share_button, sharePendingIntent)
            
            Log.d(TAG, "🔄 Calling updateAppWidget for widgetId=$appWidgetId")
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "✅ Widget updated successfully")
        }
    }
    
    private fun showLocationDataCached(context: Context, views: RemoteViews, data: LocationWidgetCachedData) {
        Log.d(TAG, "=== showLocationDataCached ===")
        Log.d(TAG, "distance: ${data.distance}")
        Log.d(TAG, "partnerLocation: '${data.partnerLocation}'")
        Log.d(TAG, "partnerLastUpdate: ${data.partnerLastUpdate}")
        
        views.setViewVisibility(R.id.location_content_container, View.VISIBLE)
        views.setViewVisibility(R.id.location_empty_container, View.GONE)
        
        // Distance - main display
        val distanceText = formatDistanceCompact(data.distance)
        Log.d(TAG, "Setting distance to: '$distanceText'")
        views.setTextViewText(R.id.location_distance_text, distanceText)
        
        // Partner location address
        val locationText = formatLocationForWidget(data.partnerLocation)
        Log.d(TAG, "Setting partner location to: '$locationText'")
        try {
            views.setTextViewText(R.id.location_partner_address, locationText)
            Log.d(TAG, "✅ Partner location set successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set partner location: ${e.message}", e)
        }
        
        // Last update time
        val lastUpdateText = formatLastUpdateCompact(data.partnerLastUpdate)
        Log.d(TAG, "Setting lastUpdate to: '$lastUpdateText'")
        views.setTextViewText(R.id.location_last_update, lastUpdateText)
        
        // Emojis
        views.setTextViewText(R.id.location_my_emoji, "😊")
        views.setTextViewText(R.id.location_partner_emoji, "🥦")
    }
    
    /**
     * Format location string for widget display (compact)
     */
    private fun formatLocationForWidget(location: String): String {
        if (location.isBlank() || location == "Không rõ" || location == "Chưa chia sẻ") {
            return "📍 Chưa cập nhật"
        }
        
        // Shorten common Vietnamese location prefixes
        var shortened = location
            .replace("Phường ", "P.")
            .replace("Quận ", "Q.")
            .replace("Huyện ", "H.")
            .replace("Thành phố ", "")
            .replace("TP. ", "")
            .replace("Việt Nam", "")
            .replace(", ,", ",")
            .trim()
            .trimEnd(',')
        
        // Try to extract just district/ward for compact display
        val parts = shortened.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        
        // Get the most relevant part (usually district or ward)
        val relevantPart = when {
            parts.size >= 2 -> {
                // Try to find district (Q. or Quận)
                val district = parts.find { it.startsWith("Q.") || it.contains("Quận") }
                val ward = parts.find { it.startsWith("P.") || it.contains("Phường") }
                when {
                    district != null && ward != null -> "$ward, $district"
                    district != null -> district
                    ward != null -> ward
                    else -> parts.take(2).joinToString(", ")
                }
            }
            parts.isNotEmpty() -> parts.first()
            else -> shortened
        }
        
        // Limit length for widget
        val displayText = if (relevantPart.length > 25) {
            relevantPart.take(22) + "..."
        } else {
            relevantPart
        }
        
        return "📍 $displayText"
    }

    private fun shortenName(name: String): String {
        return if (name.length > 6) {
            name.take(5) + "."
        } else {
            name
        }
    }
    
    private fun shortenLocationCompact(location: String): String {
        // Extract district or short name for compact display
        val cleaned = location
            .replace("Quận ", "Q.")
            .replace("Huyện ", "H.")
            .replace("Thành phố ", "TP.")
            .replace("Phường ", "P.")
        return if (cleaned.length > 8) {
            cleaned.take(7) + "."
        } else {
            cleaned
        }
    }
    
    private fun formatDistanceCompact(distance: Double?): String {
        return if (distance != null) {
            if (distance < 1.0) {
                "💕 ${(distance * 1000).toInt()}m"
            } else {
                "💕 ${String.format("%.1f", distance)}km"
            }
        } else {
            "💕 --"
        }
    }
    
    private fun formatLastUpdateCompact(timestamp: Long): String {
        if (timestamp == 0L) return ""
        
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (1000 * 60)
        
        return when {
            minutes < 1 -> "vừa xong"
            minutes < 60 -> "${minutes}p"
            minutes < 1440 -> "${minutes / 60}h"
            else -> "${minutes / 1440}d"
        }
    }

    private suspend fun loadLocationData(userId: String): LocationWidgetData? {
        return withContext(Dispatchers.IO) {
            try {
                val db = FirebaseFirestore.getInstance()
                
                // Get user info
                val userDoc = db.collection("users").document(userId).get().await()
                val userName = userDoc.getString("displayName") ?: "You"
                val partnerId = userDoc.getString("partnerId")
                val coupleId = userDoc.getString("coupleId")
                
                if (partnerId.isNullOrEmpty()) {
                    Log.d(TAG, "No partner linked for user: $userId")
                    return@withContext LocationWidgetData(
                        myName = userName,
                        partnerName = "Chưa kết nối",
                        myLocation = "Chưa chia sẻ",
                        partnerLocation = "Chưa chia sẻ",
                        distance = null,
                        isSharing = false,
                        partnerLastUpdate = null
                    )
                }
                
                // Get partner info
                val partnerDoc = db.collection("users").document(partnerId).get().await()
                val partnerName = partnerDoc.getString("displayName") ?: "Partner"
                
                // Generate coupleId if not set
                val effectiveCoupleId = coupleId ?: listOf(userId, partnerId).sorted().joinToString("_")
                
                // Get my location - using "locations" collection with document ID format: {coupleId}_{userId}
                val myLocationDocId = "${effectiveCoupleId}_${userId}"
                val myLocationDoc = db.collection("locations")
                    .document(myLocationDocId)
                    .get()
                    .await()
                
                // Get partner's location
                val partnerLocationDocId = "${effectiveCoupleId}_${partnerId}"
                val partnerLocationDoc = db.collection("locations")
                    .document(partnerLocationDocId)
                    .get()
                    .await()
                
                val myLat = myLocationDoc.getDouble("latitude")
                val myLng = myLocationDoc.getDouble("longitude")
                val myLocationName = myLocationDoc.getString("address") ?: "Không rõ"
                val myLastUpdate = myLocationDoc.getTimestamp("timestamp")
                
                val partnerLat = partnerLocationDoc.getDouble("latitude")
                val partnerLng = partnerLocationDoc.getDouble("longitude")
                val partnerLocationName = partnerLocationDoc.getString("address") ?: "Không rõ"
                val partnerLastUpdate = partnerLocationDoc.getTimestamp("timestamp")
                
                // Calculate distance if both have locations
                val distance = if (myLat != null && myLng != null && partnerLat != null && partnerLng != null) {
                    calculateDistance(myLat, myLng, partnerLat, partnerLng)
                } else {
                    null
                }
                
                // Check if sharing is enabled
                val isSharing = myLat != null && myLng != null
                
                LocationWidgetData(
                    myName = userName,
                    partnerName = partnerName,
                    myLocation = myLocationName,
                    partnerLocation = partnerLocationName,
                    distance = distance,
                    isSharing = isSharing,
                    partnerLastUpdate = partnerLastUpdate?.toDate()?.time
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading location data from Firebase", e)
                // Log more detailed error info for debugging
                when (e) {
                    is com.google.firebase.firestore.FirebaseFirestoreException -> {
                        Log.e(TAG, "Firestore error code: ${e.code}, message: ${e.message}")
                    }
                    is java.util.concurrent.ExecutionException -> {
                        Log.e(TAG, "Execution error: ${e.cause?.message}")
                    }
                    else -> {
                        Log.e(TAG, "Unknown error type: ${e.javaClass.simpleName}")
                    }
                }
                null
            }
        }
    }

    /**
     * Calculate distance between two coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun showLocationData(views: RemoteViews, data: LocationWidgetData) {
        views.setViewVisibility(R.id.location_content_container, View.VISIBLE)
        views.setViewVisibility(R.id.location_empty_container, View.GONE)
        
        // Distance - main display (simplified layout)
        views.setTextViewText(R.id.location_distance_text, formatDistanceCompact(data.distance))
        
        // Partner location
        views.setTextViewText(R.id.location_partner_address, formatLocationForWidget(data.partnerLocation))
        
        // Last update time
        val lastUpdateText = data.partnerLastUpdate?.let { formatLastUpdateCompact(it) } ?: ""
        views.setTextViewText(R.id.location_last_update, lastUpdateText)
        
        // Emojis
        views.setTextViewText(R.id.location_my_emoji, "😊")
        views.setTextViewText(R.id.location_partner_emoji, "🥦")
    }

    private fun showNoDataState(views: RemoteViews, message: String) {
        views.setViewVisibility(R.id.location_content_container, View.GONE)
        views.setViewVisibility(R.id.location_empty_container, View.VISIBLE)
        views.setTextViewText(R.id.location_empty_message, message)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "First Location widget added")
        WidgetUpdateWorker.schedulePeriodicUpdates(context) // General widgets: 20 min
        WidgetUpdateWorker.scheduleLocationUpdates(context) // Location widget: 15 min
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Last Location widget removed")
    }
}

/**
 * Simple data class for widget display
 */
data class LocationWidgetData(
    val myName: String,
    val partnerName: String,
    val myLocation: String,
    val partnerLocation: String,
    val distance: Double?,
    val isSharing: Boolean,
    val partnerLastUpdate: Long?
)
