# Device Info Plugin - Technical Documentation

## Table of Contents

1. [Overview](#overview)
2. [Purpose & Features](#purpose--features)
3. [Plugin Architecture](#plugin-architecture)
4. [Directory Structure](#directory-structure)
5. [Core Files & Classes](#core-files--classes)
6. [Data Model](#data-model)
7. [REST API Endpoints](#rest-api-endpoints)
8. [Configuration](#configuration)
9. [Android Client Integration](#android-client-integration)
10. [Troubleshooting GPS](#troubleshooting-gps)

---

## Overview

The **Device Info Plugin** is a core component module for Headwind MDM that collects dynamic status information from managed Android devices. Unlike static device inventory data, this plugin handles time-series data that changes frequently, such as GPS location, battery level, memory usage, and signal strength.

### Plugin Identification

| Property           | Value                                  |
| ------------------ | -------------------------------------- |
| **Plugin ID**      | `deviceinfo`                           |
| **Package**        | `com.hmdm.plugins.deviceinfo`          |
| **Root Path**      | `hmdm-server/plugins/deviceinfo/`      |

---

## Purpose & Features

### Core Capabilities

- **GPS Tracking**: Real-time and historical location tracking on a map.
- **Battery Monitoring**: Track battery level and charging status.
- **Network Status**: Monitor Wi-Fi, Mobile Data, and RSSI (Signal Strength).
- **Storage & Memory**: Track available RAM and internal storage.
- **On-Demand Updates**: Admin ability to request an immediate status update from the device.

---

## Plugin Architecture

The plugin operates on a poll-and-push mechanism:

1.  **Periodic Updates**: The Android client automatically collects and uploads data based on a configured interval (e.g., every 15 minutes).
2.  **On-Demand Updates**: The Admin clicks "Get Latest GPS Location" in the web console. The server sends a Push Notification to the device. The device wakes up, captures status, and uploads it immediately.

---

## Directory Structure

```
plugins/deviceinfo/
├── pom.xml                                     # Maven build file
├── src/
│   └── main/
│       ├── java/com/hmdm/plugins/deviceinfo/   # Java backend code
│       │   ├── persistence/                    # Database access layer
│       │   ├── rest/                           # REST API endpoints
│       │   └── service/                        # Business logic
│       └── webapp/                             # AngularJS Frontend
│           ├── deviceinfo.module.js            # Main frontend module
│           └── views/
│               └── dynamic.html                # UI for Dynamic Info tab
```

---

## Core Files & Classes

### Backend (Java)

- **`DeviceInfoResource.java`**: The main REST controller. Handles:
    - Receiving data from devices (`saveDeviceInfo`)
    - Serving history data to UI (`getPhotos` / `searchDynamicData`)
    - Triggering refresh requests (`refreshDevice`)
- **`DeviceInfoPluginSettingsResource.java`**: Manages plugin settings (intervals, enable/disable).
- **`DeviceInfoDAO.java`**: Database Access Object for storing and retrieving history.
- **`DeviceDynamicInfo.java`**: Domain model representing a single data point (snapshot of device state).

### Frontend (AngularJS)

- **`deviceinfo.module.js`**: Contains the controller logic for the Dynamic Info tab.
- **`dynamic.html`**: The HTML template displaying the map and data table.

---

## Data Model

The plugin collects the following data points per record:

### Device Data
- Battery Level (%)
- Battery Charging (Bool)
- Memory Total / Available

### GPS Data (`GpsData`)
- Latitude / Longitude
- Altitude
- Speed
- Course (Heading)
- State (On/Off)

### Connectivity (`WifiData`, `MobileData`)
- RSSI (Signal Strength)
- SSID / Carrier
- IP Address
- Data Enabled State

---

## REST API Endpoints

### 1. Upload Data (Device Side)
**PUT** `rest/plugins/deviceinfo/deviceinfo/public/{deviceNumber}`
- **Used by**: Android Client
- **Purpose**: Uploads a new batch of dynamic info records.
- **Body**: JSON Array of `DeviceDynamicInfo` objects.

### 2. Trigger Refresh (Admin Side)
**POST** `rest/plugins/deviceinfo/deviceinfo/private/refresh/{deviceNumber}`
- **Used by**: Web Console ("Get Latest GPS Location" button)
- **Purpose**: Sends a `configUpdated` push notification to the device to trigger immediate sync.

### 3. Search History (Admin Side)
**POST** `rest/plugins/deviceinfo/deviceinfo/private/search/dynamic`
- **Used by**: Web Console
- **Purpose**: Retrieves historical data points for the table and map.

---

## Configuration

Settings are managed via the web console under the "Device Info" plugin settings tab.

| Setting | Description | Default |
| ------- | ----------- | ------- |
| **Send Data** | Global switch to enable/disable data collection. Must be **ON** for GPS to work. | `false` |
| **Interval (minutes)** | How often the device autonomously uploads data. | `15` |
| **Data Preserve Period** | How many days to keep history in the database. | `30` |

---

## Android Client Integration

To enable GPS tracking, the Android client must implement the following logic.

### 1. Permissions
The `AndroidManifest.xml` must include:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. Handling the "Get Latest GPS Location" Button
When the admin clicks the button, the server sends a generic `configUpdated` push notification.

**The Android app must:**
1.  Listen for the `configUpdated` notification.
2.  Recognize this trigger.
3.  Immediately capture the current GPS location.
4.  POST the data to the `public/{deviceNumber}` endpoint.

**Pseudo-code Implementation:**

```java
public class PushNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String type = intent.getStringExtra("type");
        
        if ("configUpdated".equals(type)) {
            // 1. Standard config sync
            ConfigService.updateConfig(context); 
            
            // 2. CRITICAL: Trigger immediate Device Info Report
            // This is required for the "Get Latest GPS" button to work
            DeviceInfoService.reportNow(context); 
        }
    }
}
```

---

## Troubleshooting GPS

If the "Get Latest GPS Location" button is not working:

1.  **Check Plugin Settings**: Ensure "Send Data" is checked in the server settings.
2.  **Check Interval**: If interval is set to 0 or very high, auto-updates won't happen.
3.  **Android Permissions**: Verify the app has granted Location permissions on the device.
4.  **Client Implementation**:
    - Does the client actually send data on `configUpdated`?
    - Most standard HMDM client implementations only sync settings on `configUpdated`, they might not trigger a sensor dump. You may need to modify the client source code to add this behavior.
