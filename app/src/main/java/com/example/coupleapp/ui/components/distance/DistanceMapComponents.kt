package com.example.coupleapp.ui.components.distance

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.coupleapp.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.coupleapp.data.model.SharedPlace
import com.example.coupleapp.data.model.UserLocation
import com.example.coupleapp.ui.theme.*
import com.example.coupleapp.util.Base64ImageDecoder
import com.example.coupleapp.util.createImageLoaderWithBase64Support

/**
 * Custom avatar marker for map display with kawaii style and arrow pointing down
 * Now displays actual user avatar from URL
 */
@Composable
fun AvatarMapMarkerWithArrow(
    user: UserLocation,
    isMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Bounce animation when pressed
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "markerScale"
    )
    
    // Pulse animation for online status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val borderColor = if (isMe) SoftPink else Color(0xFF98E4C8)
    val backgroundColor = if (isMe) PastelPink else PastelGreen
    val batteryColor = when {
        user.batteryLevel > 50 -> Color(0xFF4CAF50)
        user.batteryLevel > 20 -> Color(0xFFFFC107)
        else -> Color(0xFFFF5252)
    }
    
    Column(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Pulse effect background (only when online)
            if (user.isOnline) {
                Box(
                    modifier = Modifier
                        .size(size * pulseScale)
                        .clip(CircleShape)
                        .background(borderColor.copy(alpha = pulseAlpha))
                )
            }
            
            // Circular battery indicator ring
            CircularBatteryIndicator(
                batteryLevel = user.batteryLevel,
                batteryColor = batteryColor,
                indicatorSize = size + 8.dp
            )
            
            // Main avatar container
            Box(
                modifier = Modifier
                    .size(size)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(backgroundColor)
                    .border(3.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val context = LocalContext.current
                val imageLoader = remember { createImageLoaderWithBase64Support(context) }
                
                // Avatar image - display actual avatar if URL exists, otherwise show emoji
                if (user.avatarUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(user.avatarUrl)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = "Avatar of ${user.userName}",
                        modifier = Modifier
                            .size(size - 8.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Fallback to emoji placeholder
                    Box(
                        modifier = Modifier
                            .size(size - 8.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        backgroundColor,
                                        borderColor.copy(alpha = 0.5f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMe) "🌸" else "🍋",
                            fontSize = (size.value * 0.45f).sp
                        )
                    }
                }
            }
            
            // Online indicator
            if (user.isOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(14.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50))
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
        
        // Arrow pointing down
        Canvas(
            modifier = Modifier
                .size(16.dp, 12.dp)
                .offset(y = (-2).dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(this@Canvas.size.width, 0f)
                lineTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                close()
            }
            drawPath(path, borderColor)
        }
    }
}

/**
 * Circular battery indicator that wraps around the avatar
 */
@Composable
private fun CircularBatteryIndicator(
    batteryLevel: Int,
    batteryColor: Color,
    indicatorSize: Dp
) {
    val sweepAngle = (batteryLevel / 100f) * 360f
    
    Canvas(modifier = Modifier.size(indicatorSize)) {
        // Background ring
        drawArc(
            color = Color(0xFFE0E0E0),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = Size(this.size.width - 6.dp.toPx(), this.size.height - 6.dp.toPx())
        )
        
        // Battery level arc
        drawArc(
            color = batteryColor,
            startAngle = -90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = Size(this.size.width - 6.dp.toPx(), this.size.height - 6.dp.toPx())
        )
    }
}

/**
 * Shared place marker content for map
 */
@Composable
fun SharedPlaceMapMarkerContent(
    place: SharedPlace,
    size: Dp = 52.dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Main circle
            Box(
                modifier = Modifier
                    .size(size)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(PastelPurple)
                    .border(2.dp, SoftLavender, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "📸", fontSize = (size.value * 0.4f).sp)
            }
            
            // Photo count badge
            if (place.photosCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF6B9D))
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (place.photosCount > 99) "99" else place.photosCount.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        
        // Arrow pointing down
        Canvas(
            modifier = Modifier
                .size(14.dp, 10.dp)
                .offset(y = (-2).dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(this@Canvas.size.width, 0f)
                lineTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                close()
            }
            drawPath(path, SoftLavender)
        }
    }
}

/**
 * Custom avatar marker for map display with kawaii style (original without arrow)
 */
