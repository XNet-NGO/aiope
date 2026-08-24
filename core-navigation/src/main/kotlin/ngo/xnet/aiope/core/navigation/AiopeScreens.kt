package ngo.xnet.aiope.core.navigation

sealed class AiopeScreens(val route: String) {
  data object Home : AiopeScreens("home")

  /**
   * Chat screen. The optional `new` argument tells the screen to start a fresh conversation
   * instead of restoring the last one — Home's "New Chat" action passes true, everything else
   * (e.g. returning to an in-progress chat) leaves it false.
   */
  data object Chat : AiopeScreens("chat?new={new}") {
    fun createRoute(new: Boolean = false) = "chat?new=$new"
  }

  data object Settings : AiopeScreens("settings")
}
