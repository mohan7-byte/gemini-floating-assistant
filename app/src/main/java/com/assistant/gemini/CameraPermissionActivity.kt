package com.assistant.gemini

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class CameraPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            AssistantAccessibilityService.requestCameraPreview()
            finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 8810)
        }
    }

    @Deprecated("Permission callback retained for API 26 compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8810 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            AssistantAccessibilityService.requestCameraPreview()
        } else {
            ScreenshotBridge.statusReceiver?.invoke("Camera permission is required to preview and share a camera frame.")
            Toast.makeText(this, "Camera permission not granted", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
