package ngo.xnet.aiope.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Chat route carries a `new` flag that decides whether opening the chat screen starts a fresh
 * conversation or restores the previous one. A typo in either half (the pattern with the `{new}`
 * placeholder, or the concrete route built by [AiopeScreens.Chat.createRoute]) silently breaks
 * "New Chat" — the argument stops binding and the screen falls back to the default. These tests
 * pin both halves together.
 */
class AiopeScreensTest {
  @Test
  fun chatRoutePatternDeclaresTheNewArgument() {
    assertEquals("chat?new={new}", AiopeScreens.Chat.route)
  }

  @Test
  fun createRouteMatchesThePatternItIsNavigatedAgainst() {
    val pattern = AiopeScreens.Chat.route
    // Turn the nav pattern into a matcher by escaping the literal segments around the {new}
    // placeholder — escaping the whole string first would emit \Q...\E and swallow the placeholder.
    val regex = Regex(
      "^" + pattern.split("{new}").joinToString("(true|false)") { Regex.escape(it) } + "$",
    )
    assertTrue(
      "createRoute(true)=${AiopeScreens.Chat.createRoute(true)} does not match $pattern",
      regex.matches(AiopeScreens.Chat.createRoute(new = true)),
    )
    assertTrue(
      "createRoute(false)=${AiopeScreens.Chat.createRoute(false)} does not match $pattern",
      regex.matches(AiopeScreens.Chat.createRoute(new = false)),
    )
  }

  @Test
  fun createRouteEncodesTheRequestedValue() {
    assertEquals("chat?new=true", AiopeScreens.Chat.createRoute(new = true))
    assertEquals("chat?new=false", AiopeScreens.Chat.createRoute(new = false))
    assertEquals("chat?new=false", AiopeScreens.Chat.createRoute())
  }

  @Test
  fun routesAreDistinct() {
    val routes = listOf(AiopeScreens.Home.route, AiopeScreens.Chat.route, AiopeScreens.Settings.route)
    assertEquals(routes.size, routes.toSet().size)
  }
}
