package ngo.xnet.aiope.feature.chat.engine

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Parsing of the loose date/time strings a model produces for tools like create_event and set_alarm.
 *
 * Pure JVM (no Android APIs) so it is unit-testable — the previous inline version lived in
 * [ToolExecutor] and silently mapped a time-only input like "2:00 PM" to 1970-01-01, because
 * SimpleDateFormat defaults the missing date fields to the epoch. Every accepted format is covered
 * by AgentTimeParsingTest.
 */
internal object AgentTimeParsing {

  /** Formats that carry a full date, so the parsed instant can be used as-is. */
  private val DATED_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy-MM-dd",
    "MM/dd/yyyy HH:mm",
    "MM/dd/yyyy",
    "MMM d yyyy h:mm a",
    "MMM d yyyy",
    "d MMM yyyy HH:mm",
  )

  /** Formats that carry only a wall-clock time; the date has to come from [now]. */
  private val TIME_ONLY_FORMATS = listOf("h:mm a", "h a", "HH:mm:ss", "HH:mm")

  /**
   * Parse [s] into epoch millis, or null when nothing matches.
   *
   * Time-only inputs resolve to the next occurrence of that wall-clock time relative to [now]
   * (today if still ahead, otherwise tomorrow) — never 1970. Bare digits are treated as epoch
   * millis so a value round-tripped out of another tool still works.
   */
  fun parse(s: String, now: Long = System.currentTimeMillis()): Long? {
    val text = s.trim()
    if (text.isEmpty()) return null

    text.toLongOrNull()?.let { return if (it > 100_000_000_000L) it else it * 1000L }

    for (fmt in DATED_FORMATS) {
      runCatching { SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }.parse(text)?.time }
        .getOrNull()?.let { return it }
    }

    for (fmt in TIME_ONLY_FORMATS) {
      val parsed = runCatching {
        SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }.parse(text)
      }.getOrNull() ?: continue
      // Read back the wall-clock fields and graft them onto today's date.
      val t = Calendar.getInstance().apply { time = parsed }
      val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, t.get(Calendar.MINUTE))
        set(Calendar.SECOND, t.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, 0)
      }
      if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
      return cal.timeInMillis
    }

    return null
  }

  /** [parse] with a one-hour-from-now fallback, for callers that must produce some instant. */
  fun parseOrDefault(s: String, now: Long = System.currentTimeMillis()): Long = parse(s, now) ?: (now + 3_600_000L)
}
