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

package org.radarcns.application;

import android.os.Bundle;
import android.support.annotation.NonNull;

import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.device.DeviceServiceProvider;

import java.util.Collections;
import java.util.List;

public class ApplicationServiceProvider extends DeviceServiceProvider<ApplicationState> {
    private static final String PREFIX = ApplicationServiceProvider.class.getPackage().getName() + '.';
    private static final String UPDATE_RATE = "application_status_update_rate";
    private static final String TZ_UPDATE_RATE = "application_time_zone_update_rate";
    private static final String SEND_IP = "application_send_ip";
    private static final long UPDATE_RATE_DEFAULT = 300L; // seconds == 5 minutes
    private static final long TZ_UPDATE_RATE_DEFAULT = 86400L; // seconds == 1 day
    private static final String NTP_SERVER_CONFIG = "ntp_server";
    public static final String SEND_IP_KEY = PREFIX + SEND_IP;
    public static final String UPDATE_RATE_KEY = PREFIX + UPDATE_RATE;
    public static final String TZ_UPDATE_RATE_KEY = PREFIX + TZ_UPDATE_RATE;
    public static final String NTP_SERVER_KEY = PREFIX + NTP_SERVER_CONFIG;


    @Override
    public String getDescription() {
        return getRadarService().getString(R.string.application_status_description);
    }

    @Override
    public Class<?> getServiceClass() {
        return ApplicationStatusService.class;
    }

    @Override
    public boolean isDisplayable() {
        return false;
    }

    @Override
    public List<String> needsPermissions() {
        return Collections.emptyList();
    }

    @Override
    protected void configure(Bundle bundle) {
        super.configure(bundle);
        RadarConfiguration config = getConfig();
        bundle.putLong(UPDATE_RATE_KEY, config.getLong(UPDATE_RATE, UPDATE_RATE_DEFAULT));
        bundle.putString(NTP_SERVER_KEY, config.getString(NTP_SERVER_CONFIG, null));
        bundle.putBoolean(SEND_IP_KEY, config.getBoolean(SEND_IP, false));
        bundle.putLong(TZ_UPDATE_RATE_KEY, config.getLong(TZ_UPDATE_RATE, TZ_UPDATE_RATE_DEFAULT));
    }

    @Override
    public String getDisplayName() {
        return getRadarService().getString(R.string.applicationServiceDisplayName);
    }

    @Override
    @NonNull
    public String getDeviceProducer() {
        return "RADAR";
    }

    @Override
    @NonNull
    public String getDeviceModel() {
        return "pRMT";
    }

    @Override
    @NonNull
    public String getVersion() {
        return BuildConfig.VERSION_NAME;
    }
}
