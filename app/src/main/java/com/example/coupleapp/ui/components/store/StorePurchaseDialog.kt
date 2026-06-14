package com.example.coupleapp.ui.components.store

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.coupleapp.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupleapp.data.model.PurchaseType
import com.example.coupleapp.data.model.StoreItem
import java.util.Locale

/**
 * Purchase confirmation dialog with quantity selector
 */
@Composable
fun PurchaseConfirmDialog(
    item: StoreItem,
    quantity: Int,
    userCoins: Int,
    canClaimFree: Boolean,
    cooldownDays: Int,
    onDismiss: () -> Unit,
    onQuantityChange: (Int) -> Unit,
    onPurchaseCoins: (StoreItem) -> Unit,
    onClaimFree: (StoreItem) -> Unit,
    onWatchAd: (StoreItem) -> Unit,
    onPurchaseReal: (StoreItem) -> Unit,
    isPurchasing: Boolean = false
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isVietnamese = Locale.getDefault().language == "vi"
    val localizedName = item.getLocalizedName(isVietnamese)
    val localizedDescription = item.getLocalizedDescription(isVietnamese)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFF8E1),
        shape = RoundedCornerShape(28.dp),
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Main content area with image on LEFT, quantity on RIGHT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // LEFT: Item image
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFFFFFF),
                                        Color(0xFFFFF9C4)
                                    )
                                )
                            )
                            .border(3.dp, Color(0xFFFFD54F), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = item.iconRes),
                            contentDescription = localizedName,
                            modifier = Modifier
                                .size(110.dp)
                                .padding(6.dp),
                            contentScale = ContentScale.Fit
                        )
                        
                        // Rarity/Duration badge
                        item.rarity?.let { rarity ->
                            RarityBadge(
                                rarity = rarity,
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        }
                        
                        item.durationHours?.let { hours ->
                            DurationBadge(
                                hours = hours,
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                        }
                    }
                    
                    // RIGHT: Quantity selection - ONLY for COIN purchases
                    // FREE_DAILY and WATCH_AD packages should always be quantity = 1
                    if (item.purchaseType == PurchaseType.COIN) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.quantity),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5D4037)
                            )
                            
                            // +/- buttons with current quantity
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Minus button
                                QuantityButton(
                                    text = "−",
                                    enabled = quantity > 1,
                                    onClick = { 
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onQuantityChange(quantity - 1)
                                    }
                                )
                                
                                // Current quantity display
                                Box(
                                    modifier = Modifier
                                        .size(45.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .border(2.dp, Color(0xFFFFB300), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$quantity",
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF5D4037)
                                    )
                                }
                                
                                // Plus button
                                QuantityButton(
                                    text = "+",
                                    enabled = true,
                                    onClick = { 
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onQuantityChange(quantity + 1)
                                    }
                                )
                            }
                            
                            // Quick select buttons (3, 5, 10)
                            Text(
                                text = stringResource(R.string.quick_select),
                                fontSize = 11.sp,
                                color = Color(0xFF8D6E63),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                QuickSelectButton(
                                    number = 3,
                                    isSelected = quantity == 3,
                                    onClick = { 
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onQuantityChange(3)
                                    }
                                )
                                QuickSelectButton(
                                    number = 5,
                                    isSelected = quantity == 5,
                                    onClick = { 
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onQuantityChange(5)
                                    }
                                )
                                // Wrap button 10 in a Box to contain the badge overflow
                                Box {
                                    QuickSelectButton(
                                        number = 10,
                                        isSelected = quantity == 10,
                                        hasBonus = true,
                                        onClick = { 
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onQuantityChange(10)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // For FREE and AD packages, show empty space to maintain layout
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Item name and description
                Text(
                    text = localizedName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF5D4037),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = localizedDescription,
                    fontSize = 13.sp,
                    color = Color(0xFF8D6E63),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Beautiful divider
                BeautifulDivider()
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Price and purchase button
                when (item.purchaseType) {
                    PurchaseType.FREE_DAILY -> {
                        if (canClaimFree) {
                            FreePriceInfo()
                        } else {
                            CooldownInfo(cooldownDays)
                        }
                    }
                    PurchaseType.WATCH_AD -> {
                        AdPriceInfo()
                    }
                    PurchaseType.COIN -> {
                        val totalCost = item.coinPrice * quantity
                        CoinPriceInfo(
                            unitPrice = item.coinPrice,
                            quantity = quantity,
                            totalCost = totalCost,
                            userCoins = userCoins
                        )
                    }
                    PurchaseType.REAL_MONEY -> {
                        RealMoneyPriceInfo(item.realPrice)
                    }
                }
            }
        },
        confirmButton = {
            val enabled = when (item.purchaseType) {
                PurchaseType.FREE_DAILY -> canClaimFree && !isPurchasing
                PurchaseType.WATCH_AD -> !isPurchasing
                PurchaseType.COIN -> userCoins >= (item.coinPrice * quantity) && !isPurchasing
                PurchaseType.REAL_MONEY -> !isPurchasing
            }

            BeautifulPurchaseButton(
                purchaseType = item.purchaseType,
                enabled = enabled,
                isPurchasing = isPurchasing,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (item.purchaseType) {
                        PurchaseType.FREE_DAILY -> onClaimFree(item)
                        PurchaseType.WATCH_AD -> onWatchAd(item)
                        PurchaseType.COIN -> onPurchaseCoins(item)
                        PurchaseType.REAL_MONEY -> onPurchaseReal(item)
                    }
                }
            )
        },
        dismissButton = {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.9f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "cancel_scale"
            )
            
            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE0E0E0))
                    .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color(0xFF5D4037)
                )
            }
        }
    )
}

