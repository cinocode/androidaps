package info.nightscout.androidaps.plugins.pump.omnipod.dash

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.activities.ErrorHelperActivity.Companion.runAlarm
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.PumpEnactResult
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.events.EventProfileSwitchChanged
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.events.EventTempBasalChange
import info.nightscout.androidaps.extensions.convertedToAbsolute
import info.nightscout.androidaps.extensions.plannedRemainingMinutes
import info.nightscout.androidaps.extensions.toStringFull
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.common.ManufacturerType
import info.nightscout.androidaps.plugins.general.actions.defs.CustomAction
import info.nightscout.androidaps.plugins.general.actions.defs.CustomActionType
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.plugins.pump.common.utils.DateTimeUtil
import info.nightscout.androidaps.plugins.pump.omnipod.common.definition.OmnipodCommandType
import info.nightscout.androidaps.plugins.pump.omnipod.common.queue.command.*
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.OmnipodDashManager
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.pod.definition.*
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.pod.response.ResponseType
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.pod.state.CommandConfirmed
import info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.pod.state.OmnipodDashPodStateManager
import info.nightscout.androidaps.plugins.pump.omnipod.dash.history.DashHistory
import info.nightscout.androidaps.plugins.pump.omnipod.dash.history.data.BasalValuesRecord
import info.nightscout.androidaps.plugins.pump.omnipod.dash.history.data.BolusRecord
import info.nightscout.androidaps.plugins.pump.omnipod.dash.history.data.BolusType
import info.nightscout.androidaps.plugins.pump.omnipod.dash.history.data.TempBasalRecord
import info.nightscout.androidaps.plugins.pump.omnipod.dash.ui.OmnipodDashOverviewFragment
import info.nightscout.androidaps.plugins.pump.omnipod.dash.util.Constants
import info.nightscout.androidaps.plugins.pump.omnipod.dash.util.mapProfileToBasalProgram
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.queue.commands.Command
import info.nightscout.androidaps.queue.commands.CustomCommand
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter.to2Decimal
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.Round
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.TimeChangeType
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.ceil

