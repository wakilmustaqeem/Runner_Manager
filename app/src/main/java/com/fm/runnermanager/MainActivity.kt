package com.fm.runnermanager

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
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
import java.util.Locale

class MainActivity : Activity() {
    private val ink = 0xFF172033.toInt()
    private val muted = 0xFF586579.toInt()
    private val panel = 0xFFF3F6FA.toInt()
    private val preferences by lazy { getSharedPreferences("local_account_aliases", MODE_PRIVATE) }

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
                renderDashboard()
            }
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().take(48)
                if (value.isEmpty()) {
                    preferences.edit().remove(aliasKey(index)).apply()
                } else {
                    preferences.edit().putString(aliasKey(index), value).apply()
                }
                renderDashboard()
                Toast.makeText(this, "Local label saved; account remains disconnected.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun diagnosticsText(): String {
        val dataStats = StatFs(Environment.getDataDirectory().absolutePath)
        val freeBytes = dataStats.availableBytes
        val totalBytes = dataStats.totalBytes
        val memoryInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Supported ABI: " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown") + "\n" +
            "Internal storage free: " + formatBytes(freeBytes) + " / " + formatBytes(totalBytes) + "\n" +
            "System-reported available RAM: " + formatBytes(memoryInfo.availMem) + "\n" +
            "External storage state: " + Environment.getExternalStorageState()
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
