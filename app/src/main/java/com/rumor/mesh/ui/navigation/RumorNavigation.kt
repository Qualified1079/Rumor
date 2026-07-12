package com.rumor.mesh.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    object Onboarding    : Screen("onboarding")
    object Unlock        : Screen("unlock")
    object Feed          : Screen("feed")
    object Messages      : Screen("messages")
    object Thread        : Screen("thread/{peerId}") {
        fun forPeer(peerId: String) = "thread/$peerId"
    }
    object Contacts      : Screen("contacts")
    object Settings      : Screen("settings")
    object Logs          : Screen("logs")
    object Plugins       : Screen("settings/plugins")
    object InboxPolicy   : Screen("settings/inbox")
    object Blocks        : Screen("settings/blocks")
    object ChangePassphrase : Screen("settings/change_passphrase")
    object Transfers     : Screen("transfers")
    object DebugMetrics  : Screen("settings/metrics")
}

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String,
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Feed,     Icons.Default.Campaign,    "Feed"),
    BottomNavItem(Screen.Messages, Icons.Default.ChatBubble,  "Messages"),
    BottomNavItem(Screen.Contacts, Icons.Default.People,      "Contacts"),
    BottomNavItem(Screen.Settings, Icons.Default.Settings,    "Settings"),
)
