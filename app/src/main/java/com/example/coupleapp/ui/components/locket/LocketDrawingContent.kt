package com.example.coupleapp.ui.components.locket

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupleapp.R

/**
 * Drawing content view for Locket
 * Shows a placeholder image and button to open drawing canvas
 */
@Composable
fun LocketDrawingContent(
    hasDrawing: Boolean,
    drawingBitmap: Bitmap? = null,
    onOpenDrawing: () -> Unit,
    onSendDrawing: () -> Unit,
    isSending: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drawing display box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .then(
                    if (drawingBitmap != null) {
                        Modifier.background(Color.White)
                    } else {
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFFFF0E8),
                                    Color(0xFFFFE8E0)
                                )
                            )
                        )
                    }
                )
                .clickable { onOpenDrawing() },
            contentAlignment = Alignment.Center
        ) {
            if (drawingBitmap != null) {
                // Show actual drawing preview
                Image(
                    bitmap = drawingBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.your_drawing),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                
                // Edit overlay icon
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit drawing",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // Placeholder
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Drawing illustration
                    Icon(
                        imageVector = Icons.Filled.Brush,
                        contentDescription = null,
                        tint = Color(0xFFFF9ECE),
                        modifier = Modifier.size(80.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = stringResource(R.string.draw_for_love),
                        color = Color(0xFF757575),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 24.sp
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Draw button
            CaptureButton(
                onClick = onOpenDrawing,
                color = if (hasDrawing) Color(0xFFF5F5F5) else Color(0xFFFF9ECE),
                modifier = Modifier.size(if (hasDrawing) 56.dp else 76.dp)
            )
            
            if (hasDrawing) {
                Spacer(modifier = Modifier.width(32.dp))
                
                // Send button
                CaptureButton(
                    onClick = onSendDrawing,
                    color = Color(0xFF4CAF50),
                    enabled = !isSending
                )
            }
        }
    }
}
