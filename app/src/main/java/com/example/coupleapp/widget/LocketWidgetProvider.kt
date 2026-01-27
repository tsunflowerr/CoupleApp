package com.example.coupleapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.coupleapp.MainActivity
import com.example.coupleapp.R
import com.example.coupleapp.data.model.LocketPost
import com.example.coupleapp.data.model.LocketType
import com.example.coupleapp.widget.cache.WidgetImageCache
import com.example.coupleapp.widget.data.LocketWidgetCachedData
import com.example.coupleapp.widget.data.WidgetDataRepository
import com.example.coupleapp.widget.worker.WidgetUpdateWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Locket Widget Provider - Battery & Data Optimized
 * Displays the latest Locket content from your partner in a 4x2 widget
 * 
 * Optimization Features:
 * - Widget Thumbnail: Uses small thumbnail (~20KB) instead of full image (~200KB)
 * - Disk Cache: Caches decoded bitmaps to avoid re-downloading
 * - Memory Cache: Quick access for frequently displayed images
 * - WorkManager for periodic updates (respects Doze mode)
 * - Smart data invalidation on new Locket
 * - Fallback to cache when network unavailable
 * 
 * Data Savings: ~90% reduction in data usage for image lockets
 */
class LocketWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "LocketWidgetProvider"
        private const val ACTION_WIDGET_CLICK = "com.example.coupleapp.LOCKET_WIDGET_CLICK"
        private const val ACTION_UPDATE_WIDGET = "com.example.coupleapp.UPDATE_LOCKET_WIDGET"
        private const val ACTION_SEND_LOCKET = "com.example.coupleapp.SEND_LOCKET_FROM_WIDGET"
        
        /**
         * Update all widgets using cached data (battery-efficient)
         */
        fun updateWidgets(context: Context) {
            Log.d(TAG, "Requesting Locket widget update")
            val intent = Intent(context, LocketWidgetProvider::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            context.sendBroadcast(intent)
        }
        
        /**
         * Force update with fresh data - call when new Locket received
         */
        fun forceUpdateWidgets(context: Context) {
            Log.d(TAG, "Force updating Locket widgets")
            WidgetDataRepository.invalidateLocketCache(context)
            WidgetUpdateWorker.requestImmediateUpdate(context, WidgetUpdateWorker.WIDGET_TYPE_LOCKET)
        }
        
        /**
         * Notify that new Locket was received - invalidate cache and update
         */
        fun onNewLocketReceived(context: Context) {
            Log.d(TAG, "New Locket received, invalidating cache")
            WidgetDataRepository.invalidateLocketCache(context)
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
            ACTION_WIDGET_CLICK, ACTION_SEND_LOCKET -> {
                // Open app and navigate to Locket
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "locket")
                }
                context.startActivity(mainIntent)
            }
            ACTION_UPDATE_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val widgetIds = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, LocketWidgetProvider::class.java)
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
            val views = RemoteViews(context.packageName, R.layout.widget_locket)
            
            try {
                val auth = FirebaseAuth.getInstance()
                val currentUser = auth.currentUser
                
                if (currentUser == null) {
                    Log.d(TAG, "User not authenticated")
                    showEmptyState(views, "Đăng nhập để xem Locket")
                } else {
                    Log.d(TAG, "Loading Locket data for authenticated user")
                    // Use cached data for battery efficiency
                    val cachedData = WidgetDataRepository.getLocketWidgetData(context)
                    
                    if (cachedData != null && cachedData.type != "EMPTY") {
                        Log.d(TAG, "Locket widget data loaded successfully")
                        showLocketContentCached(context, views, cachedData, appWidgetManager, appWidgetId)
                    } else {
                        Log.d(TAG, "No cached data, loading from Firebase")
                        // Fallback to direct Firebase query
                        val latestLocket = loadLatestLocket(currentUser.uid)
                        if (latestLocket != null) {
                            showLocketContent(context, views, latestLocket, appWidgetManager, appWidgetId)
                        } else {
                            Log.d(TAG, "No Locket data available")
                            showEmptyState(views, "Chưa có Locket mới")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating Locket widget: ${e.message}", e)
                val errorMessage = when {
                    e.message?.contains("auth", ignoreCase = true) == true -> "Lỗi xác thực"
                    e.message?.contains("network", ignoreCase = true) == true -> "Không có mạng"
                    e.message?.contains("storage", ignoreCase = true) == true -> "Lỗi tải ảnh"
                    else -> "Không thể tải Locket"
                }
                showEmptyState(views, errorMessage)
            }
            
            // Set click intents
            val clickIntent = Intent(context, LocketWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLICK
            }
            val clickPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.locket_widget_container, clickPendingIntent)
            
            // Set send button intent
            val sendIntent = Intent(context, LocketWidgetProvider::class.java).apply {
                action = ACTION_SEND_LOCKET
            }
            val sendPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 1000,
                sendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.locket_send_button, sendPendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    private suspend fun showLocketContentCached(
        context: Context, 
        views: RemoteViews, 
        data: LocketWidgetCachedData,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        views.setViewVisibility(R.id.locket_content_container, View.VISIBLE)
        views.setViewVisibility(R.id.locket_empty_container, View.GONE)
        
        // Set sender name with new indicator
        val senderDisplay = if (data.hasNewLocket) "💌 ${data.senderName}" else data.senderName
        views.setTextViewText(R.id.locket_sender_name, senderDisplay)
        
        // Set timestamp
        val timeText = formatTimestamp(data.timestamp)
        views.setTextViewText(R.id.locket_timestamp, timeText)
        
        // Set content based on type
        when (data.type) {
            "EMOJI" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_text_content, View.GONE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_emoji_content, data.content)
            }
            "TEXT" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_text_content, data.content)
            }
            "PHOTO", "DRAWING" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.GONE)
                views.setViewVisibility(R.id.locket_image_content, View.VISIBLE)
                
                // OPTIMIZATION: Use widget thumbnail if available, fallback to full content
                // Widget thumbnail is ~20KB vs full content ~200KB (90% savings)
                val imageContent = data.widgetThumbnail?.takeIf { it.isNotEmpty() } ?: data.content
                val usingThumbnail = data.widgetThumbnail?.isNotEmpty() == true
                
                if (imageContent.isNotEmpty()) {
                    // Try disk cache first to avoid re-decoding Base64
                    val bitmap = loadBitmapWithCache(context, imageContent, usingThumbnail)
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.locket_image_content, bitmap)
                        val source = if (usingThumbnail) "thumbnail" else "full"
                        Log.d(TAG, "✅ Loaded image ($source), type: ${data.type}")
                    } else {
                        views.setImageViewResource(R.id.locket_image_content, R.drawable.locket)
                        Log.w(TAG, "Failed to load image, using placeholder")
                    }
                } else {
                    views.setImageViewResource(R.id.locket_image_content, R.drawable.locket)
                }
                
                // Update widget immediately after loading image
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
            else -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_text_content, data.caption ?: "❤️")
            }
        }
        
        // Set caption if available
        if (!data.caption.isNullOrEmpty() && data.type != "TEXT") {
            views.setViewVisibility(R.id.locket_caption, View.VISIBLE)
            views.setTextViewText(R.id.locket_caption, data.caption)
        } else {
            views.setViewVisibility(R.id.locket_caption, View.GONE)
        }
    }
    
    /**
     * Load bitmap with disk cache support.
     * 
     * Priority:
     * 1. Memory cache (instant, ~0ms)
     * 2. Disk cache (fast, ~10-50ms)
     * 3. Decode from content (slow, ~100-500ms)
     * 
     * This avoids re-decoding Base64 on every widget update, saving CPU and battery.
     */
    private suspend fun loadBitmapWithCache(context: Context, content: String, isThumbnail: Boolean): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Try cache first
                val cachedBitmap = WidgetImageCache.get(context, content)
                if (cachedBitmap != null) {
                    val source = if (isThumbnail) "thumbnail" else "full"
                    Log.d(TAG, "📦 Cache hit for $source image")
                    return@withContext cachedBitmap
                }
                
                // 2. Decode from content
                val bitmap = loadBitmapFromContent(content)
                
                // 3. Save to cache for next time
                if (bitmap != null) {
                    WidgetImageCache.put(context, content, bitmap)
                    val source = if (isThumbnail) "thumbnail" else "full"
                    Log.d(TAG, "💾 Cached $source image for future use")
                }
                
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap with cache", e)
                null
            }
        }
    }
    
    /**
     * Load bitmap from content string (can be Base64 or URL)
     * The app stores images as Base64 in Firestore, so we need to handle that
     * Based on Base64ImageLoader.kt from the app
     */
    private suspend fun loadBitmapFromContent(content: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "loadBitmapFromContent: content length=${content.length}, starts with=${content.take(20)}")
                
                val originalBitmap = when {
                    // Format: data:image/...;base64,<data>
                    content.startsWith("data:image/") && content.contains("base64,") -> {
                        val commaIndex = content.indexOf(",")
                        if (commaIndex != -1 && commaIndex < content.length - 1) {
                            val base64Data = content.substring(commaIndex + 1)
                            Log.d(TAG, "Detected data:image format, extracting base64 data (${base64Data.length} chars)")
                            decodeBase64ToBitmap(base64Data)
                        } else {
                            Log.w(TAG, "Invalid data:image format")
                            null
                        }
                    }
                    // Raw Base64 data (stored directly by LocketFirebaseRepository)
                    isRawBase64Data(content) -> {
                        Log.d(TAG, "Detected raw base64 data (${content.length} chars)")
                        decodeBase64ToBitmap(content)
                    }
                    // HTTP URL
                    content.startsWith("http") -> {
                        Log.d(TAG, "Loading from URL: ${content.take(50)}...")
                        loadBitmapFromUrl(content)
                    }
                    else -> {
                        Log.w(TAG, "Unknown image format, content length: ${content.length}, preview: ${content.take(50)}")
                        null
                    }
                }
                
                if (originalBitmap != null) {
                    Log.d(TAG, "Bitmap loaded: ${originalBitmap.width}x${originalBitmap.height}")
                    // Scale down for widget to save memory (max 800px for better quality)
                    scaleBitmapForWidget(originalBitmap, 800)
                } else {
                    Log.w(TAG, "Failed to load bitmap")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from content", e)
                null
            }
        }
    }
    
    /**
     * Check if the string is raw Base64 encoded data
     * Based on Base64ImageMapper.isLikelyBase64() from Base64ImageLoader.kt
     */
    private fun isRawBase64Data(content: String): Boolean {
        // Raw base64 is long (min 500 chars for images) and contains only base64 characters
        if (content.length < 500) return false
        if (content.startsWith("http")) return false
        
        // Check first and last 100 characters for base64 validity
        val sample = content.take(100) + content.takeLast(100)
        val isValid = sample.all { c ->
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
        }
        
        Log.d(TAG, "isRawBase64Data check: length=${content.length}, isValid=$isValid")
        return isValid
    }
    
    /**
     * Decode Base64 string to Bitmap
     * Uses Base64.NO_WRAP flag as per app's Base64ImageDecoder
     * Memory optimized with inSampleSize and RGB_565 for widgets
     */
    private fun decodeBase64ToBitmap(base64String: String): Bitmap? {
        return try {
            // Use NO_WRAP flag - same as Base64ImageLoader.kt uses
            val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.NO_WRAP)
            Log.d(TAG, "Decoded ${decodedBytes.size} bytes from base64")
            
            // First, decode bounds only to calculate sample size
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, boundsOptions)
            
            // Calculate inSampleSize for memory efficiency (target 800px max for better quality)
            val maxSize = 800
            var sampleSize = 1
            if (boundsOptions.outWidth > maxSize || boundsOptions.outHeight > maxSize) {
                val halfWidth = boundsOptions.outWidth / 2
                val halfHeight = boundsOptions.outHeight / 2
                while ((halfWidth / sampleSize) >= maxSize && (halfHeight / sampleSize) >= maxSize) {
                    sampleSize *= 2
                }
            }
            
            // Decode with calculated sample size - use ARGB_8888 for better quality
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888 // Better quality than RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)
            if (bitmap == null) {
                Log.e(TAG, "BitmapFactory.decodeByteArray returned null")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding Base64 to bitmap: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError decoding Base64 to bitmap", e)
            // Try with more aggressive sampling
            try {
                val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.NO_WRAP)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, options)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed even with aggressive sampling", e2)
                null
            }
        }
    }
    
    /**
     * Load bitmap from URL
     * Memory optimized with inSampleSize and RGB_565 for widgets
     */
    private fun loadBitmapFromUrl(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.connect()
            
            val inputStream = connection.getInputStream()
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            // First, decode bounds only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            
            // Calculate sample size for memory efficiency (800px for better quality)
            val maxSize = 800
            var sampleSize = 1
            if (boundsOptions.outWidth > maxSize || boundsOptions.outHeight > maxSize) {
                val halfWidth = boundsOptions.outWidth / 2
                val halfHeight = boundsOptions.outHeight / 2
                while ((halfWidth / sampleSize) >= maxSize && (halfHeight / sampleSize) >= maxSize) {
                    sampleSize *= 2
                }
            }
            
            // Decode with calculated options - use ARGB_8888 for better quality
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888 // Better quality
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URL: $urlString", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError loading bitmap from URL", e)
            null
        }
    }
    
    /**
     * Scale bitmap for widget to save memory
     */
    private fun scaleBitmapForWidget(originalBitmap: Bitmap, maxSize: Int): Bitmap {
        val scale = minOf(
            maxSize.toFloat() / originalBitmap.width,
            maxSize.toFloat() / originalBitmap.height,
            1f
        )
        
        return if (scale < 1f) {
            val scaledBitmap = Bitmap.createScaledBitmap(
                originalBitmap,
                (originalBitmap.width * scale).toInt(),
                (originalBitmap.height * scale).toInt(),
                true
            )
            if (scaledBitmap != originalBitmap) {
                originalBitmap.recycle()
            }
            scaledBitmap
        } else {
            originalBitmap
        }
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return ""
        
        val localDateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val now = LocalDateTime.now()
        
        return when {
            localDateTime.toLocalDate() == now.toLocalDate() -> {
                "Hôm nay ${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            }
            localDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> {
                "Hôm qua ${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            }
            else -> {
                localDateTime.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
            }
        }
    }

    private suspend fun loadLatestLocket(userId: String): LocketData? {
        return withContext(Dispatchers.IO) {
            try {
                val db = FirebaseFirestore.getInstance()
                
                // Query lockets where current user is the receiver
                // Use only receiverId filter (no orderBy) to avoid needing composite index
                // Sort client-side like app does in LocketFirebaseRepository
                val locketQuery = db.collection("locket_posts")
                    .whereEqualTo("receiverId", userId)
                    .get()
                    .await()
                
                if (locketQuery.documents.isEmpty()) {
                    Log.d(TAG, "No lockets found for user")
                    return@withContext null
                }
                
                // Sort by timestamp descending and get the latest
                val doc = locketQuery.documents
                    .sortedByDescending { it.getTimestamp("timestamp")?.toDate()?.time ?: 0L }
                    .first()
                val senderName = doc.getString("senderName") ?: "Partner"
                val typeStr = doc.getString("type") ?: "text"
                val caption = doc.getString("caption")
                val timestamp = doc.getTimestamp("timestamp")?.toDate()
                
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
                
                // Convert type to uppercase for widget display
                val type = typeStr.uppercase()
                
                // Log thumbnail availability
                if (widgetThumbnail != null) {
                    Log.d(TAG, "✅ Widget thumbnail available (${widgetThumbnail.length} chars)")
                }
                
                LocketData(
                    senderName = senderName,
                    content = content,
                    type = type,
                    caption = caption,
                    timestamp = timestamp,
                    widgetThumbnail = widgetThumbnail
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locket", e)
                null
            }
        }
    }

    private suspend fun showLocketContent(
        context: Context, 
        views: RemoteViews, 
        locket: LocketData,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        views.setViewVisibility(R.id.locket_content_container, View.VISIBLE)
        views.setViewVisibility(R.id.locket_empty_container, View.GONE)
        
        // Set sender name
        views.setTextViewText(R.id.locket_sender_name, locket.senderName)
        
        // Set timestamp
        val timeText = locket.timestamp?.let { date ->
            val localDateTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            val now = LocalDateTime.now()
            when {
                localDateTime.toLocalDate() == now.toLocalDate() -> {
                    "Hôm nay ${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                }
                localDateTime.toLocalDate() == now.toLocalDate().minusDays(1) -> {
                    "Hôm qua ${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                }
                else -> {
                    localDateTime.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                }
            }
        } ?: ""
        views.setTextViewText(R.id.locket_timestamp, timeText)
        
        // Set content based on type
        when (locket.type) {
            "EMOJI" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_text_content, View.GONE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_emoji_content, locket.content)
            }
            "TEXT" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_text_content, locket.content)
            }
            "PHOTO", "DRAWING" -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.GONE)
                views.setViewVisibility(R.id.locket_image_content, View.VISIBLE)
                
                // OPTIMIZATION: Use widget thumbnail if available, fallback to full content
                // Widget thumbnail is ~20KB vs full content ~200KB (90% savings)
                val imageContent = locket.widgetThumbnail?.takeIf { it.isNotEmpty() } ?: locket.content
                val usingThumbnail = locket.widgetThumbnail?.isNotEmpty() == true
                
                if (imageContent.isNotEmpty()) {
                    val bitmap = loadBitmapWithCache(context, imageContent, isThumbnail = usingThumbnail)
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.locket_image_content, bitmap)
                        val source = if (usingThumbnail) "thumbnail" else "full"
                        Log.d(TAG, "✅ Loaded image ($source), type: ${locket.type}")
                    } else {
                        views.setImageViewResource(R.id.locket_image_content, R.drawable.locket)
                        Log.w(TAG, "Failed to load image, using placeholder")
                    }
                } else {
                    views.setImageViewResource(R.id.locket_image_content, R.drawable.locket)
                }
                
                // Update widget immediately after loading image
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
            else -> {
                views.setViewVisibility(R.id.locket_emoji_content, View.GONE)
                views.setViewVisibility(R.id.locket_text_content, View.VISIBLE)
                views.setViewVisibility(R.id.locket_image_content, View.GONE)
                views.setTextViewText(R.id.locket_text_content, locket.caption ?: "❤️")
            }
        }
        
        // Set caption if available
        if (!locket.caption.isNullOrEmpty() && locket.type != "TEXT") {
            views.setViewVisibility(R.id.locket_caption, View.VISIBLE)
            views.setTextViewText(R.id.locket_caption, locket.caption)
        } else {
            views.setViewVisibility(R.id.locket_caption, View.GONE)
        }
    }

    private fun showEmptyState(views: RemoteViews, message: String) {
        views.setViewVisibility(R.id.locket_content_container, View.GONE)
        views.setViewVisibility(R.id.locket_empty_container, View.VISIBLE)
        views.setTextViewText(R.id.locket_empty_message, message)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "First Locket widget added")
        WidgetUpdateWorker.schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Last Locket widget removed")
    }
}

/**
 * Simple data class for widget display
 * widgetThumbnail: Small version (~256px) for efficient widget display
 */
data class LocketData(
    val senderName: String,
    val content: String,
    val type: String,
    val caption: String?,
    val timestamp: Date?,
    val widgetThumbnail: String? = null
)