@Singleton
class OmnipodDashPumpPlugin @Inject constructor(
    private val omnipodManager: OmnipodDashManager,
    private val podStateManager: OmnipodDashPodStateManager,
    private val sp: SP,
    private val profileFunction: ProfileFunction,
    private val history: DashHistory,
    private val pumpSync: PumpSync,
    private val rxBus: RxBus,
    private val context: Context,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil,

    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue
) : PumpPluginBase(pluginDescription, injector, aapsLogger, rh, commandQueue), Pump {

    private enum class AutoDeacMode { IDLE, EXPIRY_POLLING, EXPIRY_DISMISSUNG, PRE_DEAC, FORCED_DEAC }

    @Volatile var bolusCanceled = false
    @Volatile var bolusDeliveryInProgress = false

    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)
    private lateinit var statusChecker: Runnable
    private var autoDeacChecker: Runnable
    private var autoDeacMode = AutoDeacMode.IDLE
    private var lastDismissedPodWarning: LocalDateTime = LocalDateTime.now().minusMinutes(5)
    private var lastStatusCheck: LocalDateTime = LocalDateTime.now()
    private var nextPodWarningCheck: Long = 0
    @Volatile var stopConnecting: CountDownLatch? = null
    private var disposables: CompositeDisposable = CompositeDisposable()

    companion object {

        private const val BOLUS_RETRY_INTERVAL_MS = 2000.toLong()
        private const val BOLUS_RETRIES = 5 // number of retries for cancel/get bolus status
        private const val STATUS_CHECK_INTERVAL_MS = (60L * 1000)
        private const val RESERVOIR_OVER_50_UNITS_DEFAULT = 75.0
        private const val DASH_ALERT_NOTIF_CHAN = "AndroidAPS-Dash"
        private const val DASH_ONG_NOTIF_CHAN = "AndroidAPS-Expiration"

        private val pluginDescription = PluginDescription()
            .mainType(PluginType.PUMP)
            .fragmentClass(OmnipodDashOverviewFragment::class.java.name)
            .pluginIcon(R.drawable.ic_pod_128)
            .pluginName(R.string.omnipod_dash_name)
            .shortName(R.string.omnipod_dash_name_short)
            .preferencesId(R.xml.omnipod_dash_preferences)
            .description(R.string.omnipod_dash_pump_description)

        private val pumpDescription = PumpDescription(PumpType.OMNIPOD_DASH)
    }

    init {
        statusChecker = Runnable {
            refreshStatusOnUnacknowledgedCommands()
            updatePodWarnings()
            scheduleAutoDeac()
            aapsLogger.info(LTag.PUMP, "statusChecker")

            try {
                createFakeTBRWhenNoActivePod()
                    .subscribeOn(aapsSchedulers.io)
                    .blockingAwait()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.PUMP, "Error on createFakeTBRWhenNoActivePod=$e")
            }
            handler.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
        }

        autoDeacChecker = Runnable {
            pollAutoDeac()
        }
    }

    private fun createFakeTBRWhenNoActivePod(): Completable = Completable.defer {
        if (!podStateManager.isPodRunning) {
            val expectedState = pumpSync.expectedPumpState()
            val tbr = expectedState.temporaryBasal
            if (tbr == null || tbr.rate != 0.0) {
                aapsLogger.info(LTag.PUMP, "createFakeTBRWhenNoActivePod")
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = System.currentTimeMillis(),
                    rate = 0.0,
                    duration = T.mins(PodConstants.MAX_POD_LIFETIME.toMinutes()).msecs(),
                    isAbsolute = true,
                    type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = System.currentTimeMillis(), // we don't use this, just make sure it's unique
                    pumpType = PumpType.OMNIPOD_DASH,
                    pumpSerial = Constants.PUMP_SERIAL_FOR_FAKE_TBR // switching the serialNumber here would need a
                    // call to connectNewPump. If we do that, then we will have a TBR started by the "n/a" pump and
                    // cancelled by "4241". This did not work ok.
                )
            }
        }
        Completable.complete()
    }

    private fun updatePodWarnings() {
        if (System.currentTimeMillis() > nextPodWarningCheck) {
            if (!podStateManager.isPodRunning) {
                val notification =
                    Notification(
                        Notification.OMNIPOD_POD_NOT_ATTACHED,
                        "Pod not activated",
                        Notification.NORMAL
                    )
                rxBus.send(EventNewNotification(notification))
            } else {
                rxBus.send(EventDismissNotification(Notification.OMNIPOD_POD_NOT_ATTACHED))
                if (podStateManager.isSuspended) {
                    showNotification(
                        Notification.OMNIPOD_POD_SUSPENDED,
                        "Insulin delivery suspended",
                        Notification.NORMAL,
                        R.raw.boluserror
                    )
                } else {
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_POD_SUSPENDED))
                    if (!podStateManager.sameTimeZone) {
                        val notification =
                            Notification(
                                Notification.OMNIPOD_TIME_OUT_OF_SYNC,
                                "Timezone on pod is different from the timezone on phone. " +
                                    "Basal rate is incorrect" +
                                    "Switch profile to fix",
                                Notification.NORMAL
                            )
                        rxBus.send(EventNewNotification(notification))
                    }
                }
            }
            nextPodWarningCheck = DateTimeUtil.getTimeInFutureFromMinutes(15)
        }
    }

    private fun refreshStatusOnUnacknowledgedCommands() {
        if (podStateManager.isPodRunning &&
            commandQueue.size() == 0 &&
            commandQueue.performing() == null
        ) {
            if (podStateManager.activeCommand != null) {
                this.readStatus(rh.gs(R.string.unconfirmed_command), null)
            }
        }
    }

    fun readStatus(reason: String, callback: Callback?) {
        this.lastStatusCheck = LocalDateTime.now()
        commandQueue.readStatus(reason, object : Callback() {
            override fun run() {
                if (result.success) {
                    checkForAlertsToDismiss()
                }

                callback?.result(result)?.run()
            }
        })

    }

    private fun isAutoDeacDebugOn(): Boolean {
        return sp.getBoolean(R.string.key_omnipod_dash_preferences_deactivation_debug, false)
    }

    private fun isAutoAlertDismissalOn(): Boolean {
        return sp.getBoolean(R.string.key_omnipod_dash_preferences_deactivation_auto_dismiss, false)
    }


    private fun timeLeftOnPod(forcedDeac: Boolean): Duration? {
        return if (forcedDeac) {
            Duration.between(ZonedDateTime.now(), podStateManager.expiry?.plusHours(8) ?: return null)
        } else {
            Duration.between(ZonedDateTime.now(), podStateManager.expiry ?: return null)
        }
    }

    private fun scheduleAutoDeac() {
        if (!podStateManager.isPodRunning || !isAutoAlertDismissalOn()) {
            this.autoDeacMode = AutoDeacMode.IDLE
            return
        }

        val timeToExpiry = timeLeftOnPod(false) ?: return
        if (timeToExpiry < Duration.ofMinutes(1) && timeToExpiry > Duration.ofMinutes(-2)) {
            if (autoDeacMode == AutoDeacMode.IDLE && !lastDismissedPodWarning.isAfter(LocalDateTime.now().minusMinutes(5))) {
                if (isAutoDeacDebugOn()) {
                    this.notificationHandler("Going into poll mode for pod expiration.", "DEBUG Dash Alert")
                }
                this.autoDeacMode = AutoDeacMode.EXPIRY_POLLING
                val pollTime = sp.getInt(R.string.key_omnipod_dash_preferences_deactivation_poll_time, 250).toLong()
                handler.postDelayed(autoDeacChecker, pollTime)
            }
        } else if (timeToExpiry >= Duration.ofMinutes(1)) {
            this.autoDeacMode = AutoDeacMode.IDLE
        }
    }

    private fun pollAutoDeac() {
        if (!podStateManager.isPodRunning || !isAutoAlertDismissalOn() || autoDeacMode == AutoDeacMode.IDLE || autoDeacMode == AutoDeacMode.PRE_DEAC) {
            return
        }

        val timeToExpiry = timeLeftOnPod(false) ?: return
        val pollTime = sp.getInt(R.string.key_omnipod_dash_preferences_deactivation_poll_time, 500).toLong()
        var done = false

        if (timeToExpiry >= Duration.ofSeconds(5) && timeToExpiry < Duration.ofMinutes(1)) {
            // not quite time yet
            handler.postDelayed(autoDeacChecker, pollTime)
        } else if (timeToExpiry < Duration.ofSeconds(5) && timeToExpiry > Duration.ofMinutes(-5)) {
            this.notificationHandler("Trying to dismiss Dash expiry alert...", "Dash Alert", true)

            if (podStateManager.activeAlerts!!.size > 0) {
                this.autoDeacMode = AutoDeacMode.EXPIRY_DISMISSUNG
                if (isAutoDeacDebugOn()) {
                    this.notificationHandler("Alert seen in poll cycle.", "DEBUG Dash Alert")
                }
                checkForAlertsToDismiss()
            } else if (this.autoDeacMode == AutoDeacMode.EXPIRY_POLLING && commandQueue.size() == 0) {
                if (isAutoDeacDebugOn()) {
                    this.notificationHandler("Nothing in command queue. Polling via status check.", "DEBUG Dash Alert")
                }
                this.readStatus(rh.gs(R.string.requested_by_dash_deac), null)
            }

            if (lastDismissedPodWarning.isAfter(LocalDateTime.now().minusMinutes(5))) {
                done = true
            }
        } else {
            done = true
        }

        if (done) {
            this.autoDeacMode = AutoDeacMode.PRE_DEAC

            val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mNotificationManager.cancel(Constants.omnipodDashOngNotificationID)
        } else {
            handler.postDelayed(autoDeacChecker, pollTime)
        }
    }

    private fun checkForAlertsToDismiss() {
        if (
            isAutoAlertDismissalOn()
            && podStateManager.isPodRunning
            && podStateManager.activeAlerts!!.size > 0
            && !commandQueue.isCustomCommandInQueue(CommandSilenceAlerts::class.java)
        ) {
            val message = podStateManager.activeAlerts?.let { it ->
                it.joinToString(System.lineSeparator()) { t -> translatedActiveAlert(t) }
            } ?: return

            this.lastDismissedPodWarning = LocalDateTime.now()

            if (isAutoDeacDebugOn()) {
                val bool = commandQueue.isCustomCommandInQueue(CommandSilenceAlerts::class.java)
                val extra = if (bool) "(command silence in queue)" else "(command silence not in queue)"
                this.notificationHandler("Silenced alert for: '${message}' ${extra}.", "DEBUG Dash Alert")
            } else {
                this.notificationHandler(message)
            }
            this.executeCustomCommand(CommandSilenceAlerts())
        }
    }

    fun translatedActiveAlert(alert: AlertType): String {
        val id = when (alert) {
            AlertType.LOW_RESERVOIR       ->
                R.string.omnipod_common_alert_low_reservoir
            AlertType.EXPIRATION          ->
                R.string.omnipod_common_alert_expiration_advisory
            AlertType.EXPIRATION_IMMINENT ->
                R.string.omnipod_common_alert_expiration
            AlertType.USER_SET_EXPIRATION ->
                R.string.omnipod_common_alert_expiration_advisory
            AlertType.AUTO_OFF            ->
                R.string.omnipod_common_alert_shutdown_imminent
            AlertType.SUSPEND_IN_PROGRESS ->
                R.string.omnipod_common_alert_delivery_suspended
            AlertType.SUSPEND_ENDED       ->
                R.string.omnipod_common_alert_delivery_suspended
            else                          ->
                R.string.omnipod_common_alert_unknown_alert
        }
        return rh.gs(id)
    }

    private fun createNotificationChannels() {
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        @SuppressLint("WrongConstant") val mainChan = NotificationChannel(
            DASH_ALERT_NOTIF_CHAN,
            DASH_ALERT_NOTIF_CHAN,
            NotificationManager.IMPORTANCE_HIGH
        )
        mNotificationManager.createNotificationChannel(mainChan)

        @SuppressLint("WrongConstant") val onGoingChan = NotificationChannel(
            DASH_ONG_NOTIF_CHAN,
            DASH_ONG_NOTIF_CHAN,
            NotificationManager.IMPORTANCE_HIGH
        )
        mNotificationManager.createNotificationChannel(onGoingChan)
    }

    private fun notificationHandler(text: String, title: String = "Dash Alert", onGoing: Boolean = false) {
        val channel = if (onGoing) DASH_ONG_NOTIF_CHAN else DASH_ALERT_NOTIF_CHAN
        val builder = NotificationCompat.Builder(context, channel)
        builder
            .setSmallIcon(R.drawable.ic_pod_128)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(Notification.IMPORTANCE_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 100, 50, 100, 50))

            if (onGoing) {
                builder
                    .setOngoing(true)
                    .setTimeoutAfter(30 * 60 * 1000)
            }

        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = if (onGoing) Constants.omnipodDashOngNotificationID else Constants.omnipodDashNotificationID
        mNotificationManager.notify(id, builder.build())
    }

    override fun isInitialized(): Boolean {
        return podStateManager.isPodRunning
    }

    override fun isSuspended(): Boolean {
        return podStateManager.isSuspended
    }

    override fun isBusy(): Boolean {
        // prevents the queue from executing commands
        return podStateManager.activationProgress.isBefore(ActivationProgress.COMPLETED)
    }

    override fun isConnected(): Boolean {

        return !podStateManager.isPodRunning ||
            podStateManager.bluetoothConnectionState == OmnipodDashPodStateManager.BluetoothConnectionState.CONNECTED
    }

    override fun isConnecting(): Boolean {
        return stopConnecting != null
    }

    override fun isHandshakeInProgress(): Boolean {
        return stopConnecting != null &&
            podStateManager.bluetoothConnectionState == OmnipodDashPodStateManager.BluetoothConnectionState.CONNECTED
    }

    override fun finishHandshaking() {
    }

    override fun connect(reason: String) {
        aapsLogger.info(LTag.PUMP, "connect reason=$reason")
        podStateManager.bluetoothConnectionState = OmnipodDashPodStateManager.BluetoothConnectionState.CONNECTING
        synchronized(this) {
            stopConnecting?.let {
                aapsLogger.warn(LTag.PUMP, "Already connecting: $stopConnecting")
                return
            }
            val stop = CountDownLatch(1)
            stopConnecting = stop
        }
        if (lastStatusCheck.isBefore(LocalDateTime.now().minusMinutes(5L))) {
            this.readStatus("Check status on connect every 5 min", null)
        }
        thread(
            start = true,
            name = "ConnectionThread",
        ) {
            try {
                stopConnecting?.let {
                    omnipodManager.connect(it).ignoreElements()
                        .doOnComplete { podStateManager.incrementSuccessfulConnectionAttemptsAfterRetries() }
                        .blockingAwait()
                }
            } catch (e: Exception) {
                aapsLogger.info(LTag.PUMPCOMM, "connect error=$e")
            } finally {
                synchronized(this) {
                    stopConnecting = null
                }
            }
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.info(LTag.PUMP, "disconnect reason=$reason")
        stopConnecting?.countDown()
        omnipodManager.disconnect(false)
    }

    override fun stopConnecting() {
        aapsLogger.info(LTag.PUMP, "stopConnecting")
        podStateManager.incrementFailedConnectionsAfterRetries()
        stopConnecting?.countDown()
        omnipodManager.disconnect(true)
    }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus reason=$reason")
        if (reason != "REQUESTED BY USER" && !podStateManager.isActivationCompleted) {
            // prevent races on BLE when the pod is not activated
            return
        }

        try {
            getPodStatus()
                .doOnComplete {
                    aapsLogger.info(LTag.PUMP, "getPumpStatus executed with success")
                    if (!podStateManager.isActivationCompleted) {
                        val msg = podStateManager.recoverActivationFromPodStatus()
                        msg?.let {
                            // TODO: show dialog with "try again, the pod is busy now"
                            aapsLogger.info(LTag.PUMP, "recoverActivationFromPodStatus msg=$msg")
                        }
                    }
                }.blockingAwait()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error in getPumpStatus", e)
        }
    }

    private fun getPodStatus(): Completable = Completable.concat(
        listOf(
            omnipodManager
                .getStatus(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
                .ignoreElements(),
            history.updateFromState(podStateManager),
            podStateManager.updateActiveCommand()
                .map { handleCommandConfirmation(it) }
                .ignoreElement(),
            checkPodKaput(),
        )
    )

    private fun checkPodKaput(): Completable = Completable.defer {
        if (podStateManager.isPodKaput) {
            val tbr = pumpSync.expectedPumpState().temporaryBasal
            if (tbr == null || tbr.rate != 0.0) {
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = System.currentTimeMillis(),
                    rate = 0.0,
                    duration = T.mins(PodConstants.MAX_POD_LIFETIME.toMinutes()).msecs(),
                    isAbsolute = true,
                    type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = System.currentTimeMillis(), // we don't use this, just make sure it's unique
                    pumpType = PumpType.OMNIPOD_DASH,
                    pumpSerial = serialNumber()
                )
            }
            podStateManager.lastBolus?.run {
                if (!deliveryComplete) {
                    val deliveredUnits = markComplete()
                    deliveryComplete = true
                    val bolusHistoryEntry = history.getById(historyId)
                    val sync = pumpSync.syncBolusWithPumpId(
                        timestamp = bolusHistoryEntry.createdAt,
                        amount = deliveredUnits,
                        pumpId = bolusHistoryEntry.pumpId(),
                        pumpType = PumpType.OMNIPOD_DASH,
                        pumpSerial = serialNumber(),
                        type = bolusType
                    )
                    aapsLogger.info(LTag.PUMP, "syncBolusWithPumpId on CANCEL_BOLUS returned: $sync")
                }
            }
            if (!podStateManager.alarmSynced) {
                podStateManager.alarmType?.let {
                    if (!commandQueue.isCustomCommandInQueue(CommandDeactivatePod::class.java)) {
                        showNotification(
                            Notification.OMNIPOD_POD_FAULT,
                            it.toString(),
                            Notification.URGENT,
                            R.raw.boluserror
                        )
                    }
                    pumpSync.insertAnnouncement(
                        error = it.toString(),
                        pumpId = System.currentTimeMillis(),
                        pumpType = PumpType.OMNIPOD_DASH,
                        pumpSerial = serialNumber()
                    )
                    podStateManager.alarmSynced = true
                }
            }
        }
        Completable.complete()
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        if (!podStateManager.isActivationCompleted) {
            return PumpEnactResult(injector).success(true).enacted(true)
        }
        aapsLogger.debug(LTag.PUMP, "setNewBasalProfile profile=$profile")
        return setNewBasalProfile(profile, OmnipodCommandType.SET_BASAL_PROFILE)
    }

    private fun setNewBasalProfile(profile: Profile, historyType: OmnipodCommandType): PumpEnactResult {
        var deliverySuspended = false
        val basalProgram = mapProfileToBasalProgram(profile)
        return executeProgrammingCommand(
            pre = suspendDeliveryIfActive().doOnComplete {
                if (podStateManager.activeCommand == null) {
                    // suspend delivery is confirmed
                    deliverySuspended = true
                }
            },
            historyEntry = history.createRecord(
                commandType = historyType,
                basalProfileRecord = BasalValuesRecord(profile.getBasalValues().toList())
            ),
            activeCommandEntry = { historyId ->
                podStateManager.createActiveCommand(historyId, basalProgram = basalProgram)
            },
            command = omnipodManager.setBasalProgram(basalProgram, hasBasalBeepEnabled()).ignoreElements(),
            post = failWhenUnconfirmed(deliverySuspended),
            // mark as failed even if it worked OK and try again vs. mark ok and deny later
        ).toPumpEnactResult()
    }

    private fun failWhenUnconfirmed(deliverySuspended: Boolean): Completable = Completable.defer {
        rxBus.send(EventTempBasalChange())
        if (podStateManager.activeCommand != null) {
            if (deliverySuspended) {
                showNotification(
                    Notification.FAILED_UPDATE_PROFILE,
                    "Failed to set the new basal profile. Delivery suspended",
                    Notification.URGENT,
                    R.raw.boluserror
                )
            } else {
                showNotification(
                    Notification.FAILED_UPDATE_PROFILE,
                    "Setting basal profile might have failed. Delivery might be suspended!" +
                        " Please manually refresh the Pod status from the Omnipod tab and resume delivery if needed.",
                    Notification.URGENT,
                    R.raw.boluserror
                )
            }
            Completable.error(java.lang.IllegalStateException("Command not confirmed"))
        } else {
            showNotification(Notification.PROFILE_SET_OK, "Profile set OK", Notification.INFO, null)

            Completable.complete()
        }
    }

    private fun suspendDeliveryIfActive(): Completable = Completable.defer {
        if (podStateManager.deliveryStatus == DeliveryStatus.SUSPENDED)
            Completable.complete()
        else
            executeProgrammingCommand(
                historyEntry = history.createRecord(OmnipodCommandType.SUSPEND_DELIVERY),
                command = omnipodManager.suspendDelivery(hasBasalBeepEnabled())
                    .filter { podEvent -> podEvent.isCommandSent() }
                    .map {
                        pumpSyncTempBasal(
                            0.0,
                            PodConstants.MAX_POD_LIFETIME.toMinutes(),
                            PumpSync.TemporaryBasalType.PUMP_SUSPEND
                        )
                        rxBus.send(EventTempBasalChange())
                    }
                    .ignoreElements()
            ).doOnComplete {
                notifyOnUnconfirmed(
                    Notification.FAILED_UPDATE_PROFILE,
                    "Suspend delivery is unconfirmed! " +
                        "Please manually refresh the Pod status from the Omnipod tab and resume delivery if needed.",
                    R.raw.boluserror,
                )
            }
    }

    override fun onStart() {
        super.onStart()
        createNotificationChannels()
        podStateManager.onStart()
        handler.postDelayed(statusChecker, STATUS_CHECK_INTERVAL_MS)
        disposables += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                {
                    if (it.isChanged(rh, R.string.key_omnipod_common_expiration_reminder_enabled) ||
                        it.isChanged(rh, R.string.key_omnipod_common_expiration_reminder_hours_before_shutdown) ||
                        it.isChanged(rh, R.string.key_omnipod_common_low_reservoir_alert_enabled) ||
                        it.isChanged(rh, R.string.key_omnipod_common_low_reservoir_alert_units)
                    ) {
                        commandQueue.customCommand(CommandUpdateAlertConfiguration(), null)
                    }
                },
                fabricPrivacy::logException
            )
    }

    override fun onStop() {
        super.onStop()
        disposables.clear()
        handler.removeCallbacks(statusChecker)
    }

    private fun observeDeliverySuspended(): Completable = Completable.defer {
        if (podStateManager.deliveryStatus == DeliveryStatus.SUSPENDED)
            Completable.complete()
        else {
            Completable.error(java.lang.IllegalStateException("Expected suspended delivery"))
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!podStateManager.isActivationCompleted) {
            // prevent setBasal requests
            return true
        }
        if (podStateManager.isSuspended) {
            // set new basal profile failed midway
            return false
        }
        val running = podStateManager.basalProgram
        val equal = (mapProfileToBasalProgram(profile) == running)
        aapsLogger.info(LTag.PUMP, "set: $equal. profile=$profile, running=$running")
        return equal
    }

    override fun lastDataTime(): Long {
        return podStateManager.lastUpdatedSystem
    }

    override val baseBasalRate: Double
        get() {
            val date = System.currentTimeMillis()
            val ret = podStateManager.basalProgram?.rateAt(date) ?: 0.0
            aapsLogger.info(LTag.PUMP, "baseBasalRate: $ret at $date}")
            return if (podStateManager.alarmType != null) {
                0.0
            } else
                ret
        }

    override val reservoirLevel: Double
        get() {
            if (podStateManager.activationProgress.isBefore(ActivationProgress.COMPLETED)) {
                return 0.0
            }

            // Omnipod only reports reservoir level when there's < 1023 pulses left
            return podStateManager.pulsesRemaining?.let {
                it * PodConstants.POD_PULSE_BOLUS_UNITS
            } ?: RESERVOIR_OVER_50_UNITS_DEFAULT
        }

    override val batteryLevel: Int
        // Omnipod Dash doesn't report it's battery level. We return 0 here and hide related fields in the UI
        get() = 0

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        try {
            bolusDeliveryInProgress = true
            aapsLogger.info(LTag.PUMP, "Delivering treatment: $detailedBolusInfo $bolusCanceled")
            if (detailedBolusInfo.carbs > 0 ||
                detailedBolusInfo.insulin == 0.0
            ) {
                // Accept only valid insulin requests
                return PumpEnactResult(injector)
                    .success(false)
                    .enacted(false)
                    .bolusDelivered(0.0)
                    .comment("Invalid input")
            }
            val requestedBolusAmount = detailedBolusInfo.insulin
            if (requestedBolusAmount > reservoirLevel) {
                return PumpEnactResult(injector)
                    .success(false)
                    .enacted(false)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_dash_not_enough_insulin))
            }
            if (podStateManager.deliveryStatus == DeliveryStatus.BOLUS_AND_BASAL_ACTIVE ||
                podStateManager.deliveryStatus == DeliveryStatus.BOLUS_AND_TEMP_BASAL_ACTIVE
            ) {
                return PumpEnactResult(injector)
                    .success(false)
                    .enacted(false)
                    .bolusDelivered(0.0)
                    .comment(rh.gs(R.string.omnipod_dash_bolus_already_in_progress))
            }

            EventOverviewBolusProgress.t = EventOverviewBolusProgress.Treatment(detailedBolusInfo.insulin, 0, detailedBolusInfo.bolusType == DetailedBolusInfo.BolusType.SMB, detailedBolusInfo.id)
            var deliveredBolusAmount = 0.0

            val beepsConfigurationKey = if (detailedBolusInfo.bolusType == DetailedBolusInfo.BolusType.SMB)
                R.string.key_omnipod_common_smb_beeps_enabled
            else
                R.string.key_omnipod_common_bolus_beeps_enabled
            val bolusBeeps = sp.getBoolean(beepsConfigurationKey, false)
            aapsLogger.info(
                LTag.PUMP,
                "deliverTreatment: requestedBolusAmount=$requestedBolusAmount"
            )
            val ret = executeProgrammingCommand(
                pre = observeDeliveryNotCanceled(),
                historyEntry = history.createRecord(
                    commandType = OmnipodCommandType.SET_BOLUS,
                    bolusRecord = BolusRecord(
                        requestedBolusAmount,
                        BolusType.fromBolusInfoBolusType(detailedBolusInfo.bolusType)
                    )
                ),
                activeCommandEntry = { historyId ->
                    podStateManager.createActiveCommand(
                        historyId,
                        requestedBolus = requestedBolusAmount
                    )
                },
                command = omnipodManager.bolus(
                    detailedBolusInfo.insulin,
                    bolusBeeps,
                    bolusBeeps
                ).filter { podEvent -> podEvent.isCommandSent() }
                    .map { pumpSyncBolusStart(requestedBolusAmount, detailedBolusInfo.bolusType) }
                    .ignoreElements(),
                post = waitForBolusDeliveryToComplete(requestedBolusAmount, detailedBolusInfo.bolusType)
                    .map {
                        deliveredBolusAmount = it
                        aapsLogger.info(LTag.PUMP, "deliverTreatment: deliveredBolusAmount=$deliveredBolusAmount")
                    }
                    .ignoreElement()
            ).doFinally {
                if (detailedBolusInfo.bolusType == DetailedBolusInfo.BolusType.SMB) {
                    notifyOnUnconfirmed(
                        Notification.OMNIPOD_UNCERTAIN_SMB,
                        "Unable to verify whether SMB bolus ($requestedBolusAmount U) succeeded. " +
                            "<b>Refresh pod status to confirm or deny this command.",
                        R.raw.boluserror
                    )
                } else {
                    if (podStateManager.activeCommand != null) {
                        val sound =
                            if (sp.getBoolean(
                                    R.string
                                        .key_omnipod_common_notification_uncertain_bolus_sound_enabled, true
                                )
                            ) R.raw.boluserror
                            else 0

                        showErrorDialog("Bolus delivery status uncertain. Refresh pod status to confirm or deny.", sound)
                    }
                }
            }.toSingle {
                PumpEnactResult(injector).success(true).enacted(true).bolusDelivered(deliveredBolusAmount)
            }.onErrorReturnItem(
                // success if canceled
                PumpEnactResult(injector).success(bolusCanceled).enacted(false)
            )
                .blockingGet()
            aapsLogger.info(
                LTag.PUMP,
                "deliverTreatment result: $ret. " +
                    "deliveredBolusAmount=$deliveredBolusAmount. " +
                    "ret bolus=${ret.bolusDelivered}" +
                    "bolusCanceled=$bolusCanceled"
            )
            return ret
        } finally {
            bolusCanceled = false
            bolusDeliveryInProgress = false
        }
    }

    private fun observeDeliveryNotCanceled(): Completable = Completable.defer {
        if (bolusCanceled) {
            Completable.error(java.lang.IllegalStateException("Bolus canceled"))
        } else {
            Completable.complete()
        }
    }

    private fun updateBolusProgressDialog(msg: String, percent: Int) {
        val progressUpdateEvent = EventOverviewBolusProgress
        progressUpdateEvent.status = msg
        progressUpdateEvent.percent = percent
        rxBus.send(progressUpdateEvent)
    }

    private fun waitForBolusDeliveryToComplete(
        requestedBolusAmount: Double,
        bolusType: DetailedBolusInfo.BolusType
    ): Single<Double> = Single.defer {

        if (bolusCanceled && podStateManager.activeCommand != null) {
            var errorGettingStatus: Throwable? = null
            for (tries in 1..BOLUS_RETRIES) {
                try {
                    getPodStatus().blockingAwait()
                    break
                } catch (err: Throwable) {
                    errorGettingStatus = err
                    aapsLogger.debug(LTag.PUMP, "waitForBolusDeliveryToComplete errorGettingStatus=$errorGettingStatus")
                    Thread.sleep(BOLUS_RETRY_INTERVAL_MS) // retry every 2 sec
                }
            }
            if (errorGettingStatus != null) {
                // requestedBolusAmount will be updated later, via pumpSync
                // This state is: cancellation requested and getPodStatus failed 5 times.
                return@defer Single.just(requestedBolusAmount)
            }
        }

        val estimatedDeliveryTimeSeconds = estimateBolusDeliverySeconds(requestedBolusAmount)
        aapsLogger.info(LTag.PUMP, "estimatedDeliveryTimeSeconds: $estimatedDeliveryTimeSeconds")
        var waited = 0
        while (waited < estimatedDeliveryTimeSeconds && !bolusCanceled) {
            waited += 1
            Thread.sleep(1000)
            if (bolusType == DetailedBolusInfo.BolusType.SMB) {
                continue
            }
            val percent = (waited.toFloat() / estimatedDeliveryTimeSeconds) * 100
            updateBolusProgressDialog(
                rh.gs(R.string.bolus_delivered, Round.roundTo(percent*requestedBolusAmount/100, PodConstants.POD_PULSE_BOLUS_UNITS), requestedBolusAmount),
                percent.toInt()
            )
        }

        for (tryNumber in 1..BOLUS_RETRIES) {
            updateBolusProgressDialog("Checking delivery status", 100)

            val cmd = if (bolusCanceled)
                cancelBolus()
            else
                getPodStatus()

            var errorGettingStatus: Throwable? = null
            try {
                cmd.blockingAwait()
            } catch (e: Exception) {
                errorGettingStatus = e
                aapsLogger.debug(LTag.PUMP, "waitForBolusDeliveryToComplete errorGettingStatus=$errorGettingStatus")
                Thread.sleep(BOLUS_RETRY_INTERVAL_MS) // retry every 3 sec
            }
            if (errorGettingStatus != null) {
                continue
            }
            val bolusDeliveringActive = podStateManager.deliveryStatus?.bolusDeliveringActive() ?: false

            if (bolusDeliveringActive) {
                // delivery not complete yet
                val remainingUnits = podStateManager.lastBolus!!.bolusUnitsRemaining
                val percent = ((requestedBolusAmount - remainingUnits) / requestedBolusAmount) * 100
                updateBolusProgressDialog(
                    rh.gs(R.string.bolus_delivered, Round.roundTo(requestedBolusAmount - remainingUnits, PodConstants.POD_PULSE_BOLUS_UNITS), requestedBolusAmount),
                    percent.toInt()
                )

                val sleepSeconds = if (bolusCanceled)
                    BOLUS_RETRY_INTERVAL_MS
                else
                    estimateBolusDeliverySeconds(remainingUnits)
                Thread.sleep(sleepSeconds * 1000.toLong())
            } else {
                // delivery is complete. If pod is Kaput, we are handling this in getPodStatus
                return@defer Single.just(podStateManager.lastBolus!!.deliveredUnits()!!)
            }
        }
        Single.just(requestedBolusAmount) // will be updated later!
    }

    private fun cancelBolus(): Completable {
        val bolusBeeps = sp.getBoolean(R.string.key_omnipod_common_bolus_beeps_enabled, false)
        return executeProgrammingCommand(
            historyEntry = history.createRecord(commandType = OmnipodCommandType.CANCEL_BOLUS),
            command = omnipodManager.stopBolus(bolusBeeps).ignoreElements(),
            checkNoActiveCommand = false,
        )
    }

    private fun estimateBolusDeliverySeconds(requestedBolusAmount: Double): Long {
        return ceil(requestedBolusAmount / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
    }

    private fun pumpSyncBolusStart(
        requestedBolusAmount: Double,
        bolusType: DetailedBolusInfo.BolusType
    ): Boolean {
        require(requestedBolusAmount > 0) { "requestedBolusAmount has to be positive" }

        val activeCommand = podStateManager.activeCommand
        if (activeCommand == null) {
            throw IllegalArgumentException(
                "No active command or illegal podEvent: " +
                    "activeCommand=$activeCommand"
            )
        }
        val historyEntry = history.getById(activeCommand.historyId)
        val ret = pumpSync.syncBolusWithPumpId(
            timestamp = historyEntry.createdAt,
            amount = requestedBolusAmount,
            type = bolusType,
            pumpId = historyEntry.pumpId(),
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.debug(LTag.PUMP, "pumpSyncBolusStart: $ret")
        return ret
    }

    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "stopBolusDelivering called")
        if (bolusDeliveryInProgress) {
            bolusCanceled = true
        }
    }

    override fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val tempBasalBeeps = hasTempBasalBeepEnabled()
        aapsLogger.info(
            LTag.PUMP,
            "setTempBasalAbsolute: " +
                "duration=$durationInMinutes min :: " +
                "rate=$absoluteRate U/h :: " +
                "enforce=$enforceNew ::" +
                "tbrType=$tbrType"
        )

        val ret = executeProgrammingCommand(
            pre = observeNoActiveTempBasal(),
            historyEntry = history.createRecord(
                commandType = OmnipodCommandType.SET_TEMPORARY_BASAL,
                tempBasalRecord = TempBasalRecord(duration = durationInMinutes, rate = absoluteRate)
            ),
            activeCommandEntry = { historyId ->
                podStateManager.createActiveCommand(
                    historyId,
                    tempBasal = OmnipodDashPodStateManager.TempBasal(
                        startTime = System.currentTimeMillis(),
                        rate = absoluteRate,
                        durationInMinutes = durationInMinutes.toShort(),
                    )
                )
            },
            command = omnipodManager.setTempBasal(
                absoluteRate,
                durationInMinutes.toShort(),
                tempBasalBeeps
            )
                .filter { podEvent -> podEvent.isCommandSent() }
                .map { pumpSyncTempBasal(absoluteRate, durationInMinutes.toLong(), tbrType) }
                .ignoreElements(),
        ).doOnComplete {
            notifyOnUnconfirmed(
                Notification.OMNIPOD_TBR_ALERTS,
                "Setting temp basal might have basal failed. If a temp basal was previously running, " +
                    "it has been cancelled. Please manually refresh the Pod status from the Omnipod tab.",
                R.raw.boluserror,
            )
        }.toPumpEnactResult()

        if (ret.success && ret.enacted) {
            ret.isPercent(false).absolute(absoluteRate).duration(durationInMinutes)
        }
        aapsLogger.info(LTag.PUMP, "setTempBasalAbsolute: result=$ret")
        return ret
    }

    private fun pumpSyncTempBasal(
        absoluteRate: Double,
        durationInMinutes: Long,
        tbrType: PumpSync.TemporaryBasalType
    ): Boolean {
        val activeCommand = podStateManager.activeCommand
        if (activeCommand == null) {
            throw IllegalArgumentException(
                "No active command: " +
                    "activeCommand=$activeCommand"
            )
        }
        val historyEntry = history.getById(activeCommand.historyId)
        aapsLogger.debug(
            LTag.PUMP,
            "pumpSyncTempBasal: absoluteRate=$absoluteRate, durationInMinutes=$durationInMinutes pumpId=${historyEntry.pumpId()}"
        )
        val ret = pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = historyEntry.createdAt,
            rate = absoluteRate,
            duration = T.mins(durationInMinutes).msecs(),
            isAbsolute = true,
            type = tbrType,
            pumpId = historyEntry.pumpId(),
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.debug(LTag.PUMP, "pumpSyncTempBasal: $ret")
        return ret
    }

    private fun observeNoActiveTempBasal(): Completable {
        return Completable.defer {
            if (podStateManager.deliveryStatus !in
                arrayOf(DeliveryStatus.TEMP_BASAL_ACTIVE, DeliveryStatus.BOLUS_AND_TEMP_BASAL_ACTIVE)
            ) {
                // TODO: what happens if we try to cancel nonexistent temp basal?
                aapsLogger.info(LTag.PUMP, "No temporary basal to cancel")
                Completable.complete()
            } else {
                // enforceNew == true
                aapsLogger.info(LTag.PUMP, "Canceling existing temp basal")
                executeProgrammingCommand(
                    historyEntry = history.createRecord(OmnipodCommandType.CANCEL_TEMPORARY_BASAL),
                    command = omnipodManager.stopTempBasal(hasTempBasalBeepEnabled()).ignoreElements()
                ).doOnComplete {
                    notifyOnUnconfirmed(
                        Notification.OMNIPOD_TBR_ALERTS,
                        "Cancelling temp basal might have failed." +
                            "If a temp basal was previously running, it might have been cancelled." +
                            "Please manually refresh the Pod status from the Omnipod tab.", // TODO: i8n
                        R.raw.boluserror,
                    )
                }
            }
        }
    }

    override fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        profile: Profile,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        // TODO i18n
        return PumpEnactResult(injector).success(false).enacted(false)
            .comment("Omnipod Dash driver does not support percentage temp basals")
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        // TODO i18n
        return PumpEnactResult(injector).success(false).enacted(false)
            .comment("Omnipod Dash driver does not support extended boluses")
    }

    private fun hasTempBasalBeepEnabled(): Boolean {
        return sp.getBoolean(R.string.key_omnipod_common_tbr_beeps_enabled, false)
    }

    private fun hasBasalBeepEnabled(): Boolean {
        return sp.getBoolean(R.string.key_omnipod_common_basal_beeps_enabled, false)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        if (!podStateManager.tempBasalActive &&
            pumpSync.expectedPumpState().temporaryBasal == null
        ) {
            // nothing to cancel
            return PumpEnactResult(injector).success(true).enacted(false)
        }

        return executeProgrammingCommand(
            historyEntry = history.createRecord(OmnipodCommandType.CANCEL_TEMPORARY_BASAL),
            command = omnipodManager.stopTempBasal(hasTempBasalBeepEnabled()).ignoreElements(),
        ).doOnComplete {
            notifyOnUnconfirmed(
                Notification.OMNIPOD_TBR_ALERTS,
                "Cancel temp basal result is uncertain", // TODO: i8n,
                R.raw.boluserror, // TODO: add setting for this
            )
        }.toPumpEnactResult()
    }

    private fun notifyOnUnconfirmed(notificationId: Int, msg: String, sound: Int?) {
        if (podStateManager.activeCommand != null) {
            aapsLogger.debug(LTag.PUMP, "Notification for active command: ${podStateManager.activeCommand}")
            showNotification(notificationId, msg, Notification.URGENT, sound)
        }
    }

    private fun Completable.toPumpEnactResult(): PumpEnactResult {
        return this.toSingleDefault(PumpEnactResult(injector).success(true).enacted(true))
            .doOnError { throwable ->
                aapsLogger.error(LTag.PUMP, "toPumpEnactResult, error executing command: $throwable")
            }
            .onErrorReturnItem(PumpEnactResult(injector).success(false).enacted(false))
            .blockingGet()
    }

    override fun cancelExtendedBolus(): PumpEnactResult {
        // TODO i18n
        return PumpEnactResult(injector).success(false).enacted(false)
            .comment("Omnipod Dash driver does not support extended boluses")
    }

    override fun getJSONStatus(profile: Profile, profileName: String, version: String): JSONObject {
        val now = System.currentTimeMillis()
        if (podStateManager.lastUpdatedSystem + 60 * 60 * 1000L < now) {
            return JSONObject()
        }
        val pumpJson = JSONObject()
        val status = JSONObject()
        val extended = JSONObject()
        try {
            val podStatus = when {
                podStateManager.isPodRunning && podStateManager.isSuspended -> "suspended"
                podStateManager.isPodRunning                                -> "normal"
                else                                                        -> "no active Pod"
            }
            status.put("status", podStatus)
            status.put("timestamp", dateUtil.toISOString(podStateManager.lastUpdatedSystem))

            extended.put("Version", version)
            try {
                extended.put("ActiveProfile", profileName)
            } catch (ignored: Exception) {
            }
            val tb = pumpSync.expectedPumpState().temporaryBasal
            tb?.run {
                extended.put("TempBasalAbsoluteRate", this.convertedToAbsolute(now, profile))
                extended.put("TempBasalStart", dateUtil.dateAndTimeString(this.timestamp))
                extended.put("TempBasalRemaining", this.plannedRemainingMinutes)
            }
            podStateManager.lastBolus?.run {
                extended.put("LastBolus", dateUtil.dateAndTimeString(this.startTime))
                extended.put("LastBolusAmount", this.deliveredUnits() ?: this.requestedUnits)
            }
            extended.put("BaseBasalRate", baseBasalRate)

            pumpJson.put("status", status)
            pumpJson.put("extended", extended)
            if (podStateManager.pulsesRemaining == null) {
                pumpJson.put("reservoir_display_override", "50+")
            }
            pumpJson.put("reservoir", reservoirLevel.toInt())
            pumpJson.put("clock", dateUtil.toISOString(now))
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Unhandled exception: $e")
        }
        return pumpJson
    }

    override val pumpDescription: PumpDescription = Companion.pumpDescription

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Insulet
    }

    override fun model(): PumpType {
        return pumpDescription.pumpType
    }

    override fun serialNumber(): String {
        return podStateManager.uniqueId?.toString()
            ?: "n/a" // TODO i18n
    }

    override fun shortStatus(veryShort: Boolean): String {
        if (!podStateManager.isActivationCompleted) {
            return rh.gs(R.string.omnipod_common_short_status_no_active_pod)
        }
        var ret = ""
        if (podStateManager.lastUpdatedSystem != 0L) {
            val agoMsec: Long = System.currentTimeMillis() - podStateManager.lastUpdatedSystem
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            ret += rh.gs(R.string.omnipod_common_short_status_last_connection, agoMin) + "\n"
        }
        podStateManager.lastBolus?.run {
            ret += rh.gs(
                R.string.omnipod_common_short_status_last_bolus, to2Decimal(this.deliveredUnits() ?: this.requestedUnits),
                DateFormat.format("HH:mm", Date(this.startTime))
            ) + "\n"
        }
        val temporaryBasal = pumpSync.expectedPumpState().temporaryBasal
        temporaryBasal?.run {
            ret += rh.gs(
                R.string.omnipod_common_short_status_temp_basal,
                this.toStringFull(dateUtil)
            ) + "\n"
        }
        ret += rh.gs(
            R.string.omnipod_common_short_status_reservoir,
            podStateManager.pulsesRemaining?.let { reservoirLevel.toString() } ?: "50+"
        )
        return ret.trim()
    }

    override val isFakingTempsByExtendedBoluses: Boolean
        get() = false

    override fun loadTDDs(): PumpEnactResult {
        // TODO i18n
        return PumpEnactResult(injector).success(false).enacted(false)
            .comment("Omnipod Dash driver does not support TDD")
    }

    override fun canHandleDST(): Boolean {
        return false
    }

    override fun getCustomActions(): List<CustomAction> {
        return emptyList()
    }

    override fun executeCustomAction(customActionType: CustomActionType) {
        aapsLogger.warn(LTag.PUMP, "Unsupported custom action: $customActionType")
    }

    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult {
        return when (customCommand) {
            is CommandSilenceAlerts            ->
                silenceAlerts()
            is CommandResumeDelivery           ->
                resumeDelivery()
            is CommandDeactivatePod            ->
                deactivatePod()
            is CommandHandleTimeChange         ->
                handleTimeChange()
            is CommandUpdateAlertConfiguration ->
                updateAlertConfiguration()
            is CommandPlayTestBeep             ->
                playTestBeep()
            is CommandDisableSuspendAlerts     ->
                disableSuspendAlerts()

            else                               -> {
                aapsLogger.warn(LTag.PUMP, "Unsupported custom command: " + customCommand.javaClass.name)
                PumpEnactResult(injector).success(false).enacted(false).comment(
                    rh.gs(
                        R.string.omnipod_common_error_unsupported_custom_command,
                        customCommand.javaClass.name
                    )
                )
            }
        }
    }

    private fun silenceAlerts(): PumpEnactResult {
        // TODO filter alert types
        return podStateManager.activeAlerts?.let {
            executeProgrammingCommand(
                historyEntry = history.createRecord(commandType = OmnipodCommandType.ACKNOWLEDGE_ALERTS),
                command = omnipodManager.silenceAlerts(it).ignoreElements(),
            ).toPumpEnactResult()
        } ?: PumpEnactResult(injector).success(false).enacted(false).comment("No active alerts") // TODO i18n
    }

    private fun disableSuspendAlerts(): PumpEnactResult {
        val alerts = listOf(
            AlertConfiguration(
                AlertType.SUSPEND_ENDED,
                enabled = false,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.TimerTrigger(
                    0
                ),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            ),
        )
        val ret = executeProgrammingCommand(
            historyEntry = history.createRecord(OmnipodCommandType.CONFIGURE_ALERTS),
            command = omnipodManager.programAlerts(alerts).ignoreElements(),
        ).toPumpEnactResult()
        if (ret.success && ret.enacted) {
            podStateManager.suspendAlertsEnabled = false
        }
        return ret
    }

    private fun observeDeliveryActive(): Completable = Completable.defer {
        if (podStateManager.deliveryStatus != DeliveryStatus.SUSPENDED)
            Completable.complete()
        else
            Completable.error(java.lang.IllegalStateException("Expected active delivery"))
    }

    private fun resumeDelivery(): PumpEnactResult {
        return profileFunction.getProfile()?.let {
            executeProgrammingCommand(
                pre = observeDeliverySuspended(),
                historyEntry = history.createRecord(OmnipodCommandType.RESUME_DELIVERY, basalProfileRecord = BasalValuesRecord(it.getBasalValues().toList())),
                command = omnipodManager.setBasalProgram(mapProfileToBasalProgram(it), hasBasalBeepEnabled())
                    .ignoreElements()
            ).doFinally {
                notifyOnUnconfirmed(
                    Notification.FAILED_UPDATE_PROFILE,
                    "Unconfirmed resumeDelivery command. Please refresh pod status",
                    R.raw.boluserror
                )
            }.toPumpEnactResult()
        } ?: PumpEnactResult(injector).success(false).enacted(false).comment("No profile active") // TODO i18n
    }

    private fun deactivatePod(): PumpEnactResult {
        var success = true
        val ret = executeProgrammingCommand(
            historyEntry = history.createRecord(OmnipodCommandType.DEACTIVATE_POD),
            command = omnipodManager.deactivatePod().ignoreElements(),
            checkNoActiveCommand = false
        ).doOnComplete {
            if (podStateManager.activeCommand != null) {
                success = false
            } else {
                podStateManager.reset()
                rxBus.send(EventDismissNotification(Notification.OMNIPOD_POD_FAULT))
            }
        }.toPumpEnactResult()
        if (!success) {
            ret.success(false)
        }
        return ret
    }

    private fun handleTimeChange(): PumpEnactResult {
        return profileFunction.getProfile()?.let {
            setNewBasalProfile(it, OmnipodCommandType.SET_TIME)
        } ?: PumpEnactResult(injector).success(false).enacted(false).comment("No profile active")
    }

    private fun updateAlertConfiguration(): PumpEnactResult {

        val expirationReminderEnabled = sp.getBoolean(R.string.key_omnipod_common_expiration_reminder_enabled, true)
        val expirationHours = sp.getInt(R.string.key_omnipod_common_expiration_reminder_hours_before_shutdown, 7)
        val lowReservoirAlertEnabled = sp.getBoolean(R.string.key_omnipod_common_low_reservoir_alert_enabled, true)
        val lowReservoirAlertUnits = sp.getInt(R.string.key_omnipod_common_low_reservoir_alert_units, 10)

        when {
            podStateManager.sameAlertSettings(
                expirationReminderEnabled,
                expirationHours,
                lowReservoirAlertEnabled,
                lowReservoirAlertUnits
            )                             -> {
                aapsLogger.debug(LTag.PUMP, "Ignoring updateAlertConfiguration because the settings did not change")
                return PumpEnactResult(injector).success(true).enacted(false)
            }

            !podStateManager.isPodRunning -> {
                aapsLogger.debug(LTag.PUMP, "Ignoring updateAlertConfiguration because there is no active pod")
                return PumpEnactResult(injector).success(true).enacted(false)
            }
        }

        val podLifeLeft = Duration.between(ZonedDateTime.now(), podStateManager.expiry)
        val expiryAlertDelay = podLifeLeft.minus(Duration.ofHours(expirationHours.toLong()))
        if (expiryAlertDelay.isNegative) {
            aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "updateAlertConfiguration negative " +
                    "expiryAlertDuration=$expiryAlertDelay"
            )
            PumpEnactResult(injector).success(false).enacted(false)
        }
        val alerts = listOf(
            AlertConfiguration(
                AlertType.LOW_RESERVOIR,
                enabled = lowReservoirAlertEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.ReservoirVolumeTrigger((lowReservoirAlertUnits * 10).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX
            ),
            AlertConfiguration(
                AlertType.USER_SET_EXPIRATION,
                enabled = expirationReminderEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.TimerTrigger(
                    expiryAlertDelay.toMinutes().toShort()
                ),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            )
        )
        return executeProgrammingCommand(
            historyEntry = history.createRecord(OmnipodCommandType.CONFIGURE_ALERTS),
            command = omnipodManager.programAlerts(alerts).ignoreElements(),
            post = podStateManager.updateExpirationAlertSettings(
                expirationReminderEnabled,
                expirationHours
            ).andThen(
                podStateManager.updateLowReservoirAlertSettings(
                    lowReservoirAlertEnabled,
                    lowReservoirAlertUnits
                )
            )
        ).toPumpEnactResult()
    }

    private fun playTestBeep(): PumpEnactResult {
        return executeProgrammingCommand(
            historyEntry = history.createRecord(OmnipodCommandType.PLAY_TEST_BEEP),
            command = omnipodManager.playBeep(BeepType.LONG_SINGLE_BEEP).ignoreElements()
        ).toPumpEnactResult()
    }

    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        aapsLogger.info(LTag.PUMP, "Ignoring time change because automatic time handling is not implemented. timeChangeType=${timeChangeType.name}")
    }

    private fun executeProgrammingCommand(
        pre: Completable = Completable.complete(),
        historyEntry: Single<Long>,
        activeCommandEntry: (historyId: Long) -> Single<OmnipodDashPodStateManager.ActiveCommand> =
            { historyId -> podStateManager.createActiveCommand(historyId) },
        command: Completable,
        post: Completable = Completable.complete(),
        checkNoActiveCommand: Boolean = true
    ): Completable {
        return Completable.concat(
            listOf(
                pre,
                if (checkNoActiveCommand)
                    podStateManager.observeNoActiveCommand()
                else
                    Completable.complete(),
                historyEntry
                    .flatMap { activeCommandEntry(it) }
                    .ignoreElement(),
                command.doOnError {
                    podStateManager.activeCommand?.sendError = it
                    aapsLogger.error(LTag.PUMP, "Error executing command", it)
                }.onErrorComplete(),
                history.updateFromState(podStateManager),
                podStateManager.updateActiveCommand()
                    .map { handleCommandConfirmation(it) }
                    .ignoreElement(),
                checkPodKaput(),
                refreshOverview(),
                post,
            )
        )
    }

    private fun refreshOverview(): Completable = Completable.defer {
        rxBus.send(EventRefreshOverview("Dash command", false))
        Completable.complete()
    }

    private fun handleCommandConfirmation(confirmation: CommandConfirmed) {
        val command = confirmation.command
        val historyEntry = history.getById(command.historyId)
        aapsLogger.debug(LTag.PUMPCOMM, "handling command confirmation: $confirmation ${historyEntry.commandType}")
        when (historyEntry.commandType) {
            OmnipodCommandType.CANCEL_TEMPORARY_BASAL -> {
                if (confirmation.success) {
                    val ret = pumpSync.syncStopTemporaryBasalWithPumpId(
                        historyEntry.createdAt,
                        historyEntry.pumpId(),
                        PumpType.OMNIPOD_DASH,
                        serialNumber()
                    )
                    aapsLogger.info(LTag.PUMP, "syncStopTemporaryBasalWithPumpId ret=$ret pumpId=${historyEntry.pumpId()}")
                    podStateManager.tempBasal = null
                }
                rxBus.send(EventDismissNotification(Notification.OMNIPOD_TBR_ALERTS))
            }

            OmnipodCommandType.RESUME_DELIVERY        -> {
                // We can't invalidate this command,
                // and this is why it is pumpSync-ed at this point
                if (confirmation.success) {
                    pumpSync.syncStopTemporaryBasalWithPumpId(
                        historyEntry.createdAt,
                        historyEntry.pumpId(),
                        PumpType.OMNIPOD_DASH,
                        serialNumber()
                    )
                    podStateManager.tempBasal = null
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_POD_SUSPENDED))
                    rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_TBR_ALERTS))
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_TIME_OUT_OF_SYNC))
                    commandQueue.customCommand(CommandDisableSuspendAlerts(), null)
                }
            }

            OmnipodCommandType.SET_BASAL_PROFILE      -> {
                if (confirmation.success) {
                    podStateManager.basalProgram = command.basalProgram
                    if (podStateManager.basalProgram == null) {
                        aapsLogger.warn(LTag.PUMP, "Saving null basal profile")
                    }
                    if (!commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)) {
                        // we are late-confirming this command. before that, we answered with success:false
                        rxBus.send(EventProfileSwitchChanged())
                    }
                    pumpSync.syncStopTemporaryBasalWithPumpId(
                        historyEntry.createdAt,
                        historyEntry.pumpId(),
                        PumpType.OMNIPOD_DASH,
                        serialNumber()
                    )
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_POD_SUSPENDED))
                    rxBus.send(EventDismissNotification(Notification.FAILED_UPDATE_PROFILE))
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_TBR_ALERTS))
                    rxBus.send(EventDismissNotification(Notification.OMNIPOD_TIME_OUT_OF_SYNC))
                    commandQueue.customCommand(CommandDisableSuspendAlerts(), null)
                }
            }

            OmnipodCommandType.SET_TEMPORARY_BASAL    -> {
                // This treatment was synced before sending the command
                if (!confirmation.success) {
                    aapsLogger.info(LTag.PUMPCOMM, "temporary basal denied. PumpId: ${historyEntry.pumpId()}")
                    pumpSync.invalidateTemporaryBasalWithPumpId(
                        historyEntry.pumpId(),
                        PumpType.OMNIPOD_DASH,
                        serialNumber()
                    )
                } else {
                    podStateManager.tempBasal = command.tempBasal
                }
                rxBus.send(EventDismissNotification(Notification.OMNIPOD_TBR_ALERTS))
            }

            OmnipodCommandType.SUSPEND_DELIVERY       -> {
                if (!confirmation.success) {
                    pumpSync.invalidateTemporaryBasalWithPumpId(
                        historyEntry.pumpId(),
                        PumpType.OMNIPOD_DASH,
                        serialNumber()
                    )
                } else {
                    podStateManager.tempBasal = null
                }
            }

            OmnipodCommandType.SET_BOLUS              -> {
                if (confirmation.success) {
                    if (command.requestedBolus == null) {
                        aapsLogger.error(LTag.PUMP, "Requested bolus not found: $command")
                    }
                    val record = historyEntry.record
                    if (record !is BolusRecord) {
                        aapsLogger.error(
                            LTag.PUMP,
                            "Expected SET_BOLUS history record to be a BolusRecord, found " +
                                "$record"
                        )
                    }
                    record as BolusRecord

                    podStateManager.createLastBolus(
                        record.amout,
                        command.historyId,
                        record.bolusType.toBolusInfoBolusType()
                    )
                } else {
                    pumpSync.syncBolusWithPumpId(
                        timestamp = historyEntry.createdAt,
                        amount = 0.0,
                        pumpId = historyEntry.pumpId(),
                        pumpType = PumpType.OMNIPOD_DASH,
                        pumpSerial = serialNumber(),
                        type = null
                    )
                }
                rxBus.send(EventDismissNotification(Notification.OMNIPOD_UNCERTAIN_SMB))
            }

            OmnipodCommandType.CANCEL_BOLUS           -> {
                if (confirmation.success) {
                    podStateManager.lastBolus?.run {
                        val deliveredUnits = markComplete()
                        if (deliveredUnits < 0) {
                            aapsLogger.error(LTag.PUMP, "Negative delivered units!!! $deliveredUnits")
                            return
                        }
                        val bolusHistoryEntry = history.getById(historyId)
                        val sync = pumpSync.syncBolusWithPumpId(
                            timestamp = bolusHistoryEntry.createdAt,
                            amount = deliveredUnits,
                            pumpId = bolusHistoryEntry.pumpId(),
                            pumpType = PumpType.OMNIPOD_DASH,
                            pumpSerial = serialNumber(),
                            type = bolusType
                        )
                        aapsLogger.info(LTag.PUMP, "syncBolusWithPumpId on CANCEL_BOLUS returned: $sync")
                    } ?: aapsLogger.error(LTag.PUMP, "Cancelled bolus that does not exist")
                }
            }

            else                                      ->
                aapsLogger.warn(
                    LTag.PUMP,
                    "Will not sync confirmed command of type: $historyEntry and " +
                        "success: ${confirmation.success}"
                )
        }
    }

    private fun showErrorDialog(message: String, sound: Int) {
        runAlarm(context, message, rh.gs(R.string.error), sound)
    }

    private fun showNotification(id: Int, message: String, urgency: Int, sound: Int?) {
        val notification = Notification(
            id,
            message,
            urgency
        )
        if (sound != null && soundEnabledForNotificationType(id)) {
            notification.soundId = sound
        }
        rxBus.send(EventNewNotification(notification))
    }

    private fun soundEnabledForNotificationType(notificationType: Int): Boolean {
        return when (notificationType) {
            Notification.OMNIPOD_TBR_ALERTS    ->
                sp.getBoolean(R.string.key_omnipod_common_notification_uncertain_tbr_sound_enabled, true)
            Notification.OMNIPOD_UNCERTAIN_SMB ->
                sp.getBoolean(R.string.key_omnipod_common_notification_uncertain_smb_sound_enabled, true)
            Notification.OMNIPOD_POD_SUSPENDED ->
                sp.getBoolean(R.string.key_omnipod_common_notification_delivery_suspended_sound_enabled, true)
            else                               -> true
        }
    }
}
