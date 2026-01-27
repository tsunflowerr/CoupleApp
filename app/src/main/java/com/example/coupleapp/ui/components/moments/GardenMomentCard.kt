package com.example.coupleapp.ui.components.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupleapp.data.model.GardenEventType
import com.example.coupleapp.data.model.GardenMoment

/**
 * Card for displaying garden/plant moments
 */
@Composable
fun GardenMomentCard(
    moment: GardenMoment,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Plant emoji avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFE8F5E9),
                                Color(0xFFC8E6C9)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = moment.plantEmoji,
                    fontSize = 24.sp
                )
            }
            
            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Header with event type
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = moment.userName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFF2D2D2D)
                    )
                    
                    // Event type badge
                    val (badgeColor, badgeText) = when (moment.eventType) {
                        GardenEventType.PLANTED -> Color(0xFF4CAF50) to "🌱 Planted"
                        GardenEventType.WATERED -> Color(0xFF2196F3) to "💧 Watered"
                        GardenEventType.EVOLVED -> Color(0xFFFF9800) to "✨ Evolved"
                        GardenEventType.HARVESTED -> Color(0xFFE91E63) to "🎉 Harvested"
                        GardenEventType.WILTED -> Color(0xFF9E9E9E) to "😢 Wilted"
                        GardenEventType.NEEDS_WATER -> Color(0xFF03A9F4) to "💧 Cần nước!"
                        GardenEventType.NEEDS_SUN -> Color(0xFFFFC107) to "☀️ Cần ánh sáng!"
                    }
                    
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                // Plant name
                Text(
                    text = moment.plantName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )
                
                // Message
                if (moment.message.isNotEmpty()) {
                    Text(
                        text = moment.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF757575)
                    )
                }
                
                // Growth progress bar
                if (moment.growthStage > 0 && moment.eventType != GardenEventType.WILTED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { moment.growthStage / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFFE0E0E0)
                        )
                        Text(
                            text = "${moment.growthStage}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Time
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = moment.getFormattedTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBDBDBD)
                )
            }
        }
    }
}
