package io.candy.tick.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.candy.tick.di.AppContainer
import io.candy.tick.ui.add.AddScreen
import io.candy.tick.ui.detail.DetailScreen
import io.candy.tick.ui.list.ListScreen
import io.candy.tick.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object RouteHome : NavKey

@Serializable
data object RouteAdd : NavKey

@Serializable
data object RouteSettings : NavKey

@Serializable
data class RouteDetail(val repoId: Long) : NavKey

@Composable
fun AppNavHost(container: AppContainer) {
    val backStack = rememberNavBackStack(RouteHome)
    fun close(key: NavKey) {
        backStack.remove(key)
    }
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeAt(backStack.lastIndex) },
        entryProvider = { key: NavKey ->
            NavEntry(key) { k ->
                when (k) {
                    RouteHome -> ListScreen(
                        onAdd = { backStack.add(RouteAdd) },
                        onOpenDetail = { backStack.add(RouteDetail(it)) },
                        onOpenSettings = { backStack.add(RouteSettings) },
                    )
                    RouteAdd -> AddScreen(
                        onClose = { close(k) },
                        onAdded = { id ->
                            close(k)
                            backStack.add(RouteDetail(id))
                        },
                    )
                    RouteSettings -> SettingsScreen(
                        onBack = { close(k) },
                    )
                    is RouteDetail -> DetailScreen(
                        repoId = k.repoId,
                        onBack = { close(k) },
                    )
                }
            }
        },
    )
}
