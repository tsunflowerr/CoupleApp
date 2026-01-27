package com.example.coupleapp.data.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Represents different types of moment cards
 */
enum class MomentCardType {
    SLEEP,
    MISSING,
    LOCKET,
    EVENT,
    ANNIVERSARY,
    GARDEN,
    MESSAGE,
    CALENDAR_MEMORY // New type for past calendar events (memories)
}

/**
 * Base interface for all moment items
 */
sealed class MomentItem {
    abstract val id: String
    abstract val timestamp: LocalDateTime
    abstract val type: MomentCardType
    
    /**
     * Get formatted time for display
     */
    fun getFormattedTime(): String {
        val now = LocalDateTime.now()
        val diff = java.time.Duration.between(timestamp, now)
        
        return when {
            diff.toMinutes() < 1 -> "Just now"
            diff.toMinutes() < 60 -> "${diff.toMinutes()}m ago"
            diff.toHours() < 24 -> "${diff.toHours()}h ago"
            diff.toDays() == 1L -> "Yesterday"
            diff.toDays() < 7 -> "${diff.toDays()}d ago"
            else -> timestamp.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }
    }
    
    fun getFormattedDate(): String {
        return timestamp.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }
}

/**
 * Sleep moment card data
 */
data class SleepMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val userName: String,
    val userAvatar: String?,
    val bedTime: LocalTime,
    val wakeUpTime: LocalTime,
    val sleepDuration: Int, // in minutes
    val quality: SleepQuality,
    val achievementPercentage: Float
) : MomentItem() {
    override val type = MomentCardType.SLEEP
    
    fun getSleepDurationFormatted(): String {
        val hours = sleepDuration / 60
        val minutes = sleepDuration % 60
        return if (minutes > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${hours}h"
        }
    }
    
    fun getTimeRangeFormatted(): String {
        val bedTimeStr = bedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        val wakeUpTimeStr = wakeUpTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        return "$bedTimeStr - $wakeUpTimeStr"
    }
}

/**
 * Missing moment card data
 */
data class MissingMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val senderName: String,
    val senderAvatar: String?,
    val receiverName: String,
    val receiverAvatar: String?,
    val missCount: Int
) : MomentItem() {
    override val type = MomentCardType.MISSING
}

/**
 * Locket moment card data
 */
data class LocketMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val senderName: String,
    val senderAvatar: String?,
    val locketType: LocketType,
    val content: String, // Photo URL, emoji, or text
    val caption: String?
) : MomentItem() {
    override val type = MomentCardType.LOCKET
}

/**
 * Event types for moments
 */
enum class MomentEventType {
    BIRTHDAY,
    ANNIVERSARY,
    SPECIAL_DAY,
    REMINDER
}

/**
 * Event moment card data
 */
data class EventMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val title: String,
    val description: String?,
    val eventDate: LocalDate,
    val eventType: MomentEventType,
    val daysUntil: Long
) : MomentItem() {
    override val type = MomentCardType.EVENT
    
    fun getDaysUntilFormatted(): String {
        return when {
            daysUntil == 0L -> "Today"
            daysUntil == 1L -> "Tomorrow"
            daysUntil < 7 -> "In $daysUntil days"
            daysUntil < 30 -> "In ${daysUntil / 7} weeks"
            else -> "In ${daysUntil / 30} months"
        }
    }
}

/**
 * Anniversary moment card data
 */
data class AnniversaryMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val daysTogether: Long,
    val monthsTogether: Long,
    val yearsTogether: Long,
    val user1Name: String,
    val user1Avatar: String?,
    val user2Name: String,
    val user2Avatar: String?
) : MomentItem() {
    override val type = MomentCardType.ANNIVERSARY
    
    fun getMilestoneText(): String {
        return when {
            yearsTogether > 0 -> {
                if (monthsTogether % 12 == 0L) {
                    "$yearsTogether ${if (yearsTogether == 1L) "year" else "years"}"
                } else {
                    "$yearsTogether ${if (yearsTogether == 1L) "year" else "years"} ${monthsTogether % 12} ${if (monthsTogether % 12 == 1L) "month" else "months"}"
                }
            }
            monthsTogether > 0 -> "$monthsTogether ${if (monthsTogether == 1L) "month" else "months"}"
            else -> "$daysTogether ${if (daysTogether == 1L) "day" else "days"}"
        }
    }
}

/**
 * Timeline section header
 */
data class TimelineSection(
    val date: LocalDate,
    val label: String // "Hôm nay", "Hôm qua", "24 Nov 2024"
) {
    companion object {
        fun from(date: LocalDate): TimelineSection {
            val now = LocalDate.now()
            val label = when {
                date == now -> "Today"
                date == now.minusDays(1) -> "Yesterday"
                date.isAfter(now.minusDays(7)) -> date.format(DateTimeFormatter.ofPattern("EEEE"))
                else -> date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
            }
            return TimelineSection(date, label)
        }
    }
}

/**
 * Grouped moments by date
 */
data class MomentsGroup(
    val section: TimelineSection,
    val moments: List<MomentItem>
)

/**
 * Garden moment card data - Shows plant growth milestones and achievements
 */
data class GardenMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val userName: String,
    val userAvatar: String?,
    val plantName: String,
    val plantEmoji: String,
    val eventType: GardenEventType,
    val growthStage: Int, // 0-100
    val message: String
) : MomentItem() {
    override val type = MomentCardType.GARDEN
}

/**
 * Garden event types
 */
enum class GardenEventType {
    PLANTED,      // New plant planted
    WATERED,      // Plant watered
    EVOLVED,      // Plant evolved to next stage
    HARVESTED,    // Plant fully grown and harvested
    WILTED,       // Plant wilted due to neglect
    NEEDS_WATER,  // Plant needs water (below 30%)
    NEEDS_SUN     // Plant needs sunlight (below 30%)
}

/**
 * Message notification moment card data
 */
data class MessageMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val senderName: String,
    val senderAvatar: String?,
    val messagePreview: String,
    val messageCount: Int,
    val isRead: Boolean
) : MomentItem() {
    override val type = MomentCardType.MESSAGE
}

/**
 * Calendar Memory moment card data - shows past events as memories
 */
data class CalendarMemoryMoment(
    override val id: String,
    override val timestamp: LocalDateTime,
    val title: String,
    val description: String?,
    val eventDate: LocalDate,
    val eventType: MomentEventType,
    val daysAgo: Long
) : MomentItem() {
    override val type = MomentCardType.CALENDAR_MEMORY
    
    fun getDaysAgoFormatted(): String {
        return when {
            daysAgo == 0L -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo < 7 -> "$daysAgo days ago"
            daysAgo < 30 -> "${daysAgo / 7} weeks ago"
            daysAgo < 365 -> "${daysAgo / 30} months ago"
            else -> "${daysAgo / 365} years ago"
        }
    }
}
