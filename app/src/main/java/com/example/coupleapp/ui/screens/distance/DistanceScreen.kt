package com.example.coupleapp.ui.screens.distance

import android.Manifest
import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coupleapp.R
import com.example.coupleapp.data.model.SharedPlace
import com.example.coupleapp.data.model.UserLocation
import com.example.coupleapp.service.LocationTrackingService
import com.example.coupleapp.ui.components.LightweightLoadingScreen
import com.example.coupleapp.ui.components.distance.*
import com.example.coupleapp.ui.components.home.BottomNavItem
import com.example.coupleapp.ui.components.home.CoupleBottomNavigation
import com.example.coupleapp.ui.theme.*
import com.example.coupleapp.viewmodel.DistanceViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow

/**
 * Main Distance Screen with map view and user locations
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DistanceScreen(
    onBackClick: () -> Unit,
    onNavigateToSharedPlaces: () -> Unit,
    onNavigateToPlacePhotos: (String) -> Unit,
    onNavigateToHome: () -> Unit = {},
    onNavigateToPartnerHub: () -> Unit = {},
    onNavigateToMoments: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    targetPlaceId: String? = null,
    viewModel: DistanceViewModel = viewModel(
        factory = DistanceViewModel.Factory(LocalContext.current.applicationContext as Application)
    ),
    questViewModel: com.example.coupleapp.viewmodel.QuestViewModelFirebase? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    
    var visible by remember { mutableStateOf(false) }
    var selectedSharedPlace by remember { mutableStateOf<SharedPlace?>(null) }
    var selectedBottomNavItem by remember { mutableStateOf<BottomNavItem?>(null) }
    
    // Handle navigation
    LaunchedEffect(selectedBottomNavItem) {
        when (selectedBottomNavItem) {
            BottomNavItem.HOME -> {
                onNavigateToHome()
            }
            BottomNavItem.FRIENDS -> {
                onNavigateToPartnerHub()
            }
            BottomNavItem.ACTIVITIES -> {
                onNavigateToMoments()
            }
            BottomNavItem.PROFILE -> {
                onNavigateToProfile()
            }
            null -> { /* Initial state, do nothing */ }
        }
    }
    
    // Camera state for controlling map animations from outside
    val cameraState = remember { CoupleMapCameraState() }
    
    // Location permissions
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            // Nếu đang loading thì ẩn content
            visible = false
        } else {
            // Nếu loading xong (isLoading = false)
            if (!visible) {
                // Giảm delay để UI hiện nhanh hơn, map sẽ render song song
                kotlinx.coroutines.delay(200)
                visible = true
            }
            // Nếu visible đã là true (quay lại từ màn hình khác), giữ nguyên -> Không bị chớp
        }
    }
    
    // Xử lý trường hợp quay lại màn hình (Hot Reload)
    LaunchedEffect(Unit) {
        if (!uiState.isLoading) {
            visible = true
        }
    }
    
    // ★ AUTO-REFRESH: Force refresh from SERVER when screen resumes
    // This fixes stale cache issue where real-time listener returns cached data
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        android.util.Log.d("DistanceScreen", "ON_RESUME: Force refreshing partner data from SERVER")
        viewModel.refreshLocations()
    }
    
    // Handle navigation with target place animation
    LaunchedEffect(targetPlaceId, uiState.sharedPlaces) {
        if (targetPlaceId != null && uiState.sharedPlaces.isNotEmpty()) {
            val targetPlace = uiState.sharedPlaces.find { it.id == targetPlaceId }
            targetPlace?.let { place ->
                kotlinx.coroutines.delay(800) // Wait for map to be ready
                cameraState.animateToSharedPlace?.invoke(place)
                selectedSharedPlace = place
            }
        }
    }
    
    // Request permissions on first launch
    var permissionsRequested by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!locationPermissions.allPermissionsGranted && !permissionsRequested) {
            permissionsRequested = true
            locationPermissions.launchMultiplePermissionRequest()
        }
    }
    
    // Update quest progress when location is shared (permissions granted and screen loaded)
    LaunchedEffect(locationPermissions.allPermissionsGranted, uiState.isLoading) {
        if (locationPermissions.allPermissionsGranted && !uiState.isLoading) {
            // User has shared location by granting permissions and viewing distance screen
            questViewModel?.updateQuestProgress(com.example.coupleapp.data.model.QuestType.SHARE_LOCATION, 1)
        }
    }
    
    // Adaptive tracking - switch to active mode when on this screen
    val context = LocalContext.current
    DisposableEffect(Unit) {
        // Enter active tracking mode for faster updates
        LocationTrackingService.updateTrackingMode(context, LocationTrackingService.TRACKING_MODE_ACTIVE)
        
        onDispose {
            // Return to foreground mode when leaving screen
            LocationTrackingService.updateTrackingMode(context, LocationTrackingService.TRACKING_MODE_FOREGROUND)
        }
    }
    
    // Handle loading state with smooth crossfade - using lightweight loading
    Crossfade(
        targetState = uiState.isLoading,
        animationSpec = tween(durationMillis = 400),  // Faster transition
        label = "loadingCrossfade"
    ) { isLoading ->
        if (isLoading) {
            // Use lightweight loading instead of heavy Lottie for map screen
            LightweightLoadingScreen(message = stringResource(R.string.loading_location))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFFFF5F8),
                                Color(0xFFFFFBF5),
                                Color(0xFFF5F5F5)
                            )
                        )
                    )
            ) {
                // Google Maps or Fallback Map
                if (locationPermissions.allPermissionsGranted) {
                    // Real Google Maps with shared places
                    CoupleGoogleMap(
                        myLocation = uiState.myLocation,
                        partnerLocation = uiState.partnerLocation,
                        sharedPlaces = uiState.sharedPlaces,
                        onMyMarkerClick = { 
                            uiState.myLocation?.let { viewModel.selectUser(it) }
                        },
                        onPartnerMarkerClick = {
                            uiState.partnerLocation?.let { viewModel.selectUser(it) }
                        },
                        onSharedPlaceClick = { place ->
                            selectedSharedPlace = place
                        },
                        cameraState = cameraState,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Fallback to fake map when permissions not granted
                    FakeMapView(
                        myLocation = uiState.myLocation,
                        partnerLocation = uiState.partnerLocation,
                        sharedPlaces = uiState.sharedPlaces,
                        onMyMarkerClick = { 
                            uiState.myLocation?.let { viewModel.selectUser(it) }
                        },
                        onPartnerMarkerClick = {
                            uiState.partnerLocation?.let { viewModel.selectUser(it) }
                        },
                        onSharedPlaceClick = { place ->
                            selectedSharedPlace = place
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Permission request overlay
                    PermissionRequestOverlay(
                        onRequestPermission = {
                            locationPermissions.launchMultiplePermissionRequest()
                        },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                
                // Top Bar
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                ) {
                    DistanceTopBar(
                        onBackClick = onBackClick
                    )
                }
                
                // Distance Info Bubble
                AnimatedVisibility(
                    visible = visible && uiState.distanceText.isNotEmpty(),
                    enter = fadeIn(tween(500, delayMillis = 100)) + 
                            scaleIn(tween(500, delayMillis = 100), initialScale = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 70.dp)
                ) {
                    DistanceInfoBubble(
                        distanceText = uiState.distanceText,
                        lastSync = uiState.lastSyncTime,
                        isColocationActive = uiState.isColocationActive,
                        colocationDurationMinutes = uiState.colocationDurationMinutes
                    )
                }
                
                // Floating Action Buttons - positioned above bottom navigation
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 200)) + 
                            slideInVertically(tween(500, delayMillis = 200)) { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp) // Above bottom navigation
                ) {
                    DistanceFloatingButtons(
                        myUser = uiState.myLocation,
                        partnerUser = uiState.partnerLocation,
                        onMyAvatarClick = {
                            // Animate to my location on map
                            cameraState.animateToMyLocation?.invoke()
                            uiState.myLocation?.let { viewModel.selectUser(it) }
                        },
                        onPartnerAvatarClick = {
                            // Animate to partner location on map
                            cameraState.animateToPartnerLocation?.invoke()
                            uiState.partnerLocation?.let { viewModel.selectUser(it) }
                        },
                        onSharedPlacesClick = onNavigateToSharedPlaces
                    )
                }
                
                // My Location button - doesn't reload, just targets
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMillis = 300)) + 
                            slideInHorizontally(tween(500, delayMillis = 300)) { it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 180.dp, end = 16.dp) // Above floating buttons
                ) {
                    MyLocationButton(
                        onClick = { 
                            // Animate to my location on map
                            cameraState.animateToMyLocation?.invoke()
                        }
                    )
                }
                
                // Bottom Navigation
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    CoupleBottomNavigation(
                        selectedItem = selectedBottomNavItem ?: BottomNavItem.HOME,
                        onItemSelected = { item ->
                            selectedBottomNavItem = item
                        }
                    )
                }
            }
            
            // User Info Bottom Sheet with improved animation
            if (uiState.showUserInfoSheet && uiState.selectedUser != null) {
                // Compare with actual currentUserId from Firebase, not hardcoded value
                val isMe = uiState.selectedUser?.userId == uiState.currentUserId
                val history = if (isMe) uiState.myLocationHistory else uiState.partnerLocationHistory
                
                android.util.Log.d("DistanceScreen", "UserInfoBottomSheet - selectedUserId: ${uiState.selectedUser?.userId}, currentUserId: ${uiState.currentUserId}, isMe: $isMe")
                
                UserInfoBottomSheet(
                    user = uiState.selectedUser,
                    locationHistory = history,
                    isMe = isMe,
                    onDismiss = { viewModel.dismissUserInfoSheet() },
                    onLocationHistoryClick = { location ->
                        // Animate map to the clicked location history
                        cameraState.animateToHistoryLocation?.invoke(location)
                    }
                )
            }
            
            // Shared Place Bottom Sheet
            if (selectedSharedPlace != null) {
                SharedPlaceBottomSheet(
                    place = selectedSharedPlace!!,
                    onDismiss = { selectedSharedPlace = null },
                    onViewAllPhotos = { place ->
                        onNavigateToPlacePhotos(place.id)
                        selectedSharedPlace = null
                    },
                    onAddPhoto = { place ->
                        // Handle add photo
                        onNavigateToPlacePhotos(place.id)
                        selectedSharedPlace = null
                    }
                )
            }
        }
    }
}

