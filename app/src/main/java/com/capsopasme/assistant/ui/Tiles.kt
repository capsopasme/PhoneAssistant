package com.capsopasme.assistant.ui

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.capsopasme.assistant.call.CallActivity

/**
 * Quick Settings tiles ("语音助手", "和噜噜通话"): a way in that works on every ROM. ColorOS has no
 * AOSP corner swipe for the digital assistant; its control center takes third-party tiles.
 * Nothing runs in the background for them: the system binds a tile only while the panel shows it.
 */
abstract class LaunchTile : TileService() {

    protected abstract fun intent(): Intent

    /** the assistant sheet can't show over the lock screen: unlock first */
    protected open val needsUnlock = true

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val launch = Runnable {
            val pi = PendingIntent.getActivity(
                this, 0, intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        }
        if (needsUnlock && isLocked) unlockAndRun(launch) else launch.run()
    }
}

class AssistTileService : LaunchTile() {
    override fun intent() = Intent(this, AssistActivity::class.java).setAction(AssistActivity.ACTION_START)
}

class CallTileService : LaunchTile() {
    // the call screen shows over the lock screen like an incoming call
    override val needsUnlock = false
    override fun intent() = Intent(this, CallActivity::class.java).setAction(CallActivity.ACTION_CALL)
}
