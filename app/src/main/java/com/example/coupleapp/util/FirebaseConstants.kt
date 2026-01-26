package com.example.coupleapp.util

/**
 * Centralized Firebase configuration constants
 * 
 * Note: This project uses two Firebase projects:
 * - coupleapp-46367: For Auth, Firestore, Storage (from google-services.json)
 * - coupleapp-69f4c: For Realtime Database (chat messaging)
 * 
 * If you want to consolidate to a single project, update REALTIME_DATABASE_URL
 * to use the same project as google-services.json
 */
object FirebaseConstants {
    
    /**
     * Firebase Realtime Database URL for chat messaging
     * Using the main project's Realtime Database
     */
    const val REALTIME_DATABASE_URL = "https://coupleapp-46367-default-rtdb.asia-southeast1.firebasedatabase.app/"
    
    // Chat paths
    const val CHATS_PATH = "chats"
    const val MESSAGES_PATH = "messages"
    
    // Message types
    const val MESSAGE_TYPE_TEXT = "text"
    const val MESSAGE_TYPE_IMAGE = "image"
    const val MESSAGE_TYPE_EMOJI = "emoji"
    
    // Timeouts
    const val SEND_TIMEOUT_MS = 10000L // 10 seconds
    
    // Image constraints for optimized storage
    const val MAX_IMAGE_WIDTH = 1024
    const val MAX_IMAGE_HEIGHT = 1024
    const val IMAGE_QUALITY = 80 // JPEG quality 0-100
    const val MAX_IMAGE_SIZE_BYTES = 500 * 1024 // 500KB max per image
}
