package com.assistant.gemini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    companion object { private const val REQUEST_MICROPHONE = 702 }
    private val legacyNonLiveModels = setOf(
        "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro",
        "gemini-3-flash-preview", "gemini-3-pro-preview"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = SecurePrefs.get(this)
        val apiKey = findViewById<EditText>(R.id.etApiKey)
        val model = findViewById<EditText>(R.id.etModel)
        apiKey.setText(prefs.getString("api_key", ""))
        val storedModel = prefs.getString("model", "").orEmpty().trim().removePrefix("models/")
        model.setText(if (storedModel.isBlank() || storedModel in legacyNonLiveModels) "gemini-3.8-live" else storedModel)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings(apiKey, model) ?: return@setOnClickListener
            Toast.makeText(this, "Settings saved securely on this device", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Floating control-bar permission is already allowed", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable Gemini Float Shortcut to use double-press Volume Down", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnShowOrb).setOnClickListener {
            if (saveSettings(apiKey, model) == null) return@setOnClickListener
            if (apiKey.text.toString().isBlank()) {
                apiKey.error = "Paste your Google AI Studio API key first"
                Toast.makeText(this, "An API key is required for Gemini Live", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Allow display over other apps, then tap Open Live assistant again", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
                return@setOnClickListener
            }
            openLiveAssistant()
        }
    }

    private fun saveSettings(apiKey: EditText, model: EditText): String? {
        val modelId = model.text.toString().trim().removePrefix("models/")
        if (modelId.isBlank()) {
            model.error = "Enter a Gemini Live model ID"
            return null
        }
        SecurePrefs.get(this).edit()
            .putString("api_key", apiKey.text.toString().trim())
            .putString("model", modelId)
            .apply()
        return modelId
    }

    private fun openLiveAssistant() {
        if (AssistantAccessibilityService.requestShow()) {
            finish()
        } else {
            Toast.makeText(this, "Enable Gemini Float Shortcut in Accessibility settings first, then return and tap Open Live assistant.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    @Deprecated("Permission callback retained for API 26 compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openLiveAssistant()
            else Toast.makeText(this, "Microphone permission is needed for Gemini Live voice.", Toast.LENGTH_LONG).show()
        }
    }
}
