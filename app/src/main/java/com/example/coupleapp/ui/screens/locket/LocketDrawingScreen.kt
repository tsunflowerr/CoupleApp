package com.example.coupleapp.ui.screens.locket

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.coupleapp.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupleapp.data.model.DrawingPath
import com.example.coupleapp.data.model.DrawingPoint

/**
 * Drawing screen similar to Paint app
 */
@Composable
fun LocketDrawingScreen(
    onBackClick: () -> Unit,
    onSaveDrawing: (List<DrawingPath>, Int, Int) -> Unit, // Now includes canvas size
    modifier: Modifier = Modifier
) {
    var paths by remember { mutableStateOf(listOf<PathData>()) }
    var currentPath by remember { mutableStateOf<PathData?>(null) }
    var selectedColor by remember { mutableStateOf(Color(0xFF2D2D2D)) }
    var strokeWidth by remember { mutableStateOf(8f) }
    var selectedTool by remember { mutableStateOf(DrawingTool.PEN) }
    
    // Track canvas size for proper scaling when saving
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    
    val colors = remember {
        listOf(
            Color(0xFF2D2D2D), // Black
            Color(0xFFFF6B6B), // Red
            Color(0xFFFF9ECE), // Pink
            Color(0xFFFFB347), // Orange
            Color(0xFFFFD93D), // Yellow
            Color(0xFF4CAF50), // Green
            Color(0xFF6BCB77), // Light Green
            Color(0xFF4D96FF), // Blue
            Color(0xFF9ED9FF), // Light Blue
            Color(0xFF845EC2), // Purple
            Color(0xFFFFFFFF), // White (eraser)
        )
    }
    
    val strokeWidths = remember { listOf(4f, 8f, 12f, 20f, 30f) }
    
    Scaffold(
        topBar = {
            DrawingTopBar(
                onBackClick = onBackClick,
                onUndo = {
                    if (paths.isNotEmpty()) {
                        paths = paths.dropLast(1)
                    }
                },
                onClear = { paths = emptyList() },
                onSave = {
                    val drawingPaths = paths.map { pathData ->
                        DrawingPath(
                            points = pathData.points.map { DrawingPoint(it.x, it.y) },
                            color = pathData.color.toArgb().toLong(),
                            strokeWidth = pathData.strokeWidth
                        )
                    }
                    // Pass canvas size along with paths for proper scaling
                    onSaveDrawing(drawingPaths, canvasSize.width.toInt(), canvasSize.height.toInt())
                }
            )
        },
        containerColor = Color.White
    ) { paddingValues ->
        
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Drawing canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFFAFAFA))
                    .border(2.dp, Color(0xFFE0E0E0), RoundedCornerShape(24.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selectedColor, strokeWidth, selectedTool) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPath = PathData(
                                        points = mutableListOf(offset),
                                        color = if (selectedTool == DrawingTool.ERASER) Color.White 
                                               else selectedColor,
                                        strokeWidth = if (selectedTool == DrawingTool.ERASER) 30f 
                                                     else strokeWidth
                                    )
                                },
                                onDrag = { change, _ ->
                                    currentPath?.let { path ->
                                        val newPoints = path.points.toMutableList()
                                        newPoints.add(change.position)
                                        currentPath = path.copy(points = newPoints)
                                    }
                                },
                                onDragEnd = {
                                    currentPath?.let { path ->
                                        paths = paths + path
                                    }
                                    currentPath = null
                                }
                            )
                        }
                ) {
                    // Track canvas size for scaling when saving
                    canvasSize = size
                    
                    // Draw completed paths
                    paths.forEach { pathData ->
                        drawPath(pathData)
                    }
                    
                    // Draw current path
                    currentPath?.let { pathData ->
                        drawPath(pathData)
                    }
                }
                
                // Canvas placeholder text
                if (paths.isEmpty() && currentPath == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Gesture,
                                contentDescription = null,
                                tint = Color(0xFFE0E0E0),
                                modifier = Modifier.size(64.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = stringResource(R.string.draw_here),
                                color = Color(0xFFB0B0B0),
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
            
            // Tools section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                // Tool selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(
                        icon = Icons.Default.Edit,
                        label = "Pen",
                        isSelected = selectedTool == DrawingTool.PEN,
                        onClick = { selectedTool = DrawingTool.PEN }
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    ToolButton(
                        icon = Icons.Default.Circle,
                        label = "Eraser",
                        isSelected = selectedTool == DrawingTool.ERASER,
                        onClick = { selectedTool = DrawingTool.ERASER }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Color palette
                Text(
                    text = stringResource(R.string.color),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(colors) { color ->
                        ColorButton(
                            color = color,
                            isSelected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Stroke width selector
                Text(
                    text = stringResource(R.string.stroke_width),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    strokeWidths.forEach { width ->
                        StrokeWidthButton(
                            strokeWidth = width,
                            isSelected = strokeWidth == width,
                            selectedColor = selectedColor,
                            onClick = { strokeWidth = width }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingTopBar(
    onBackClick: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color(0xFF2D2D2D)
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = stringResource(R.string.draw),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = Color(0xFF2D2D2D)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = Color(0xFF757575)
                )
            }
            
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear",
                    tint = Color(0xFF757575)
                )
            }
            
            TextButton(
                onClick = onSave,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = stringResource(R.string.save),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) Color(0xFF4CAF50).copy(alpha = 0.1f)
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color(0xFF4CAF50) else Color(0xFF757575),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = label,
            color = if (isSelected) Color(0xFF4CAF50) else Color(0xFF757575),
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ColorButton(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "colorScale"
    )
    
    Box(
        modifier = modifier
            .size((32 * scale).dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (color == Color.White) {
                    Modifier.border(1.dp, Color(0xFFE0E0E0), CircleShape)
                } else Modifier
            )
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, Color(0xFF2D2D2D), CircleShape)
                } else Modifier
            )
            .clickable { onClick() }
    )
}

@Composable
private fun StrokeWidthButton(
    strokeWidth: Float,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) Color(0xFFF5F5F5) else Color.Transparent
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, Color(0xFF4CAF50), CircleShape)
                } else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(strokeWidth.dp)
                .clip(CircleShape)
                .background(selectedColor)
                .then(
                    if (selectedColor == Color.White) {
                        Modifier.border(1.dp, Color(0xFFE0E0E0), CircleShape)
                    } else Modifier
                )
        )
    }
}