@Composable
fun AvatarMapMarker(
    user: UserLocation,
    isMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Bounce animation when pressed
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "markerScale"
    )
    
    // Pulse animation for online status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val borderColor = if (isMe) SoftPink else Color(0xFF98E4C8)
    val backgroundColor = if (isMe) PastelPink else PastelGreen

    Box(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Pulse effect background (only when online)
        if (user.isOnline) {
            Box(
                modifier = Modifier
                    .size(size * pulseScale)
                    .clip(CircleShape)
                    .background(borderColor.copy(alpha = pulseAlpha))
            )
        }
        
        // Main avatar container
        Box(
            modifier = Modifier
                .size(size)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(3.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val context = LocalContext.current
            val imageLoader = remember { createImageLoaderWithBase64Support(context) }
            
            // Avatar image - display actual avatar if URL exists, otherwise show emoji
            if (user.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(user.avatarUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "Avatar of ${user.userName}",
                    modifier = Modifier
                        .size(size - 8.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // No avatar - emoji placeholder
                Box(
                    modifier = Modifier
                        .size(size - 8.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    backgroundColor,
                                    borderColor.copy(alpha = 0.5f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMe) "🌸" else "🍋",
                        fontSize = (size.value * 0.45f).sp
                    )
                }
            }
        }
        // Online indicator
        if (user.isOnline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-4).dp, y = (-4).dp)
                    .size(14.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50))
                    .border(2.dp, Color.White, CircleShape)
            )
        }
        
        // Circular battery indicator (replaces old rectangular)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    when {
                        user.batteryLevel > 50 -> Color(0xFF4CAF50)
                        user.batteryLevel > 20 -> Color(0xFFFFC107)
                        else -> Color(0xFFFF5252)
                    }
                )
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${user.batteryLevel}",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * Distance info bubble showing distance between users
 * Also shows colocation status when both users are together
 */
@Composable
fun DistanceInfoBubble(
    distanceText: String,
    lastSync: String,
    isColocationActive: Boolean = false,
    colocationDurationMinutes: Int = 0,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "heart")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartScale"
    )
    
    // Rainbow animation for colocation
    val colocationColorShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "colorShift"
    )
    
    val containerColor = if (isColocationActive) {
        Color(0xFFFFF0F5) // Light pink for together
    } else {
        Color.White
    }
    
    Surface(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Animated heart icon
            Text(
                text = if (isColocationActive) "💑" else "💕",
                fontSize = 18.sp,
                modifier = Modifier.scale(heartScale)
            )
            
            Column {
                if (isColocationActive) {
                    Text(
                        text = stringResource(R.string.together_celebration),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = SoftPink
                    )
                    Text(
                        text = if (colocationDurationMinutes >= 5) {
                            "📸 New place created! Photos being saved..."
                        } else {
                            "📍 ${5 - colocationDurationMinutes} min until place is saved"
                        },
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        text = distanceText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = lastSync,
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Top bar for distance screen with back button
 */
@Composable
fun DistanceTopBar(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(44.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Floating action button row with user avatars and shared places button
 */
@Composable
fun DistanceFloatingButtons(
    myUser: UserLocation?,
    partnerUser: UserLocation?,
    onMyAvatarClick: () -> Unit,
    onPartnerAvatarClick: () -> Unit,
    onSharedPlacesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // My avatar button
            FloatingAvatarButton(
                emoji = "🌸",
                avatarUrl = myUser?.avatarUrl,
                backgroundColor = PastelPink,
                borderColor = SoftPink,
                onClick = onMyAvatarClick,
                isUser = true
            )
            
            // Partner avatar button  
            FloatingAvatarButton(
                emoji = "🍋",
                avatarUrl = partnerUser?.avatarUrl,
                backgroundColor = PastelGreen,
                borderColor = Color(0xFF98E4C8),
                onClick = onPartnerAvatarClick,
                isUser = true
            )
            
            // Shared places button
            FloatingAvatarButton(
                emoji = "📍",
                avatarUrl = null,
                backgroundColor = PastelPurple,
                borderColor = SoftLavender,
                onClick = onSharedPlacesClick,
                isUser = false,
                hasNotification = true
            )
        }
    }
}

@Composable
private fun FloatingAvatarButton(
    emoji: String,
    avatarUrl: String?,
    backgroundColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    isUser: Boolean,
    hasNotification: Boolean = false
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Use custom ImageLoader that supports base64 from Firestore
    val imageLoader = remember { createImageLoaderWithBase64Support(context) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "fabScale"
    )
    
    Box(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(2.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!avatarUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(avatarUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "User avatar",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // No avatar - show emoji as fallback
                Text(text = emoji, fontSize = 22.sp)
            }
        }
        
        // Notification badge
        if (hasNotification) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF6B9D))
                    .border(1.5.dp, Color.White, CircleShape)
            )
        }
    }
}

/**
 * Refresh location button
 */
@Composable
fun RefreshLocationButton(
    onClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isLoading) 360f else 0f,
        animationSpec = if (isLoading) {
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            tween(0)
        },
        label = "refreshRotation"
    )
    
    IconButton(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier
            .size(48.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
    ) {
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = "Refresh Location",
            tint = if (isLoading) TextSecondary else SoftPink,
            modifier = Modifier.size(24.dp)
        )
    }
}
