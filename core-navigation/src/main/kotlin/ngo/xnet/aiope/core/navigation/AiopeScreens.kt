package ngo.xnet.aiope.core.navigation

sealed class AiopeScreens(val route: String) {
  data object Chat : AiopeScreens("chat")
  data object Settings : AiopeScreens("settings")
}
