package com.coin11.taojinbi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.coin11.taojinbi.accessibility.TaojinbiAccessibilityService

class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val message = when (command) {
            "tap_center" -> execute(command) {
                TaojinbiAccessibilityService.debugTapCenterNow()
            }
            "swipe_up" -> execute(command) {
                TaojinbiAccessibilityService.debugSwipeUpNow()
            }
            "back" -> execute(command) {
                TaojinbiAccessibilityService.debugBackNow()
            }
            "run_one_browse_task" ->
                TaojinbiAccessibilityService.debugStartOneBrowseTask()
            "status" -> TaojinbiAccessibilityService.debugStatusText()
            else -> "unknown command=" + command
        }

        val success = when {
            command == "status" -> true
            message.startsWith("accepted ") -> true
            else -> false
        }

        Log.i(TAG, command + " -> " + message)
        resultCode = if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = message
    }

    private fun execute(
        command: String,
        action: () -> Boolean,
    ): String =
        if (action()) {
            "accepted " + command
        } else {
            "rejected " + command + ": accessibility service not connected"
        }

    companion object {
        const val ACTION = "com.coin11.taojinbi.DEBUG_COMMAND"
        const val EXTRA_COMMAND = "command"
        private const val TAG = "TaojinbiDebugCommand"
    }
}
