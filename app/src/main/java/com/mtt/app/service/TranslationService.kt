package com.mtt.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TranslationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
