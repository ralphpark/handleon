package ai.handleon.car.ui

import ai.handleon.car.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

class RestNotifications(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun cancel() { manager.cancel(ID) }
    fun finished(driving: Boolean) {
        if (driving) return
        val channel = NotificationChannel(CHANNEL, "쉬어 가기 타이머", NotificationManager.IMPORTANCE_DEFAULT)
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        manager.createNotificationChannel(channel)
        try {
            manager.notify(ID, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_handleon)
                .setContentTitle("쉬어 가기 타이머").setContentText(REST_FINISHED)
                .setStyle(Notification.BigTextStyle().bigText(REST_FINISHED)).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Notification permission may be declined; the card still finishes. */ }
    }
    private companion object { const val CHANNEL = "rest_timer"; const val ID = 2 }
}
