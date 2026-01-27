package com.example.coupleapp.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.coupleapp.ui.screens.HomeScreen
import com.example.coupleapp.ui.screens.PhoneLoginScreen
import com.example.coupleapp.ui.screens.RegisterScreen
import com.example.coupleapp.ui.screens.LoginWithFirebaseScreen
import com.example.coupleapp.ui.screens.RegisterWithFirebaseScreen
import com.example.coupleapp.ui.screens.WelcomeScreen
import com.example.coupleapp.viewmodel.AuthState
import com.example.coupleapp.viewmodel.AuthViewModel
import com.example.coupleapp.viewmodel.QuestViewModelFirebase
import com.example.coupleapp.ui.screens.SleepTrackerScreen
import com.example.coupleapp.ui.screens.SleepCalendarHistoryScreen
import com.example.coupleapp.ui.screens.MissingScreen
import com.example.coupleapp.ui.screens.distance.DistanceScreen
import com.example.coupleapp.ui.screens.distance.SharedPlacesScreen
import com.example.coupleapp.ui.screens.distance.PlacePhotosScreen
import com.example.coupleapp.ui.screens.store.StoreScreen
import com.example.coupleapp.ui.screens.calendar.CalendarScreen
import com.example.coupleapp.ui.screens.garden.GardenScreen
import com.example.coupleapp.ui.screens.partner.PartnerHubScreen
import com.example.coupleapp.ui.screens.partner.LinkPartnerScreen
import com.example.coupleapp.ui.screens.partner.ChatScreen
import com.example.coupleapp.ui.screens.partner.QAScreen
import com.example.coupleapp.ui.screens.profile.ProfileScreen
import com.example.coupleapp.ui.screens.profile.EditProfileScreen
import com.example.coupleapp.ui.screens.profile.ChangePasswordScreen
import com.example.coupleapp.ui.screens.profile.ManageLinkScreen
import com.example.coupleapp.ui.screens.profile.NotificationSettingsScreen
import com.example.coupleapp.ui.screens.profile.LanguageSettingsScreen
import com.example.coupleapp.ui.screens.profile.PermissionsSettingsScreen
import com.example.coupleapp.ui.screens.profile.HelpScreen
import com.example.coupleapp.ui.screens.profile.AboutScreen

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String? = null
) {
    // Shared QuestViewModel across all screens
    val questViewModel: QuestViewModelFirebase = viewModel()
    
    // Shared AuthViewModel for persistent login check
    val sharedAuthViewModel: AuthViewModel = viewModel()
    val authState by sharedAuthViewModel.authState.collectAsState()
    
    // Track if we've checked auth and navigated
    var hasNavigatedFromAuth by remember { mutableStateOf(false) }
    
    // Determine start destination - always start with a splash/loading approach
    // The actual start destination is determined based on what's passed OR defaults to Welcome
    // The LaunchedEffect below will handle navigation once auth is determined
    val actualStartDestination = remember {
        startDestination ?: Screen.Welcome.route
    }
    
    // Check auth status on app launch
    LaunchedEffect(Unit) {
        sharedAuthViewModel.checkAuthStatus()
    }
    
    // Handle auth state changes for navigation
    LaunchedEffect(authState) {
        when (authState) {
            AuthState.Authenticated -> {
                // Only navigate if we haven't already done so and not coming from a specific destination
                if (!hasNavigatedFromAuth) {
                    hasNavigatedFromAuth = true
                    // If there's a specific destination from notification/widget, navigate there
                    // Otherwise go to Home
                    val targetRoute = startDestination ?: Screen.Home.route
                    navController.navigate(targetRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
            AuthState.Unauthenticated -> {
                // User logged out or not authenticated
                if (hasNavigatedFromAuth) {
                    // User was logged in but now logged out - navigate to Welcome
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                    hasNavigatedFromAuth = false
                }
            }
            else -> { /* Initial or Loading - do nothing */ }
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = actualStartDestination
    ) {
        composable(
            route = Screen.Welcome.route,
            enterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300))
            }
        ) {
            // No need for LaunchedEffect here - handled above
            
            WelcomeScreen(
                onGoogleLoginClick = {
                    // Handle Google login
                    // TODO: Implement Google Sign-In
                },
                onPhoneLoginClick = {
                    navController.navigate(Screen.PhoneLogin.route) {
                        launchSingleTop = true
                    }
                },
                onSignUpClick = {
                    navController.navigate(Screen.Register.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        composable(
            route = Screen.PhoneLogin.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            }
        ) {
            LoginWithFirebaseScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onBackClick = {
                    navController.popBackStack()
                },
                onForgotPasswordClick = {
                    // Handle forgot password
                    // TODO: Implement forgot password flow
                }
            )
        }
        
        composable(
            route = Screen.Register.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            }
        ) {
            RegisterWithFirebaseScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                },
                onBackClick = {
                    navController.popBackStack()
                },
                onLoginClick = {
                    navController.popBackStack()
                    navController.navigate(Screen.PhoneLogin.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        composable(
            route = Screen.Home.route,
            enterTransition = {
                fadeIn(animationSpec = tween(600))
            }
        ) {
            HomeScreen(
                onNavigateToFeature = { featureName ->
                    // Handle feature navigation (including slider buttons)
                    when (featureName) {
                        "Store" -> navController.navigate(Screen.Store.route) {
                            launchSingleTop = true
                        }
                        "Calendar" -> navController.navigate(Screen.Calendar.route) {
                            launchSingleTop = true
                        }
                        "Quest" -> navController.navigate(Screen.Quest.route) {
                            launchSingleTop = true
                        }
                        "Garden" -> navController.navigate(Screen.Garden.route) {
                            launchSingleTop = true
                        }
                        // Added for slider navigation
                        "Sleep" -> navController.navigate(Screen.SleepTracker.route) {
                            launchSingleTop = true
                        }
                        "Locket" -> navController.navigate(Screen.Locket.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onNavigateToWidget = { widgetName ->
                    when (widgetName) {
                        "Sleep" -> navController.navigate(Screen.SleepTracker.route) {
                            launchSingleTop = true
                        }
                        "Locket" -> navController.navigate(Screen.Locket.route) {
                            launchSingleTop = true
                        }
                        "Missing" -> navController.navigate(Screen.Missing.route) {
                            launchSingleTop = true
                        }
                        "Location" -> navController.navigate(Screen.Distance.createRoute()) {
                            launchSingleTop = true
                        }
                        "Store" -> navController.navigate(Screen.Store.route) {
                            launchSingleTop = true
                        }
                        "Calendar" -> navController.navigate(Screen.Calendar.route) {
                            launchSingleTop = true
                        }
                        "Quest" -> navController.navigate(Screen.Quest.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Partner Hub Screen
        composable(
            route = Screen.PartnerHub.route,
            enterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300))
            }
        ) {
            PartnerHubScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToLinkPartner = {
                    navController.navigate(Screen.LinkPartner.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToChat = {
                    navController.navigate(Screen.Chat.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToQA = {
                    navController.navigate(Screen.QA.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToShortcut = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.PartnerHub.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.PartnerHub.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Link Partner Screen
        composable(
            route = Screen.LinkPartner.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(350)
                ) + fadeIn(animationSpec = tween(350))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350)
                ) + fadeOut(animationSpec = tween(350))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(350)
                ) + fadeOut(animationSpec = tween(350))
            }
        ) {
            LinkPartnerScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onLinkSuccess = {
                    navController.popBackStack()
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Chat Screen
        composable(
            route = Screen.Chat.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            ChatScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
        
        // Q&A Screen
        composable(
            route = Screen.QA.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            QAScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(
            route = Screen.SleepTracker.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            SleepTrackerScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToHistory = { userId ->
                    navController.navigate(Screen.SleepHistory.createRoute(userId)) {
                        launchSingleTop = true
                    }
                },
                questViewModel = questViewModel
            )
        }
        
        composable(
            route = Screen.SleepHistory.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            SleepCalendarHistoryScreen(
                userId = userId,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(
            route = Screen.WhenToSleep.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            }
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.SleepTracker.route)
            }
            val sleepViewModel: com.example.coupleapp.viewmodel.SleepTrackerViewModelFirebase = 
                androidx.lifecycle.viewmodel.compose.viewModel(parentEntry)
            val uiState by sleepViewModel.uiState.collectAsState()
            
            com.example.coupleapp.ui.screens.sleep.WhenToSleepScreen(
                currentBedTime = uiState.settings?.idealBedTime ?: java.time.LocalTime.of(22, 0),
                onBackClick = {
                    navController.popBackStack()
                },
                onSave = { newTime ->
                    sleepViewModel.updateBedTime(newTime)
                }
            )
        }
        
        composable(
            route = Screen.SleepGoal.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            }
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.SleepTracker.route)
            }
            val sleepViewModel: com.example.coupleapp.viewmodel.SleepTrackerViewModelFirebase = 
                androidx.lifecycle.viewmodel.compose.viewModel(parentEntry)
            val uiState by sleepViewModel.uiState.collectAsState()
            
            com.example.coupleapp.ui.screens.sleep.SleepGoalScreen(
                currentGoalMinutes = uiState.settings?.targetSleepDuration ?: 480,
                onBackClick = {
                    navController.popBackStack()
                },
                onSave = { newGoal ->
                    sleepViewModel.updateSleepGoal(newGoal)
                }
            )
        }
        
        // Locket Screen
        composable(
            route = Screen.Locket.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            com.example.coupleapp.ui.screens.locket.LocketScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToHistory = {
                    navController.navigate(Screen.LocketHistory.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToDrawing = {
                    navController.navigate(Screen.LocketDrawing.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        popUpTo(Screen.Locket.route) { inclusive = true }
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.Locket.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.Locket.route) { inclusive = true }
                    }
                },
                questViewModel = questViewModel
            )
        }
        
        // Locket History Screen
        composable(
            route = Screen.LocketHistory.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            com.example.coupleapp.ui.screens.locket.LocketHistoryScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        popUpTo(Screen.LocketHistory.route) { inclusive = true }
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.LocketHistory.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.LocketHistory.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Locket Drawing Screen
        composable(
            route = Screen.LocketDrawing.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            // Get the parent entry to access LocketScreen's ViewModel
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Screen.Locket.route)
            }
            val locketViewModel: com.example.coupleapp.viewmodel.LocketViewModelFirebase = 
                androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = parentEntry)
            
            com.example.coupleapp.ui.screens.locket.LocketDrawingScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSaveDrawing = { paths, canvasWidth, canvasHeight ->
                    // Convert paths to Bitmap with proper scaling
                    val bitmap = com.example.coupleapp.ui.screens.locket.convertPathsToBitmap(
                        drawingPaths = paths, 
                        canvasWidth = canvasWidth, 
                        canvasHeight = canvasHeight,
                        outputSize = 800
                    )
                    locketViewModel.setDrawingBitmap(bitmap)
                    navController.popBackStack()
                }
            )
        }
        
        // Missing Screen
        composable(
            route = Screen.Missing.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            MissingScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        popUpTo(Screen.Missing.route) { inclusive = true }
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.Missing.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.Missing.route) { inclusive = true }
                    }
                },
                questViewModel = questViewModel
            )
        }
        
        // Distance Screen
        composable(
            route = Screen.Distance.route,
            arguments = listOf(
                navArgument("targetPlaceId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) { backStackEntry ->
            val targetPlaceId = backStackEntry.arguments?.getString("targetPlaceId")
            DistanceScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToSharedPlaces = {
                    navController.navigate(Screen.SharedPlaces.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPlacePhotos = { placeId ->
                    navController.navigate(Screen.PlacePhotos.createRoute(placeId)) {
                        launchSingleTop = true
                    }
                },
                targetPlaceId = targetPlaceId,
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        popUpTo(Screen.Distance.route) { inclusive = true }
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        popUpTo(Screen.Distance.route) { inclusive = true }
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        popUpTo(Screen.Distance.route) { inclusive = true }
                    }
                },
                questViewModel = questViewModel
            )
        }
        
        // Shared Places Screen
        composable(
            route = Screen.SharedPlaces.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            SharedPlacesScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onPlaceClick = { placeId ->
                    navController.navigate(Screen.PlacePhotos.createRoute(placeId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMapWithPlace = { placeId ->
                    // Pop back to Distance screen and pass the place ID to animate to
                    navController.navigate(Screen.Distance.createRoute(placeId)) {
                        popUpTo(Screen.Distance.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Place Photos Screen
        composable(
            route = Screen.PlacePhotos.route,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) { backStackEntry ->
            val placeId = backStackEntry.arguments?.getString("placeId") ?: ""
            PlacePhotosScreen(
                placeId = placeId,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
        
        // Store Screen
        composable(
            route = Screen.Store.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            StoreScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                questViewModel = questViewModel
            )
        }
        
        // Calendar Screen
        composable(
            route = Screen.Calendar.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            CalendarScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        launchSingleTop = true
                    }
                },
                questViewModel = questViewModel
            )
        }
        
        // Quest Screen
        composable(
            route = Screen.Quest.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            com.example.coupleapp.ui.screens.quest.QuestScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLocket = {
                    navController.navigate(Screen.Locket.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMissing = {
                    navController.navigate(Screen.Missing.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToStore = {
                    navController.navigate(Screen.Store.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToSleep = {
                    navController.navigate(Screen.SleepTracker.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerLink = {
                    navController.navigate(Screen.LinkPartner.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Garden Screen
        composable(
            route = Screen.Garden.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            GardenScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateToStore = {
                    navController.navigate(Screen.Store.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Moments Screen
        composable(
            route = Screen.Moments.route,
            enterTransition = {
                fadeIn(animationSpec = tween(600))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(600))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(300))
            }
        ) {
            com.example.coupleapp.ui.screens.moments.MomentsScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToFriends = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
        
        // Profile Screen
        composable(
            route = Screen.Profile.route,
            enterTransition = {
                fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(300))
            }
        ) {
            ProfileScreen(
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToPartnerHub = {
                    navController.navigate(Screen.PartnerHub.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToMoments = {
                    navController.navigate(Screen.Moments.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToEditProfile = {
                    navController.navigate(Screen.EditProfile.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToChangePassword = {
                    navController.navigate(Screen.ChangePassword.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToManageLink = {
                    navController.navigate(Screen.ManageLink.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToNotifications = {
                    navController.navigate(Screen.NotificationSettings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToLanguage = {
                    navController.navigate(Screen.LanguageSettings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToPermissions = {
                    navController.navigate(Screen.PermissionsSettings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToHelp = {
                    navController.navigate(Screen.Help.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route) {
                        launchSingleTop = true
                    }
                },
                onLogout = {
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        
        // Edit Profile Screen
        composable(
            route = Screen.EditProfile.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            EditProfileScreen(
                onBackClick = { navController.popBackStack() },
                onSaveClick = { navController.popBackStack() }
            )
        }
        
        // Change Password Screen
        composable(
            route = Screen.ChangePassword.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            ChangePasswordScreen(
                onBackClick = { navController.popBackStack() },
                onSaveClick = { navController.popBackStack() }
            )
        }
        
        // Manage Link Screen
        composable(
            route = Screen.ManageLink.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            ManageLinkScreen(
                onBackClick = { navController.popBackStack() },
                onUnlink = { navController.popBackStack() }
            )
        }
        
        // Notification Settings Screen
        composable(
            route = Screen.NotificationSettings.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            NotificationSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // Language Settings Screen
        composable(
            route = Screen.LanguageSettings.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            LanguageSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // Help Screen
        composable(
            route = Screen.Help.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            HelpScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // About Screen
        composable(
            route = Screen.About.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            AboutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        
        // Permissions Settings Screen
        composable(
            route = Screen.PermissionsSettings.route,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it },
                    animationSpec = tween(250)
                ) + fadeIn(animationSpec = tween(250))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250)
                ) + fadeOut(animationSpec = tween(250))
            }
        ) {
            PermissionsSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
