package com.coin11.taojinbi.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
            "run_coin_mainline" -> {
                val queued = TaojinbiAccessibilityService.debugQueueCoinMainline(context)
                if (queued.contains("queued")) {
                    val opened = openCoinHome(context)
                    if (opened) {
                        queued + "; coin home launch sent"
                    } else {
                        "rejected run_coin_mainline: failed to launch coin home"
                    }
                } else {
                    queued
                }
            }
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

    private fun openCoinHome(context: Context): Boolean =
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(COIN_HOME_URL),
                ).apply {
                    setPackage(TAOBAO_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess

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
        private const val TAOBAO_PACKAGE = "com.taobao.taobao"
        private const val COIN_HOME_URL =
            "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"
    }
}