/**
 * Fake map view as placeholder
 * In production, replace with Google Maps or OSM integration
 */
@Composable
private fun FakeMapView(
    myLocation: UserLocation?,
    partnerLocation: UserLocation?,
    sharedPlaces: List<SharedPlace>,
    onMyMarkerClick: () -> Unit,
    onPartnerMarkerClick: () -> Unit,
    onSharedPlaceClick: (SharedPlace) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // Map grid background pattern
        MapGridBackground()
        
        // Shared places markers
        sharedPlaces.forEachIndexed { index, place ->
            Box(
                modifier = Modifier
                    .offset(
                        x = (50 + (index * 80) % 250).dp,
                        y = (150 + (index * 60) % 300).dp
                    )
                    .clickable { onSharedPlaceClick(place) }
            ) {
                SharedPlaceMapMarkerContent(
                    place = place,
                    size = 48.dp
                )
            }
        }
        
        // Map markers
        if (myLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 80.dp, bottom = 200.dp)
            ) {
                AvatarMapMarkerWithArrow(
                    user = myLocation,
                    isMe = true,
                    onClick = onMyMarkerClick,
                    size = 60.dp
                )
            }
        }
        
        if (partnerLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 180.dp, end = 40.dp)
            ) {
                AvatarMapMarkerWithArrow(
                    user = partnerLocation,
                    isMe = false,
                    onClick = onPartnerMarkerClick,
                    size = 60.dp
                )
            }
        }
        
        // Decorative elements
        MapDecorations()
    }
}

