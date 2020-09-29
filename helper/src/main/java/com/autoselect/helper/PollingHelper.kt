package com.autoselect.helper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.TextUtils

object PollingHelper {
    fun isExistPollingService(context: Context?, cls: Class<*>?): Boolean =
        PendingIntent.getService(
            context, 0, Intent(context, cls), PendingIntent.FLAG_NO_CREATE
        ) != null//是否存在轮询服务

    @JvmOverloads
    fun startPollingService(
        context: Context?, interval: Int, cls: Class<*>?, action: String? = null
    ) {
        val manager = context?.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        val intent =
            Intent(context, cls).apply { if (!TextUtils.isEmpty(action)) this.action = action }
        val pendingIntent =
            PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val triggerAtTime = SystemClock.elapsedRealtime()
        manager?.setRepeating(
            AlarmManager.ELAPSED_REALTIME, triggerAtTime, interval * 1000L, pendingIntent
        )
    }//开启轮询服务

    @JvmOverloads
    fun stopPollingService(context: Context?, cls: Class<*>?, action: String? = null) {
        val manager = context?.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        val intent =
            Intent(context, cls).apply { if (!TextUtils.isEmpty(action)) this.action = action }
        val pendingIntent =
            PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        manager?.cancel(pendingIntent)
    }//停止轮询服务
}