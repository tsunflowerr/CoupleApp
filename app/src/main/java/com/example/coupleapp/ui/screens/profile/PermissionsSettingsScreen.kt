package com.example.coupleapp.ui.screens.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.coupleapp.R
import com.example.coupleapp.util.BatteryOptimizationHelper
import com.example.coupleapp.data.sleep.GoogleSleepApiManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Permissions Settings Screen
 * Shows checklist of required permissions with status and "Fix" button
 * 
 * Required permissions for full functionality:
 * 1. Location (Always) - For background location sharing
 * 2. Activity Recognition - For Google Sleep API
 * 3. Battery Optimization - Disabled for reliable background work
 * 4. Notifications - For partner alerts
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PermissionsSettingsScreen(
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    
    // Activity Recognition permission state for direct request
    val activityRecognitionPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberPermissionState(Manifest.permission.ACTIVITY_RECOGNITION)
    } else null
    
    // Coroutine scope for async operations
    val scope = rememberCoroutineScope()
    
    // Auto-enable Google Sleep API when permission is granted
    LaunchedEffect(activityRecognitionPermissionState?.status?.isGranted) {
        if (activityRecognitionPermissionState?.status?.isGranted == true) {
            // Permission just granted, register Google Sleep API
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    val sleepManager = GoogleSleepApiManager(context)
                    sleepManager.ensureRegistered(autoEnable = true)
                    android.util.Log.d("PermissionsSettings", "✅ Auto-registered Google Sleep API after permission granted")
                } catch (e: Exception) {
                    android.util.Log.e("PermissionsSettings", "Failed to register Google Sleep API", e)
                }
            }
        }
    }
    
    // Permission states - refresh when screen resumes
    var refreshTrigger by remember { mutableStateOf(0) }
    
    val hasLocationPermission by remember(refreshTrigger) {
        derivedStateOf {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    val hasBackgroundLocation by remember(refreshTrigger) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                hasLocationPermission
            }
        }
    }
    
    val hasActivityRecognition by remember(refreshTrigger) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }
    
    val hasBatteryOptimizationDisabled by remember(refreshTrigger) {
        derivedStateOf {
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        }
    }
    
    val hasNotificationPermission by remember(refreshTrigger) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }
    
    // Refresh permissions when returning to screen
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }
    
    // Calculate overall status
    val allPermissionsGranted = hasLocationPermission && hasBackgroundLocation && 
            hasActivityRecognition && hasBatteryOptimizationDisabled && hasNotificationPermission
    val grantedCount = listOf(
        hasLocationPermission && hasBackgroundLocation,
        hasActivityRecognition,
        hasBatteryOptimizationDisabled,
        hasNotificationPermission
    ).count { it }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_permissions),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3748)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color(0xFF2D3748)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFFFF5F8),
                            Color(0xFFFFFBF5),
                            Color(0xFFFFFAF0)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Status Card
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(400)) +
                            slideInVertically(animationSpec = tween(400)) { -it / 4 }
                ) {
                    StatusCard(
                        allGranted = allPermissionsGranted,
                        grantedCount = grantedCount,
                        totalCount = 4
                    )
                }
                
                // Permissions List
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(400, delayMillis = 100)) +
                            slideInVertically(animationSpec = tween(400, delayMillis = 100)) { it / 4 }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.required_permissions),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF718096),
                            letterSpacing = 1.sp
                        )
                        
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Column {
                                // Location Permission
                                PermissionItem(
                                    icon = Icons.Filled.LocationOn,
                                    title = "Vị trí (Mọi lúc)",
                                    description = if (hasBackgroundLocation) 
                                        "Đã cho phép theo dõi vị trí nền" 
                                    else if (hasLocationPermission)
                                        "Cần cho phép 'Mọi lúc' trong cài đặt"
                                    else 
                                        "Cần thiết để chia sẻ vị trí với người yêu",
                                    isGranted = hasBackgroundLocation,
                                    importance = PermissionImportance.CRITICAL,
                                    onFixClick = {
                                        openAppSettings(context)
                                    }
                                )
                                
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                
                                // Activity Recognition
                                PermissionItem(
                                    icon = Icons.Filled.DirectionsWalk,
                                    title = "Hoạt động thể chất",
                                    description = if (hasActivityRecognition) 
                                        "Đã cho phép theo dõi giấc ngủ tự động"
                                    else 
                                        "Cần thiết cho Google Sleep API",
                                    isGranted = hasActivityRecognition,
                                    importance = PermissionImportance.RECOMMENDED,
                                    onFixClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            activityRecognitionPermissionState?.let { permission ->
                                                if (permission.status.shouldShowRationale) {
                                                    // Permission denied before, open app settings
                                                    openAppSettings(context)
                                                } else if (!permission.status.isGranted) {
                                                    // Request permission directly
                                                    permission.launchPermissionRequest()
                                                }
                                            } ?: openAppSettings(context)
                                        } else {
                                            openAppSettings(context)
                                        }
                                    }
                                )
                                
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                
                                // Battery Optimization
                                PermissionItem(
                                    icon = Icons.Filled.BatteryChargingFull,
                                    title = "Tắt tiết kiệm pin",
                                    description = if (hasBatteryOptimizationDisabled) 
                                        "Đã tắt tiết kiệm pin cho app"
                                    else 
                                        "Cần thiết để nhận thông báo đầy đủ",
                                    isGranted = hasBatteryOptimizationDisabled,
                                    importance = PermissionImportance.CRITICAL,
                                    onFixClick = {
                                        BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
                                    }
                                )
                                
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                
                                // Notifications
                                PermissionItem(
                                    icon = Icons.Filled.Notifications,
                                    title = "Thông báo",
                                    description = if (hasNotificationPermission) 
                                        "Đã cho phép nhận thông báo"
                                    else 
                                        "Cần thiết để nhận tin từ người yêu",
                                    isGranted = hasNotificationPermission,
                                    importance = PermissionImportance.RECOMMENDED,
                                    onFixClick = {
                                        openAppSettings(context)
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Manufacturer-specific instructions
                AnimatedVisibility(
                    visible = visible && !allPermissionsGranted,
                    enter = fadeIn(animationSpec = tween(400, delayMillis = 200)) +
                            slideInVertically(animationSpec = tween(400, delayMillis = 200)) { it / 4 }
                ) {
                    ManufacturerInstructions()
                }
                
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(
    allGranted: Boolean,
    grantedCount: Int,
    totalCount: Int
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (allGranted) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (allGranted) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (allGranted) "Hoàn tất!" else "Cần cấu hình",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (allGranted) Color(0xFF2E7D32) else Color(0xFFE65100)
                )
                Text(
                    text = if (allGranted) 
                        "Tất cả quyền đã được cấp"
                    else 
                        "$grantedCount/$totalCount quyền đã được cấp",
                    fontSize = 14.sp,
                    color = if (allGranted) Color(0xFF388E3C) else Color(0xFFF57C00)
                )
            }
        }
    }
}

