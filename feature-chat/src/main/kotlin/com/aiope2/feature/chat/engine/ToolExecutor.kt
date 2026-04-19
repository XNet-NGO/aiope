package com.aiope2.feature.chat.engine

import android.app.Application
import com.aiope2.core.network.ModelTask
import com.aiope2.core.network.ProviderProfile
import com.aiope2.core.network.TaskModelStore
import com.aiope2.feature.chat.LocationData
import com.aiope2.feature.chat.db.ChatDao
import com.aiope2.feature.chat.location.LocationProvider
import com.aiope2.feature.chat.settings.McpManager
import com.aiope2.feature.chat.settings.ProviderStore
import com.aiope2.feature.chat.settings.ToolStore

class ToolExecutor(
  private val app: Application,
  private val providerStore: ProviderStore,
  private val toolStore: ToolStore,
  private val chatDao: ChatDao,
  val mcpManager: McpManager,
  val locationProvider: LocationProvider,
  private val getBrowser: () -> com.aiope2.feature.chat.browser.WebBrowser,
  private val onBrowserVisible: (Boolean) -> Unit,
  private val onBrowserMaximized: (Boolean) -> Unit,
  private val resolveTaskModel: (ModelTask) -> Pair<ProviderProfile, String>,
  private val getAgentMode: () -> AgentMode = { AgentMode.CHAT },
  var subagentManager: SubagentManager? = null,
) {
  var lastLocationData: LocationData? = null
  var locationUsedThisTurn = false
  private var cachedDataCategories: String? = null
  var shellOutputLimit = 4000
  var fetchLimit = 12000
  var fileReadLimit = 50000

  fun buildToolDefs() = listOf(
    td("run_sh", "Execute Android shell command", """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""),
    td("run_proot", "Execute a command in the Alpine Linux proot environment. Use for apk, python, gcc, etc.", """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""),
    td("read_file", "Read file contents", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
    td("write_file", "Write file", """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""),
    td("list_directory", "List directory", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
    td("get_location", "Get the device's current GPS location. Call this FIRST when the user asks about nearby places or 'closest' anything, then use the coordinates with search_location.", """{"type":"object","properties":{}}"""),
    td("open_intent", "Open a URL, app, or navigation intent from the device. Use for opening maps, web pages, dialing, etc. Examples: 'https://google.com', 'geo:43.6,-116.3', 'google.navigation:q=123+Main+St', 'tel:5551234567'", """{"type":"object","properties":{"uri":{"type":"string","description":"URI to open. Supports https://, geo:, google.navigation:q=, tel:, mailto:, etc."}},"required":["uri"]}"""),
    td("fetch_url", "Fetch a URL. Returns extracted text and any images found as ![alt](url) markdown. Include these ![alt](url) images directly in your response to display them to the user.", """{"type":"object","properties":{"url":{"type":"string","description":"URL to fetch"},"mode":{"type":"string","description":"Optional: 'raw' for raw response, 'text' (default) for extracted text+images from HTML"}},"required":["url"]}"""),
    td("query_data", "Query live real-time data. Returns JSON and any images as ![alt](url) markdown. Include these ![alt](url) images directly in your response to display them. Automatically uses device GPS for location-based queries. Pass 'extra' for searches (nasa_media, nasa_tech) or station IDs (tides, ocean_temp) or breed IDs (cat_breed). Available categories: ${fetchDataCategories()}", """{"type":"object","properties":{"category":{"type":"string","description":"Data category"},"extra":{"type":"string","description":"Optional: search query, station ID, or breed ID depending on category"}},"required":["category"]}"""),
    td("search_location", "Search for any place, address, landmark, or business/amenity. For nearby searches ('closest pizza'), call get_location first to establish position. Handles addresses, landmarks, cities, and business/amenity searches (restaurants, cafes, gas stations, etc).", """{"type":"object","properties":{"query":{"type":"string","description":"What to search for. Examples: '1600 Pennsylvania Ave, Washington DC', 'Eiffel Tower', 'pizza in Boise, ID', 'Starbucks near Meridian, Idaho', 'gas station'"}},"required":["query"]}"""),
    td("search_web", "Search the web for current information, news, answers, or any topic. Returns results with titles, URLs, and snippets. Use when the user asks about recent events, facts you're unsure of, or anything requiring up-to-date information.", """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""),
    td("search_images", "Search for images on the web. Returns image URLs with titles. Use when the user asks to find photos, pictures, or images of something.", """{"type":"object","properties":{"query":{"type":"string","description":"Image search query"}},"required":["query"]}"""),
    td("browser_navigate", "Navigate the in-app browser to a URL. Opens a real WebView you can then interact with via browser_click, browser_fill, browser_eval, browser_content, browser_elements.", """{"type":"object","properties":{"url":{"type":"string","description":"URL to navigate to"}},"required":["url"]}"""),
    td("browser_content", "Get the current page text content, URL, and title from the in-app browser.", """{"type":"object","properties":{}}"""),
    td("browser_elements", "List all interactive elements (links, buttons, inputs) on the current browser page with their selectors.", """{"type":"object","properties":{}}"""),
    td("browser_click", "Click an element in the browser by CSS selector. IMPORTANT: Call browser_elements first to discover available selectors before clicking.", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS selector of element to click"}},"required":["selector"]}"""),
    td("browser_fill", "Fill an input field in the browser by CSS selector. IMPORTANT: Call browser_elements first to discover available selectors before filling.", """{"type":"object","properties":{"selector":{"type":"string","description":"CSS selector of input element"},"value":{"type":"string","description":"Text to fill"}},"required":["selector","value"]}"""),
    td("browser_eval", "Execute JavaScript in the browser and return the result.", """{"type":"object","properties":{"script":{"type":"string","description":"JavaScript code to evaluate"}},"required":["script"]}"""),
    td("browser_back", "Go back in the browser history.", """{"type":"object","properties":{}}"""),
    td("browser_scroll", "Scroll the browser page up or down.", """{"type":"object","properties":{"direction":{"type":"string","description":"'up' or 'down'"},"amount":{"type":"integer","description":"Pixels to scroll (default 500)"}},"required":["direction"]}"""),
    td("browser_open", "Open the browser panel so the user can see it.", """{"type":"object","properties":{}}"""),
    td("browser_close", "Close the browser panel.", """{"type":"object","properties":{}}"""),
    td("browser_maximize", "Maximize or restore the browser panel. Pass maximize=true for fullscreen, false to restore split view.", """{"type":"object","properties":{"maximize":{"type":"boolean","description":"true to maximize, false to restore"}},"required":["maximize"]}"""),
    td("memory_store", "Store a fact or preference the user wants you to remember across conversations. Use a short descriptive key.", """{"type":"object","properties":{"key":{"type":"string","description":"Short key like 'user_name' or 'preferred_language'"},"content":{"type":"string","description":"The fact to remember"},"category":{"type":"string","description":"Optional: general, preference, learning, error"}},"required":["key","content"]}"""),
    td("memory_recall", "Search your persistent memory for stored facts. Call with empty query to list all memories.", """{"type":"object","properties":{"query":{"type":"string","description":"Search term, or empty to list all"}},"required":["query"]}"""),
    td("memory_forget", "Delete a specific memory by its key.", """{"type":"object","properties":{"key":{"type":"string","description":"Key of the memory to delete"}},"required":["key"]}"""),
    td("image_generate", "Generate an image from a text prompt. Use when the user asks you to draw, create, generate, or make an image/picture/illustration.", """{"type":"object","properties":{"prompt":{"type":"string","description":"Detailed image generation prompt"}},"required":["prompt"]}"""),
    td("analyze_image", "Analyze an image from a URL using vision. Use for browser screenshots, fetched images, or any image URL the user wants described.", """{"type":"object","properties":{"url":{"type":"string","description":"URL of the image to analyze"},"question":{"type":"string","description":"What to look for or ask about the image"}},"required":["url"]}"""),
    td("read_calendar", "Read upcoming calendar events from the device.", """{"type":"object","properties":{"days":{"type":"integer","description":"Number of days ahead to look (default 7)"}},"required":[]}"""),
    td("create_event", "Create a calendar event. Opens the calendar app with pre-filled details.", """{"type":"object","properties":{"title":{"type":"string"},"start_time":{"type":"string","description":"Start time, e.g. '2025-04-20T14:00' or '2:00 PM'"},"end_time":{"type":"string","description":"End time"},"location":{"type":"string"},"description":{"type":"string"}},"required":["title"]}"""),
    td("delete_event", "Delete a calendar event by its ID (from read_calendar).", """{"type":"object","properties":{"event_id":{"type":"integer","description":"Event ID from read_calendar"}},"required":["event_id"]}"""),
    td("set_alarm", "Set an alarm on the device.", """{"type":"object","properties":{"hour":{"type":"integer","description":"Hour (0-23)"},"minutes":{"type":"integer","description":"Minutes (0-59)"},"message":{"type":"string","description":"Alarm label"},"skip_ui":{"type":"boolean","description":"Set silently without opening clock app"}},"required":["hour","minutes"]}"""),
    td("dismiss_alarm", "Dismiss/cancel an alarm by its label.", """{"type":"object","properties":{"message":{"type":"string","description":"Alarm label to dismiss"}},"required":["message"]}"""),
    td("read_contacts", "Search or list contacts from the device.", """{"type":"object","properties":{"query":{"type":"string","description":"Name to search for, or empty to list all"}},"required":[]}"""),
    td("send_notification", "Post a notification on the device. Use for reminders.", """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string","description":"Notification text"}},"required":["body"]}"""),
    td("clipboard_copy", "Copy text to the device clipboard.", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
    td("clipboard_read", "Read the current clipboard contents.", """{"type":"object","properties":{}}"""),
    td("read_sms", "Read recent SMS messages from the device.", """{"type":"object","properties":{"limit":{"type":"integer","description":"Number of messages to read (default 10)"}},"required":[]}"""),
    td("send_sms", "Send an SMS text message.", """{"type":"object","properties":{"to":{"type":"string","description":"Phone number"},"body":{"type":"string","description":"Message text"}},"required":["to","body"]}"""),
    td("delete_sms", "Delete an SMS message by its ID (from read_sms).", """{"type":"object","properties":{"sms_id":{"type":"integer","description":"SMS ID from read_sms"}},"required":["sms_id"]}"""),
    td("device_info", "Get device info: battery, storage, RAM, network, model.", """{"type":"object","properties":{}}"""),
    td("media_control", "Control media playback (play/pause, next, previous, stop).", """{"type":"object","properties":{"action":{"type":"string","description":"One of: play_pause, next, previous, stop"}},"required":["action"]}"""),
    td("task", "Spawn an async subagent to research or work on a task in the background. Use for parallel research, exploration, or any work that can run independently. Returns a task_id you can check later. The subagent has read-only access (search, fetch, read files).", """{"type":"object","properties":{"description":{"type":"string","description":"Short 3-5 word description"},"prompt":{"type":"string","description":"Detailed instructions for the subagent"}},"required":["description","prompt"]}"""),
  ).filter { toolStore.isToolEnabled(it.name) && it.name !in getAgentMode().disabledTools } + toolStore.getMcpServers().filter { it.enabled }.flatMap { server ->
    var defs = mcpManager.getToolDefs(server.id)
    if (defs.isEmpty()) {
      // Auto-discover on first use (cache is in-memory, lost on restart)
      try {
        mcpManager.discoverTools(server)
      } catch (_: Exception) {}
      defs = mcpManager.getToolDefs(server.id)
    }
    defs
  }.filter { toolStore.isToolEnabled(it.name) }

  private fun td(name: String, desc: String, params: String) = StreamingOrchestrator.ToolDef(name, desc, org.json.JSONObject(params))

  private fun fetchDataCategories(): String {
    cachedDataCategories?.let { return it }
    return try {
      val p = providerStore.getActive()
      val gwBase = p.effectiveApiBase().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/v1")
      val conn = java.net.URL("$gwBase/v1/data").openConnection() as java.net.HttpURLConnection
      if (p.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${p.apiKey}")
      conn.connectTimeout = 5_000
      conn.readTimeout = 5_000
      val body = conn.inputStream.bufferedReader().readText()
      conn.disconnect()
      val cats = org.json.JSONObject(body).getJSONArray("categories")
      val list = (0 until cats.length()).map { cats.getString(it) }.filter { it != "search_web" && it != "image_search" }.joinToString(", ")
      cachedDataCategories = list
      list
    } catch (_: Exception) {
      "air_quality, alerts, apod, asteroids, astronauts, cat, cat_breed, cat_breeds, cme, earth_events, earth_image, earthquakes, earthquakes_significant, epic, fires, geomagnetic, impact_risk, ip_location, iss, nasa_media, nasa_tech, ocean_temp, solar, solar_flares, sunrise_sunset, tides, time, uv, weather, weather_hourly"
    }
  }

  fun execute(name: String, args: Map<String, Any?>): String {
    if (!toolStore.isToolEnabled(name)) return "Tool '$name' is disabled."
    return when (name) {
      "run_sh" -> com.aiope2.core.terminal.shell.ShellExecutor.exec(args["command"]?.toString() ?: "").let { if (it.length > shellOutputLimit) it.take(shellOutputLimit) + "\n...(truncated)" else it }

      "run_proot" -> if (!com.aiope2.core.terminal.shell.ProotBootstrap.isInstalled(app)) {
        "Alpine not installed. Set up proot in Settings first."
      } else {
        com.aiope2.core.terminal.shell.ProotExecutor.exec(app, args["command"]?.toString() ?: "").let { if (it.length > shellOutputLimit) it.take(shellOutputLimit) + "\n...(truncated)" else it }
      }

      "read_file" -> try {
        java.io.File(args["path"].toString()).readText().let { if (it.length > fileReadLimit) "File too large" else it }
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "write_file" -> try {
        val f = java.io.File(args["path"].toString())
        f.parentFile?.mkdirs()
        f.writeText(args["content"].toString())
        "OK: Written ${args["content"].toString().length} bytes to ${f.absolutePath}"
      } catch (e: Exception) {
        "FAILED write_file: ${args["path"]} — ${e.message}"
      }

      "list_directory" -> try {
        java.io.File(args["path"].toString()).listFiles()?.joinToString("\n") { "${if (it.isDirectory) "d" else "-"} ${it.name}" } ?: "Empty"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "open_intent" -> try {
        val uri = android.net.Uri.parse(args["uri"].toString())
        app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened: $uri"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "get_location" -> kotlinx.coroutines.runBlocking {
        val loc = locationProvider.getFreshLocation() ?: locationProvider.getLastLocation()
        if (loc != null) {
          lastLocationData = LocationData(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, if (loc.hasSpeed()) loc.speed.toDouble() else null, if (loc.hasBearing()) loc.bearing.toDouble() else null, loc.accuracy.toDouble())
          locationUsedThisTurn = true
          val base = locationProvider.formatLocation(loc)
          val address = locationProvider.reverseGeocode(loc)
          if (address != null) "$base\n$address" else base
        } else {
          "Location unavailable -- check permissions or GPS"
        }
      }

      "search_location" -> executeSearchLocation(args["query"]?.toString() ?: "")

      "search_web" -> execute("query_data", mapOf("category" to "search_web", "extra" to (args["query"]?.toString() ?: "")))

      "search_images" -> execute("query_data", mapOf("category" to "image_search", "extra" to (args["query"]?.toString() ?: "")))

      "fetch_url" -> executeFetchUrl(args)

      "query_data" -> executeQueryData(args)

      "browser_navigate" -> kotlinx.coroutines.runBlocking { getBrowser().navigate(args["url"]?.toString() ?: "") }

      "browser_content" -> kotlinx.coroutines.runBlocking { getBrowser().getPageContent() }

      "browser_elements" -> kotlinx.coroutines.runBlocking { getBrowser().getElements() }

      "browser_click" -> kotlinx.coroutines.runBlocking { getBrowser().click(args["selector"]?.toString() ?: "") }

      "browser_fill" -> kotlinx.coroutines.runBlocking { getBrowser().fill(args["selector"]?.toString() ?: "", args["value"]?.toString() ?: "") }

      "browser_eval" -> kotlinx.coroutines.runBlocking { getBrowser().evaluateJs(args["script"]?.toString() ?: "") }

      "browser_back" -> kotlinx.coroutines.runBlocking { if (getBrowser().goBack()) "Navigated back" else "No history to go back" }

      "browser_scroll" -> kotlinx.coroutines.runBlocking { getBrowser().scroll(args["direction"]?.toString() ?: "down", (args["amount"] as? Number)?.toInt() ?: 500) }

      "browser_open" -> {
        onBrowserVisible(true)
        "Browser opened"
      }

      "browser_close" -> {
        onBrowserVisible(false)
        onBrowserMaximized(false)
        "Browser closed"
      }

      "browser_maximize" -> {
        val max = args["maximize"] as? Boolean ?: true
        onBrowserVisible(true)
        onBrowserMaximized(max)
        if (max) "Browser maximized" else "Browser restored"
      }

      "memory_store" -> kotlinx.coroutines.runBlocking {
        val key = args["key"]?.toString() ?: return@runBlocking "Error: key required"
        chatDao.upsertMemory(com.aiope2.feature.chat.db.MemoryEntity(key = key, content = args["content"]?.toString() ?: return@runBlocking "Error: content required", category = args["category"]?.toString() ?: "general"))
        "Stored memory: $key"
      }

      "memory_recall" -> kotlinx.coroutines.runBlocking {
        val q = args["query"]?.toString() ?: ""
        val m = if (q.isBlank()) chatDao.getAllMemories() else chatDao.searchMemories(q)
        if (m.isEmpty()) "No memories found." else m.joinToString("\n") { "- ${it.key}: ${it.content} [${it.category}]" }
      }

      "memory_forget" -> kotlinx.coroutines.runBlocking {
        val key = args["key"]?.toString() ?: return@runBlocking "Error: key required"
        chatDao.deleteMemory(key)
        "Deleted memory: $key"
      }

      "image_generate" -> executeImageGenerate(args)

      "analyze_image" -> executeAnalyzeImage(args)

      // Calendar
      "read_calendar" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.READ_CALENDAR)) return@execute "Calendar permission denied."
        val days = (args["days"] as? Number)?.toInt() ?: 7
        val now = System.currentTimeMillis()
        val end = now + days * 86400000L
        val cursor = app.contentResolver.query(android.provider.CalendarContract.Events.CONTENT_URI, arrayOf("_id", "title", "dtstart", "dtend", "eventLocation", "description"), "dtstart >= ? AND dtstart <= ?", arrayOf(now.toString(), end.toString()), "dtstart ASC")
        val events = mutableListOf<String>()
        cursor?.use {
          while (it.moveToNext()) {
            val id = it.getLong(0)
            val t = it.getString(1) ?: "Untitled"
            val s = java.text.SimpleDateFormat("EEE MMM d h:mm a", java.util.Locale.US).format(java.util.Date(it.getLong(2)))
            val loc = it.getString(4)?.takeIf { l -> l.isNotBlank() }
            events.add("- [id:$id] $t @ $s${loc?.let { " ($it)" } ?: ""}")
          }
        }
        if (events.isEmpty()) "No events in the next $days days." else "Events (next $days days):\n${events.joinToString("\n")}"
      } catch (e: Exception) {
        "Error: ${e.message}. Calendar permission may be needed."
      }

      "create_event" -> try {
        val title = args["title"]?.toString() ?: return@execute "Error: title required"
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
          data = android.provider.CalendarContract.Events.CONTENT_URI
          putExtra(android.provider.CalendarContract.Events.TITLE, title)
          args["description"]?.toString()?.let { putExtra(android.provider.CalendarContract.Events.DESCRIPTION, it) }
          args["location"]?.toString()?.let { putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, it) }
          args["start_time"]?.toString()?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, parseTime(it)) }
          args["end_time"]?.toString()?.let { putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, parseTime(it)) }
          addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
        "Calendar event creation opened: $title"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "delete_event" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.WRITE_CALENDAR)) return@execute "Calendar permission denied."
        val id = (args["event_id"] as? Number)?.toLong() ?: args["event_id"]?.toString()?.toLongOrNull() ?: return@execute "Error: event_id required"
        val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, id)
        val rows = app.contentResolver.delete(uri, null, null)
        if (rows > 0) "Deleted calendar event (id: $id)" else "Event not found (id: $id)"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // Alarms
      "set_alarm" -> try {
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
          args["hour"]?.let { putExtra(android.provider.AlarmClock.EXTRA_HOUR, (it as Number).toInt()) }
          args["minutes"]?.let { putExtra(android.provider.AlarmClock.EXTRA_MINUTES, (it as Number).toInt()) }
          args["message"]?.toString()?.let { putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
          putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, args["skip_ui"] as? Boolean ?: false)
          addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(app.packageManager) != null) {
          app.startActivity(intent)
        } else {
          // Fallback: use AlarmManager for a one-shot alarm
          val h = (args["hour"] as? Number)?.toInt() ?: 0
          val m = (args["minutes"] as? Number)?.toInt() ?: 0
          val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
          }
          if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
          val am = app.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
          val pi = android.app.PendingIntent.getBroadcast(app, h * 100 + m, android.content.Intent("com.aiope2.ALARM"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
          am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
        val h = (args["hour"] as? Number)?.toInt()
        val m = (args["minutes"] as? Number)?.toInt()
        if (h != null && m != null) "Alarm set for ${"%d:%02d".format(h, m)}" else "Alarm creation opened"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "dismiss_alarm" -> try {
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_DISMISS_ALARM).apply {
          args["message"]?.toString()?.let {
            putExtra(android.provider.AlarmClock.EXTRA_ALARM_SEARCH_MODE, android.provider.AlarmClock.ALARM_SEARCH_MODE_LABEL)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it)
          }
          addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(app.packageManager) != null) {
          app.startActivity(intent)
          "Alarm dismiss requested"
        } else {
          "No clock app available to dismiss alarms"
        }
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // Contacts
      "read_contacts" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.READ_CONTACTS)) return@execute "Contacts permission denied."
        val query = args["query"]?.toString() ?: ""
        val sel = if (query.isNotBlank()) "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null
        val selArgs = if (query.isNotBlank()) arrayOf("%$query%") else null
        val cursor = app.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), sel, selArgs, "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
        val contacts = mutableListOf<String>()
        cursor?.use {
          while (it.moveToNext() && contacts.size < 20) {
            contacts.add("${it.getString(0)}: ${it.getString(1)}")
          }
        }
        if (contacts.isEmpty()) "No contacts found${if (query.isNotBlank()) " matching '$query'" else ""}." else contacts.joinToString("\n")
      } catch (e: Exception) {
        "Error: ${e.message}. Contacts permission may be needed."
      }

      // Notifications
      "send_notification" -> try {
        val title = args["title"]?.toString() ?: "AIOPE"
        val body = args["body"]?.toString() ?: return@execute "Error: body required"
        val channelId = "aiope_tools"
        val nm = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(channelId, "Tool Notifications", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val n = android.app.Notification.Builder(app, channelId).setContentTitle(title).setContentText(body).setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), n)
        "Notification sent: $title"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // Clipboard
      "clipboard_copy" -> try {
        val text = args["text"]?.toString() ?: return@execute "Error: text required"
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        android.os.Handler(android.os.Looper.getMainLooper()).post { cm.setPrimaryClip(android.content.ClipData.newPlainText("AIOPE", text)) }
        "Copied to clipboard (${text.length} chars)"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "clipboard_read" -> try {
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        var result = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
          result = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
          latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        if (result.isBlank()) "Clipboard is empty" else result
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // SMS
      "read_sms" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.READ_SMS)) return@execute "SMS permission denied."
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val cursor = app.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type"), null, null, "date DESC LIMIT $limit")
        val msgs = mutableListOf<String>()
        cursor?.use {
          while (it.moveToNext()) {
            val id = it.getLong(0)
            val dir = if (it.getInt(4) == 1) "←" else "→"
            val time = java.text.SimpleDateFormat("MMM d h:mm a", java.util.Locale.US).format(java.util.Date(it.getLong(3)))
            msgs.add("[id:$id] $dir ${it.getString(1)} ($time): ${it.getString(2).take(200)}")
          }
        }
        if (msgs.isEmpty()) "No SMS messages found." else msgs.joinToString("\n")
      } catch (e: Exception) {
        "Error: ${e.message}. SMS permission may be needed."
      }

      "send_sms" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.SEND_SMS)) return@execute "SMS permission denied."
        val to = args["to"]?.toString() ?: return@execute "Error: 'to' phone number required"
        val body = args["body"]?.toString() ?: return@execute "Error: 'body' required"
        android.telephony.SmsManager.getDefault().sendTextMessage(to, null, body, null, null)
        "SMS sent to $to"
      } catch (e: Exception) {
        "Error: ${e.message}. SMS permission may be needed."
      }

      "delete_sms" -> try {
        if (!PermissionHelper.ensurePermission(app, android.Manifest.permission.READ_SMS)) return@execute "SMS permission denied."
        val id = (args["sms_id"] as? Number)?.toLong() ?: args["sms_id"]?.toString()?.toLongOrNull() ?: return@execute "Error: sms_id required"
        val uri = android.content.ContentUris.withAppendedId(android.provider.Telephony.Sms.CONTENT_URI, id)
        val rows = app.contentResolver.delete(uri, null, null)
        if (rows > 0) "Deleted SMS (id: $id)" else "SMS not found (id: $id)"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // Device info
      "device_info" -> try {
        val bm = app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val freeGb = "%.1f".format(stat.availableBytes / 1073741824.0)
        val totalGb = "%.1f".format(stat.totalBytes / 1073741824.0)
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val conn = when {
          net == null -> "Offline"
          net.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
          net.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
          else -> "Connected"
        }
        val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ramFree = "%.1f".format(mem.availMem / 1073741824.0)
        val ramTotal = "%.1f".format(mem.totalMem / 1073741824.0)
        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\nBattery: $battery%${if (charging) " ⚡charging" else ""}\nStorage: $freeGb / $totalGb GB free\nRAM: $ramFree / $ramTotal GB free\nNetwork: $conn"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      // Media control
      "media_control" -> try {
        val action = args["action"]?.toString() ?: "play_pause"
        val keyCode = when (action) {
          "play", "pause", "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
          "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
          "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
          "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
          else -> return@execute "Unknown action: $action. Use play_pause, next, previous, stop."
        }
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        "Media: $action"
      } catch (e: Exception) {
        "Error: ${e.message}"
      }

      "task" -> {
        val mgr = subagentManager ?: return@execute "Tool 'task' not available"
        val desc = args["description"]?.toString() ?: "research"
        val prompt = args["prompt"]?.toString() ?: return@execute "Error: prompt required"
        kotlinx.coroutines.runBlocking { mgr.runBlocking(desc, prompt) }
      }

      else -> mcpManager.executeTool(name, args) ?: "Unknown tool: $name"
    }
  }

  private fun executeFetchUrl(args: Map<String, Any?>): String = try {
    val fetchUrl = java.net.URL(args["url"].toString())
    val mode = args["mode"]?.toString() ?: "text"
    val conn = (fetchUrl.openConnection() as java.net.HttpURLConnection).apply {
      setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AIOPE/2.0")
      connectTimeout = 15_000
      readTimeout = 15_000
    }
    val ct = conn.contentType ?: ""
    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    conn.disconnect()
    val result = if (mode == "raw" || !ct.contains("html")) {
      body
    } else {
      val base = "${fetchUrl.protocol}://${fetchUrl.host}"
      val imgs = mutableListOf<String>()
      Regex("""<img[^>]+src=["']([^"']+)["'][^>]*(?:alt=["']([^"']*)["'])?""", RegexOption.IGNORE_CASE).findAll(body).forEach { m ->
        val src = m.groupValues[1].let {
          if (it.startsWith("http")) {
            it
          } else if (it.startsWith("/")) {
            "$base$it"
          } else {
            "$base/$it"
          }
        }
        if (src.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)) || !src.contains(".js")) imgs.add("![${m.groupValues.getOrElse(2) { "" }.take(80).ifEmpty { "image" }}]($src)")
      }
      Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach { m ->
        val src = m.groupValues[1].let { if (it.startsWith("http")) it else "$base$it" }
        if (imgs.none { it.contains(src) }) imgs.add("![og:image]($src)")
      }
      val cleaned = body.replace(Regex("<(script|style|nav|footer|header)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
      val text = android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
      (if (imgs.isNotEmpty()) imgs.distinct().take(20).joinToString("\n") + "\n\n" else "") + text
    }
    if (result.length > fetchLimit) result.take(fetchLimit) + "\n...(truncated)" else result
  } catch (e: Exception) {
    "Error: ${e.message}"
  }

  private fun executeQueryData(args: Map<String, Any?>): String = try {
    val cat = args["category"]?.toString() ?: ""
    val extra = args["extra"]?.toString() ?: ""
    val needsLoc = cat in setOf("weather", "weather_hourly", "alerts", "air_quality", "uv", "solar", "sunrise_sunset", "time")
    val (lat, lon) = if (needsLoc) {
      val loc = lastLocationData ?: kotlinx.coroutines.runBlocking {
        locationProvider.getLastLocation()?.let { l ->
          lastLocationData = LocationData(l.latitude, l.longitude, null, null, null, l.accuracy.toDouble())
          lastLocationData
        }
      }
      (loc?.latitude?.toString() ?: "") to (loc?.longitude?.toString() ?: "")
    } else {
      "" to ""
    }
    val p = providerStore.getActive()
    val gwBase = p.effectiveApiBase().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/v1")
    val conn = (java.net.URL("$gwBase/v1/data?q=$cat&lat=$lat&lon=$lon&extra=$extra").openConnection() as java.net.HttpURLConnection).apply {
      if (p.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${p.apiKey}")
      connectTimeout = 15_000
      readTimeout = 30_000
    }
    val body = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: "Error: HTTP ${conn.responseCode}"
    conn.disconnect()
    val enriched = resolveDataImages(body)
    if (enriched.length > fetchLimit) enriched.take(fetchLimit) + "\n...(truncated)" else enriched
  } catch (e: Exception) {
    "Error: ${e.message}"
  }

  private fun executeImageGenerate(args: Map<String, Any?>): String = kotlinx.coroutines.runBlocking {
    val prompt = args["prompt"]?.toString() ?: return@runBlocking "Error: prompt required"
    try {
      val (profile, modelId) = resolveTaskModel(ModelTask.IMAGE_GENERATION)
      val p = profile.copy(selectedModelId = modelId)
      val base = p.effectiveApiBase().trimEnd('/')
      val conn = (java.net.URL("$base/images/generations").openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 60_000
        readTimeout = 300_000
        setRequestProperty("Content-Type", "application/json")
        if (p.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        useCaches = false
      }
      conn.outputStream.use {
        it.write(
          org.json.JSONObject().apply {
            put("model", modelId)
            put("prompt", prompt)
            put("response_format", "b64_json")
            put("seed", System.currentTimeMillis())
          }.toString().toByteArray(),
        )
      }
      val code = conn.responseCode
      val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
      conn.disconnect()
      if (code !in 200..299) throw Exception("HTTP $code: ${body.take(200)}")
      val json = org.json.JSONObject(body)
      val b64 = json.optJSONObject("result")?.optString("image") ?: json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json") ?: ""
      val imageUrl = json.optJSONArray("data")?.optJSONObject(0)?.optString("url") ?: ""
      val bytes = if (b64.isNotBlank()) {
        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
      } else if (imageUrl.isNotBlank()) {
        java.net.URL(imageUrl).readBytes()
      } else {
        throw Exception("No image in response")
      }
      val dir = java.io.File(app.filesDir, "generated")
      dir.mkdirs()
      val file = java.io.File(dir, "img_${System.currentTimeMillis()}.png")
      file.writeBytes(bytes)
      "Image generated successfully.\nFile: file://${file.absolutePath}\nDisplay: ![generated image](file://${file.absolutePath})"
    } catch (e: Exception) {
      "Image generation FAILED.\nError: ${e.message}"
    }
  }

  private fun executeAnalyzeImage(args: Map<String, Any?>): String = kotlinx.coroutines.runBlocking {
    val url = args["url"]?.toString() ?: return@runBlocking "Error: url required"
    val question = args["question"]?.toString() ?: "Describe this image in detail."
    try {
      val (profile, modelId) = resolveTaskModel(ModelTask.IMAGE_RECOGNITION)
      val b64 = android.util.Base64.encodeToString(java.net.URL(url).readBytes(), android.util.Base64.NO_WRAP)
      val sb = StringBuilder()
      StreamingOrchestrator(baseUrl = profile.effectiveApiBase(), apiKey = profile.apiKey, model = modelId).stream(listOf("user" to question), listOf(b64)).collect { if (it.content.isNotEmpty()) sb.append(it.content) }
      "Image analysis complete.\nSource: $url\nResult: ${sb.toString().ifBlank { "No description returned." }}"
    } catch (e: Exception) {
      "Image analysis FAILED.\nError: ${e.message}"
    }
  }

  private fun executeSearchLocation(query: String): String {
    locationUsedThisTurn = true
    val q = query.lowercase()
    val businessTerms = listOf("near", "closest", "nearest", "nearby", "restaurant", "food", "eat", "coffee", "cafe", "pizza", "burger", "gas", "fuel", "pharmacy", "hotel", "grocery", "bar", "pub", "gym", "bank", "atm", "parking", "hospital", "mcdonald", "starbucks", "walmart", "target", "costco", "wendy", "subway", "taco bell", "burger king", "chick-fil", "dunkin")
    val isBusiness = businessTerms.any { q.contains(it) }
    return try {
      if (!isBusiness) {
        val geocoder = android.location.Geocoder(app, java.util.Locale.US)
        val results = geocoder.getFromLocationName(query, 5)
        if (!results.isNullOrEmpty()) {
          lastLocationData = LocationData(results[0].latitude, results[0].longitude)
          results.mapIndexed { i, addr -> "${i + 1}. ${addr.getAddressLine(0) ?: "${addr.locality}, ${addr.adminArea}"}\n   Lat: ${addr.latitude}, Lng: ${addr.longitude}" }.joinToString("\n")
        } else {
          searchPlaces(query)
        }
      } else {
        searchPlaces(query)
      }
    } catch (_: Exception) {
      try {
        searchPlaces(query)
      } catch (e: Exception) {
        "Error: ${e.message}"
      }
    }
  }

  private fun searchPlaces(query: String): String {
    var lat = lastLocationData?.latitude
    var lng = lastLocationData?.longitude
    if (lat == null || lng == null) {
      val loc = kotlinx.coroutines.runBlocking { locationProvider.getFreshLocation() ?: locationProvider.getLastLocation() }
      if (loc != null) {
        lastLocationData = LocationData(loc.latitude, loc.longitude)
        lat = loc.latitude
        lng = loc.longitude
      } else {
        return "Location unavailable. Enable GPS and try again."
      }
    }
    val q = query.trim().replace(Regex("\\s*(near|in|around|close to|closest to|nearest to)\\s+.*$", RegexOption.IGNORE_CASE), "").trim()
    val encoded = java.net.URLEncoder.encode(q, "UTF-8")
    try {
      val p = providerStore.getActive()
      val gwBase = p.effectiveApiBase().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/v1")
      val conn = (java.net.URL("$gwBase/v1/data?q=places&query=$encoded&lat=$lat&lon=$lng").openConnection() as java.net.HttpURLConnection).apply {
        if (p.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        connectTimeout = 10_000
        readTimeout = 15_000
      }
      if (conn.responseCode in 200..299) {
        val data = org.json.JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText()).optJSONObject("data")
        if (data != null) return parseGeoapifyResults(data.toString(), query)
      }
    } catch (_: Exception) {}
    val apiKey = providerStore.getGeoapifyKey().ifBlank { "d8acb75c06c04ab5a95b498a6a7090c0" }
    val conn = (java.net.URL("https://api.geoapify.com/v2/places?categories=commercial,catering,service,entertainment,leisure,sport,tourism,accommodation,education,healthcare&conditions=named&filter=circle:$lng,$lat,5000&bias=proximity:$lng,$lat&limit=5&name=$encoded&apiKey=$apiKey").openConnection() as java.net.HttpURLConnection).apply {
      connectTimeout = 15000
      readTimeout = 15000
    }
    if (conn.responseCode !in 200..299) {
      val conn2 = (java.net.URL("https://api.geoapify.com/v1/geocode/search?text=${java.net.URLEncoder.encode(query, "UTF-8")}&bias=proximity:$lng,$lat&limit=5&apiKey=$apiKey").openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 15000
      }
      if (conn2.responseCode !in 200..299) return "Search error: HTTP ${conn2.responseCode}"
      return parseGeoapifyResults(conn2.inputStream.bufferedReader(Charsets.UTF_8).readText(), query)
    }
    return parseGeoapifyResults(conn.inputStream.bufferedReader(Charsets.UTF_8).readText(), query)
  }

  private fun parseGeoapifyResults(body: String, query: String): String {
    val json = org.json.JSONObject(body)
    val features = json.optJSONArray("features")
    if (features == null || features.length() == 0) return "No results found for: $query"
    val userLat = lastLocationData?.latitude
    val userLng = lastLocationData?.longitude
    val results = (0 until minOf(features.length(), 5)).map { i ->
      val props = features.getJSONObject(i).getJSONObject("properties")
      val name = props.optString("name", "").ifBlank { props.optString("formatted", "Unnamed") }
      val addr = props.optString("formatted", "").ifBlank { null }
      val phone = props.optString("contact:phone", "").ifBlank { props.optString("phone", "").ifBlank { null } }
      val hours = props.optString("opening_hours", "").ifBlank { null }
      val pLat = props.optDouble("lat", 0.0)
      val pLng = props.optDouble("lon", 0.0)
      val dist = if (userLat != null && userLng != null) haversineKm(userLat, userLng, pLat, pLng) else null
      buildString {
        append("${i + 1}. $name")
        dist?.let { append(" (${"%.1f".format(it)} km)") }
        if (addr != null && addr != name) append("\n   Address: $addr")
        phone?.let { append("\n   Phone: $it") }
        hours?.let { append("\n   Hours: $it") }
        append("\n   Lat: $pLat, Lng: $pLng")
      }
    }
    val fp = features.getJSONObject(0).getJSONObject("properties")
    lastLocationData = LocationData(fp.optDouble("lat", 0.0), fp.optDouble("lon", 0.0))
    return results.joinToString("\n")
  }

  private fun resolveDataImages(json: String): String = try {
    val imgs = mutableListOf<String>()
    extractImageUrls(org.json.JSONTokener(json).nextValue(), imgs)
    if (imgs.isNotEmpty()) imgs.distinct().take(20).joinToString("\n") + "\n\n" + json else json
  } catch (_: Exception) {
    json
  }

  private fun extractImageUrls(obj: Any?, out: MutableList<String>) {
    when (obj) {
      is org.json.JSONObject -> {
        val imgKeys = setOf("url", "hdurl", "href", "image_url", "img_src", "thumbnail", "preview", "media_url", "src", "image")
        for (key in obj.keys()) {
          val v = obj.opt(key)
          if (v is String && key in imgKeys && v.matches(Regex("^https?://.*\\.(jpg|jpeg|png|gif|webp)(\\?.*)?$", RegexOption.IGNORE_CASE))) out.add("![${obj.optString("title", key)}]($v)") else extractImageUrls(v, out)
        }
      }

      is org.json.JSONArray -> for (i in 0 until minOf(obj.length(), 20)) extractImageUrls(obj.opt(i), out)
    }
  }

  private fun parseTime(s: String): Long = try {
    val fmts = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "MM/dd/yyyy HH:mm", "MMM d yyyy h:mm a", "h:mm a")
    fmts.firstNotNullOfOrNull { fmt -> runCatching { java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(s)?.time }.getOrNull() } ?: s.toLong()
  } catch (_: Exception) {
    System.currentTimeMillis() + 3600000
  }

  private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  }
}
