package ngo.xnet.aiope.feature.chat.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * create_event and set_alarm feed model-written time strings through [AgentTimeParsing]. The old
 * inline parser mapped a time-only string to 1970-01-01 (SimpleDateFormat defaults the date fields
 * to the epoch), so an alarm "at 2 PM" silently landed 56 years in the past.
 */
class AgentTimeParsingTest {

  private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
    Calendar.getInstance().apply {
      set(y, mo - 1, d, h, mi, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

  private fun fmt(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(millis)

  @Test
  fun parsesIsoDateTime() {
    assertEquals("2026-08-26 10:00", fmt(AgentTimeParsing.parse("2026-08-26T10:00")!!))
    assertEquals("2026-08-26 10:00", fmt(AgentTimeParsing.parse("2026-08-26 10:00")!!))
  }

  @Test
  fun timeOnlyResolvesToTodayNotTheEpoch() {
    val now = at(2026, 8, 25, 9, 0)
    val parsed = AgentTimeParsing.parse("2:00 PM", now)
    assertNotNull(parsed)
    assertEquals("2026-08-25 14:00", fmt(parsed!!))
  }

  @Test
  fun timeOnlyAlreadyPastRollsToTomorrow() {
    val now = at(2026, 8, 25, 20, 0)
    assertEquals("2026-08-26 14:00", fmt(AgentTimeParsing.parse("2:00 PM", now)!!))
  }

  @Test
  fun twentyFourHourTimeOnlyIsAccepted() {
    val now = at(2026, 8, 25, 6, 0)
    assertEquals("2026-08-25 18:30", fmt(AgentTimeParsing.parse("18:30", now)!!))
  }

  @Test
  fun neverReturnsAnInstantBeforeTheCurrentYear() {
    val now = at(2026, 8, 25, 9, 0)
    for (input in listOf("2:00 PM", "10:00", "6:30 AM", "23:59")) {
      val parsed = AgentTimeParsing.parse(input, now)
      assertNotNull("$input should parse", parsed)
      assertTrue("$input resolved to ${fmt(parsed!!)}, before now", parsed >= now)
    }
  }

  @Test
  fun epochMillisPassThrough() {
    val millis = at(2026, 8, 26, 10, 0)
    assertEquals(millis, AgentTimeParsing.parse(millis.toString()))
  }

  @Test
  fun garbageReturnsNullRatherThanAWrongInstant() {
    assertNull(AgentTimeParsing.parse("tomorrow-ish"))
    assertNull(AgentTimeParsing.parse(""))
  }

  @Test
  fun parseOrDefaultFallsBackAnHourAhead() {
    val now = at(2026, 8, 25, 9, 0)
    assertEquals(now + 3_600_000L, AgentTimeParsing.parseOrDefault("not a time", now))
  }
}