enum class PermissionImportance {
    CRITICAL,
    RECOMMENDED,
    OPTIONAL
}

@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    importance: PermissionImportance,
    onFixClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isGranted -> Color(0xFFE8F5E9)
                        importance == PermissionImportance.CRITICAL -> Color(0xFFFFEBEE)
                        else -> Color(0xFFFFF3E0)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    isGranted -> Color(0xFF4CAF50)
                    importance == PermissionImportance.CRITICAL -> Color(0xFFE53935)
                    else -> Color(0xFFFF9800)
                },
                modifier = Modifier.size(24.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2D3748)
                )
                if (importance == PermissionImportance.CRITICAL && !isGranted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bắt buộc",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF718096)
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Status / Fix button
        if (isGranted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Granted",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(28.dp)
            )
        } else {
            Button(
                onClick = onFixClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B9D)
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Sửa",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ManufacturerInstructions() {
    val context = LocalContext.current
    val instructions = remember { BatteryOptimizationHelper.getBatteryOptimizationInstructions(context) }
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "HƯỚNG DẪN CHI TIẾT",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF718096),
            letterSpacing = 1.sp
        )
        
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF5F5F5)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = instructions,
                    fontSize = 13.sp,
                    color = Color(0xFF424242),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to general settings
        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }
}
