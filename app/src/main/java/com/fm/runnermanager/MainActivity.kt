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
import com.fm.runnermanager.security.CredentialVault
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
    private val credentialVault by lazy { CredentialVault(this) }
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
    private var githubAccountSpinner: Spinner? = null
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
        val savedSlot = preferences.getInt("selected_account_slot", 1)
        selectedAccount = savedSlot.takeIf { it in accountIds() } ?: accountIds().first()
        renderDashboard()
        refreshMetrics()
        handler.postDelayed(refreshTask, 3000L)
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
        root.addView(text("Add as many GitHub accounts as you need. Each account authorizes independently; only repositories visible to that account are listed.", 13f, muted, false)
            .apply { setPadding(0, 0, 0, dp(7)) })
        val accountSpinner = Spinner(this)
        accountSpinner.adapter = neonAdapter(accountLabels())
        accountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val ids = accountIds()
                if (position in ids.indices) {
                    val nextSlot = ids[position]
                    val changed = selectedAccount != nextSlot
                    selectedAccount = nextSlot
                    preferences.edit().putInt("selected_account_slot", nextSlot).apply()
                    if (changed || !githubTokensBySlot.containsKey(nextSlot)) activateAccountSlot(nextSlot)
                    updateRepositorySpinner()
                    updateGitHubConnectionLabel()
                }
            }
        }
        accountSpinner.setSelection(accountIds().indexOf(selectedAccount).coerceAtLeast(0), false)
        githubAccountSpinner = accountSpinner
        root.addView(accountSpinner, marginParams(bottom = 8))
        val accountActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accountActions.addView(Button(this).apply {
            text = "Add account"
            setOnClickListener { addAccount() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        accountActions.addView(Button(this).apply {
            text = "Disconnect / remove"
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
        githubStatusLabel = text("GitHub connection: Not configured", 14f, ink, true)
        runnerPanel.addView(githubStatusLabel!!)
        runnerPanel.addView(text("Runner status: Read-only metadata only; Start/Stop disabled", 13f, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        runnerPanel.addView(Button(this).apply {
            text = "Connect GitHub (read-only)"
            setOnClickListener { beginGitHubConnection() }
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
        root.addView(text("Saved locally only. FM Runner Manager has no active runner runtime yet; these settings are not enforced.", 13f, muted, false)
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
        root.addView(text("Safety: selected account token encrypted at rest • no credentials logged • no packages installed • runner controls disabled",
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

    /** Consistent high-contrast neon text for every spinner and dropdown in the app. */
    private fun neonAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            private fun styleItem(view: View): View {
                (view as? TextView)?.apply {
                    setTextColor(accent)
                    textSize = 14f
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
        val alias = preferences.getString(aliasKey(id), defaultAlias(id)) ?: defaultAlias(id)
        val login = githubLoginsBySlot[id] ?: preferences.getString(githubLoginKey(id), null)
        "Account " + id + " • " + (login ?: alias) + if (githubTokensBySlot.containsKey(id)) " • active" else if (login != null) " • saved" else ""
    }

    private fun addAccount() {
        val ids = accountIds()
        val nextId = (ids.maxOrNull() ?: 0) + 1
        saveAccountIds(ids + nextId)
        selectedAccount = nextId
        preferences.edit().putInt("selected_account_slot", nextId).apply()
        recordActivity("github_account_entry_added")
        logAudit("github_account_entry_added")
        renderDashboard()
        Toast.makeText(this, "Account entry added. Connect it to authorize GitHub.", Toast.LENGTH_SHORT).show()
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
                runCatching { credentialVault.clear(id) }
                githubReposBySlot.remove(id)
                githubClientIds.remove(id)
                githubLoginsBySlot.remove(id)
                selectedRepositoryBySlot.remove(id)
                preferences.edit().remove(aliasKey(id)).remove(githubLoginKey(id)).remove(githubClientIdKey(id)).apply()
                val remaining = ids.filterNot { it == id }
                saveAccountIds(remaining)
                selectedAccount = remaining.first()
                preferences.edit().putInt("selected_account_slot", selectedAccount).apply()
                recordActivity("github_account_removed_local_session_cleared")
                logAudit("github_account_removed_local_session_cleared")
                renderDashboard()
            }.show()
    }

    private fun githubLoginKey(slot: Int) = "github_login_$slot"
    private fun githubClientIdKey(slot: Int) = "github_client_id_$slot"

    /** Only the selected slot may hold a decrypted token in memory. */
    private fun activateAccountSlot(slot: Int) {
        githubTokensBySlot.clear()
        githubReposBySlot.remove(slot)
        selectedRepositoryBySlot.remove(slot)
        updateRepositorySpinner()
        val encryptedTokenExists = runCatching { credentialVault.load(slot) }.getOrNull()
        if (encryptedTokenExists.isNullOrBlank()) {
            updateGitHubConnectionLabel()
            return
        }
        githubStatusLabel?.text = "GitHub connection: Verifying saved session…"
        githubExecutor.execute {
            try {
                val token = credentialVault.load(slot) ?: throw IllegalStateException("Saved session unavailable")
                val clientId = githubClientIds[slot] ?: preferences.getString(githubClientIdKey(slot), null) ?: BuildConfig.GITHUB_APP_CLIENT_ID.trim()
                if (clientId.isBlank()) throw IllegalStateException("GitHub App Client ID not configured")
                val client = GitHubDeviceFlowClient(clientId)
                val identity = client.identity(token)
                val repos = client.repositories(token)
                runOnUiThread {
                    if (isFinishing || isDestroyed || selectedAccount != slot) return@runOnUiThread
                    githubTokensBySlot.clear()
                    githubTokensBySlot[slot] = token
                    githubClientIds[slot] = clientId
                    githubLoginsBySlot[slot] = identity.login
                    githubReposBySlot[slot] = repos
                    preferences.edit().putString(githubClientIdKey(slot), clientId).putString(githubLoginKey(slot), identity.login).apply()
                    githubAccountSpinner?.adapter = neonAdapter(accountLabels())
                    githubAccountSpinner?.setSelection(accountIds().indexOf(slot).coerceAtLeast(0), false)
                    updateRepositorySpinner()
                    updateGitHubConnectionLabel()
                    recordActivity("github_saved_session_verified", "SUCCESS", slot)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed || selectedAccount != slot) return@runOnUiThread
                    githubTokensBySlot.clear()
                    githubStatusLabel?.text = "GitHub session needs verification; reconnect if offline or revoked"
                    recordActivity("github_saved_session_verification_unavailable", "UNVERIFIED", slot)
                    logAudit("github_saved_session_verification_unavailable")
                }
            }
        }
    }

    private fun updateRepositorySpinner() {
        val spinner = repoSpinner ?: return
        val repos = githubReposBySlot[selectedAccount].orEmpty()
        val labels = if (repos.isEmpty()) listOf("Repositories not loaded — connect this account first")
            else repos.map { it.owner + "/" + it.name + if (it.isPrivate) " • private" else " • public" }
        spinner.adapter = neonAdapter(labels)
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
        githubStatusLabel?.text = if (githubTokensBySlot.containsKey(selectedAccount) && login != null)
            "GitHub connection: " + login + " • read-only"
        else "GitHub connection: Not connected"
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
            .setMessage("This build has no GitHub App Client ID configured. Set GITHUB_APP_CLIENT_ID in the build environment. For local developer testing only, you may enter a public Client ID here. Never enter a password, PAT, client secret, or runner token.")
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
        githubStatusLabel?.text = "GitHub connection: Requesting device authorization…"
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
                            githubStatusLabel?.text = "GitHub connection: Authorization cancelled"
                            recordActivity("github_device_authorization_cancelled", "CANCELLED", slot)
                        }.show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    githubStatusLabel?.text = "GitHub connection: Authorization request failed"
                    Toast.makeText(this, "Check Client ID, Device Flow settings, and network.", Toast.LENGTH_LONG).show()
                    recordActivity("github_device_authorization_failed", "FAILED", slot)
                    logAudit("github_device_authorization_failed")
                }
            }
        }
    }

    private fun pollDeviceFlow(client: GitHubDeviceFlowClient, deviceCode: String, expiresIn: Long, initialInterval: Long, slot: Int) {
        githubStatusLabel?.text = "GitHub connection: Waiting for browser approval…"
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
                        try {
                            credentialVault.save(slot, token)
                        } catch (_: Exception) {
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (selectedAccount == slot) githubStatusLabel?.text = "GitHub connection: Secure session storage failed"
                                recordActivity("github_credential_vault_save_failed", "FAILED", slot)
                                logAudit("github_credential_vault_save_failed")
                                Toast.makeText(this, "Secure session storage failed. Please retry; the token was not retained.", Toast.LENGTH_LONG).show()
                            }
                            return@execute
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            githubTokensBySlot.clear()
                            if (selectedAccount == slot) githubTokensBySlot[slot] = token
                            githubReposBySlot[slot] = repos
                            githubLoginsBySlot[slot] = identity.login
                            preferences.edit().putString(githubLoginKey(slot), identity.login).apply()
                            githubAccountSpinner?.adapter = neonAdapter(accountLabels())
                            githubAccountSpinner?.setSelection(accountIds().indexOf(selectedAccount).coerceAtLeast(0), false)
                            if (selectedAccount == slot) {
                                githubStatusLabel?.text = "GitHub connection: " + identity.login + " • read-only"
                                updateRepositorySpinner()
                            }
                            recordActivity("github_account_connected_readonly", "SUCCESS", slot)
                            logAudit("github_account_connected_readonly")
                            Toast.makeText(this, "Connected " + identity.login + "; loaded " + repos.size + " repositories. Session is encrypted locally; GitHub may still revoke or expire it.", Toast.LENGTH_LONG).show()
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
                    if (selectedAccount == slot) githubStatusLabel?.text = "GitHub connection: Not connected"
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
        githubStatusLabel?.text = "GitHub connection: Refreshing read-only metadata…"
        githubExecutor.execute {
            try {
                val client = GitHubDeviceFlowClient(clientId)
                val identity = client.identity(token)
                val repos = client.repositories(token)
                val selectedPath = selectedRepositoryBySlot[slot]
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
                    githubAccountSpinner?.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, accountLabels())
                    githubAccountSpinner?.setSelection(accountIds().indexOf(selectedAccount).coerceAtLeast(0), false)
                    if (selectedAccount == slot) {
                        updateRepositorySpinner()
                        githubStatusLabel?.text = if (selectedRepo == null)
                            "GitHub: " + identity.login + " • " + repos.size + " repos • select a repository to load its metadata"
                        else
                            "GitHub: " + identity.login + " • " + selectedRepo.owner + "/" + selectedRepo.name +
                                " • " + runs.size + " workflow runs / " +
                                if (runnerResult?.isSuccess == true) runners.size.toString() + " runners"
                                else "runner metadata unavailable (permissions or endpoint)"
                    }
                    recordActivity("github_readonly_metadata_refreshed", "SUCCESS", slot)
                    logAudit("github_readonly_metadata_refreshed")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (selectedAccount == slot) githubStatusLabel?.text = "GitHub connection: Refresh failed"
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
            isChecked = policyPreferences.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                policyPreferences.edit().putBoolean(key, checked).apply()
                recordActivity("local_policy_" + key + "_" + if (checked) "enabled" else "disabled")
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
        AlertDialog.Builder(this).setTitle("Local Security Audit Log")
            .setMessage(if (entries.isBlank()) "No local security/admin events recorded yet." else entries)
            .setPositiveButton("Close", null)
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
