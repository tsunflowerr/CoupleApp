package com.example.coupleapp.ui.screens.profile

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.coupleapp.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Notification Settings Screen - Allows user to manage notification preferences
 * Settings are persisted to SharedPreferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE) }
    var visible by remember { mutableStateOf(false) }
    
    // Load notification settings from SharedPreferences
    var pushEnabled by remember { mutableStateOf(prefs.getBoolean("push_enabled", true)) }
    var locketNotifications by remember { mutableStateOf(prefs.getBoolean("locket_notifications", true)) }
    var missingNotifications by remember { mutableStateOf(prefs.getBoolean("missing_notifications", true)) }
    var questNotifications by remember { mutableStateOf(prefs.getBoolean("quest_notifications", true)) }
    var calendarReminders by remember { mutableStateOf(prefs.getBoolean("calendar_reminders", true)) }
    var sleepReminders by remember { mutableStateOf(prefs.getBoolean("sleep_reminders", true)) }
    var gardenNotifications by remember { mutableStateOf(prefs.getBoolean("garden_notifications", true)) }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("sound_enabled", true)) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", true)) }
    
    // Helper function to save preferences
    fun savePreference(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.notifications),
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
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // General section
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(600)) +
                            slideInVertically(animationSpec = tween(600)) { it / 4 }
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.general),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF718096),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Column {
                                NotificationToggleItem(
                                    icon = Icons.Filled.Notifications,
                                    title = stringResource(R.string.push_notifications),
                                    subtitle = stringResource(R.string.push_notifications_desc),
                                    checked = pushEnabled,
                                    onCheckedChange = { 
                                        pushEnabled = it
                                        savePreference("push_enabled", it)
                                    }
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.VolumeUp,
                                    title = stringResource(R.string.sound),
                                    subtitle = stringResource(R.string.sound_desc),
                                    checked = soundEnabled,
                                    onCheckedChange = { 
                                        soundEnabled = it
                                        savePreference("sound_enabled", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.Vibration,
                                    title = stringResource(R.string.vibration),
                                    subtitle = stringResource(R.string.vibration_desc),
                                    checked = vibrationEnabled,
                                    onCheckedChange = { 
                                        vibrationEnabled = it
                                        savePreference("vibration_enabled", it)
                                    },
                                    enabled = pushEnabled
                                )
                            }
                        }
                    }
                }
                
                // Features section
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(600, delayMillis = 100)) +
                            slideInVertically(animationSpec = tween(600, delayMillis = 100)) { it / 4 }
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.features_section),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF718096),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            shadowElevation = 2.dp
                        ) {
                            Column {
                                NotificationToggleItem(
                                    icon = Icons.Filled.Favorite,
                                    title = stringResource(R.string.feature_locket),
                                    subtitle = stringResource(R.string.locket_notification_desc),
                                    checked = locketNotifications,
                                    onCheckedChange = { 
                                        locketNotifications = it
                                        savePreference("locket_notifications", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.FavoriteBorder,
                                    title = stringResource(R.string.feature_missing),
                                    subtitle = stringResource(R.string.missing_notification_desc),
                                    checked = missingNotifications,
                                    onCheckedChange = { 
                                        missingNotifications = it
                                        savePreference("missing_notifications", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.Grass,
                                    title = stringResource(R.string.feature_garden),
                                    subtitle = stringResource(R.string.garden_notification_desc),
                                    checked = gardenNotifications,
                                    onCheckedChange = { 
                                        gardenNotifications = it
                                        savePreference("garden_notifications", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.EmojiEvents,
                                    title = stringResource(R.string.quests),
                                    subtitle = stringResource(R.string.quests_notification_desc),
                                    checked = questNotifications,
                                    onCheckedChange = { 
                                        questNotifications = it
                                        savePreference("quest_notifications", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.CalendarMonth,
                                    title = stringResource(R.string.feature_calendar),
                                    subtitle = stringResource(R.string.calendar_notification_desc),
                                    checked = calendarReminders,
                                    onCheckedChange = { 
                                        calendarReminders = it
                                        savePreference("calendar_reminders", it)
                                    },
                                    enabled = pushEnabled
                                )
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                                NotificationToggleItem(
                                    icon = Icons.Filled.Nightlight,
                                    title = stringResource(R.string.sleep_reminders),
                                    subtitle = stringResource(R.string.sleep_reminders_desc),
                                    checked = sleepReminders,
                                    onCheckedChange = { 
                                        sleepReminders = it
                                        savePreference("sleep_reminders", it)
                                    },
                                    enabled = pushEnabled
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun NotificationToggleItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color(0xFF718096) else Color(0xFFB0B0B0),
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color(0xFF2D3748) else Color(0xFFB0B0B0)
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = if (enabled) Color(0xFF718096) else Color(0xFFD0D0D0)
            )
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFFFF6B9D),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE0E0E0)
            )
        )
    }
}

/**
 * Helper object to check notification settings throughout the app
 */
object NotificationPreferences {
    private const val PREFS_NAME = "notification_prefs"
    
    fun isPushEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("push_enabled", true)
    }
    
    fun isMessageNotificationEnabled(context: Context): Boolean {
        return isPushEnabled(context) // Messages follow the main push setting
    }
    
    fun isLocketNotificationEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("locket_notifications", true)
    }
    
    fun isMissingNotificationEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("missing_notifications", true)
    }
    
    fun isGardenNotificationEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("garden_notifications", true)
    }
    
    fun isQuestNotificationEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("quest_notifications", true)
    }
    
    fun isCalendarReminderEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("calendar_reminders", true)
    }
    
    fun isSleepReminderEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("sleep_reminders", true)
    }
    
    fun isSoundEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("sound_enabled", true)
    }
    
    fun isVibrationEnabled(context: Context): Boolean {
        return isPushEnabled(context) && context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("vibration_enabled", true)
    }
}
