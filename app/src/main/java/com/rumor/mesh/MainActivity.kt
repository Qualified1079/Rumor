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
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rumor.mesh.core.identity.IdentityManager
import com.rumor.mesh.service.MeshControllerHolder
import com.rumor.mesh.service.MeshService
import com.rumor.mesh.ui.blocks.BlockManagementScreen
import com.rumor.mesh.ui.contacts.ContactsScreen
import com.rumor.mesh.ui.debug.DebugMetricsScreen
import com.rumor.mesh.ui.feed.FeedScreen
import com.rumor.mesh.ui.inbox.InboxPolicyScreen
import com.rumor.mesh.ui.logs.LogsScreen
import com.rumor.mesh.ui.messages.MessagesScreen
import com.rumor.mesh.ui.messages.ThreadScreen
import com.rumor.mesh.ui.navigation.Screen
import com.rumor.mesh.ui.navigation.bottomNavItems
import com.rumor.mesh.ui.onboarding.OnboardingScreen
import com.rumor.mesh.ui.plugins.PluginsScreen
import com.rumor.mesh.ui.settings.ChangePassphraseScreen
import com.rumor.mesh.ui.settings.SettingsScreen
import com.rumor.mesh.ui.transfers.TransfersScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.rumor.mesh.ui.theme.RumorTheme
import org.koin.android.ext.android.inject
import kotlinx.coroutines.launch

/**
 * The only Activity in the app. Owns the Compose nav host and the binding to
 * [MeshService] (the foreground service that actually runs the mesh).
 *
 * Lifecycle wiring
 * ----------------
 * - **onCreate**: requests runtime permissions, eagerly resolves the Koin
 *   singletons it needs so a misconfiguration shows a friendly error screen
 *   instead of an opaque composition-time crash, then sets the Compose root.
 * - **onStart**: if identity is already unlocked, starts + binds [MeshService]
 *   as a foreground service. The bind installs the live `MeshController` into
 *   [MeshControllerHolder] for ViewModels to read.
 * - **onStop**: unbinds and clears the holder so a backgrounded UI doesn't
 *   hold a stale controller reference.
 *
 * Identity is unlocked separately via the onboarding flow; the service is
 * deliberately *not* started until then so a locked-identity launch doesn't
 * spin up the radio.
 */
class MainActivity : ComponentActivity() {

    private val identityManager: IdentityManager by inject()
    private val meshControllerHolder: MeshControllerHolder by inject()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            (binder as? MeshService.LocalBinder)?.let {
                meshControllerHolder.set(it.getController())
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            meshControllerHolder.clear()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled reactively */ }

    private var isBound = false

    private fun bindMeshService() {
        if (isBound) return
        val intent = Intent(this, MeshService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        // O138 — DEBUG-ONLY hands-free unlock for the test fleet. A reflashed
        // debug build auto-unlocks the existing identity so multi-device
        // testing never needs a manual on-screen unlock. The identity collector
        // below then binds the mesh automatically, and the lock screen is
        // skipped because identity is already non-null at composition.
        // HARD-GATED on BuildConfig.DEBUG — this code path cannot exist in a
        // release build. The passphrase is the fleet dev passphrase only.
        if (BuildConfig.DEBUG && identityManager.hasIdentity && !identityManager.isUnlocked) {
            val ok = identityManager.unlock(DEBUG_AUTO_UNLOCK_PASSPHRASE)
            com.rumor.mesh.core.logging.RumorLog.i(
                "MainActivity", "DEBUG auto-unlock ${if (ok) "succeeded" else "FAILED (wrong dev passphrase?)"}",
            )
        }

        // Identity may still be locked at onStart() (first-run onboarding); react to
        // unlock happening later in the same activity instance rather than only
        // checking isUnlocked once, or the service never gets bound this session.
        lifecycleScope.launch {
            identityManager.identity.collect { identity ->
                if (identity != null) bindMeshService()
            }
        }

        // Eagerly resolve injected singletons so a Koin misconfiguration surfaces
        // here with a clear error UI rather than an opaque crash inside composition.
        val startupError = runCatching { identityManager; meshControllerHolder }.exceptionOrNull()

        setContent {
            RumorTheme {
                if (startupError != null) {
                    Box(
                        Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Startup failed — please reinstall the app.\n\n" +
                                (startupError.message ?: startupError::class.simpleName),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    RumorApp(identityManager = identityManager)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Start mesh service if identity is already unlocked, then bind so the
        // controller holder gets a live handle for ViewModels to call into.
        if (identityManager.isUnlocked) bindMeshService()
    }

    override fun onStop() {
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound = false
        }
        meshControllerHolder.clear()
        super.onStop()
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

    private companion object {
        /** O138: fleet dev passphrase, used ONLY by the BuildConfig.DEBUG auto-unlock. */
        const val DEBUG_AUTO_UNLOCK_PASSPHRASE = "passphrase1"
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
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenPlugins      = { navController.navigate(Screen.Plugins.route) },
                    onOpenInboxPolicy  = { navController.navigate(Screen.InboxPolicy.route) },
                    onOpenBlocks       = { navController.navigate(Screen.Blocks.route) },
                    onOpenTransfers    = { navController.navigate(Screen.Transfers.route) },
                    onOpenLogs         = { navController.navigate(Screen.Logs.route) },
                    onOpenMetrics      = { navController.navigate(Screen.DebugMetrics.route) },
                    onOpenChangePassphrase = { navController.navigate(Screen.ChangePassphrase.route) },
                )
            }
            composable(Screen.Messages.route) {
                MessagesScreen(onOpenThread = { peerId ->
                    navController.navigate(Screen.Thread.forPeer(peerId))
                })
            }
            composable(
                route = Screen.Thread.route,
                arguments = listOf(navArgument("peerId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
                ThreadScreen(
                    peerId = peerId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Screen.DebugMetrics.route) {
                DebugMetricsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Plugins.route) { PluginsScreen() }
            composable(Screen.InboxPolicy.route) { InboxPolicyScreen() }
            composable(Screen.ChangePassphrase.route) {
                ChangePassphraseScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Blocks.route) { BlockManagementScreen() }
            composable(Screen.Transfers.route) { TransfersScreen() }
            composable(Screen.Logs.route) { LogsScreen() }
        }
    }
}
