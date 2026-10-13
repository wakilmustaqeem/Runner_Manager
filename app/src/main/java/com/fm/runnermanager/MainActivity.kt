package com.fm.runnermanager

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ink = 0xFF172033.toInt()
    private val muted = 0xFF586579.toInt()
    private val panel = 0xFFF3F6FA.toInt()
    private val preferences by lazy { getSharedPreferences("local_account_aliases", MODE_PRIVATE) }
    private val auditPreferences by lazy { getSharedPreferences("local_audit_metadata", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderDashboard()
    }

    private fun renderDashboard() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        scroll.addView(page)
        setContentView(scroll)

        page.addView(text("FM Runner Manager", 27f, ink, true))
        page.addView(text("Local-first • Four isolated account slots", 14f, muted, false).apply {
            setPadding(0, dp(5), 0, dp(18))
        })
        page.addView(text("Runner overview", 19f, ink, true))
        page.addView(text(
            "This foundation keeps four separate local labels. No GitHub account is connected and no runner is registered yet.",
            14f, muted, false
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        (1..4).forEach { index ->
            val alias = preferences.getString(aliasKey(index), defaultAlias(index)) ?: defaultAlias(index)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                setBackgroundColor(panel)
            }
            card.addView(text(alias, 17f, ink, true))
            card.addView(text("Runner: Not configured", 14f, muted, false).apply {
                setPadding(0, dp(5), 0, dp(10))
            })
            card.addView(Button(this).apply {
                text = "Edit local label"
                setOnClickListener { editAlias(index, alias) }
            })
            page.addView(card, marginParams(bottom = 10))
        }

        page.addView(text("Device diagnostics", 19f, ink, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        page.addView(text(diagnosticsText(), 14f, muted, false).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(panel)
        }, marginParams(bottom = 12))

        page.addView(text("FM Commerce • PHP Lint status", 19f, ink, true).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
        page.addView(text(phpLintStatusText(), 14f, muted, false).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(panel)
        }, marginParams(bottom = 8))
        page.addView(Button(this).apply {
            text = "Record PHP Lint outcome"
            setOnClickListener { choosePhpLintOutcome() }
        })
        page.addView(Button(this).apply {
            text = "PHP runtime check (not implemented)"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("PHP runtime check unavailable")
                    .setMessage("Runner Manager has not executed PHP or installed a PHP package. This button is informational only; it does not run a lint job or contact GitHub.")
                    .setPositiveButton("Close", null)
                    .show()
                logAudit("php_runtime_check_unavailable_viewed")
            }
        })

        page.addView(Button(this).apply {
            text = "Copy diagnostics report"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FM Runner Manager diagnostics", diagnosticsText()))
                logAudit("diagnostics_copied")
                Toast.makeText(this@MainActivity, "Diagnostics copied. No account tokens are included.", Toast.LENGTH_SHORT).show()
            }
        })
        page.addView(Button(this).apply {
            text = "View local audit log"
            setOnClickListener { showAuditLog() }
        })
        page.addView(Button(this).apply {
            text = "Refresh device diagnostics"
            setOnClickListener { renderDashboard() }
        })
        page.addView(text(
            "Package readiness: not checked • Installation: not attempted",
            13f, muted, false
        ).apply { setPadding(0, dp(14), 0, 0) })
        page.addView(text(
            "Safety: labels only • no credentials stored • no network calls • no packages installed • no runner jobs started",
            12f, muted, false
        ).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun editAlias(index: Int, current: String) {
        val input = EditText(this).apply {
            setText(current)
            hint = defaultAlias(index)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Account slot $index label")
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
            }
            .show()
    }

    private fun showAuditLog() {
        val entries = auditPreferences.getString("entries", "") ?: ""
        AlertDialog.Builder(this)
            .setTitle("Local audit log")
            .setMessage(if (entries.isBlank()) "No local actions recorded yet." else entries)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear log") { _, _ ->
                auditPreferences.edit().remove("entries").apply()
                Toast.makeText(this, "Local metadata log cleared.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun logAudit(action: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val old = auditPreferences.getString("entries", "") ?: ""
        val next = (listOf("$stamp | $action") + old.lines().filter { it.isNotBlank() }).take(50)
        auditPreferences.edit().putString("entries", next.joinToString("\n")).apply()
    }

    private fun diagnosticsText(): String {
        val dataStats = StatFs(Environment.getDataDirectory().absolutePath)
        val memoryInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Supported ABI: " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown") + "\n" +
            "Internal storage free: " + formatBytes(dataStats.availableBytes) + " / " + formatBytes(dataStats.totalBytes) + "\n" +
            "System-reported available RAM: " + formatBytes(memoryInfo.availMem) + "\n" +
            "External storage state: " + Environment.getExternalStorageState() + "\n" +
            "GitHub connection: Not configured\n" +
            "Runner registration: Not configured\n" +
            "Package installation: Not attempted\n" +
            "FM Commerce PHP Lint result: " + (preferences.getString("php_lint_status", "UNVERIFIED") ?: "UNVERIFIED")
    }

    private fun phpLintStatusText(): String {
        val status = preferences.getString("php_lint_status", "UNVERIFIED") ?: "UNVERIFIED"
        val note = preferences.getString("php_lint_note", "") ?: ""
        return "Target: fmmustaqeem/fm-commerce-platform\n" +
            "Result: " + status + "\n" +
            (if (note.isBlank()) "" else "Note: " + note + "\n") +
            "This is a locally recorded classification only. No PHP lint was run by this app."
    }

    private fun choosePhpLintOutcome() {
        val statuses = arrayOf(
            "UNVERIFIED — not tested",
            "BLOCKED_BEFORE_START — billing/permissions/runner gate",
            "LINT_FAILED — PHP actually ran and reported syntax errors",
            "PASS — PHP actually ran and reported no syntax errors"
        )
        val values = arrayOf("UNVERIFIED", "BLOCKED_BEFORE_START", "LINT_FAILED", "PASS")
        AlertDialog.Builder(this)
            .setTitle("Record FM Commerce PHP Lint outcome")
            .setMessage("Choose only the outcome supported by evidence. PASS and LINT_FAILED require a real PHP lint execution. This screen does not execute PHP.")
            .setItems(statuses) { _, which ->
                val status = values[which]
                val note = when (status) {
                    "BLOCKED_BEFORE_START" -> "Job did not start; no lint result was produced."
                    "LINT_FAILED" -> "Use only when PHP ran and reported syntax errors."
                    "PASS" -> "Use only when PHP ran and reported no syntax errors."
                    else -> "No verified PHP lint result."
                }
                preferences.edit()
                    .putString("php_lint_status", status)
                    .putString("php_lint_note", note)
                    .apply()
                logAudit("fm_commerce_php_lint_status_recorded_" + status.lowercase(Locale.US))
                renderDashboard()
                Toast.makeText(this, "Status recorded locally; no lint job was run.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f GiB", gib)
    }

    private fun aliasKey(index: Int) = "account_slot_$index"
    private fun defaultAlias(index: Int) = "Account slot $index"

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun marginParams(bottom: Int): ViewGroup.MarginLayoutParams =
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