/**
 * Quantity +/- button
 */
@Composable
fun QuantityButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    Box(
        modifier = Modifier
            .scale(scale)
            .size(38.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFB300),
                            Color(0xFFFFCA28),
                            Color(0xFFFFD54F)
                        ),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(100f, 100f)
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E))
                    )
                }
            )
            .border(
                2.dp,
                if (enabled) Color(0xFFFF8F00) else Color(0xFF757575),
                RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.25f),
                    offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                    blurRadius = 2f
                )
            )
        )
    }
}

/**
 * Quick select button for predefined quantities
 */
@Composable
fun QuickSelectButton(
    number: Int,
    isSelected: Boolean,
    hasBonus: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    
    // Fixed size container to prevent layout shifts from badge
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .shadow(if (isSelected) 4.dp else 2.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF66BB6A),
                                Color(0xFF4CAF50),
                                Color(0xFF388E3C)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFFFFDE7), Color(0xFFFFF9C4))
                        )
                    }
                )
                .border(
                    2.dp,
                    if (isSelected) Color(0xFF2E7D32) else Color(0xFFFFB300),
                    RoundedCornerShape(10.dp)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$number",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else Color(0xFF5D4037),
                style = androidx.compose.ui.text.TextStyle(
                    shadow = if (isSelected) {
                        androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.3f),
                            offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                            blurRadius = 2f
                        )
                    } else null
                )
            )
        }
        
        // Bonus badge for 10 - positioned absolutely
        if (hasBonus) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(18.dp)
                    .shadow(3.dp, androidx.compose.foundation.shape.CircleShape)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFFFF6F00), Color(0xFFFF5722))
                        )
                    )
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+1",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.4f),
                            offset = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                            blurRadius = 1f
                        )
                    )
                )
            }
        }
    }
}

/**
 * Beautiful divider for dialog
 */
@Composable
fun BeautifulDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Left line
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFFFFD54F).copy(alpha = 0.6f),
                            Color(0xFFFFB300)
                        )
                    )
                )
        )
        
        // Center decoration
        Text(
            text = "✦",
            fontSize = 18.sp,
            color = Color(0xFFFFB300),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        
        // Right line
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFFFB300),
                            Color(0xFFFFD54F).copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * Beautiful purchase button with different styles based on purchase type
 */
@Composable
fun BeautifulPurchaseButton(
    purchaseType: PurchaseType,
    enabled: Boolean,
    onClick: () -> Unit,
    isPurchasing: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "scale"
    )
    
    val gradientColors = when (purchaseType) {
        PurchaseType.FREE_DAILY -> listOf(Color(0xFF4CAF50), Color(0xFF66BB6A), Color(0xFF81C784))
        PurchaseType.WATCH_AD -> listOf(Color(0xFF9C27B0), Color(0xFFAB47BC), Color(0xFFBA68C8))
        PurchaseType.COIN -> listOf(Color(0xFFFFB300), Color(0xFFFFCA28), Color(0xFFFFD54F))
        PurchaseType.REAL_MONEY -> listOf(Color(0xFF1976D2), Color(0xFF1E88E5), Color(0xFF42A5F5))
    }
    
    val buttonText = when (purchaseType) {
        PurchaseType.FREE_DAILY -> "Claim Free Gift"
        PurchaseType.WATCH_AD -> "Watch Ad"
        PurchaseType.COIN -> "Purchase"
        PurchaseType.REAL_MONEY -> "Buy Now"
    }
    
    Box(
        modifier = Modifier
            .scale(scale)
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(colors = gradientColors)
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E))
                    )
                }
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = if (enabled) {
                        listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.2f))
                    } else {
                        listOf(Color(0xFF757575), Color(0xFF616161))
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onClick() }
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isPurchasing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Icon based on purchase type
                when (purchaseType) {
                    PurchaseType.FREE_DAILY -> Text(text = "🎁", fontSize = 20.sp)
                    PurchaseType.WATCH_AD -> androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    PurchaseType.COIN -> {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(20.dp)) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFFFFD700), Color(0xFFDAA520))
                                )
                            )
                            drawCircle(
                                color = Color(0xFFB8860B),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                            )
                        }
                    }
                    PurchaseType.REAL_MONEY -> Text(text = "💎", fontSize = 20.sp)
                }
                
                Text(
                    text = buttonText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
