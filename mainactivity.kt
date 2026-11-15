package com.phpnano.panel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private var code = 0
    private var data: Intent? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
            setBackgroundColor(0xFF0B0C0F.toInt())
        }

        root.addView(TextView(this).apply {
            text = "PHP NANO"
            textSize = 30f
            setTextColor(0xFF00E676.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Panel flottant • ESP • Headshot • Boost 1.7x"
            textSize = 12f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 6, 0, 28)
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFD54F.toInt())
            setPadding(0, 0, 0, 20)
        }
        root.addView(status)

        root.addView(button("1 • Autoriser l'OVERLAY") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("2 • ACCESSIBILITÉ (tir tête)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3 • DÉMARRER PHP NANO") {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Autorise d'abord l'overlay (étape 1)."
                return@button
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        })
        root.addView(button("STOP") {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_STOP)
            )
            status.text = "Overlay arrêté."
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            val ov = Settings.canDrawOverlays(this)
            val acc = AimService.instance != null
            status.text = "Overlay : ${if (ov) "OK" else "à autoriser"}   •   Accessibilité : ${if (acc) "OK" else "à activer"}"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, d: Intent?) {
        super.onActivityResult(requestCode, resultCode, d)
        if (requestCode == 100 && resultCode == RESULT_OK && d != null) {
            code = resultCode
            data = d
            val i = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_CODE, code)
                putExtra(OverlayService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            status.text = "PHP NANO lancé — retourne dans Free Fire."
            moveTaskToBack(true)
        }
    }

    private fun button(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 34, 0, 34)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xFF00E676.toInt())
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 22 }
        }
}