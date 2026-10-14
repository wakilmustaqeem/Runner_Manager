package com.fm.runnermanager

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-first dashboard. Live GitHub/job data and runner controls require a future authorized adapter. */
class MainActivity : Activity() {
    private val ink = 0xFFEAF2FF.toInt()
    private val muted = 0xFF9FB2CC.toInt()
    private val panel = 0xFF13243B.toInt()
    private val accent = 0xFF45D7E8.toInt()
    private val background = 0xFF081426.toInt()
    private val border = 0xFF29435F.toInt()
    private val preferences by lazy { getSharedPreferences("local_account_aliases", MODE_PRIVATE) }
    private val auditPreferences by lazy { getSharedPreferences("local_audit_metadata", MODE_PRIVATE) }
    private val historyPreferences by lazy { getSharedPreferences("local_activity_history", MODE_PRIVATE) }
    private val policyPreferences by lazy { getSharedPreferences("local_runner_policy", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var cpuLabel: TextView? = null
    private var ramLabel: TextView? = null
    private var diskLabel: TextView? = null
    private var batteryLabel: TextView? = null
    private var powerLabel: TextView? = null
    private var cpuBar: ProgressBar? = null
    private var ramBar: ProgressBar? = null
    private var diskBar: ProgressBar? = null
    private var batteryBar: ProgressBar? = null
    private var lastCpu = readCpuCounters()
    private var selectedAccount = 1
    private var repoSpinner: Spinner? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshMetrics()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderDashboard()
        refreshMetrics()
        handler.postDelayed(refreshTask, 3000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    private fun renderDashboard() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(background)
        }
        scroll.addView(root)
        setContentView(scroll)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedPanel(panel, 22)
        }
        hero.addView(text("FM RUNNER MANAGER", 25f, ink, true))
        hero.addView(text("LOCAL-FIRST • SAFE CONTROL", 12f, accent, true).apply { setPadding(0, dp(6), 0, dp(8)) })
        hero.addView(text("Device health, account slots, activity history and security audit — together in one workspace.", 13f, muted, false))
        root.addView(hero, marginParams(bottom = 12))
        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickActions.addView(Button(this).apply {
            text = "History"
            setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        quickActions.addView(Button(this).apply {
            text = "Audit Log"
            setOnClickListener { showAuditLog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        root.addView(quickActions, marginParams(bottom = 8))

        section(root, "Device health")
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedPanel(panel, 18)
        }
        cpuLabel = text("CPU usage: reading…", 14f, ink, true)
        ramLabel = text("RAM: reading…", 14f, ink, true)
        diskLabel = text("Disk: reading…", 14f, ink, true)
        batteryLabel = text("Battery: reading…", 14f, ink, true)
        powerLabel = text("Power: reading…", 13f, muted, false)
        cpuBar = progressBar()
        ramBar = progressBar()
        diskBar = progressBar()
        batteryBar = progressBar()
        addMetric(metrics, cpuLabel!!, cpuBar!!)
        addMetric(metrics, ramLabel!!, ramBar!!)
        addMetric(metrics, diskLabel!!, diskBar!!)
        addMetric(metrics, batteryLabel!!, batteryBar!!)
        metrics.addView(powerLabel!!)
        metrics.addView(text("Device readings refresh locally every 3 seconds.", 11f, muted, false)
            .apply { setPadding(0, dp(8), 0, 0) })
        root.addView(metrics, marginParams(bottom = 12))

        section(root, "Account & repository")
        root.addView(text("Choose a local slot. Real repository names require explicit authorization for that account.", 13f, muted, false)
            .apply { setPadding(0, 0, 0, dp(7)) })
        val accounts = (1..4).map { index ->
            val alias = preferences.getString(aliasKey(index), defaultAlias(index)) ?: defaultAlias(index)
            "Slot " + index + " • " + alias
        }
        val accountSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, accounts)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedAccount = position + 1
                    updateRepositorySpinner()
                }
            }
        }
        root.addView(accountSpinner, marginParams(bottom = 8))
        repoSpinner = Spinner(this)
        root.addView(repoSpinner, marginParams(bottom = 8))
        updateRepositorySpinner()
        root.addView(Button(this).apply {
            text = "Edit selected account label"
            setOnClickListener {
                val alias = preferences.getString(aliasKey(selectedAccount), defaultAlias(selectedAccount)) ?: defaultAlias(selectedAccount)
                editAlias(selectedAccount, alias)
            }
        }, marginParams(bottom = 12))

        section(root, "Runner & job activity")
        val runnerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedPanel(panel, 18)
        }
        runnerPanel.addView(text("GitHub connection: Not configured", 14f, ink, true))
        runnerPanel.addView(text("Runner status: Not connected", 13f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        runnerPanel.addView(text("Job listing: Unavailable until an authorized GitHub/job adapter is implemented.", 13f, muted, false)
            .apply { setPadding(0, dp(5), 0, dp(8)) })
        runnerPanel.addView(Button(this).apply {
            text = "View runner / job integration status"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Runner integration not implemented")
                    .setMessage("This build does not connect to GitHub or control DroidRunner. No runner is started or stopped by this screen. Add and authorize a dedicated adapter before enabling live job data or controls.")
                    .setPositiveButton("Close", null).show()
                logAudit("runner_integration_unavailable_viewed")
            }
        })
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow.addView(Button(this).apply {
            text = "Start runner"
            isEnabled = false
            contentDescription = "Disabled until an authorized runner control adapter exists"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        controlRow.addView(Button(this).apply {
            text = "Stop runner"
            isEnabled = false
            contentDescription = "Disabled until an authorized runner control adapter exists"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        runnerPanel.addView(controlRow)
        root.addView(runnerPanel, marginParams(bottom = 12))

        section(root, "Power & job policy")
        root.addView(text("Saved locally only. These settings do not yet change DroidRunner admission or interrupt jobs.", 13f, muted, false)
            .apply { setPadding(0, 0, 0, dp(7)) })
        root.addView(text("Battery optimization: Android may restrict background runner activity. Grant exemption manually in system settings if needed; this app does not change it automatically.", 12f, muted, false)
            .apply { setPadding(0, 0, 0, dp(8)) })
        addPolicySwitch(root, "Run only on mains power", "mains_only")
        addPolicySwitch(root, "Ephemeral runner (re-register / wipe work dir per job)", "ephemeral")
        addPolicySwitch(root, "Start runner on device boot", "boot_start")
        addChoice(root, "Minimum battery", "min_battery", arrayOf("0%", "20%", "30%", "50%"), arrayOf(0, 20, 30, 50), 20)
        addChoice(root, "Maximum thermal policy", "max_thermal", arrayOf("None", "Light", "Moderate"), arrayOf(0, 1, 2), 2)
        addChoice(root, "Minimum free storage", "min_free_mb", arrayOf("512 MB", "1 GB", "2 GB", "5 GB"), arrayOf(512, 1024, 2048, 5120), 5120)

        section(root, "Diagnostics & security")
        root.addView(Button(this).apply {
            text = "Copy device diagnostics"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FM Runner Manager diagnostics", diagnosticsText()))
                logAudit("diagnostics_copied")
                Toast.makeText(this@MainActivity, "Local diagnostics copied; no tokens included.", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply { text = "Open Activity History"; setOnClickListener { showHistory() } })
        root.addView(Button(this).apply { text = "View local audit log"; setOnClickListener { showAuditLog() } })
        root.addView(Button(this).apply {
            text = "Refresh device metrics"
            setOnClickListener { refreshMetrics(); Toast.makeText(this@MainActivity, "Device metrics refreshed.", Toast.LENGTH_SHORT).show() }
        })
        root.addView(text("Safety: no credentials stored • no network calls • no packages installed • runner controls disabled",
            12f, muted, false).apply { setPadding(0, dp(10), 0, 0) })
        styleTree(root)
    }

    private fun styleTree(view: View) {
        when (view) {
            is Button -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
                view.setTextColor(0xFF071426.toInt())
                view.textSize = 13f
                view.isAllCaps = false
            }
            is EditText -> {
                view.setTextColor(ink)
                view.setHintTextColor(muted)
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            is Spinner -> view.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) styleTree(view.getChildAt(index))
        }
    }

    private fun roundedPanel(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), border)
        }

    private fun updateRepositorySpinner() {
        val spinner = repoSpinner ?: return
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Repositories not loaded — connect this account first"))
        spinner.isEnabled = false
    }

    private fun refreshMetrics() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val usedRam = (memory.totalMem - memory.availMem).coerceAtLeast(0L)
        val ramPercent = if (memory.totalMem > 0) (usedRam * 100 / memory.totalMem).toInt().coerceIn(0, 100) else 0
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val free = stat.availableBytes
        val total = stat.totalBytes.coerceAtLeast(1L)
        val usedDiskPercent = (((total - free).coerceAtLeast(0L) * 100) / total).toInt().coerceIn(0, 100)
        val cpuPercent = readCpuPercent()
        val battery = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        cpuLabel?.text = "CPU usage: " + if (cpuPercent < 0) "Unavailable" else cpuPercent.toString() + "%"
        ramLabel?.text = "RAM used: " + formatBytes(usedRam) + " / " + formatBytes(memory.totalMem) + " • " + ramPercent + "%"
        diskLabel?.text = "Disk used: " + formatBytes(total - free) + " / " + formatBytes(total) + " • " + usedDiskPercent + "% (free " + formatBytes(free) + ")"
        batteryLabel?.text = "Battery: " + if (percent < 0) "Unavailable" else percent.toString() + "%"
        val powerText = when {
            charging -> "Charging"
            plugged != 0 -> "Connected to power"
            else -> "Not charging"
        }
        powerLabel?.text = "Power: " + powerText + if (tempTenths >= 0) " • Battery temp " + String.format(Locale.US, "%.1f", tempTenths / 10.0) + "°C" else ""
        cpuBar?.progress = cpuPercent.coerceAtLeast(0)
        ramBar?.progress = ramPercent
        diskBar?.progress = usedDiskPercent
        batteryBar?.progress = percent.coerceAtLeast(0)
    }

    private fun readCpuPercent(): Int {
        val current = readCpuCounters() ?: return -1
        val previous = lastCpu
        lastCpu = current
        if (previous == null) return -1
        val totalDelta = current.first - previous.first
        val idleDelta = current.second - previous.second
        if (totalDelta <= 0L) return -1
        return (((totalDelta - idleDelta).coerceAtLeast(0L) * 100) / totalDelta).toInt().coerceIn(0, 100)
    }

    private fun readCpuCounters(): Pair<Long, Long>? {
        return try {
            val values = java.io.File("/proc/stat").readText().lineSequence().first().trim().split(Regex("\\s+"))
            if (values.firstOrNull() != "cpu" || values.size < 5) null
            else {
                val counters = values.drop(1).mapNotNull { it.toLongOrNull() }
                if (counters.size < 4) null
                else Pair(counters.sum(), counters[3] + (counters.getOrNull(4) ?: 0L))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun addMetric(parent: LinearLayout, label: TextView, bar: ProgressBar) {
        parent.addView(label)
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
            topMargin = dp(5); bottomMargin = dp(10)
        })
    }

    private fun progressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDCE3ED.toInt())
    }

    private fun addPolicySwitch(parent: LinearLayout, label: String, key: String) {
        parent.addView(Switch(this).apply {
            text = label
            isChecked = policyPreferences.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                policyPreferences.edit().putBoolean(key, checked).apply()
                logAudit("local_policy_" + key + "_" + if (checked) "enabled" else "disabled")
            }
        }, marginParams(bottom = 2))
    }

