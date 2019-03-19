/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.application

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.DeviceService.Companion.CACHE_RECORDS_UNSENT_NUMBER
import org.radarbase.android.device.DeviceService.Companion.CACHE_TOPIC
import org.radarbase.android.device.DeviceService.Companion.SERVER_RECORDS_SENT_NUMBER
import org.radarbase.android.device.DeviceService.Companion.SERVER_RECORDS_SENT_TOPIC
import org.radarbase.android.device.DeviceService.Companion.SERVER_STATUS_CHANGED
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.kafka.ServerStatusListener
import org.radarbase.android.util.BroadcastRegistration
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.register
import org.radarcns.application.ApplicationStatusService.Companion.UPDATE_RATE_DEFAULT
import org.radarcns.kafka.ObservationKey
import org.radarcns.monitor.application.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.max

class ApplicationStatusManager internal constructor(service: ApplicationStatusService) : AbstractDeviceManager<ApplicationStatusService, ApplicationState>(service) {
    private val serverTopic: DataCache<ObservationKey, ApplicationServerStatus>
    private val recordCountsTopic: DataCache<ObservationKey, ApplicationRecordCounts>
    private val uptimeTopic: DataCache<ObservationKey, ApplicationUptime>
    private val ntpTopic: DataCache<ObservationKey, ApplicationExternalTime>
    private val timeZoneTopic: DataCache<ObservationKey, ApplicationTimeZone>
    private val deviceInfoTopic: DataCache<ObservationKey, ApplicationDeviceInfo>

    private val processor: OfflineProcessor
    private val creationTimeStamp: Long = SystemClock.elapsedRealtime()
    private val sntpClient: SntpClient
    private val prefs: SharedPreferences = service.getSharedPreferences(ApplicationStatusManager::class.java.name, Context.MODE_PRIVATE)
    private lateinit var storedDeviceInfo: ApplicationDeviceInfo
    private var tzProcessor: OfflineProcessor? = null

    @get:Synchronized
    @set:Synchronized
    var isProcessingIp: Boolean = false

    @get:Synchronized
    @set:Synchronized
    var ntpServer: String? = null
        set(value) {
            field = value
                    ?.let { it.trim { c -> c <= ' ' } }
                    ?.takeIf(String::isNotEmpty)
        }

    private var previousInetAddress: InetAddress? = null
    private var previousOffset: Int = -1

    private lateinit var serverStatusReceiver: BroadcastRegistration
    private lateinit var serverRecordsReceiver: BroadcastRegistration
    private lateinit var cacheReceiver: BroadcastRegistration

    init {
        this.isProcessingIp = false
        serverTopic = createCache("application_server_status", ApplicationServerStatus::class.java)
        recordCountsTopic = createCache("application_record_counts", ApplicationRecordCounts::class.java)
        uptimeTopic = createCache("application_uptime", ApplicationUptime::class.java)
        ntpTopic = createCache("application_external_time", ApplicationExternalTime::class.java)
        timeZoneTopic = createCache("application_time_zone", ApplicationTimeZone::class.java)
        deviceInfoTopic = createCache("application_device_info", ApplicationDeviceInfo::class.java)
        sntpClient = SntpClient()

        this.processor = OfflineProcessor(service) {
            process = listOf(
                    this@ApplicationStatusManager::processServerStatus,
                    this@ApplicationStatusManager::processUptime,
                    this@ApplicationStatusManager::processRecordsSent,
                    this@ApplicationStatusManager::processReferenceTime,
                    this@ApplicationStatusManager::processDeviceInfo)
            requestCode = APPLICATION_PROCESSOR_REQUEST_CODE
            requestName = APPLICATION_PROCESSOR_REQUEST_NAME
            interval(UPDATE_RATE_DEFAULT, SECONDS)
            wake = false
        }
    }

