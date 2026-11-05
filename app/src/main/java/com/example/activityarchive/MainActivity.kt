package com.example.activityarchive

import android.app.Activity
import android.app.AppOpsManager
import android.app.DatePickerDialog
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val REQUEST_CREATE_EXPORT = 4102
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

private data class ActivityRecord(
    val timestamp: Long,
    val type: String,
    val packageName: String,
    val appName: String,
    val detail: String
)

private data class DailyUsage(
    val day: String,
    val packageName: String,
    val appName: String,
    val foregroundMs: Long,
    val launches: Int // -1 means Android did not provide this metric.
)

private data class AppSession(
    val packageName: String,
    val appName: String,
    val start: Long,
    val end: Long
) {
    val durationMs: Long get() = (end - start).coerceAtLeast(0L)
}

private class ArchiveDb(context: android.content.Context) :
    SQLiteOpenHelper(context, "activity_archive.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE activity_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_key TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                detail TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_timestamp ON activity_events(timestamp)")
        db.execSQL(
            """CREATE TABLE daily_usage (
                day TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                foreground_ms INTEGER NOT NULL,
                launches INTEGER NOT NULL,
                PRIMARY KEY(day, package_name)
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertEvents(records: List<ActivityRecord>): Int {
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            records.forEach { record ->
                val key = "${record.timestamp}|${record.type}|${record.packageName}|${record.detail}" 
                val values = ContentValues().apply {
                    put("event_key", key)
                    put("timestamp", record.timestamp)
                    put("type", record.type)
                    put("package_name", record.packageName)
                    put("app_name", record.appName)
                    put("detail", record.detail)
                }
                if (db.insertWithOnConflict("activity_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun replaceDailyUsage(rows: List<DailyUsage>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("day", row.day)
                    put("package_name", row.packageName)
                    put("app_name", row.appName)
                    put("foreground_ms", row.foregroundMs)
                    put("launches", row.launches)
                }
                db.insertWithOnConflict("daily_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(fromMs: Long, toMs: Long): List<ActivityRecord> {
        val result = mutableListOf<ActivityRecord>()
        readableDatabase.query(
            "activity_events", arrayOf("timestamp", "type", "package_name", "app_name", "detail"),
            "timestamp >= ? AND timestamp <= ?", arrayOf(fromMs.toString(), toMs.toString()),
            null, null, "timestamp DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ActivityRecord(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4)
                )
            }
        }
        return result
    }

    fun allEvents(): List<ActivityRecord> = events(0L, Long.MAX_VALUE)

    fun usage(fromDay: String, toDay: String): List<DailyUsage> {
        val result = mutableListOf<DailyUsage>()
        readableDatabase.query(
            "daily_usage", arrayOf("day", "package_name", "app_name", "foreground_ms", "launches"),
            "day >= ? AND day <= ?", arrayOf(fromDay, toDay), null, null, "day DESC, foreground_ms DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += DailyUsage(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getLong(3), cursor.getInt(4)
                )
            }
        }
        return result
    }

    fun allUsage(): List<DailyUsage> = usage("0000-01-01", "9999-12-31")

    fun counts(): Pair<Int, Int> {
        val db = readableDatabase
        fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        return count("activity_events") to count("daily_usage")
    }
}

class MainActivity : Activity() {
    private lateinit var db: ArchiveDb
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var rangeText: TextView
    private lateinit var summaryText: TextView
    private lateinit var usageText: TextView
    private lateinit var timelineText: TextView
    private lateinit var archiveText: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedStart: LocalDate = LocalDate.now()
    private var selectedEnd: LocalDate = LocalDate.now()
    private var allSavedHistory = false
    private var pendingExport: String? = null
    private var pendingMime: String = "text/plain"
    private var pendingFilename: String = "activity-report.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ArchiveDb(this)
        buildScreen()
        renderSavedData()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) updatePermissionStatus()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, windowInsets ->
                val bars = windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(dp(18) + bars.left, dp(18) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom)
                windowInsets
            }
            root.requestApplyInsets()
        }

        addText("Activity Archive", 27f, true, 0xFF18243A.toInt())
        addText("Read-only viewer for Android's existing usage history", 14f, false, 0xFF526078.toInt())
        addSpace(14)

        statusText = addCardText("Checking Usage Access…", 14f)
        addButton("Grant / check Usage Access") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addButton("Import history available on this phone") { importHistory() }
        addText("Import is manual. The app does not run a monitoring service or collect events in the background.", 12f, false, 0xFF65728A.toInt())
        addSpace(16)

        addText("FILTER", 12f, true, 0xFF526078.toInt())
        addButtonRow(
            listOf("Today", "Pick day", "Date range", "All saved") ,
            listOf(
                { allSavedHistory = false; selectedStart = LocalDate.now(); selectedEnd = selectedStart; renderSavedData() },
                { pickDate(false) },
                { pickDate(true) },
                { allSavedHistory = true; renderSavedData() }
            )
        )
        rangeText = addCardText("Showing today", 13f)
        addSpace(8)

        addText("OVERVIEW", 12f, true, 0xFF526078.toInt())
        summaryText = addCardText("No records loaded yet.", 15f)
        usageText = addCardText("No app usage summaries saved yet.", 14f)
        addSpace(8)

        addText("ACTIVITY TIMELINE", 12f, true, 0xFF526078.toInt())
        timelineText = addCardText("Import Android history to see available events.", 13f)
        addSpace(12)

        addText("EXPORT SELECTED FILTER", 12f, true, 0xFF526078.toInt())
        addButtonRow(
            listOf("HTML report", "CSV", "JSON", "Text report"),
            listOf(
                { export("html") }, { export("csv") }, { export("json") }, { export("txt") }
            )
        )
        addText("Exports are created using Android's file picker. Choose Downloads or another folder.", 12f, false, 0xFF65728A.toInt())
        addSpace(8)
        archiveText = addText("Local archive: 0 events · 0 daily summaries", 12f, false, 0xFF65728A.toInt())
        addSpace(8)
        addText("Privacy: records stay in this app's local database unless you export them. No internet permission is requested.", 12f, false, 0xFF65728A.toInt())
    }

    private fun addText(text: String, size: Float, bold: Boolean, color: Int): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return view
    }

    private fun addCardText(text: String, size: Float): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFF25324A.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
            gravity = Gravity.START
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(5)
        params.bottomMargin = dp(5)
        root.addView(view, params)
        return view
    }

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(3)
        params.bottomMargin = dp(3)
        root.addView(button, params)
    }

    private fun addButtonRow(labels: List<String>, actions: List<() -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { actions[index]() }
            }
            val params = LinearLayout.LayoutParams(0, dp(48), 1f)
            params.marginEnd = dp(3)
            row.addView(button, params)
        }
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addSpace(size: Int) {
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, dp(size)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updatePermissionStatus() {
        statusText.text = if (hasUsageAccess()) {
            "✓ Usage Access is granted. You can import the history Android still retains."
        } else {
            "Usage Access is not granted. Tap the button above and enable Activity Archive."
        }
    }

    private fun pickDate(isRange: Boolean) {
        val initial = if (isRange) selectedStart else selectedStart
        DatePickerDialog(this, { _, year, month, day ->
            val chosen = LocalDate.of(year, month + 1, day)
            if (isRange) {
                selectedStart = chosen
                DatePickerDialog(this, { _, y2, m2, d2 ->
                    val second = LocalDate.of(y2, m2 + 1, d2)
                    selectedEnd = if (second.isBefore(selectedStart)) selectedStart else second
                    allSavedHistory = false
                    renderSavedData()
                }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).apply {
                    setTitle("Choose end date")
                    datePicker.minDate = chosen.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            } else {
                selectedStart = chosen
                selectedEnd = chosen
                allSavedHistory = false
                renderSavedData()
            }
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            setTitle(if (isRange) "Choose start date" else "Choose day")
        }.show()
    }

    private fun currentBounds(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        if (allSavedHistory) return 0L to Long.MAX_VALUE
        val start = selectedStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = selectedEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        return start to end
    }

    private fun renderSavedData() {
        if (!::timelineText.isInitialized) return
        updatePermissionStatus()
        val (fromMs, toMs) = currentBounds()
        val fromDay = if (allSavedHistory) "0000-01-01" else selectedStart.format(DAY_FORMAT)
        val toDay = if (allSavedHistory) "9999-12-31" else selectedEnd.format(DAY_FORMAT)
        rangeText.text = if (allSavedHistory) "Showing all history saved in this app" else if (selectedStart == selectedEnd) {
            "Showing ${selectedStart.format(DAY_FORMAT)}"
        } else {
            "Showing ${selectedStart.format(DAY_FORMAT)} through ${selectedEnd.format(DAY_FORMAT)}"
        }

        val events = if (allSavedHistory) db.allEvents() else db.events(fromMs, toMs)
        val usages = if (allSavedHistory) db.allUsage() else db.usage(fromDay, toDay)
        val sessions = buildSessions(events)
        val sessionTime = sessions.sumOf { it.durationMs }
        summaryText.text = "${events.size} timeline events\n${sessions.size} complete app sessions\n${formatDuration(sessionTime)} total duration from paired foreground/background events"

        val usageByPackage = usages.groupBy { it.packageName }
            .map { (_, rows) -> rows.first().appName to rows.sumOf { it.foregroundMs } }
            .sortedByDescending { it.second }
            .take(12)
        usageText.text = if (usageByPackage.isEmpty()) {
            "No daily usage summaries saved for this filter. Import history to retrieve whatever Android provides."
        } else {
            "Daily app usage totals (Android aggregates):\n" + usageByPackage.joinToString("\n") { "• ${it.first}: ${formatDuration(it.second)}" }
        }

        val eventRows = events.sortedByDescending { it.timestamp }.take(300)
        timelineText.text = if (eventRows.isEmpty()) {
            "No events saved for this filter. Try importing history or selecting another date."
        } else {
            eventRows.joinToString("\n\n") { record ->
                "${formatDateTime(record.timestamp)}\n${readableEvent(record)}" +
                    if (record.detail.isNotBlank()) "\n${record.detail}" else ""
            } + if (events.size > 300) "\n\nShowing newest 300 of ${events.size} saved events." else ""
        }
        val counts = db.counts()
        archiveText.text = "Local archive: ${counts.first} events · ${counts.second} daily app summaries"
    }

    private fun importHistory() {
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Grant Usage Access first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        Toast.makeText(this, "Reading Android history…", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val now = System.currentTimeMillis()
                // Detailed UsageEvents are retained by Android only for a few days.
                // Query a slightly wider window; Android returns only what it still has.
                val eventStart = now - 7L * 24 * 60 * 60 * 1000
                val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val importedEvents = readUsageEvents(manager, eventStart, now)
                val dailyRows = readDailyUsage(manager, now - 400L * 24 * 60 * 60 * 1000, now)
                val inserted = db.insertEvents(importedEvents)
                db.replaceDailyUsage(dailyRows)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Import complete: $inserted new events, ${dailyRows.size} daily usage rows.",
                        Toast.LENGTH_LONG
                    ).show()
                    renderSavedData()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readUsageEvents(manager: UsageStatsManager, start: Long, end: Long): List<ActivityRecord> {
        val events = manager.queryEvents(start, end) ?: return emptyList()
        val labels = launcherAppLabels()
        val result = mutableListOf<ActivityRecord>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: "android"
            if (packageName == this.packageName) continue
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> "APP_OPEN"
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> "APP_BACKGROUND"
                UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_ON"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_OFF"
                UsageEvents.Event.KEYGUARD_SHOWN -> "PHONE_LOCKED"
                UsageEvents.Event.KEYGUARD_HIDDEN -> "PHONE_UNLOCKED"
                UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTED"
                UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
                else -> continue
            }
            val label = when (type) {
                "SCREEN_ON" -> "Screen"
                "SCREEN_OFF" -> "Screen"
                "PHONE_LOCKED", "PHONE_UNLOCKED" -> "Lock screen"
                "DEVICE_STARTED", "DEVICE_SHUTDOWN" -> "Android system"
                else -> labels[packageName] ?: packageName
            }
            val className = event.className?.takeIf { it.isNotBlank() }
            val detail = if (className != null && type.startsWith("APP_")) "Activity: $className" else ""
            result += ActivityRecord(event.timeStamp, type, packageName, label, detail)
        }
        return result
    }

    private fun launcherAppLabels(): Map<String, String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return activities.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            activityInfo.packageName to (activityInfo.loadLabel(packageManager)?.toString() ?: activityInfo.packageName)
        }.toMap()
    }

    private fun readDailyUsage(manager: UsageStatsManager, start: Long, end: Long): List<DailyUsage> {
        val labels = launcherAppLabels()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .orEmpty()
            .filter { it.packageName != packageName && it.totalTimeInForeground > 0L }
            .mapNotNull { stats ->
                val day = try {
                    Instant.ofEpochMilli(stats.firstTimeStamp).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMAT)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                DailyUsage(
                    day,
                    stats.packageName,
                    labels[stats.packageName] ?: stats.packageName,
                    stats.totalTimeInForeground,
                    -1 // Android does not expose appLaunchCount in the public UsageStats API.
                )
            }
    }

    private fun buildSessions(events: List<ActivityRecord>): List<AppSession> {
        val open = mutableMapOf<String, ActivityRecord>()
        val sessions = mutableListOf<AppSession>()
        events.sortedBy { it.timestamp }.forEach { event ->
            when (event.type) {
                "APP_OPEN" -> {
                    if (!open.containsKey(event.packageName)) open[event.packageName] = event
                }
                "APP_BACKGROUND" -> {
                    val started = open.remove(event.packageName)
                    if (started != null && event.timestamp >= started.timestamp) {
                        sessions += AppSession(started.packageName, started.appName, started.timestamp, event.timestamp)
                    }
                }
            }
        }
        return sessions.sortedByDescending { it.start }
    }

    private fun readableEvent(record: ActivityRecord): String = when (record.type) {
        "APP_OPEN" -> "Opened / moved to foreground: ${record.appName}"
        "APP_BACKGROUND" -> "Paused / moved to background: ${record.appName}"
        "SCREEN_ON" -> "Screen became interactive"
        "SCREEN_OFF" -> "Screen became non-interactive / turned off"
        "PHONE_LOCKED" -> "Device lock screen shown"
        "PHONE_UNLOCKED" -> "Device unlocked / lock screen hidden"
        "DEVICE_STARTED" -> "Android device/runtime startup recorded"
        "DEVICE_SHUTDOWN" -> "Android shutdown marker recorded"
        else -> record.type
    }

    private fun export(format: String) {
        val (fromMs, toMs) = currentBounds()
        val fromDay = if (allSavedHistory) "0000-01-01" else selectedStart.format(DAY_FORMAT)
        val toDay = if (allSavedHistory) "9999-12-31" else selectedEnd.format(DAY_FORMAT)
        val events = if (allSavedHistory) db.allEvents() else db.events(fromMs, toMs)
        val usages = if (allSavedHistory) db.allUsage() else db.usage(fromDay, toDay)
        val sessions = buildSessions(events)
        val report = when (format) {
            "html" -> htmlReport(events, usages, sessions)
            "csv" -> csvReport(events, usages)
            "json" -> jsonReport(events, usages, sessions)
            else -> textReport(events, usages, sessions)
        }
        pendingExport = report
        pendingMime = when (format) {
            "html" -> "text/html"
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> "text/plain"
        }
        val datePart = if (allSavedHistory) "all-history" else if (selectedStart == selectedEnd) selectedStart.format(DAY_FORMAT) else "${selectedStart}_${selectedEnd}"
        pendingFilename = "activity-archive-$datePart.$format"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = pendingMime
            putExtra(Intent.EXTRA_TITLE, pendingFilename)
        }
        startActivityForResult(intent, REQUEST_CREATE_EXPORT)
    }

    @Deprecated("Using the document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREATE_EXPORT && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val content = pendingExport ?: return
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                Toast.makeText(this, "Export saved.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this, "Could not save export: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingExport = null
    }

    private fun csvReport(events: List<ActivityRecord>, usages: List<DailyUsage>): String {
        val out = StringBuilder("record_type,date_time,event,app_name,package_name,duration_ms,launches,detail\n")
        events.sortedBy { it.timestamp }.forEach { e ->
            out.append(listOf("event", csv(formatDateTime(e.timestamp)), csv(readableEvent(e)), csv(e.appName), csv(e.packageName), "", "", csv(e.detail)).joinToString(",")).append('\n')
        }
        usages.sortedWith(compareBy<DailyUsage> { it.day }.thenBy { it.appName }).forEach { u ->
            out.append(listOf("daily_usage", csv(u.day), "daily app usage summary", csv(u.appName), csv(u.packageName), u.foregroundMs.toString(), if (u.launches >= 0) u.launches.toString() else "", "Android aggregated usage; launch count is not exposed by the public UsageStats API").joinToString(",")).append('\n')
        }
        return out.toString()
    }

    private fun jsonReport(events: List<ActivityRecord>, usages: List<DailyUsage>, sessions: List<AppSession>): String {
        val eventJson = events.sortedBy { it.timestamp }.joinToString(",\n") {
            "    {\"timestamp\":${it.timestamp},\"dateTime\":${json(formatDateTime(it.timestamp))},\"type\":${json(it.type)},\"event\":${json(readableEvent(it))},\"appName\":${json(it.appName)},\"packageName\":${json(it.packageName)},\"detail\":${json(it.detail)}}"
        }
        val usageJson = usages.joinToString(",\n") {
            "    {\"date\":${json(it.day)},\"appName\":${json(it.appName)},\"packageName\":${json(it.packageName)},\"foregroundMs\":${it.foregroundMs},\"launchCount\":${if (it.launches >= 0) it.launches.toString() else "null"}}"
        }
        val sessionJson = sessions.joinToString(",\n") {
            "    {\"appName\":${json(it.appName)},\"packageName\":${json(it.packageName)},\"start\":${json(formatDateTime(it.start))},\"end\":${json(formatDateTime(it.end))},\"durationMs\":${it.durationMs}}"
        }
        return """{
  "generatedAt": ${json(formatDateTime(System.currentTimeMillis()))},
  "note": "Read-only export of Android history available when imported. Daily usage is aggregated data; session durations are calculated only from matching foreground/background events. Launch counts are not exposed by the public UsageStats API.",
  "events": [
$eventJson
  ],
  "dailyUsage": [
$usageJson
  ],
  "completeSessions": [
$sessionJson
  ]
}""".trimIndent()
    }

    private fun htmlReport(events: List<ActivityRecord>, usages: List<DailyUsage>, sessions: List<AppSession>): String {
        val total = usages.sumOf { it.foregroundMs }
        val topApps = usages.groupBy { it.appName }.map { it.key to it.value.sumOf { row -> row.foregroundMs } }.sortedByDescending { it.second }.take(10)
        val max = topApps.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        val bars = topApps.joinToString("\n") { (name, value) ->
            "<div class=\"barrow\"><span>${html(name)}</span><div class=\"track\"><i style=\"width:${(value * 100 / max).coerceIn(2L, 100L)}%\"></i></div><b>${html(formatDuration(value))}</b></div>"
        }
        val eventRows = events.sortedByDescending { it.timestamp }.joinToString("\n") {
            "<tr><td>${html(formatDateTime(it.timestamp))}</td><td>${html(readableEvent(it))}</td><td>${html(it.appName)}</td><td>${html(it.detail)}</td></tr>"
        }
        val usageRows = usages.sortedWith(compareByDescending<DailyUsage> { it.day }.thenByDescending { it.foregroundMs }).joinToString("\n") {
            "<tr><td>${html(it.day)}</td><td>${html(it.appName)}</td><td>${html(formatDuration(it.foregroundMs))}</td><td>${if (it.launches >= 0) it.launches.toString() else "Not available"}</td></tr>"
        }
        val sessionRows = sessions.joinToString("\n") {
            "<tr><td>${html(it.appName)}</td><td>${html(formatDateTime(it.start))}</td><td>${html(formatDateTime(it.end))}</td><td>${html(formatDuration(it.durationMs))}</td></tr>"
        }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Activity Archive report</title>
<style>body{font:15px system-ui,sans-serif;margin:24px auto;padding:0 16px;max-width:1100px;color:#1d2a40;background:#f5f7fb}h1{margin-bottom:4px}.muted{color:#61708a}.cards{display:flex;gap:12px;flex-wrap:wrap}.card{background:white;border-radius:12px;padding:16px;min-width:180px;box-shadow:0 1px 5px #0001}.value{font-size:24px;font-weight:700}section{background:white;padding:16px;border-radius:12px;margin:16px 0;overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:9px;border-bottom:1px solid #e6eaf1;vertical-align:top}th{background:#f5f7fb}.barrow{display:grid;grid-template-columns:minmax(90px,180px) 1fr 90px;gap:10px;align-items:center;margin:10px 0}.track{height:10px;background:#e8edf5;border-radius:10px;overflow:hidden}.track i{display:block;height:10px;background:#527bc0;border-radius:10px}@media(max-width:600px){.barrow{grid-template-columns:1fr 1fr}.barrow b{grid-column:2}}</style></head><body>
<h1>Activity Archive</h1><p class="muted">Generated ${html(formatDateTime(System.currentTimeMillis()))}. This report contains only history Android exposed when imported and data previously saved in this app.</p>
<div class="cards"><div class="card"><div class="muted">Timeline events</div><div class="value">${events.size}</div></div><div class="card"><div class="muted">Matched app sessions</div><div class="value">${sessions.size}</div></div><div class="card"><div class="muted">Aggregated app foreground time</div><div class="value">${html(formatDuration(total))}</div></div></div>
<section><h2>Most-used apps</h2>${if (bars.isBlank()) "<p class=\"muted\">No daily usage summaries are available.</p>" else bars}</section>
<section><h2>Daily app usage (Android aggregates)</h2><p class="muted">This is aggregated usage, not a reconstructed exact open/close timeline.</p><table><tr><th>Date</th><th>App</th><th>Foreground time</th><th>Launch count</th></tr>${usageRows}</table></section>
<section><h2>Complete app sessions</h2><p class="muted">Durations are calculated only where a foreground event has a matching background event.</p><table><tr><th>App</th><th>Start</th><th>Backgrounded</th><th>Duration</th></tr>${sessionRows}</table></section>
<section><h2>Activity timeline</h2><table><tr><th>Timestamp</th><th>Event</th><th>App / source</th><th>Details</th></tr>${eventRows}</table></section>
<p class="muted">Privacy: generated locally by Activity Archive. No network upload.</p></body></html>""".trimIndent()
    }

    private fun textReport(events: List<ActivityRecord>, usages: List<DailyUsage>, sessions: List<AppSession>): String = buildString {
        appendLine("ACTIVITY ARCHIVE")
        appendLine("Generated: ${formatDateTime(System.currentTimeMillis())}")
        appendLine("Timeline events: ${events.size}")
        appendLine("Complete paired sessions: ${sessions.size}")
        appendLine("\nDAILY APP USAGE")
        usages.sortedWith(compareBy<DailyUsage> { it.day }.thenBy { it.appName }).forEach {
            appendLine("${it.day} | ${it.appName} | ${formatDuration(it.foregroundMs)} | launches: ${if (it.launches >= 0) it.launches.toString() else "not exposed by Android"}")
        }
        appendLine("\nCOMPLETE APP SESSIONS")
        sessions.sortedBy { it.start }.forEach {
            appendLine("${it.appName} | ${formatDateTime(it.start)} -> ${formatDateTime(it.end)} | ${formatDuration(it.durationMs)}")
        }
        appendLine("\nACTIVITY TIMELINE")
        events.sortedBy { it.timestamp }.forEach {
            appendLine("${formatDateTime(it.timestamp)} | ${readableEvent(it)} | ${it.detail}")
        }
        appendLine("\nNote: Android retains detailed UsageEvents for only a few days. This archive includes data imported earlier plus whatever Android returned at import time.")
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        val hours = minutes / 60L
        val remaining = minutes % 60L
        return if (hours > 0) "${hours}h ${remaining}m" else "${remaining}m"
    }

    private fun formatDateTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun json(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
