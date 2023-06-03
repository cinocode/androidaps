package info.nightscout.androidaps.activities

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.RawRes
import androidx.core.app.NotificationCompat
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.core.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.transactions.InsertTherapyEventAnnouncementTransaction
import info.nightscout.androidaps.dialogs.ErrorDialog
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class ErrorHelperActivity : DialogAppCompatActivity() {

    @Inject lateinit var sp: SP
    @Inject lateinit var repository: AppRepository

    private val disposable = CompositeDisposable()

    @Override
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorDialog = ErrorDialog()
        errorDialog.helperActivity = this
        errorDialog.status = intent.getStringExtra(STATUS) ?: ""
        errorDialog.sound = intent.getIntExtra(SOUND_ID, R.raw.error)
        errorDialog.title = intent.getStringExtra(TITLE)?: ""
        errorDialog.show(supportFragmentManager, "Error")

        if (sp.getBoolean(R.string.key_ns_create_announcements_from_errors, true))
            disposable += repository.runTransaction(InsertTherapyEventAnnouncementTransaction(intent.getStringExtra(STATUS) ?: "")).subscribe()
    }

    companion object {

        const val SOUND_ID = "soundId"
        const val STATUS = "status"
        const val TITLE = "title"

        fun runAlarm(ctx: Context, status: String, title: String, @RawRes soundId: Int = 0) {
            val builder = NotificationCompat.Builder(ctx, Constants.NOTIF_CHAN_ALARMS)
            builder
                .setSmallIcon(R.drawable.ic_pod_128)
                .setContentTitle("Alarm - $status")
                .setContentText(title)
                .setPriority(Notification.IMPORTANCE_HIGH)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(0, 100, 50, 100, 50))

            val mNotificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mNotificationManager.notify(Constants.notificationIDAlarm, builder.build())
        }
    }
}
