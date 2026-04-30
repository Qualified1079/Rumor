package com.rumor.mesh

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.service.MeshService
import com.rumor.mesh.ui.contacts.ContactsScreen
import com.rumor.mesh.ui.feed.FeedScreen
import com.rumor.mesh.ui.navigation.Screen
import com.rumor.mesh.ui.navigation.bottomNavItems
import com.rumor.mesh.ui.onboarding.OnboardingScreen
import com.rumor.mesh.ui.settings.SettingsScreen
import com.rumor.mesh.ui.theme.RumorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identityManager: IdentityManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled reactively */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            RumorTheme {
                RumorApp(identityManager = identityManager)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start mesh service if identity is already unlocked
        if (identityManager.isUnlocked) {
            ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
        }
    }

    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
private fun RumorApp(identityManager: IdentityManager) {
    val navController = rememberNavController()
    val identity by identityManager.identity.collectAsState()

    val startDestination = when {
        !identityManager.hasIdentity -> Screen.Onboarding.route
        identity == null             -> Screen.Unlock.route
        else                         -> Screen.Feed.route
    }

    Scaffold(
        bottomBar = {
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            val showBottomBar = currentRoute in bottomNavItems.map { it.screen.route }
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.screen.route,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Onboarding.route) {
                var error by remember { mutableStateOf<String?>(null) }
                OnboardingScreen(
                    isFirstLaunch = true,
                    onPassphraseConfirmed = { passphrase ->
                        identityManager.createIdentity(passphrase)
                        navController.navigate(Screen.Feed.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    },
                    errorMessage = error,
                )
            }

            composable(Screen.Unlock.route) {
                var error by remember { mutableStateOf<String?>(null) }
                OnboardingScreen(
                    isFirstLaunch = false,
                    onPassphraseConfirmed = { passphrase ->
                        val ok = identityManager.unlock(passphrase)
                        if (ok) {
                            navController.navigate(Screen.Feed.route) {
                                popUpTo(Screen.Unlock.route) { inclusive = true }
                            }
                        } else {
                            error = "Wrong passphrase"
                        }
                    },
                    errorMessage = error,
                )
            }

            composable(Screen.Feed.route) { FeedScreen() }
            composable(Screen.Contacts.route) {
                ContactsScreen(
                    onOpenThread = { peerId ->
                        navController.navigate(Screen.Thread.forPeer(peerId))
                    }
                )
            }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(Screen.Messages.route) {
                // Thread list — placeholder; full DM thread list is next iteration
                ContactsScreen(onOpenThread = { peerId ->
                    navController.navigate(Screen.Thread.forPeer(peerId))
                })
            }
        }
    }
}
