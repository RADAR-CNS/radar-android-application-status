# Application status plugin for RADAR-pRMT

[![Build Status](https://travis-ci.org/RADAR-CNS/radar-android-application-status.svg?branch=master)](https://travis-ci.org/RADAR-CNS/radar-android-application-status)

Plugin that sends application statuses about the RADAR pRMT app.

## Installation

First, add the plugin code to your application:

```gradle
repositories {
    maven { url  'http://dl.bintray.com/radar-cns/org.radarcns' }
}

dependencies {
    compile 'org.radarcns:radar-android-application-status:0.2.2'
}
```

Set the `ntp_server` property in the configuration settings to synchronize with an external NTP server. Set the `application_status_update_rate` (in seconds) to change the frequency of messages sent.

## Configuration

This plugin takes the following Firebase configuration parameters:

| Name | Type | Default | Description |
| ---- | ---- | ------- | ----------- |
| `ntp_server` | string | `<empty>` | NTP server to synchronize time with. If empty, time is not synchronized and the `application_external_time` topic will not receive data. |
| `application_status_update_rate` | int (seconds) | `300` = 5 minutes | Rate at which to send data for all application topics. |
| `application_send_ip` | boolean | `false` | Whether to send the device IP address with the server status. |
| `application_time_zone_update_rate` | int (seconds) | `86400` = 1 day | How often to send the current time zone. Set to `0` to disable. |

This plugin produces data for the following topics: (types starts with `org.radarcns.monitor.application` prefix)

| Topic | Type | Description |
| ----- | ---- | ----------- |
| `application_external_time` | `ApplicationExternalTime` | External NTP time. Requires `ntp_server` parameter to be set. |
| `application_record_counts` | `ApplicationRecordCounts` | Number of records sent and in queue. |
| `application_uptime` | `ApplicationUptime` | Time since the device booted. |
| `application_server_status` | `ApplicationServerStatus` | Server connection status. |
| `application_time_zone` | `ApplicationTimeZone` | Application time zone. |

## Contributing

Code should be formatted using the [Google Java Code Style Guide](https://google.github.io/styleguide/javaguide.html), except using 4 spaces as indentation. Make a pull request once the code is working.