/**
 * Draw path on canvas
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPath(pathData: PathData) {
    if (pathData.points.size < 2) return
    
    val path = Path().apply {
        val firstPoint = pathData.points.first()
        moveTo(firstPoint.x, firstPoint.y)
        
        for (i in 1 until pathData.points.size) {
            val point = pathData.points[i]
            lineTo(point.x, point.y)
        }
    }
    
    drawPath(
        path = path,
        color = pathData.color,
        style = Stroke(
            width = pathData.strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

/**
 * Internal path data class
 */
private data class PathData(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

/**
 * Drawing tools enum
 */
private enum class DrawingTool {
    PEN,
    ERASER
}

/**
 * Convert drawing paths to Bitmap with proper scaling
 * @param drawingPaths The paths drawn by user
 * @param canvasWidth The width of the canvas where user drew (in pixels)
 * @param canvasHeight The height of the canvas where user drew (in pixels)
 * @param outputSize The desired output bitmap size (square)
 */
fun convertPathsToBitmap(
    drawingPaths: List<DrawingPath>, 
    canvasWidth: Int, 
    canvasHeight: Int,
    outputSize: Int = 800
): Bitmap {
    val bitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    
    // Fill with white background
    canvas.drawColor(android.graphics.Color.WHITE)
    
    // Calculate scale factors to map canvas coordinates to output bitmap
    // We want to fit the drawing into the output while maintaining aspect ratio
    val scaleX = outputSize.toFloat() / canvasWidth.coerceAtLeast(1)
    val scaleY = outputSize.toFloat() / canvasHeight.coerceAtLeast(1)
    val scale = minOf(scaleX, scaleY)
    
    // Calculate offset to center the drawing
    val offsetX = (outputSize - canvasWidth * scale) / 2f
    val offsetY = (outputSize - canvasHeight * scale) / 2f
    
    // Draw all paths with scaling
    drawingPaths.forEach { drawingPath ->
        if (drawingPath.points.isEmpty()) return@forEach
        
        val paint = android.graphics.Paint().apply {
            color = drawingPath.color.toInt()
            strokeWidth = drawingPath.strokeWidth * scale
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
        }
        
        val path = android.graphics.Path()
        val firstPoint = drawingPath.points.first()
        path.moveTo(firstPoint.x * scale + offsetX, firstPoint.y * scale + offsetY)
        
        for (i in 1 until drawingPath.points.size) {
            val point = drawingPath.points[i]
            path.lineTo(point.x * scale + offsetX, point.y * scale + offsetY)
        }
        
        canvas.drawPath(path, paint)
    }
    
    return bitmap
}
