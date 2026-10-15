package com.fm.runnermanager

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.fm.runnermanager.adapters.GitHubDeviceFlowClient
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-first dashboard with explicit GitHub Device Flow authorization and read-only metadata. Runner controls remain disabled. */
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
    private val sessionMetadataPreferences by lazy { getSharedPreferences("github_session_metadata", MODE_PRIVATE) }
    private val tokenVault by lazy { EncryptedGitHubTokenVault(this) }
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
    private var activeAccountLabel: TextView? = null
    private val githubExecutor = Executors.newSingleThreadExecutor()
    private val githubTokensBySlot = mutableMapOf<Int, String>()
    private val githubReposBySlot = mutableMapOf<Int, List<GitHubDeviceFlowClient.Repository>>()
    private val githubClientIds = mutableMapOf<Int, String>()
    private val githubLoginsBySlot = mutableMapOf<Int, String>()
    private val selectedRepositoryBySlot = mutableMapOf<Int, String>()
    private var githubStatusLabel: TextView? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshMetrics()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedAccount = accountIds().first()
        renderDashboard()
        refreshMetrics()
        handler.postDelayed(refreshTask, 3000L)
        restorePersistedGitHubSessions()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        githubExecutor.shutdownNow()
        githubTokensBySlot.clear()
        super.onDestroy()
    }

    private fun renderDashboard() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(this@MainActivity.background)
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
        root.addView(text("One active GitHub account at a time. Other added accounts stay inactive until selected. Repositories are fetched automatically after authorization.", 13f, muted, false)
            .apply { setPadding(0, 0, 0, dp(7)) })
        val activeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(6), dp(7))
            background = roundedPanel(panel, 12)
        }
        val activeLabel = text(activeAccountDisplay(), 14f, 0xFF4DFF88.toInt(), true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        activeAccountLabel = activeLabel
        activeRow.addView(activeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        activeRow.addView(Button(this).apply {
            text = "Switch"
            setOnClickListener { showAccountSwitcher() }
        })
        root.addView(activeRow, marginParams(bottom = 8))
        val accountActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accountActions.addView(Button(this).apply {
            text = "Add & connect account"
            setOnClickListener { addAccount() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        accountActions.addView(Button(this).apply {
            text = "Remove selected"
            contentDescription = "danger-action"
            setOnClickListener { removeSelectedAccount() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        root.addView(accountActions, marginParams(bottom = 8))
        repoSpinner = Spinner(this).apply {
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                    selectedRepositoryBySlot.remove(selectedAccount)
                }
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val repos = githubReposBySlot[selectedAccount].orEmpty()
                    if (position in repos.indices) {
                        val repo = repos[position]
                        selectedRepositoryBySlot[selectedAccount] = repo.owner + "/" + repo.name
                    } else {
                        selectedRepositoryBySlot.remove(selectedAccount)
                    }
                }
            }
        }
        root.addView(repoSpinner, marginParams(bottom = 8))
        updateRepositorySpinner()
        section(root, "Runner & job activity")
        val runnerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedPanel(panel, 18)
        }
        githubStatusLabel = text("🔴 DISCONNECTED • Connect GitHub to begin", 14f, 0xFFFF6B6B.toInt(), true)
        runnerPanel.addView(githubStatusLabel!!)
        runnerPanel.addView(text("Runner control: not connected to DroidRunner service yet; Start/Stop will not launch or stop the runner.", 13f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        runnerPanel.addView(Button(this).apply {
            text = "🟢 Connect GitHub"
            setOnClickListener { beginGitHubConnection() }
        }, marginParams(bottom = 6))
        runnerPanel.addView(Button(this).apply {
            text = "🔴 Disconnect GitHub"
            contentDescription = "danger-action"
            setOnClickListener { disconnectSelectedAccount() }
        }, marginParams(bottom = 6))
        runnerPanel.addView(Button(this).apply {
            text = "Refresh repositories / runner metadata"
            setOnClickListener { refreshGitHubMetadata() }
        }, marginParams(bottom = 6))
        runnerPanel.addView(text("Workflow runs and runner details load only after GitHub authorization and sufficient read permissions.", 13f, muted, false)
            .apply { setPadding(0, dp(5), 0, dp(8)) })
        runnerPanel.addView(Button(this).apply {
            text = "View runner / job integration status"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Runner controls disabled")
                    .setMessage("GitHub read-only metadata can be loaded after authorization. This screen does not start, stop, register, or dispatch runners. Those controls remain disabled.")
                    .setPositiveButton("Close", null).show()
                recordActivity("runner_integration_unavailable_viewed")
                logAudit("runner_integration_unavailable_viewed")
            }
        })
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow.addView(Button(this).apply {
            text = "Start runner"
            contentDescription = "runner-control-unavailable"
            setOnClickListener { showRunnerControlUnavailable("start") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        controlRow.addView(Button(this).apply {
            text = "Stop runner"
            contentDescription = "runner-control-unavailable"
            setOnClickListener { showRunnerControlUnavailable("stop") }
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
                recordActivity("diagnostics_copied")
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
        root.addView(text("Safety: access token memory-only • no credentials logged • no packages installed • runner controls disabled",
            12f, muted, false).apply { setPadding(0, dp(10), 0, 0) })
        styleTree(root)
    }

    private fun styleTree(view: View) {
        when (view) {
            is Button -> {
                val danger = view.contentDescription == "danger-action"
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(if (danger) 0xFFFF4D5A.toInt() else accent)
                view.setTextColor(if (danger) 0xFFFFFFFF.toInt() else 0xFF071426.toInt())
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

    /** Consistent high-contrast neon text for every spinner and dropdown in the app. */
    private fun neonAdapter(items: List<String>, itemTextSizeSp: Float = 14f): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            private fun styleItem(view: View): View {
                (view as? TextView)?.apply {
                    setTextColor(accent)
                    textSize = itemTextSizeSp
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(panel)
                }
                return view
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                styleItem(super.getView(position, convertView, parent))

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                styleItem(super.getDropDownView(position, convertView, parent))
        }

    private fun roundedPanel(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), border)
        }

    private fun accountIds(): List<Int> {
        val raw = preferences.getString("account_ids", "1").orEmpty()
        val ids = raw.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()
        return ids.ifEmpty { listOf(1) }
    }

    private fun saveAccountIds(ids: List<Int>) {
        preferences.edit().putString("account_ids", ids.distinct().filter { it > 0 }.joinToString(",")).apply()
    }

    private fun accountLabels(): List<String> = accountIds().map { id ->
        val login = githubLoginsBySlot[id]
        val connected = githubTokensBySlot.containsKey(id) && login != null
        "Account " + id + " • " + (login ?: "Not connected") +
            if (id == selectedAccount) " • ACTIVE" else if (connected) " • inactive (connected)" else " • inactive"
    }

    private fun activeAccountDisplay(): String {
        val login = githubLoginsBySlot[selectedAccount]
        return if (githubTokensBySlot.containsKey(selectedAccount) && login != null)
            "🟢 ACTIVE • " + login + " • Account " + selectedAccount
        else "🔴 ACTIVE SLOT • Account " + selectedAccount + " • Not connected"
    }

    private fun updateActiveAccountDisplay() {
        activeAccountLabel?.apply {
            text = activeAccountDisplay()
            setTextColor(if (githubTokensBySlot.containsKey(selectedAccount) && githubLoginsBySlot[selectedAccount] != null)
                0xFF4DFF88.toInt() else 0xFFFF6B6B.toInt())
        }
    }

    private fun showAccountSwitcher() {
        val ids = accountIds()
        val labels = ids.map { id ->
            val login = githubLoginsBySlot[id]
            val connected = githubTokensBySlot.containsKey(id) && login != null
            (if (id == selectedAccount) "🟢 ACTIVE  •  " else "○ INACTIVE  •  ") +
                "Account " + id + "  •  " + (login ?: "Not connected") +
                if (connected && id != selectedAccount) " (session ready)" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose one active GitHub account")
            .setSingleChoiceItems(labels, ids.indexOf(selectedAccount).coerceAtLeast(0)) { dialog, which ->
                if (which in ids.indices) {
                    val previous = selectedAccount
                    selectedAccount = ids[which]
                    updateActiveAccountDisplay()
                    updateRepositorySpinner()
                    updateGitHubConnectionLabel()
                    recordActivity("github_active_account_switched", "SUCCESS", selectedAccount)
                    logAudit("github_active_account_switched")
                    dialog.dismiss()
                    if (previous != selectedAccount && githubTokensBySlot.containsKey(selectedAccount)) {
                        refreshGitHubMetadata()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addAccount() {
        val ids = accountIds()
        val nextId = (ids.maxOrNull() ?: 0) + 1
        saveAccountIds(ids + nextId)
        selectedAccount = nextId
        recordActivity("github_account_entry_added")
        logAudit("github_account_entry_added")
        renderDashboard()
        beginGitHubConnection()
    }

    private fun disconnectSelectedAccount() {
        val slot = selectedAccount
        if (!githubTokensBySlot.containsKey(slot)) {
            Toast.makeText(this, "This account slot is already disconnected.", Toast.LENGTH_SHORT).show()
            updateGitHubConnectionLabel()
            return
        }
        githubTokensBySlot.remove(slot)
        tokenVault.remove(slot)
        sessionMetadataPreferences.edit().remove("client_id_slot_" + slot).apply()
        githubReposBySlot.remove(slot)
        githubClientIds.remove(slot)
        githubLoginsBySlot.remove(slot)
        selectedRepositoryBySlot.remove(slot)
        updateRepositorySpinner()
        updateGitHubConnectionLabel()
        updateActiveAccountDisplay()
        recordActivity("github_account_disconnected_local_session_cleared", "SUCCESS", slot)
        logAudit("github_account_disconnected_local_session_cleared")
        Toast.makeText(this, "Disconnected locally. GitHub authorization was not revoked.", Toast.LENGTH_LONG).show()
    }

    private fun showRunnerControlUnavailable(action: String) {
        AlertDialog.Builder(this)
            .setTitle("Runner " + action + " is not connected")
            .setMessage("No action was taken. This app currently reads GitHub metadata but has no verified control bridge to the DroidRunner process on this phone. GitHub authorization alone cannot start or stop a local runner. The DroidRunner control interface must be integrated and tested before this button can safely control it.")
            .setPositiveButton("Close", null)
            .show()
        recordActivity("runner_" + action + "_blocked_no_control_bridge", "UNAVAILABLE", selectedAccount)
        logAudit("runner_" + action + "_blocked_no_control_bridge")
    }

    private fun removeSelectedAccount() {
        val ids = accountIds()
        if (ids.size <= 1) {
            Toast.makeText(this, "Keep at least one account entry. Add another before removing this one.", Toast.LENGTH_LONG).show()
            return
        }
        val id = selectedAccount
        AlertDialog.Builder(this)
            .setTitle("Disconnect and remove account?")
            .setMessage("This clears the account's in-memory token, repository list and local label from this app. It does not revoke authorization at GitHub; revoke the app in GitHub settings if needed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                githubTokensBySlot.remove(id)
                tokenVault.remove(id)
                sessionMetadataPreferences.edit().remove("client_id_slot_" + id).apply()
                githubReposBySlot.remove(id)
                githubClientIds.remove(id)
                githubLoginsBySlot.remove(id)
                selectedRepositoryBySlot.remove(id)
                preferences.edit().remove(aliasKey(id)).apply()
                val remaining = ids.filterNot { it == id }
                saveAccountIds(remaining)
                selectedAccount = remaining.first()
                recordActivity("github_account_removed_local_session_cleared")
                logAudit("github_account_removed_local_session_cleared")
                renderDashboard()
            }.show()
    }

    private fun updateRepositorySpinner() {
        val spinner = repoSpinner ?: return
        val repos = githubReposBySlot[selectedAccount].orEmpty()
        val labels = if (repos.isEmpty()) listOf("Repositories not loaded — connect this account first")
            else repos.map { it.owner + "/" + it.name + if (it.isPrivate) " • private" else " • public" }
        // Repository paths can be long (owner/repo); use compact text so the full path fits.
        spinner.adapter = neonAdapter(labels, 11f)
        spinner.isEnabled = repos.isNotEmpty()
        val selected = selectedRepositoryBySlot[selectedAccount]
        val index = repos.indexOfFirst { it.owner + "/" + it.name == selected }
        if (index >= 0) spinner.setSelection(index)
        else if (repos.isNotEmpty()) {
            spinner.setSelection(0)
            selectedRepositoryBySlot[selectedAccount] = repos.first().owner + "/" + repos.first().name
        }
    }

    private fun updateGitHubConnectionLabel() {
        val login = githubLoginsBySlot[selectedAccount]
        githubStatusLabel?.apply {
            if (githubTokensBySlot.containsKey(selectedAccount) && login != null) {
                text = "🟢 CONNECTED • " + login + " • read-only"
                setTextColor(0xFF4DFF88.toInt())
            } else {
                text = "🔴 DISCONNECTED • Connect GitHub to begin"
                setTextColor(0xFFFF6B6B.toInt())
            }
        }
    }

    /**
     * Restore saved sessions on launch. GitHub is always consulted before showing CONNECTED.
     * Transient failures keep encrypted credentials and never issue runner stop/start commands.
     */
    private fun restorePersistedGitHubSessions() {
        for (slot in accountIds()) {
            if (!tokenVault.contains(slot)) continue
            val token = tokenVault.load(slot)
            if (token.isNullOrBlank()) {
                if (selectedAccount == slot) githubStatusLabel?.apply {
                    text = "🟠 SAVED SESSION • Cannot decrypt; reconnect required"
                    setTextColor(0xFFFFB454.toInt())
                }
                recordActivity("github_session_restore_unavailable", "FAILED", slot)
                logAudit("github_session_restore_unavailable")
                continue
            }
            githubTokensBySlot[slot] = token
            val clientId = sessionMetadataPreferences.getString(
                "client_id_slot_" + slot, BuildConfig.GITHUB_APP_CLIENT_ID.trim()
            ).orEmpty()
            if (clientId.isNotBlank()) githubClientIds[slot] = clientId
            if (selectedAccount == slot) githubStatusLabel?.apply {
                text = "🟡 RECONNECTING • Verifying saved GitHub session…"
                setTextColor(0xFFFFD166.toInt())
            }
            githubExecutor.execute {
                try {
                    require(clientId.isNotBlank()) { "GitHub Client ID unavailable" }
                    val client = GitHubDeviceFlowClient(clientId)
                    val identity = client.identity(token)
                    val repos = client.repositories(token)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        githubTokensBySlot[slot] = token
                        githubLoginsBySlot[slot] = identity.login
                        githubReposBySlot[slot] = repos
                        if (selectedAccount == slot) {
                            updateGitHubConnectionLabel()
                            updateRepositorySpinner()
                            githubStatusLabel?.apply {
                                text = "🟢 CONNECTED • " + identity.login + " • restored session • read-only"
                                setTextColor(0xFF4DFF88.toInt())
                            }
                        }
                        updateActiveAccountDisplay()
                        recordActivity("github_session_auto_reconnected", "SUCCESS", slot)
                        logAudit("github_session_auto_reconnected")
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (selectedAccount == slot) githubStatusLabel?.apply {
                            text = "🟠 RECONNECTING • Verification failed; saved session kept"
                            setTextColor(0xFFFFB454.toInt())
                        }
                        recordActivity("github_session_auto_reconnect_failed_token_preserved", "FAILED", slot)
                        logAudit("github_session_auto_reconnect_failed_token_preserved")
                    }
                }
            }
        }
    }

    private fun beginGitHubConnection() {
        val slot = selectedAccount
        val configuredClientId = BuildConfig.GITHUB_APP_CLIENT_ID.trim()
        if (configuredClientId.isNotBlank()) {
            // Public app builds carry their own public Client ID; users never enter it.
            githubClientIds[slot] = configuredClientId
            startDeviceFlow(slot, configuredClientId)
            return
        }

        // Developer fallback only: production/public builds should configure the ID at build time.
        val input = EditText(this).apply {
            hint = "Client ID (developer setup only)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(githubClientIds[slot].orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("GitHub App setup required")
            .setMessage("This build has no GitHub Client ID configured. Set GITHUB_APP_CLIENT_ID in the build environment. For local developer testing only, enter the registered public Client ID. OAuth Apps need the repo scope for private repositories; GitHub Apps need Metadata: read and access to the intended repositories configured in GitHub. This app cannot grant itself repository access. Never enter a password, PAT, client secret, or runner token.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val clientId = input.text.toString().trim()
                if (clientId.isBlank()) {
                    Toast.makeText(this, "Configure the GitHub App Client ID in this build.", Toast.LENGTH_LONG).show()
                } else {
                    githubClientIds[slot] = clientId
                    startDeviceFlow(slot, clientId)
                }
            }.show()
    }

    private fun startDeviceFlow(slot: Int, clientId: String) {
        githubStatusLabel?.apply { text = "🟡 CONNECTING • Requesting GitHub authorization…"; setTextColor(0xFFFFD166.toInt()) }
        githubExecutor.execute {
            try {
                val client = GitHubDeviceFlowClient(clientId)
                val device = client.requestDeviceCode()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("Authorize GitHub account " + slot)
                        .setMessage("Open " + device.verificationUri + "\nEnter one-time code:\n" + device.userCode + "\n\nApprove only the intended GitHub account.")
                        .setPositiveButton("Open page & wait") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(device.verificationUri))) } catch (_: Exception) { }
                            pollDeviceFlow(client, device.deviceCode, device.expiresInSeconds, device.intervalSeconds, slot)
                        }
                        .setNeutralButton("I've opened it") { _, _ -> pollDeviceFlow(client, device.deviceCode, device.expiresInSeconds, device.intervalSeconds, slot) }
                        .setNegativeButton("Cancel") { _, _ ->
                            githubStatusLabel?.apply { text = "🔴 DISCONNECTED • Authorization cancelled"; setTextColor(0xFFFF6B6B.toInt()) }
                            recordActivity("github_device_authorization_cancelled", "CANCELLED", slot)
                        }.show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    githubStatusLabel?.apply { text = "🔴 ERROR • Authorization request failed"; setTextColor(0xFFFF6B6B.toInt()) }
                    Toast.makeText(this, "Check Client ID, Device Flow settings, and network.", Toast.LENGTH_LONG).show()
                    recordActivity("github_device_authorization_failed", "FAILED", slot)
                    logAudit("github_device_authorization_failed")
                }
            }
        }
    }

    private fun pollDeviceFlow(client: GitHubDeviceFlowClient, deviceCode: String, expiresIn: Long, initialInterval: Long, slot: Int) {
        runOnUiThread { if (selectedAccount == slot) githubStatusLabel?.apply { text = "🟡 CONNECTING • Waiting for browser approval…"; setTextColor(0xFFFFD166.toInt()) } }
        githubExecutor.execute {
            val deadline = System.currentTimeMillis() + expiresIn.coerceAtLeast(1L) * 1000L
            var interval = initialInterval.coerceAtLeast(1L)
            try {
                while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L)
                    val result = client.pollDeviceToken(deviceCode)
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        val identity = client.identity(token)
                        val repos = client.repositories(token)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            try {
                                tokenVault.save(slot, token)
                                sessionMetadataPreferences.edit().putString("client_id_slot_" + slot, clientId).apply()
                            } catch (_: Exception) {
                                recordActivity("github_session_secure_save_failed", "FAILED", slot)
                                logAudit("github_session_secure_save_failed")
                            }
                            githubTokensBySlot[slot] = token
                            githubReposBySlot[slot] = repos
                            githubLoginsBySlot[slot] = identity.login
                            updateActiveAccountDisplay()
                            if (selectedAccount == slot) {
                                updateGitHubConnectionLabel()
                                updateRepositorySpinner()
                            }
                            recordActivity("github_account_connected_readonly", "SUCCESS", slot)
                            logAudit("github_account_connected_readonly")
                            Toast.makeText(this, "Connected " + identity.login + "; loaded " + repos.size + " repositories. Fetching workflow and runner metadata…", Toast.LENGTH_LONG).show()
                            if (selectedAccount == slot) refreshGitHubMetadata()
                        }
                        return@execute
                    }
                    when (result.error) {
                        "authorization_pending" -> Unit
                        "slow_down" -> interval += 5L
                        "expired_token" -> throw IllegalStateException("Device code expired")
                        "access_denied" -> throw IllegalStateException("Authorization denied")
                        "incorrect_client_credentials" -> throw IllegalStateException("Invalid Client ID")
                    }
                    if (result.intervalSeconds != null) interval = maxOf(interval, result.intervalSeconds)
                }
                throw IllegalStateException("Device authorization timed out")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (selectedAccount == slot) githubStatusLabel?.apply { text = "🔴 DISCONNECTED • Authorization failed"; setTextColor(0xFFFF6B6B.toInt()) }
                    Toast.makeText(this, "Authorization failed or timed out. Verify app configuration and retry.", Toast.LENGTH_LONG).show()
                    recordActivity("github_device_authorization_failed", "FAILED", slot)
                    logAudit("github_device_authorization_failed")
                }
            }
        }
    }

    private fun refreshGitHubMetadata() {
        val slot = selectedAccount
        val token = githubTokensBySlot[slot]
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "Connect the selected account slot first.", Toast.LENGTH_SHORT).show()
            return
        }
        val clientId = githubClientIds[slot] ?: return
        githubStatusLabel?.apply { text = "🟡 CONNECTING • Refreshing GitHub metadata…"; setTextColor(0xFFFFD166.toInt()) }
        githubExecutor.execute {
            try {
                val client = GitHubDeviceFlowClient(clientId)
                val identity = client.identity(token)
                val repos = client.repositories(token)
                // Automatically load metadata for the first repository when no prior choice exists.
                val selectedPath = selectedRepositoryBySlot[slot]
                    ?: repos.firstOrNull()?.let { it.owner + "/" + it.name }
                val selectedRepo = repos.firstOrNull { it.owner + "/" + it.name == selectedPath }
                val runs = if (selectedRepo == null) emptyList() else client.workflowRuns(token, selectedRepo.owner, selectedRepo.name)
                // Runner listing can require stronger repository permissions than workflow-run metadata.
                // Keep successful workflow data usable if the runner endpoint is unavailable.
                val runnerResult = if (selectedRepo == null) null
                    else runCatching { client.runners(token, selectedRepo.owner, selectedRepo.name) }
                val runners = runnerResult?.getOrNull().orEmpty()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    githubReposBySlot[slot] = repos
                    githubLoginsBySlot[slot] = identity.login
                    if (selectedRepo != null) selectedRepositoryBySlot[slot] = selectedRepo.owner + "/" + selectedRepo.name
                    updateActiveAccountDisplay()
                    if (selectedAccount == slot) {
                        updateRepositorySpinner()
                        githubStatusLabel?.apply {
                            text = if (selectedRepo == null)
                                "🟢 CONNECTED • " + identity.login + " • " + repos.size + " repos • select one for metadata"
                            else
                                "🟢 CONNECTED • " + identity.login + " • " + selectedRepo.owner + "/" + selectedRepo.name +
                                    " • " + runs.size + " workflow runs / " +
                                    if (runnerResult?.isSuccess == true) runners.size.toString() + " runners"
                                    else "runner metadata unavailable (permissions or endpoint)"
                            setTextColor(0xFF4DFF88.toInt())
                        }
                    }
                    recordActivity("github_readonly_metadata_refreshed", "SUCCESS", slot)
                    logAudit("github_readonly_metadata_refreshed")
                    if (selectedAccount == slot) {
                        val runnerSummary = if (runnerResult?.isSuccess == true) runners.size.toString() + " runners"
                            else "runner details unavailable"
                        Toast.makeText(this, "Refreshed " + repos.size + " repositories" +
                            if (selectedRepo != null) ", " + runs.size + " workflow runs, " + runnerSummary else "",
                            Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (selectedAccount == slot) githubStatusLabel?.apply { text = "🔴 ERROR • Metadata refresh failed"; setTextColor(0xFFFF6B6B.toInt()) }
                    Toast.makeText(this, "Metadata refresh failed. Check token permissions and network.", Toast.LENGTH_LONG).show()
                    recordActivity("github_readonly_metadata_refresh_failed", "FAILED", slot)
                    logAudit("github_readonly_metadata_refresh_failed")
                }
            }
        }
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
            // The dashboard uses a dark panel; explicitly set high-contrast text so these
            // three policy rows remain readable above the Minimum battery selector.
            setTextColor(ink)
            textSize = 14f
            setPadding(dp(2), dp(7), dp(2), dp(7))
            minimumHeight = dp(48)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                thumbTintList = android.content.res.ColorStateList.valueOf(accent)
                trackTintList = android.content.res.ColorStateList.valueOf(0xFF3A627D.toInt())
            }
            isChecked = policyPreferences.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                policyPreferences.edit().putBoolean(key, checked).apply()
                recordActivity("local_policy_" + key + "_" + if (checked) "enabled" else "disabled")
                logAudit("local_policy_" + key + "_" + if (checked) "enabled" else "disabled")
            }
        }, marginParams(bottom = 5))
    }

    private fun addChoice(parent: LinearLayout, title: String, key: String, labels: Array<String>, values: Array<Int>, defaultValue: Int) {
        parent.addView(text(title, 14f, ink, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        val spinner = Spinner(this)
        spinner.adapter = neonAdapter(labels.toList())
        spinner.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        spinner.setBackgroundColor(panel)
        val saved = policyPreferences.getInt(key, defaultValue)
        spinner.setSelection(values.indexOf(saved).takeIf { it >= 0 } ?: 0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var initial = true
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parentView: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initial) { initial = false; return }
                policyPreferences.edit().putInt(key, values[position]).apply()
                recordActivity("local_policy_" + key + "_changed")
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
            .setTitle("Account " + index + " label")
            .setMessage("Save a local display label only. This does not connect or authorize a GitHub account.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                preferences.edit().remove(aliasKey(index)).apply()
                recordActivity("github_account_" + index + "_label_reset")
                logAudit("account_slot_" + index + "_label_reset")
                renderDashboard()
            }
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().take(48)
                if (value.isEmpty()) {
                    preferences.edit().remove(aliasKey(index)).apply()
                    recordActivity("github_account_" + index + "_label_cleared")
                    logAudit("account_slot_" + index + "_label_cleared")
                } else {
                    preferences.edit().putString(aliasKey(index), value).apply()
                    recordActivity("github_account_" + index + "_label_saved")
                    logAudit("account_slot_" + index + "_label_saved")
                }
                renderDashboard()
                Toast.makeText(this, "Local label saved; account remains disconnected.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showAuditLog() {
        val entries = auditPreferences.getString("entries", "") ?: ""
        val displayText = if (entries.isBlank()) "No local security/admin events recorded yet." else entries
        AlertDialog.Builder(this).setTitle("Local Security Audit Log")
            .setMessage(displayText)
            .setPositiveButton("Close", null)
            .setNegativeButton("Copy log") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Local Security Audit Log", displayText))
                Toast.makeText(this@MainActivity, "Audit log copied to clipboard.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear log…") { _, _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Clear Security Audit Log?")
                    .setMessage("This removes local security/admin event history. Activity History remains separate. This action cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear Audit Log") { _, _ ->
                        auditPreferences.edit().remove("entries").apply()
                        logAuditWithoutHistory("audit_log_cleared_after_confirmation")
                        Toast.makeText(this@MainActivity, "Audit Log cleared after confirmation.", Toast.LENGTH_SHORT).show()
                    }.show()
            }.show()
    }

    private fun logAudit(action: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = auditPreferences.getString("entries", "") ?: ""
        val next = (listOf(stamp + " | " + action) + old.lines().filter { it.isNotBlank() }).take(100)
        auditPreferences.edit().putString("entries", next.joinToString("\n")).apply()
    }

    private fun recordActivity(
        action: String,
        resultOverride: String? = null,
        accountSlot: Int = selectedAccount
    ) {
        val result = resultOverride ?: when {
            action.contains("unavailable", ignoreCase = true) -> "Unavailable"
            action.contains("failed", ignoreCase = true) || action.contains("error", ignoreCase = true) -> "Failed"
            listOf("copied", "saved", "reset", "enabled", "disabled", "changed", "cleared").any { action.contains(it, ignoreCase = true) } -> "Success"
            else -> "Recorded"
        }
        logHistory(action, result, accountSlot)
    }

    private fun logHistory(action: String, result: String, accountSlot: Int) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeAction = action.replace("|", "/").take(80)
        val safeResult = result.replace("|", "/").take(60)
        val entry = listOf(stamp, safeAction, safeResult, "Account " + accountSlot).joinToString("|")
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
            setTextColor(ink)
            setHintTextColor(muted)
        }
        val dateFilter = EditText(this).apply {
            hint = "Filter date (YYYY-MM-DD)"
            setSingleLine(true)
            setTextColor(0xFF172033.toInt())
            setHintTextColor(0xFF66758A.toInt())
        }
        val actionFilter = Spinner(this).apply {
            adapter = neonAdapter(listOf("All actions", "account", "diagnostics", "policy", "runner", "history"))
        }
        val resultFilter = Spinner(this).apply {
            adapter = neonAdapter(listOf("All results", "Recorded", "Success", "Failed", "Unavailable"))
        }
        val results = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF172033.toInt())
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
            val dateQuery = dateFilter.text.toString().trim()
            val action = actionFilter.selectedItem?.toString() ?: "All actions"
            val outcome = resultFilter.selectedItem?.toString() ?: "All results"
            val entries = (historyPreferences.getString("entries", "") ?: "").lines()
                .filter { it.isNotBlank() }
                .filter { row ->
                    val fields = row.split("|")
                    val actionText = fields.getOrElse(1) { "" }
                    val resultText = fields.getOrElse(2) { "" }
                    row.lowercase(Locale.ROOT).contains(query) &&
                        (dateQuery.isEmpty() || fields.getOrElse(0) { "" }.startsWith(dateQuery)) &&
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
        dateFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
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
        wrapper.addView(dateFilter)
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
            "GitHub connection: " + (if (githubTokensBySlot.containsKey(selectedAccount) && githubLoginsBySlot[selectedAccount] != null)
                "Connected read-only as " + githubLoginsBySlot[selectedAccount] + " (Account " + selectedAccount + ")"
            else "Not connected in this app session (Account " + selectedAccount + ")") + "\n" +
            "Repositories loaded: " + githubReposBySlot[selectedAccount].orEmpty().size + "\n" +
            "Runner registration: Not configured (control bridge not implemented)\n" +
            "Repository/job adapter: Not implemented\n" +
            "FM Commerce PHP Lint result: " + (preferences.getString("php_lint_status", "UNVERIFIED") ?: "UNVERIFIED")
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 19f, ink, true).apply { setPadding(0, dp(12), 0, dp(8)) })
    }
    private fun formatBytes(bytes: Long): String =
        String.format(Locale.US, "%.2f GiB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    private fun aliasKey(index: Int) = "account_slot_" + index
    private fun defaultAlias(index: Int) = "Account " + index
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