    override fun start(acceptableIds: Set<String>) {
        updateStatus(DeviceStatusListener.Status.READY)

        processor.start {
            val osVersionCode: Int = this.prefs.getInt("operatingSystemVersionCode", -1)
            val appVersionCode: Int = this.prefs.getInt("appVersionCode", -1)
            this.storedDeviceInfo = ApplicationDeviceInfo(
                    0.0,
                    this.prefs.getString("manufacturer", null),
                    this.prefs.getString("model", null),
                    OperatingSystem.ANDROID,
                    this.prefs.getString("operatingSystemVersion", null),
                    if (osVersionCode > 0) osVersionCode else null,
                    this.prefs.getString("appVersion", null),
                    if (appVersionCode > 0) appVersionCode else null)

            val deviceInfo = currentDeviceInfo

            register(name = "pRMT", attributes = mapOf(
                    Pair("manufacturer", deviceInfo.getManufacturer()),
                    Pair("model", deviceInfo.getModel()),
                    Pair("operatingSystem", deviceInfo.getOperatingSystem().toString()),
                    Pair("operatingSystemVersion", deviceInfo.getOperatingSystemVersion().toString()),
                    Pair("appVersion", deviceInfo.getAppVersion()),
                    Pair("appVersionCode", appVersionCode.toString()),
                    Pair("appName", service.application.packageName)))

            this.previousOffset = this.prefs.getInt("timeZoneOffset", -1)
        }
        tzProcessor?.start()

        logger.info("Starting ApplicationStatusManager")
        LocalBroadcastManager.getInstance(service).apply {
            serverStatusReceiver = register(SERVER_STATUS_CHANGED) { _, intent ->
                state.serverStatus = ServerStatusListener.Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, 0)]
            }
            serverRecordsReceiver = register(SERVER_RECORDS_SENT_TOPIC) { _, intent ->
                val numberOfRecordsSent = intent.getIntExtra(SERVER_RECORDS_SENT_NUMBER, 0)
                state.addRecordsSent(max(numberOfRecordsSent, 0))
            }
            cacheReceiver = register(CACHE_TOPIC) { _, intent ->
                val topic = intent.getStringExtra(CACHE_TOPIC)
                val records = intent.getLongExtra(CACHE_RECORDS_UNSENT_NUMBER, 0)
                state.cachedRecords[topic] = max(records, 0)
            }
        }

        updateStatus(DeviceStatusListener.Status.CONNECTED)
    }

    private val currentDeviceInfo: ApplicationDeviceInfo
        get() {
            val packageInfo = try {
                service.packageManager.getPackageInfo(service.packageName, 0)
            } catch (ex: PackageManager.NameNotFoundException) {
                logger.error("Cannot find package info for pRMT app")
                null
            }

            return ApplicationDeviceInfo(
                    System.currentTimeMillis() / 1000.0,
                    Build.MANUFACTURER,
                    Build.MODEL,
                    OperatingSystem.ANDROID,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                    packageInfo?.versionName,
                    packageInfo?.versionCode)
        }

    // using versionCode
    private fun processDeviceInfo() {
        val deviceInfo = currentDeviceInfo
        if (deviceInfo.getManufacturer() != storedDeviceInfo.getManufacturer()
                || deviceInfo.getModel() != storedDeviceInfo.getModel()
                || deviceInfo.getOperatingSystemVersion() != storedDeviceInfo.getOperatingSystemVersion()
                || deviceInfo.getOperatingSystemVersionCode() != storedDeviceInfo.getOperatingSystemVersionCode()
                || deviceInfo.getAppVersion() != storedDeviceInfo.getAppVersion()
                || deviceInfo.getAppVersionCode() != storedDeviceInfo.getAppVersionCode()) {

            send(deviceInfoTopic, deviceInfo)
            storedDeviceInfo = deviceInfo
            prefs.edit()
                    .putString("manufacturer", deviceInfo.getManufacturer())
                    .putString("model", deviceInfo.getModel())
                    .putString("operatingSystemVersion", deviceInfo.getOperatingSystemVersion())
                    .putInt("operatingSystemVersionCode", deviceInfo.getOperatingSystemVersionCode() ?: -1)
                    .putString("appVersion", deviceInfo.getAppVersion())
                    .putInt("appVersionCode", deviceInfo.getAppVersionCode() ?: -1)
                    .apply()
        }
    }

    private fun processReferenceTime() {
        ntpServer?.let { server ->
            if (sntpClient.requestTime(server, 5000)) {
                val delay = sntpClient.roundTripTime / 1000.0
                val time = currentTime
                val ntpTime = (sntpClient.ntpTime + SystemClock.elapsedRealtime() - sntpClient.ntpTimeReference) / 1000.0

                send(ntpTopic, ApplicationExternalTime(time, ntpTime,
                        server, ExternalTimeProtocol.SNTP, delay))
            }
        }
    }

    private fun processServerStatus() {
        val time = currentTime

        val status: ServerStatus = when (state.serverStatus) {
            ServerStatusListener.Status.CONNECTED, ServerStatusListener.Status.READY, ServerStatusListener.Status.UPLOADING -> ServerStatus.CONNECTED
            ServerStatusListener.Status.DISCONNECTED, ServerStatusListener.Status.DISABLED, ServerStatusListener.Status.UPLOADING_FAILED -> ServerStatus.DISCONNECTED
            else -> ServerStatus.UNKNOWN
        }
        val ipAddress = if (isProcessingIp) lookupIpAddress() else null
        logger.info("Server Status: {}; Device IP: {}", status, ipAddress)

        send(serverTopic, ApplicationServerStatus(time, status, ipAddress))
    }

    // Find Ip via NetworkInterfaces. Works via wifi, ethernet and mobile network
    // This finds both xx.xx.xx ip and rmnet. Last one is always ip.
    private fun lookupIpAddress(): String? {
        previousInetAddress = try {
            previousInetAddress?.takeUnless { NetworkInterface.getByInetAddress(it) == null }
                    ?: NetworkInterface.getNetworkInterfaces().asSequence()
                            .flatMap { it.inetAddresses.asSequence() }
                            .findLast { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        } catch (ex: SocketException) {
            logger.warn("No IP Address could be determined", ex)
            null
        }

        return previousInetAddress?.hostAddress
    }

    private fun processUptime() {
        val time = currentTime
        val uptime = (SystemClock.elapsedRealtime() - creationTimeStamp) / 1000.0
        send(uptimeTopic, ApplicationUptime(time, uptime))
    }

    private fun processRecordsSent() {
        val time = currentTime

        var recordsCachedUnsent = state.cachedRecords.values.sum().toIntCapped()
        val recordsCachedSent = 0

        for (records in state.cachedRecords.values) {
            if (records != NUMBER_UNKNOWN) {
                recordsCachedUnsent += records.toInt()
            }
        }
        val recordsCached = recordsCachedUnsent + recordsCachedSent
        val recordsSent = state.recordsSent.toIntCapped()

        logger.info("Number of records: {sent: {}, unsent: {}, cached: {}}",
                recordsSent, recordsCachedUnsent, recordsCached)
        send(recordCountsTopic, ApplicationRecordCounts(time,
                recordsCached, recordsSent, recordsCachedUnsent))
    }

    @Throws(IOException::class)
    override fun close() {
        if (isClosed) {
            return
        }
        super.close()

        logger.info("Closing ApplicationStatusManager")
        this.processor.close()
        cacheReceiver.unregister()
        serverRecordsReceiver.unregister()
        serverStatusReceiver.unregister()
    }

    private fun processTimeZone() {
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val offset = tz.getOffset(now) / 1000
        if (offset != previousOffset) {
            send(timeZoneTopic, ApplicationTimeZone(now / 1000.0, offset))
            previousOffset = offset
            prefs.edit()
                    .putInt("timeZoneOffset", offset)
                    .apply()
        }
    }

    @Synchronized
    fun setTzUpdateRate(tzUpdateRate: Long, unit: TimeUnit) {
        if (tzUpdateRate > 0) {
            if (this.tzProcessor == null) {
                this.tzProcessor = OfflineProcessor(service) {
                    process = listOf(this@ApplicationStatusManager::processTimeZone)
                    requestCode = APPLICATION_TZ_PROCESSOR_REQUEST_CODE
                    requestName = APPLICATION_TZ_PROCESSOR_REQUEST_NAME
                    interval(tzUpdateRate, unit)
                    wake = false
                }.also {
                    if (this.state.status == DeviceStatusListener.Status.CONNECTED) {
                        it.start()
                    }
                }
            } else {
                this.tzProcessor!!.interval(tzUpdateRate, unit)
            }
        } else {
            this.tzProcessor?.close()
            this.tzProcessor = null
        }
    }

    fun setApplicationStatusUpdateRate(period: Long, unit: TimeUnit) {
        processor.interval(period, unit)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ApplicationStatusManager::class.java)
        private const val NUMBER_UNKNOWN = -1L
        private const val APPLICATION_PROCESSOR_REQUEST_CODE = 72553575
        private const val APPLICATION_TZ_PROCESSOR_REQUEST_CODE = 72553576
        private const val APPLICATION_PROCESSOR_REQUEST_NAME = "org.radarcns.application.ApplicationStatusManager"
        private const val APPLICATION_TZ_PROCESSOR_REQUEST_NAME = "$APPLICATION_PROCESSOR_REQUEST_NAME.timeZone"

        private fun Long.toIntCapped(): Int = if (this <= Int.MAX_VALUE) toInt() else Int.MAX_VALUE
    }
}