    private fun addChoice(parent: LinearLayout, title: String, key: String, labels: Array<String>, values: Array<Int>, defaultValue: Int) {
        parent.addView(text(title, 14f, ink, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = policyPreferences.getInt(key, defaultValue)
        spinner.setSelection(values.indexOf(saved).takeIf { it >= 0 } ?: 0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var initial = true
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parentView: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initial) { initial = false; return }
                policyPreferences.edit().putInt(key, values[position]).apply()
                logAudit("local_policy_" + key + "_changed")
            }
        }
        parent.addView(spinner, marginParams(bottom = 5))
    }

    private fun editAlias(index: Int, current: String) {
        val input = EditText(this).apply {
            setText(current); hint = defaultAlias(index)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Account slot " + index + " label")
            .setMessage("Save a local display label only. This does not connect or authorize a GitHub account.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                preferences.edit().remove(aliasKey(index)).apply()
                logAudit("account_slot_" + index + "_label_reset")
                renderDashboard()
            }
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().take(48)
                if (value.isEmpty()) {
                    preferences.edit().remove(aliasKey(index)).apply()
                    logAudit("account_slot_" + index + "_label_cleared")
                } else {
                    preferences.edit().putString(aliasKey(index), value).apply()
                    logAudit("account_slot_" + index + "_label_saved")
                }
                renderDashboard()
                Toast.makeText(this, "Local label saved; account remains disconnected.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showAuditLog() {
        val entries = auditPreferences.getString("entries", "") ?: ""
        AlertDialog.Builder(this).setTitle("Local audit log")
            .setMessage(if (entries.isBlank()) "No local actions recorded yet." else entries)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear log") { _, _ ->
                auditPreferences.edit().remove("entries").apply()
                Toast.makeText(this, "Local metadata log cleared.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun logAudit(action: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = auditPreferences.getString("entries", "") ?: ""
        val next = (listOf(stamp + " | " + action) + old.lines().filter { it.isNotBlank() }).take(100)
        auditPreferences.edit().putString("entries", next.joinToString("\n")).apply()
        logHistory(action, "Recorded", selectedAccount)
    }

    private fun logHistory(action: String, result: String, accountSlot: Int) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeAction = action.replace("|", "/").take(80)
        val safeResult = result.replace("|", "/").take(60)
        val entry = listOf(stamp, safeAction, safeResult, "Slot " + accountSlot.coerceIn(1, 4)).joinToString("|")
        val old = historyPreferences.getString("entries", "") ?: ""
        val next = (listOf(entry) + old.lines().filter { it.isNotBlank() }).take(200)
        historyPreferences.edit().putString("entries", next.joinToString("\n")).apply()
    }

    private fun showHistory() {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val search = EditText(this).apply {
            hint = "Search action, result, account slot…"
            setSingleLine(true)
        }
        val actionFilter = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("All actions", "account", "diagnostics", "policy", "runner", "history"))
        }
        val resultFilter = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("All results", "Recorded", "Success", "Failed", "Unavailable"))
        }
        val results = TextView(this).apply {
            textSize = 12f
            setTextColor(ink)
            setPadding(0, dp(10), 0, dp(10))
        }
        val clearButton = Button(this).apply {
            text = "Clear History…"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear activity history?")
                    .setMessage("This permanently removes local History entries. The separate Security Audit Log will not be cleared.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear History") { _, _ ->
                        historyPreferences.edit().remove("entries").apply()
                        logAuditWithoutHistory("history_cleared_after_confirmation")
                        results.text = "History is empty. Audit Log remains separate and unchanged."
                    }.show()
            }
        }
        fun render() {
            val query = search.text.toString().trim().lowercase(Locale.ROOT)
            val action = actionFilter.selectedItem?.toString() ?: "All actions"
            val outcome = resultFilter.selectedItem?.toString() ?: "All results"
            val entries = (historyPreferences.getString("entries", "") ?: "").lines()
                .filter { it.isNotBlank() }
                .filter { row ->
                    val fields = row.split("|")
                    val actionText = fields.getOrElse(1) { "" }
                    val resultText = fields.getOrElse(2) { "" }
                    row.lowercase(Locale.ROOT).contains(query) &&
                        (action == "All actions" || actionText.contains(action, ignoreCase = true)) &&
                        (outcome == "All results" || resultText.equals(outcome, ignoreCase = true))
                }
            results.text = if (entries.isEmpty()) "No matching history entries.\n\nHistory stores metadata only — never passwords, tokens, or message contents."
                else entries.joinToString("\n\n") { row ->
                    val parts = row.split("|")
                    "TIME   " + parts.getOrElse(0) { "Unknown" } + "\nACTION " + parts.getOrElse(1) { "Unknown" } +
                        "\nRESULT " + parts.getOrElse(2) { "Unknown" } + "\nSLOT   " + parts.getOrElse(3) { "Unknown" }
                }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        actionFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = render()
        }
        resultFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = render()
        }
        wrapper.addView(search)
        wrapper.addView(actionFilter)
        wrapper.addView(resultFilter)
        val scroller = ScrollView(this).apply { addView(results) }
        wrapper.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        wrapper.addView(clearButton)
        render()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Activity History")
            .setView(wrapper)
            .setPositiveButton("Copy", null)
            .setNeutralButton("Export / Share", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = results.text.toString()
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("FM Runner Manager History", text))
                logAuditWithoutHistory("history_copied")
                Toast.makeText(this, "Filtered history copied.", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val body = (historyPreferences.getString("entries", "") ?: "").lines()
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { it.replace("|", ",") }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "FM Runner Manager Activity History")
                    putExtra(Intent.EXTRA_TEXT, "time,action,result,account_slot\n" + body)
                }
                logAuditWithoutHistory("history_export_share_opened")
                startActivity(Intent.createChooser(send, "Export History"))
            }
        }
        dialog.show()
    }

    private fun logAuditWithoutHistory(action: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = auditPreferences.getString("entries", "") ?: ""
        val next = (listOf(stamp + " | " + action) + old.lines().filter { it.isNotBlank() }).take(100)
        auditPreferences.edit().putString("entries", next.joinToString("\n")).apply()
    }

    private fun diagnosticsText(): String {
        val dataStats = StatFs(Environment.getDataDirectory().absolutePath)
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val battery = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Supported ABI: " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown") + "\n" +
            "CPU usage: " + (readCpuPercent().let { if (it < 0) "Unavailable" else it.toString() + "%" }) + "\n" +
            "Internal storage free: " + formatBytes(dataStats.availableBytes) + " / " + formatBytes(dataStats.totalBytes) + "\n" +
            "System-reported available RAM: " + formatBytes(memory.availMem) + " / " + formatBytes(memory.totalMem) + "\n" +
            "Battery: " + (if (percent < 0) "Unavailable" else percent.toString() + "%") + " • " + (if (charging) "Charging" else "Not charging") + "\n" +
            "GitHub connection: Not configured\n" +
            "Runner registration: Not configured\n" +
            "Repository/job adapter: Not implemented\n" +
            "FM Commerce PHP Lint result: " + (preferences.getString("php_lint_status", "UNVERIFIED") ?: "UNVERIFIED")
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 19f, ink, true).apply { setPadding(0, dp(12), 0, dp(8)) })
    }
    private fun formatBytes(bytes: Long): String =
        String.format(Locale.US, "%.2f GiB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    private fun aliasKey(index: Int) = "account_slot_" + index
    private fun defaultAlias(index: Int) = "Account slot " + index
    private fun marginParams(bottom: Int): ViewGroup.MarginLayoutParams =
        ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(bottom) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value; textSize = sizeSp; setTextColor(color); gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
}
