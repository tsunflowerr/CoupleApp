package com.example.coupleapp.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.example.coupleapp.ui.components.home.CoupleBottomNavigation
import com.example.coupleapp.ui.components.home.BottomNavItem
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.coupleapp.R
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.example.coupleapp.ui.components.sleep.*
import com.example.coupleapp.ui.components.LoadingScreen
import com.example.coupleapp.viewmodel.SleepTrackerViewModelFirebase
import com.example.coupleapp.viewmodel.TimeEditorType
import com.example.coupleapp.viewmodel.SleepDataStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.foundation.lazy.LazyColumn
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SleepTrackerScreen(
    onBackClick: () -> Unit,
    onNavigateToHome: () -> Unit = {},
    onNavigateToPartnerHub: () -> Unit = {},
    onNavigateToMoments: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
    onNavigateToHistory: (String) -> Unit = {},
    questViewModel: com.example.coupleapp.viewmodel.QuestViewModelFirebase? = null
) {
    val context = LocalContext.current
    val viewModel: SleepTrackerViewModelFirebase = viewModel(
        factory = viewModelFactory {
            initializer {
                SleepTrackerViewModelFirebase(context)
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var showHealthConnectPermissionDialog by remember { mutableStateOf(false) }
    var showActivityRecognitionPermissionDialog by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    
    // Activity Recognition permission state
    val activityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberPermissionState(Manifest.permission.ACTIVITY_RECOGNITION)
    } else null
    var selectedBottomNavItem by remember { mutableStateOf<BottomNavItem?>(null) }
    
    // Auto-enable Google Sleep API when permission is granted
    LaunchedEffect(activityRecognitionPermission?.status?.isGranted) {
        if (activityRecognitionPermission?.status?.isGranted == true) {
            // Permission just granted, enable Google Sleep API
            viewModel.enableGoogleSleepApi()
        }
    }
    
    // Lifecycle observer to refresh sleep state when app resumes
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSleepState()
                // Also check for new data when screen becomes visible
                viewModel.onScreenVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Check for new data when screen first appears
    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }
    
    // Show sync status snackbar
    LaunchedEffect(uiState.healthConnectSyncStatus) {
        uiState.healthConnectSyncStatus?.let { status ->
            snackbarHostState.showSnackbar(
                message = status,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSyncStatus()
        }
    }
    
    // Handle activity recognition permission request
    LaunchedEffect(uiState.needsActivityRecognitionPermission) {
        if (uiState.needsActivityRecognitionPermission) {
            showActivityRecognitionPermissionDialog = true
        }
    }
    
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
    
    // TODO: Remove after testing - Temporary state to show BedtimeReminderDialog for testing
    var showTestBedtimeDialog by remember { mutableStateOf(false) }

    val scrollState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) {
            visible = false
        } else {
            if (!visible) {
                delay(100)  // Minimal delay for smooth animation
                visible = true
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!uiState.isLoading) {
            visible = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CoupleBottomNavigation(
                selectedItem = selectedBottomNavItem ?: BottomNavItem.HOME,
                onItemSelected = { item ->
                    selectedBottomNavItem = item
                }
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->

        Crossfade(
            targetState = uiState.isLoading,
            animationSpec = tween(durationMillis = 400),
            label = "LoadingCrossfade",
            modifier = modifier.fillMaxSize()
        ) { loading ->
            if (loading) {
                LoadingScreen(message = stringResource(R.string.loading_sleep_data))
            } else {
                Box(
                    modifier = Modifier
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
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {

                        // Top Bar
                        item {
                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(animationSpec = tween(600)) +
                                        slideInVertically(animationSpec = tween(600)) { -it }
                            ) {
                                TopBar(
                                    userName = viewModel.getActiveUser().name,
                                    onBackClick = onBackClick,
                                    onMenuClick = {
                                        scope.launch { viewModel.showBottomSheet(true) }
                                    }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }

                        // Sleep Quality Circle
                        item {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                AnimatedVisibility(
                                    visible = visible,
                                    // Delay nhẹ nếu là lần đầu load
                                    enter = fadeIn(animationSpec = tween(600, delayMillis = if(uiState.isLoading) 100 else 0)) +
                                            scaleIn(
                                                animationSpec = tween(600, delayMillis = if(uiState.isLoading) 100 else 0),
                                                initialScale = 0.8f
                                            )
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        uiState.sleepRecord?.let { record ->
                                            key(record.quality, record.achievementPercentage) {
                                                SleepQualityCircle(
                                                    quality = record.quality,
                                                    achievementPercentage = record.achievementPercentage
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(24.dp))

                                            SleepQualityStatus(
                                                qualityText = record.qualityText,
                                                achievementPercentage = record.achievementPercentage,
                                                quality = record.quality
                                            )
                                        } ?: run {
                                            // Empty state - show different messages based on status
                                            val isViewingPartner = !uiState.isCurrentUser
                                            val (emoji, title, subtitle) = when (uiState.sleepDataStatus) {
                                                SleepDataStatus.WAITING_TODAY -> {
                                                    if (isViewingPartner) {
                                                        Triple(
                                                            "💤",
                                                            stringResource(R.string.sleep_waiting_partner_data),
                                                            stringResource(R.string.sleep_waiting_partner_data_subtitle)
                                                        )
                                                    } else {
                                                        Triple(
                                                            "🌅",
                                                            stringResource(R.string.sleep_waiting_today_data),
                                                            stringResource(R.string.sleep_waiting_today_data_subtitle)
                                                        )
                                                    }
                                                }
                                                SleepDataStatus.DATA_DELAYED -> {
                                                    if (isViewingPartner) {
                                                        Triple(
                                                            "🤔",
                                                            stringResource(R.string.sleep_waiting_partner_data),
                                                            stringResource(R.string.sleep_data_delayed_subtitle)
                                                        )
                                                    } else {
                                                        Triple(
                                                            "⏳",
                                                            stringResource(R.string.sleep_data_delayed),
                                                            stringResource(R.string.sleep_data_delayed_subtitle)
                                                        )
                                                    }
                                                }
                                                else -> Triple(
                                                    "😴",
                                                    stringResource(R.string.sleep_no_data),
                                                    stringResource(R.string.sleep_no_data_subtitle)
                                                )
                                            }
                                            
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.padding(vertical = 48.dp)
                                            ) {
                                                Text(
                                                    text = emoji,
                                                    fontSize = 64.sp
                                                )
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Text(
                                                    text = title,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = Color(0xFF757575),
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFB0B0B0),
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(horizontal = 32.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(40.dp)) }

                        // Sleep Times
                        item {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                AnimatedVisibility(
                                    visible = visible,
                                    enter = fadeIn(animationSpec = tween(600, delayMillis = if(uiState.isLoading) 250 else 0)) +
                                            slideInVertically(animationSpec = tween(600, delayMillis = if(uiState.isLoading) 250 else 0)) { it / 4 }
                                ) {
                                    Column {
                                        uiState.sleepRecord?.let { record ->
                                            SleepTimesRow(
                                                bedTime = record.bedTime,
                                                wakeUpTime = record.wakeUpTime,
                                                duration = record.actualSleepDuration,
                                                onEditBedTime = { viewModel.showTimeEditor(true, TimeEditorType.BED_TIME) },
                                                onEditWakeUpTime = { viewModel.showTimeEditor(true, TimeEditorType.WAKE_UP_TIME) },
                                                onEditDuration = { viewModel.showTimeEditor(true, TimeEditorType.SLEEP_GOAL) }
                                            )

                                            Spacer(modifier = Modifier.height(16.dp))

                                            SleepStagesCard(
                                                awakeMinutes = record.sleepStages.awakeDurationMinutes,
                                                sleepMinutes = record.sleepStages.sleepDurationMinutes
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(40.dp)) }

                        // Recent Sleep Header
                        item {
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                AnimatedVisibility(
                                    visible = visible,
                                    enter = fadeIn(animationSpec = tween(600, delayMillis = if(uiState.isLoading) 400 else 0))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.recent_sleep),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 22.sp
                                            ),
                                            color = Color(0xFF2D2D2D)
                                        )

                                        Text(
                                            text = stringResource(R.string.view_all),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = Color(0xFFFF9ECE),
                                            fontSize = 14.sp,
                                            modifier = Modifier.clickable { onNavigateToHistory(viewModel.getActiveUser().id) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        // Sleep History Items
                        items(uiState.sleepHistory) { record ->
                            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                                AnimatedVisibility(
                                    visible = visible,
                                    // Delay nối tiếp nhau nếu muốn, hoặc hiện cùng lúc
                                    enter = fadeIn(animationSpec = tween(500, delayMillis = if(uiState.isLoading) 500 else 0))
                                ) {
                                    SleepHistoryItem(record = record)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    // User Toggle Button
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(600, delayMillis = if(uiState.isLoading) 600 else 0)) +
                                slideInVertically(
                                    animationSpec = tween(600, delayMillis = if(uiState.isLoading) 600 else 0),
                                    initialOffsetY = { it }
                                ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = paddingValues.calculateBottomPadding() + 16.dp)
                    ) {
                        UserToggleButton(
                            currentUserName = uiState.currentUser.name,
                            partnerUserName = uiState.partnerUser.name,
                            isCurrentUser = uiState.isCurrentUser,
                            onToggle = { viewModel.toggleUser() },
                            currentUserAvatar = uiState.currentUser.avatarUrl,
                            partnerUserAvatar = uiState.partnerUser.avatarUrl
                        )
                    }
                }
            }
        }

        // Bedtime Reminder Dialog
        if (uiState.showBedtimeReminder || showTestBedtimeDialog) {
            val bedTime = uiState.settings?.idealBedTime ?: LocalTime.of(22, 0)
            BedtimeReminderDialog(
                bedTime = bedTime,
                onDismiss = {
                    viewModel.dismissBedtimeReminder()
                    showTestBedtimeDialog = false
                },
                onGoToSleep = {
                    viewModel.startManualSleepTracking()
                    showTestBedtimeDialog = false
                }
            )
        }

        // Wake Up Dialog
        if (uiState.showWakeUpDialog && uiState.activeSleepSession != null) {
            val startTime = uiState.activeSleepSession?.startTime?.toDate()?.toInstant()
            if (startTime != null) {
                val startDateTime = LocalDateTime.ofInstant(
                    startTime,
                    java.time.ZoneId.systemDefault()
                )
                WakeUpDialog(
                    sleepStartTime = startDateTime,
                    onWakeUp = {
                        viewModel.endManualSleepTracking()
                    },
                    onDismiss = {
                        viewModel.dismissWakeUpDialog()
                    }
                )
            }
        }

        // --- Bottom Sheet & Dialogs giữ nguyên ---
        if (uiState.showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.showBottomSheet(false) },
                sheetState = sheetState,
                containerColor = Color.Transparent,
                dragHandle = null
            ) {
                SleepSettingsBottomSheet(
                    bedTime = uiState.settings?.idealBedTime?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: "22:00",
                    sleepGoal = uiState.settings?.targetSleepDuration?.let { 
                        val hours = it / 60
                        val minutes = it % 60
                        "${hours}h ${minutes}m"
                    } ?: "8h 0m",
                    isGoogleSleepApiEnabled = uiState.isGoogleSleepApiEnabled,
                    onAddWidgetClick = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                            // TODO: Implement widget instructions
                        }
                    },
                    onWhenToSleepClick = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                            viewModel.showTimeEditor(true, TimeEditorType.BED_TIME)
                        }
                    },
                    onSleepGoalClick = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                            viewModel.showTimeEditor(true, TimeEditorType.SLEEP_GOAL)
                        }
                    },
                    onMyHistoryClick = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                            onNavigateToHistory(viewModel.getActiveUser().id)
                        }
                    },
                    onSyncHealthConnectClick = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                            
                            // Check if Health Connect is available
                            val isAvailable = viewModel.checkHealthConnectAvailability()
                            if (!isAvailable) {
                                snackbarHostState.showSnackbar(context.getString(R.string.health_connect_unavailable))
                                return@launch
                            }
                            
                            // Check if permissions are granted
                            val hasPermissions = viewModel.hasHealthConnectPermissions()
                            if (!hasPermissions) {
                                // Show guide dialog to manually enable permissions in Health Connect
                                showHealthConnectPermissionDialog = true
                            } else {
                                // Already have permissions, sync directly
                                viewModel.syncFromHealthConnect()
                                // Update quest progress when syncing sleep data
                                questViewModel?.updateQuestProgress(com.example.coupleapp.data.model.QuestType.SLEEP_TRACKING, 1)
                            }
                        }
                    },
                    onToggleGoogleSleepApi = { enabled ->
                        scope.launch {
                            if (enabled) {
                                viewModel.enableGoogleSleepApi()
                            } else {
                                viewModel.disableGoogleSleepApi()
                            }
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            sheetState.hide()
                            viewModel.showBottomSheet(false)
                        }
                    }
                )
            }
        }

        if (uiState.showTimeEditor) {
            when (uiState.timeEditorType) {
                TimeEditorType.BED_TIME -> {
                    TimeEditorDialog(
                        title = stringResource(R.string.bed_time_title),
                        initialTime = uiState.settings?.idealBedTime ?: LocalTime.of(22, 0),
                        onDismiss = { viewModel.showTimeEditor(false, null) },
                        onConfirm = { newTime ->
                            viewModel.updateBedTime(newTime)
                            viewModel.showTimeEditor(false, null)
                        }
                    )
                }
                TimeEditorType.WAKE_UP_TIME -> {
                    TimeEditorDialog(
                        title = stringResource(R.string.wake_up_time_title),
                        initialTime = uiState.settings?.idealWakeUpTime ?: LocalTime.of(7, 0),
                        onDismiss = { viewModel.showTimeEditor(false, null) },
                        onConfirm = { newTime ->
                            viewModel.updateWakeUpTime(newTime)
                            viewModel.showTimeEditor(false, null)
                        }
                    )
                }
                TimeEditorType.SLEEP_GOAL -> {
                    DurationEditorDialog(
                        title = stringResource(R.string.sleep_goal_title),
                        initialDurationMinutes = uiState.settings?.targetSleepDuration ?: 480,
                        onDismiss = { viewModel.showTimeEditor(false, null) },
                        onConfirm = { newDuration ->
                            viewModel.updateSleepGoal(newDuration)
                            viewModel.showTimeEditor(false, null)
                        }
                    )
                }
                TimeEditorType.NONE -> { }
            }
        }

        if (uiState.showBedtimeReminder) {
            uiState.settings?.let { settings ->
                BedtimeReminderDialog(
                    bedTime = settings.idealBedTime,
                    onDismiss = { viewModel.dismissBedtimeReminder() },
                    onGoToSleep = { 
                        viewModel.startManualSleepTracking()
                    }
                )
            }
        }

        // Widget instructions removed - not needed with Firebase implementation
        
        // Health Connect permission guide dialog
        if (showHealthConnectPermissionDialog) {
            HealthConnectPermissionDialog(
                onDismiss = { showHealthConnectPermissionDialog = false },
                onOpenSettings = { showHealthConnectPermissionDialog = false }
            )
        }
        
        // Activity Recognition permission dialog for Google Sleep API
        if (showActivityRecognitionPermissionDialog) {
            ActivityRecognitionPermissionDialog(
                onDismiss = { showActivityRecognitionPermissionDialog = false },
                onRequestPermission = { 
                    showActivityRecognitionPermissionDialog = false
                    // Request permission directly first
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        activityRecognitionPermission?.let { permission ->
                            if (permission.status.shouldShowRationale) {
                                // Permission denied before, open app settings
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                // Request permission normally
                                permission.launchPermissionRequest()
                            }
                        }
                    }
                }
            )
        }
        
        // TODO: Remove after testing - Temporary dialog for testing BedtimeReminderDialog
        if (showTestBedtimeDialog) {
            BedtimeReminderDialog(
                bedTime = LocalTime.of(22, 30), // Test với thời gian cố định
                onDismiss = { showTestBedtimeDialog = false },
                onGoToSleep = { showTestBedtimeDialog = false }
            )
        }
    }
}

/**
 * Dialog to request Activity Recognition permission for Google Sleep API
 */
@Composable
private fun ActivityRecognitionPermissionDialog(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.activity_recognition_required),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        },
        text = {
            Text(
                text = stringResource(R.string.activity_recognition_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.later))
            }
        }
    )
}

@Composable
private fun TopBar(
    userName: String,
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color(0xFF2D2D2D),
                modifier = Modifier.size(22.dp)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF9ECE).copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = userName,
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFFFF9ECE)
                )
            }

            Text(
                text = userName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                color = Color(0xFF2D2D2D)
            )
        }

        IconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Color(0xFF2D2D2D),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}