package com.example.coupleapp.data.model

import java.time.LocalDateTime

/**
 * Represents a geographic location with coordinates
 */
data class LocationCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Represents a user's current location with additional info
 */
data class UserLocation(
    val userId: String,
    val userName: String,
    val avatarUrl: String,
    val coordinate: LocationCoordinate,
    val address: String,
    val lastUpdated: LocalDateTime,
    val batteryLevel: Int = 100,
    val isOnline: Boolean = true
)

/**
 * Represents a location history entry
 */
data class LocationHistory(
    val id: String,
    val locationName: String,
    val address: String,
    val coordinate: LocationCoordinate,
    val arrivalTime: LocalDateTime,
    val departureTime: LocalDateTime?,
    val durationMinutes: Int,
    val locationType: LocationType = LocationType.OTHER
)

/**
 * Types of locations for icon display
 */
enum class LocationType {
    HOME,
    WORK,
    CAFE,
    RESTAURANT,
    SHOPPING,
    GYM,
    PARK,
    ENTERTAINMENT,
    SCHOOL,
    HOSPITAL,
    OTHER
}

/**
 * Represents a place both users visited together
 */
data class SharedPlace(
    val id: String,
    val placeName: String,
    val address: String,
    val coordinate: LocationCoordinate,
    val representativePhotoUrl: String,
    val visitDate: LocalDateTime,
    val durationMinutes: Int,
    val photosCount: Int,
    val locationType: LocationType = LocationType.OTHER
)

/**
 * Represents a photo taken at a shared place
 */
data class SharedPlacePhoto(
    val id: String,
    val photoUrl: String,
    val takenAt: LocalDateTime,
    val takenByUserId: String,
    val caption: String? = null
)

/**
 * State for the Distance feature UI
 */
data class DistanceUiState(
    val isLoading: Boolean = true,
    val myLocation: UserLocation? = null,
    val partnerLocation: UserLocation? = null,
    val distanceInMeters: Double = 0.0,
    val distanceText: String = "",
    val lastSyncTime: String = "",
    val myLocationHistory: List<LocationHistory> = emptyList(),
    val partnerLocationHistory: List<LocationHistory> = emptyList(),
    val sharedPlaces: List<SharedPlace> = emptyList(),
    val selectedUser: UserLocation? = null,
    val showUserInfoSheet: Boolean = false,
    val error: String? = null,
    // Colocation status - when both users are together
    val isColocationActive: Boolean = false,
    val colocationStartTime: LocalDateTime? = null,
    val colocationDurationMinutes: Int = 0,
    // Current user ID for avatar comparison
    val currentUserId: String = ""
)

/**
 * State for shared place photos screen
 */
data class SharedPlacePhotosState(
    val isLoading: Boolean = true,
    val place: SharedPlace? = null,
    val photos: List<SharedPlacePhoto> = emptyList(),
    val isAddingPhoto: Boolean = false,
    val error: String? = null
)