/**
 * My Location button that directly targets without reloading
 */
@Composable
private fun MyLocationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
    ) {
        Icon(
            imageVector = Icons.Default.MyLocation,
            contentDescription = "My Location",
            tint = SoftPink,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun MapGridBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8F4E8),
                        Color(0xFFF5F5F0),
                        Color(0xFFF0F0E8)
                    )
                )
            )
    ) {
        // Street grid pattern simulation
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(8) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color(0xFFD0D0D0).copy(alpha = 0.3f))
                )
            }
        }
        
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(6) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(Color(0xFFD0D0D0).copy(alpha = 0.3f))
                )
            }
        }
        
        // Green park area
        Box(
            modifier = Modifier
                .size(120.dp, 80.dp)
                .offset(x = 200.dp, y = 350.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF98E4C8).copy(alpha = 0.4f))
        )
        
        // Water area
        Box(
            modifier = Modifier
                .size(80.dp, 100.dp)
                .offset(x = 30.dp, y = 400.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF9ED9FF).copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun MapDecorations() {
    // Cute location pins decoration
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Location pin icons scattered
        Text(
            text = "🏢",
            fontSize = 24.sp,
            modifier = Modifier.offset(x = 250.dp, y = 280.dp)
        )
        
        Text(
            text = "🏥",
            fontSize = 20.sp,
            modifier = Modifier.offset(x = 180.dp, y = 320.dp)
        )
        
        Text(
            text = "🌳",
            fontSize = 28.sp,
            modifier = Modifier.offset(x = 230.dp, y = 380.dp)
        )
        
        Text(
            text = "☕",
            fontSize = 18.sp,
            modifier = Modifier.offset(x = 120.dp, y = 250.dp)
        )
        
        Text(
            text = "🏬",
            fontSize = 22.sp,
            modifier = Modifier.offset(x = 50.dp, y = 300.dp)
        )
        
        // Metro icon
        Text(
            text = "Ⓜ️",
            fontSize = 16.sp,
            modifier = Modifier.offset(x = 90.dp, y = 140.dp)
        )
    }
}

/**
 * Overlay to request location permissions
 */
@Composable
private fun PermissionRequestOverlay(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(32.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated location icon
            val infiniteTransition = rememberInfiniteTransition(label = "locationPulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = SoftPink,
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
            
            Text(
                text = stringResource(R.string.enable_location),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Text(
                text = stringResource(R.string.location_request_message),
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            
            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftPink
                ),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.allow_location),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
