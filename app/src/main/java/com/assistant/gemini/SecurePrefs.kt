package com.assistant.gemini

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val FILE = "gemini_secure_prefs"
    @Volatile private var cached: android.content.SharedPreferences? = null

    fun get(context: Context): android.content.SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appContext = context.applicationContext
            val prefs = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext, FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                // Never persist the key in plaintext if encryption isn't available.
                appContext.getSharedPreferences("gemini_unavailable_volatile", Context.MODE_PRIVATE).also {
                    it.edit().clear().apply()
                }
            }
            cached = prefs
            return prefs
        }
    }
}
