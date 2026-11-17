package io.github.zeus1357.btwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅 후, 감시가 켜져 있었다면 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && EventLog.isEnabled(context)) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }
    }
}
