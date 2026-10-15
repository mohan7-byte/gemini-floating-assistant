package com.assistant.gemini

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/** When invoked by Android's assistant shortcut, open Buddy's existing floating control. */
class BuddyVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The system-selected assistant enters here. Launch the same overlay used by Buddy's app
        // button, then dismiss the otherwise-empty system assistant window.
        main.post {
            FloatingAssistantService.show(context)
            hide()
        }
    }

    override fun onHide() {
        super.onHide()
    }
}
