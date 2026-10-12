package com.assistant.gemini

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val models = arrayOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3-pro-preview")
    private val thinkingModes = arrayOf("Default", "Fast", "Balanced", "Deep")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = SecurePrefs.get(this)
        val apiKey = findViewById<EditText>(R.id.etApiKey)
        val model = findViewById<Spinner>(R.id.spnModel)
        val thinking = findViewById<Spinner>(R.id.spnThinking)
        model.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        thinking.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, thinkingModes)
        apiKey.setText(prefs.getString("api_key", ""))
        model.setSelection(models.indexOf(prefs.getString("model", models[0])).coerceAtLeast(0))
        thinking.setSelection(thinkingModes.indexOf(prefs.getString("thinking", "Default")).coerceAtLeast(0))
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit().putString("api_key", apiKey.text.toString().trim())
                .putString("model", model.selectedItem.toString())
                .putString("thinking", thinking.selectedItem.toString()).apply()
            Toast.makeText(this, "Settings saved on this device", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else Toast.makeText(this, "Floating window permission is already allowed", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable Gemini Float Shortcut in Accessibility settings", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnShowOrb).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startService(Intent(this, AssistantAccessibilityService::class.java).setAction(AssistantAccessibilityService.ACTION_SHOW))
                Toast.makeText(this, "Floating assistant requested", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
