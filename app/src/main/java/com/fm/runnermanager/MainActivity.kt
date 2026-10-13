package com.fm.runnermanager

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ink = 0xFF172033.toInt()
    private val muted = 0xFF586579.toInt()
    private val panel = 0xFFF3F6FA.toInt()

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
            "No account is connected yet. Add and verify each identity separately. This starter screen does not register runners or access GitHub.",
            14f, muted, false
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        val accountNames = listOf("Account slot 1", "Account slot 2", "Account slot 3", "Account slot 4")
        accountNames.forEachIndexed { index, name ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                setBackgroundColor(panel)
            }
            card.addView(text(name, 17f, ink, true))
            card.addView(text("Status: Not connected", 14f, muted, false).apply {
                setPadding(0, dp(5), 0, dp(10))
            })
            card.addView(Button(this).apply {
                text = "Configure account slot"
                setOnClickListener {
                    Toast.makeText(
                        this@MainActivity,
                        "Account connection is not implemented yet. Slot " + (index + 1) + " remains disconnected.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
            page.addView(card, marginParams(bottom = 10))
        }

        page.addView(text("Device diagnostics", 19f, ink, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        page.addView(text(
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Supported ABI: " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown") + "\n" +
                "External storage: " + (if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) "Available" else "Unavailable") + "\n" +
                "Package installation: Not attempted",
            14f, muted, false
        ).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(panel)
        }, marginParams(bottom = 12))

        page.addView(Button(this).apply {
            text = "Refresh device diagnostics"
            setOnClickListener { renderDashboard() }
        })
        page.addView(text(
            "Safety: no credentials stored • no network calls • no packages installed • no runner jobs started",
            12f, muted, false
        ).apply { setPadding(0, dp(18), 0, 0) })
    }

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
