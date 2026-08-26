package ngo.xnet.aiope.core.navigation

import androidx.navigation.NavOptionsBuilder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiopeComposeNavigator @Inject constructor() : AppComposeNavigator() {
  override fun navigate(route: String, optionsBuilder: (NavOptionsBuilder.() -> Unit)?) {
    val options = optionsBuilder?.let { androidx.navigation.navOptions(it) }
    navigationCommands.tryEmit(ComposeNavigationCommand.NavigateToRoute(route, options))
  }

  override fun <T> navigateBackWithResult(key: String, result: T, route: String?) {
    navigationCommands.tryEmit(ComposeNavigationCommand.NavigateUpWithResult(key, result, route))
  }

  override fun popUpTo(route: String, inclusive: Boolean) {
    navigationCommands.tryEmit(ComposeNavigationCommand.PopUpToRoute(route, inclusive))
  }

  override fun navigateAndClearBackStack(route: String) {
    navigationCommands.tryEmit(
      ComposeNavigationCommand.NavigateToRoute(
        route,
        androidx.navigation.navOptions {
          popUpTo(0)
        },
      ),
    )
  }
}
