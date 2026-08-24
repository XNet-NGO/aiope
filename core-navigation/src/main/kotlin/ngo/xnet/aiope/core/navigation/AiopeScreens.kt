package ngo.xnet.aiope.core.navigation

sealed class AiopeScreens(val route: String) {
  data object Home : AiopeScreens("home")
  data object Chat : AiopeScreens("chat")
  data object Settings : AiopeScreens("settings")
}
