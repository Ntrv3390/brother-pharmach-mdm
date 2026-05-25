/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

// Localization completed
angular.module('plugin-deviceinfo', ['ngResource', 'ui.bootstrap', 'ui.router', 'ngTagsInput', 'ncy-angular-breadcrumb'])
    .constant('DEVICE_PARAMS', [
        'deviceBatteryLevel',
        'deviceBatteryCharging',
        'deviceIpAddress',
        'deviceKeyguard',
        'deviceRingVolume',
        'deviceWifiEnabled',
        'deviceMobileDataEnabled',
        'deviceGpsEnabled',
        'deviceBluetoothEnabled',
        'deviceUsbEnabled',
        'deviceMemoryTotal',
        'deviceMemoryAvailable'
    ])
    .constant('WIFI_PARAMS', [
        'wifiRssi',
        'wifiSsid',
        'wifiSecurity',
        'wifiState',
        'wifiIpAddress',
        'wifiTx',
        'wifiRx',
    ])
    .constant('GPS_PARAMS', [
        'gpsState',
        'gpsLat',
        'gpsLon',
        'gpsAlt',
        'gpsSpeed',
        'gpsCourse',
    ])
    .constant('MOBILE1_PARAMS', [
        'mobile1Rssi',
        'mobile1Carrier',
        'mobile1DataEnabled',
        'mobile1IpAddress',
        'mobile1State',
        'mobile1SimState',
        'mobile1Tx',
        'mobile1Rx',
    ])
    .constant('MOBILE2_PARAMS', [
        'mobile2Rssi',
        'mobile2Carrier',
        'mobile2DataEnabled',
        'mobile2IpAddress',
        'mobile2State',
        'mobile2SimState',
        'mobile2Tx',
        'mobile2Rx',
    ])
    .constant('splitDynamicInfoRecord', function (record) {
        var deviceData = {};
        var wifiData = {};
        var gpsData = {};
        var mobile1Data = {};
        var mobile2Data = {};
        for (var p in record) {
            if (record.hasOwnProperty(p)) {
                var target = undefined;

                if (p.startsWith("device")) {
                    target = deviceData;
                } else if (p.startsWith("wifi")) {
                    target = wifiData;
                } else if (p.startsWith("gps")) {
                    target = gpsData;
                } else if (p.startsWith("mobile1")) {
                    target = mobile1Data;
                } else if (p.startsWith("mobile2")) {
                    target = mobile2Data;
                }

                if (target) {
                    target[p] = {
                        value: record[p],
                        isBoolean: typeof record[p] === 'boolean',
                        displayed: !p.endsWith('DataIncluded'),
                        name: p,
                        isEnumerated: p.endsWith("State")
                    };
                }
            }
        }

        return {
            "deviceData": deviceData,
            "wifiData": wifiData,
            "gpsData": gpsData,
            "mobile1Data": mobile1Data,
            "mobile2Data": mobile2Data,
        }
    })
    .constant('parseDynamicInfoRecord', function (record) {
        var data = {};
        for (var p in record) {
            if (record.hasOwnProperty(p)) {
                data[p] = {
                    value: record[p],
                    isBoolean: typeof record[p] === 'boolean',
                    displayed: !p.endsWith('DataIncluded'),
                    name: p,
                    isEnumerated: p.endsWith("State")
                };
            }
        }

        return data;
    })
    .config(function ($stateProvider) {
        try {
            $stateProvider.state('plugin-deviceinfo', {
                url: "/" + 'plugin-deviceinfo/{deviceNumber}',
                params:  {
                    deviceNumber: {
                        value: null,
                        squash: true
                    }
                },
                templateUrl: 'app/components/main/view/content.html',
                controller: 'TabController',
                ncyBreadcrumb: {
                    // label: '{{"breadcrumb.plugin.deviceinfo.main" | localize}}', //label to show in breadcrumbs
                    label: '{{formData.deviceNumber}}', //label to show in breadcrumbs
                },
                resolve: {
                    openTab: function () {
                        return 'plugin-deviceinfo'
                    }
                },
            });
        } catch (e) {
            console.log('An error when adding state ' + 'plugin-deviceinfo', e);
        }

        try {
            $stateProvider.state('plugin-deviceinfo-dynamic', {
                url: "/" + 'plugin-deviceinfo-dynamic/{deviceNumber}',
                templateUrl: 'app/components/plugins/deviceinfo/views/dynamic.html',
                controller: 'PluginDeviceDynamicInfoController',
                ncyBreadcrumb: {
                    label: '{{"breadcrumb.plugin.deviceinfo.dynamic.main" | localize}}', //label to show in breadcrumbs
                    parent: 'plugin-deviceinfo'
                },
            });
        } catch (e) {
            console.log('An error when adding state ' + 'plugin-deviceinfo-dynamic', e);
        }

        try {
            $stateProvider.state('plugin-settings-deviceinfo', {
                url: "/" + 'plugin-settings-deviceinfo',
                templateUrl: 'app/components/main/view/content.html',
                controller: 'TabController',
                ncyBreadcrumb: {
                    label: '{{"breadcrumb.plugin.deviceinfo.main" | localize}}', //label to show in breadcrumbs
                },
                resolve: {
                    openTab: function () {
                        return 'plugin-settings-deviceinfo'
                    }
                },
            });
        } catch (e) {
            console.log('An error when adding state ' + 'plugin-settings-deviceinfo', e);
        }
    })
    .factory('pluginDeviceInfoService', function ($resource) {
        return $resource('', {}, {
            getSettings: {url: 'rest/plugins/deviceinfo/deviceinfo-plugin-settings/private', method: 'GET'},
            saveSettings: {url: 'rest/plugins/deviceinfo/deviceinfo-plugin-settings/private', method: 'PUT'},
            getDeviceInfo: {url: 'rest/plugins/deviceinfo/deviceinfo/private/:deviceNumber', method: 'GET'},
            searchDynamicData: {url: 'rest/plugins/deviceinfo/deviceinfo/private/search/dynamic', method: 'POST'},
        });
    })
    .factory('pluginDeviceInfoExportService', function ($resource) {
        return $resource('', {}, {
            exportDynamicInfo: {
                url: 'rest/plugins/deviceinfo/deviceinfo/private/export',
                method: 'POST',
                responseType: 'arraybuffer',
                cache: false,
                transformResponse: function (data) {
                    return {
                        response: new Blob([data], {
                            // type: "text/plain"
                        })
                    };
                }
            },
        });
    })
    .controller('PluginDeviceInfoSettingsController', function ($scope, $rootScope, pluginDeviceInfoService, localization) {
        $scope.successMessage = undefined;
        $scope.errorMessage = undefined;

        $rootScope.settingsTabActive = true;
        $rootScope.pluginsTabActive = false;

        $scope.settings = {};

        var intervalOptionValues = [15, 30, 60, 120, 360, 720, 1440];
        $scope.intervalOptions = intervalOptionValues.map(function (value, index) {
            return {value: value, label: localization.localize('plugin.deviceinfo.intervalMins.option.' + (index + 1))};
        });

        pluginDeviceInfoService.getSettings(function (response) {
            if (response.status === 'OK') {
                $scope.settings = response.data;
            } else {
                $scope.errorMessage = localization.localize('error.internal.server');
            }
        });

        $scope.save = function () {
            $scope.successMessage = undefined;
            $scope.errorMessage = undefined;

            pluginDeviceInfoService.saveSettings($scope.settings, function (response) {
                if (response.status === 'OK') {
                    $scope.successMessage = localization.localize('success.plugin.deviceinfo.settings.saved');
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            });
        }
    })
    .controller('PluginDeviceInfoController', function ($scope, $rootScope, $location, $http, $state, $stateParams,
                                                        $interval,
                                                        pluginDeviceInfoService, localization, splitDynamicInfoRecord,
                                                        DEVICE_PARAMS, WIFI_PARAMS, GPS_PARAMS,
                                                        MOBILE1_PARAMS, MOBILE2_PARAMS) {
        $scope.successMessage = undefined;
        $scope.errorMessage = undefined;

        $rootScope.settingsTabActive = false;
        $rootScope.pluginsTabActive = true;

        // var deviceNumber = ($location.search()).deviceNumber;
        var deviceNumber = $stateParams.deviceNumber;
        $scope.formData = {
            deviceNumber: deviceNumber
        };

        var clearMessages = function () {
            $scope.successMessage = undefined;
            $scope.errorMessage = undefined;
        };

        var hasValidCoordinates = function (lat, lon) {
            return isFinite(parseFloat(lat)) && isFinite(parseFloat(lon));
        };

        $scope.refreshState = {
            lastRequestedAt: null,
            waitingForNewData: false,
            pendingCount: 0,
            completedCount: 0
        };

        var pendingRefreshes = [];
        var completedRefreshCount = 0;
        var MAX_REFRESH_WAIT_MS = 120000;
        var REFRESH_POLL_INTERVAL_MS = 1000;

        var updateRefreshState = function () {
            $scope.refreshState.waitingForNewData = pendingRefreshes.length > 0;
            $scope.refreshState.pendingCount = pendingRefreshes.length;
            $scope.refreshState.completedCount = completedRefreshCount;
        };

        var getLatestGpsRecordTs = function(items) {
            if (!items || !items.length) {
                return 0;
            }

            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                if (item && item.gpsLat && item.gpsLon && item.latestUpdateTime) {
                    return item.latestUpdateTime;
                }
            }

            return 0;
        };

        $scope.dynamicDataDeviceFieldsOrder = [].concat(DEVICE_PARAMS);
        $scope.dynamicDataWifiFieldsOrder = [].concat(WIFI_PARAMS);
        $scope.dynamicDataGpsFieldsOrder = [].concat(GPS_PARAMS);
        $scope.dynamicDataMobile1FieldsOrder = [].concat(MOBILE1_PARAMS);
        $scope.dynamicDataMobile2FieldsOrder = [].concat(MOBILE2_PARAMS);

        var loadData = function () {
            pluginDeviceInfoService.getDeviceInfo({"deviceNumber": deviceLookupFormatter($scope.formData.deviceNumber)}, function (response) {
                if (response.status === 'OK') {
                    $scope.deviceInfo = response.data;
                    $scope.latestDynamicData = response.data.latestDynamicData;
                    if (response.data.latestDynamicData) {
                        var data = splitDynamicInfoRecord(response.data.latestDynamicData);

                        $scope.dynamicDeviceData = data.deviceData;
                        $scope.dynamicWifiData = data.wifiData;
                        $scope.dynamicGpsData = data.gpsData;
                        $scope.dynamicMobile1Data = data.mobile1Data;
                        $scope.dynamicMobile2Data = data.mobile2Data;
                    }
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, function () {
                $scope.errorMessage = localization.localize("error.request.failure");
            });
        };

        var resolveDeviceField = function (serverData, deviceInfoData) {
            if (serverData === deviceInfoData) {
                return serverData;
            } else if (serverData.length === 0 && deviceInfoData.length > 0) {
                return deviceInfoData;
            } else if (serverData.length > 0 && deviceInfoData.length === 0) {
                return serverData;
            } else {
                return deviceInfoData;
            }
        };

        var getDeviceInfo = function( device ) {
            if ( device.info ) {
                try {
                    return JSON.parse( device.info );
                } catch ( e ) {}
            }

            return undefined;
        };

        var deviceLookupFormatter = function (v) {
            if (v) {
                var pos = v.indexOf('/');
                if (pos > -1) {
                    return v.substr(0, pos).trim();
                }
            }
            return v;
        };

        $scope.deviceLookupFormatter = deviceLookupFormatter;

        $scope.searchDevices = function (val) {
            return $http.get('rest/plugins/deviceinfo/deviceinfo/private/search/device?limit=10&filter=' + val)
                .then(function (response) {
                    if (response.data.status === 'OK') {
                        return response.data.data.map(function (device) {
                            var deviceInfo = getDeviceInfo(device);
                            var serverIMEI = device.imei || '';
                            var deviceInfoIMEI = deviceInfo ? (deviceInfo.imei || '') : '';
                            var resolvedIMEI = resolveDeviceField(serverIMEI, deviceInfoIMEI);

                            return device.name + (resolvedIMEI.length > 0 ? " / " + resolvedIMEI : "");
                        });
                    } else {
                        return [];
                    }
                });
        };

        $scope.search = function () {
            clearMessages();
            loadData();
        };

        $scope.viewDynamicData = function () {
            $state.transitionTo('plugin-deviceinfo-dynamic', {deviceNumber: $scope.deviceInfo.deviceNumber});
        };

        $scope.formatMultiLine = function (text) {
            if (!text) {
                return text;
            } else {
                return text.replace(/\n/g, "<br/>");
            }
        };

        if (deviceNumber) {
            loadData();
        }

        const updateInterval = $interval(function () {
            if ($scope.formData.deviceNumber) {
                loadData();
            }
        }, 60 * 1000);
        $scope.$on('$destroy', function () {
            $interval.cancel(updateInterval);
        });
    })
    .controller('PluginDeviceDynamicInfoController', function ( $scope, $stateParams, $window, $interval, $http, $timeout,
                                                                pluginDeviceInfoService, hmdmMap,
                                                                localization, parseDynamicInfoRecord, spinnerService,
                                                                alertService, pluginDeviceInfoExportService,
                                                                DEVICE_PARAMS, WIFI_PARAMS, GPS_PARAMS,
                                                                MOBILE1_PARAMS, MOBILE2_PARAMS) {
        var clearMessages = function () {
            $scope.successMessage = undefined;
            $scope.errorMessage = undefined;
        };

        $scope.refreshState = {
            lastRequestedAt: null,
            waitingForNewData: false
        };

        var refreshBaselineTs = 0;

        var getLatestGpsRecordTs = function(items) {
            if (!items || !items.length) {
                return 0;
            }

            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                var lat = parseFloat(item && item.gpsLat);
                var lon = parseFloat(item && item.gpsLon);
                if (isFinite(lat) && isFinite(lon) && item.latestUpdateTime) {
                    return item.latestUpdateTime;
                }
            }

            return 0;
        };

        var postRefreshPollingPromise = null;
        var postRefreshPollingAttempts = 0;
        var maxPostRefreshPollingAttempts = 180;

        var stopPostRefreshPolling = function () {
            if (postRefreshPollingPromise) {
                $interval.cancel(postRefreshPollingPromise);
                postRefreshPollingPromise = null;
            }
            postRefreshPollingAttempts = 0;
        };

        var startPostRefreshPolling = function () {
            if (postRefreshPollingPromise || !pendingRefreshes.length) {
                return;
            }

            postRefreshPollingPromise = $interval(function () {
                postRefreshPollingAttempts += 1;

                var now = Date.now();
                var timedOutCount = 0;
                pendingRefreshes = pendingRefreshes.filter(function (requestInfo) {
                    if ((now - requestInfo.startedAt) > MAX_REFRESH_WAIT_MS) {
                        timedOutCount += 1;
                        return false;
                    }
                    return true;
                });

                if (timedOutCount > 0) {
                    $scope.errorMessage = localization.localize('error.plugin.deviceinfo.refresh.timeout') ||
                        "No updated GPS data received from device within 120 seconds.";
                }

                updateRefreshState();
                if (!pendingRefreshes.length) {
                    stopPostRefreshPolling();
                    return;
                }

                loadData();

                if (postRefreshPollingAttempts >= maxPostRefreshPollingAttempts) {
                    pendingRefreshes = [];
                    updateRefreshState();
                    stopPostRefreshPolling();
                    $scope.errorMessage = localization.localize('error.plugin.deviceinfo.refresh.timeout') ||
                        "No updated GPS data received from device within 120 seconds.";
                }
            }, REFRESH_POLL_INTERVAL_MS);
        };

        $scope.refreshLocation = function () {
            clearMessages();
            $scope.refreshState.lastRequestedAt = new Date();
            var refreshBaselineTs = getLatestGpsRecordTs($scope.data);

            $http.post('rest/plugins/deviceinfo/deviceinfo/private/refresh/' + $stateParams.deviceNumber)
                .then(function (response) {
                    if (response.data.status === 'OK') {
                        var refreshData = response.data.data || {};
                        pendingRefreshes.push({
                            requestId: refreshData.requestId || ("local-" + Date.now() + "-" + pendingRefreshes.length),
                            baselineTs: refreshBaselineTs,
                            startedAt: Date.now()
                        });
                        updateRefreshState();
                        $scope.successMessage = localization.localize('success.plugin.deviceinfo.refresh.started') || "Refresh request sent to device. Updating map...";
                        loadData();
                        startPostRefreshPolling();
                    } else {
                        updateRefreshState();
                        $scope.errorMessage = localization.localize('error.plugin.deviceinfo.refresh.failed') || "Failed to send refresh request";
                    }
                }, function (error) {
                     updateRefreshState();
                     $scope.errorMessage = localization.localize('error.plugin.deviceinfo.refresh.failed') || "Failed to send refresh request: " + error.statusText;
                });
        };

        var prepareRequestToServer = function () {
            var request = copyFormData();

            delete request.totalItems;

            if (request.useFixedInterval) {
                delete request.timeFrom;
                delete request.timeTo;
                delete request.dateFrom;
                delete request.dateTo;
            } else {
                var from = new Date(request.dateFrom.getTime());
                from.setHours(request.timeFrom.getHours());
                from.setMinutes(request.timeFrom.getMinutes());
                from.setSeconds(0, 0);

                request.dateFrom = from;
                delete request.timeFrom;

                var to = new Date(request.dateTo.getTime());
                to.setHours(request.timeTo.getHours());
                to.setMinutes(request.timeTo.getMinutes());
                to.setSeconds(59, 999);

                request.dateTo = to;
                delete request.timeTo;
            }

            return request;
        };

        var loading = false;
        var mapInitialized = false;
        var mapService = hmdmMap.get();
        var mapInstanceRef = null;
        var userAdjustedMapViewport = false;
        var mapCenteringInProgress = false;
        var mapViewportStorageKey = 'hmdm-plugin-deviceinfo-map-viewport-' + ($stateParams.deviceNumber || 'unknown');

        var saveMapViewport = function () {
            if (!mapInstanceRef) {
                return;
            }
            try {
                var center = mapInstanceRef.getCenter();
                var zoom = mapInstanceRef.getZoom();
                $window.localStorage.setItem(mapViewportStorageKey, JSON.stringify({
                    lat: center.lat,
                    lon: center.lng,
                    zoom: zoom
                }));
            } catch (e) {
            }
        };

        var restoreMapViewport = function () {
            if (!mapInstanceRef) {
                return false;
            }
            try {
                var rawValue = $window.localStorage.getItem(mapViewportStorageKey);
                if (!rawValue) {
                    return false;
                }
                var viewport = JSON.parse(rawValue);
                var lat = parseFloat(viewport.lat);
                var lon = parseFloat(viewport.lon);
                var zoom = parseInt(viewport.zoom, 10);
                if (!isFinite(lat) || !isFinite(lon) || !isFinite(zoom)) {
                    return false;
                }

                mapCenteringInProgress = true;
                mapInstanceRef.setView([lat, lon], zoom);
                $timeout(function () {
                    mapCenteringInProgress = false;
                }, 0, false);
                userAdjustedMapViewport = true;
                return true;
            } catch (e) {
                return false;
            }
        };

        var bindMapInteractionTracking = function (mapInstance) {
            if (!mapInstance) {
                return;
            }

            var markUserAdjustedViewport = function () {
                if (!mapCenteringInProgress) {
                    userAdjustedMapViewport = true;
                }
            };

            // Track explicit user viewport actions and preserve them across auto-refreshes.
            mapInstance.on('dragstart', markUserAdjustedViewport);
            mapInstance.on('zoomstart', markUserAdjustedViewport);
            mapInstance.on('moveend', saveMapViewport);
            mapInstance.on('zoomend', saveMapViewport);
        };

        var updateMap = function(items) {
            if (!mapInitialized) {
                mapInitialized = true;
                $timeout(function () {
                    try {
                        mapInstanceRef = mapService.initMap($scope, 'locationMap', 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png');
                        bindMapInteractionTracking(mapInstanceRef);
                        restoreMapViewport();
                        addMarkers(items);

                        if (mapInstanceRef && mapInstanceRef.invalidateSize) {
                            $timeout(function () {
                                mapInstanceRef.invalidateSize();
                            }, 0, false);
                        }
                    } catch (e) {
                        mapInitialized = false;
                        $scope.errorMessage = localization.localize('error.request.failure') || 'Failed to initialize map';
                    }
                }, 500, false);
            } else {
                addMarkers(items);
            }
        };

        var addMarkers = function (items) {
            if (!items) return;
            mapService.removeAllMarkers();
            
            // Find the most recent item with valid GPS coordinates
            var latestItem = null;
            for (var i = 0; i < items.length; i++) {
                var lat = parseFloat(items[i] && items[i].gpsLat);
                var lon = parseFloat(items[i] && items[i].gpsLon);
                if (isFinite(lat) && isFinite(lon)) {
                    latestItem = items[i];
                    break; // Found the latest one
                }
            }

            if (latestItem) {
                var markerLat = parseFloat(latestItem.gpsLat);
                var markerLon = parseFloat(latestItem.gpsLon);
                $scope.mapLat = markerLat;
                $scope.mapLon = markerLon;
                mapService.addMarker(
                    latestItem.id,
                    markerLat,
                    markerLon,
                    {
                        iconUrl: 'images/circle-green.png',
                        iconSize: [48, 48],
                        iconAnchor: [24, 24]
                    },
                    "Time: " + new Date(latestItem.latestUpdateTime).toLocaleString()
                );

                if (!userAdjustedMapViewport) {
                    mapCenteringInProgress = true;
                    mapService.centerMap(markerLat, markerLon);
                    $timeout(function () {
                        mapCenteringInProgress = false;
                    }, 0, false);
                }
            } else {
                $scope.mapLat = null;
                $scope.mapLon = null;
            }
        };

        $scope.hasMapCoordinates = function () {
            return hasValidCoordinates($scope.mapLat, $scope.mapLon);
        };

        $scope.getGoogleMapsUrl = function () {
            if (!$scope.hasMapCoordinates()) {
                return null;
            }
            return 'https://www.google.com/maps?q=' + $scope.mapLat + ',' + $scope.mapLon;
        };

        var loadData = function () {
            if (loading) {
                console.log("Skipping to query for list of device dynamic info dynamic since a previous request is pending");
                return;
            }

            clearMessages();

            loading = true;

            spinnerService.show('spinner2');

            var request = prepareRequestToServer();

            pluginDeviceInfoService.searchDynamicData(request, function (response) {
                loading = false;
                spinnerService.close('spinner2');
                if (response.status === 'OK') {
                    $scope.data = response.data.items;
                    $scope.formData.totalItems = response.data.totalItemsCount;

                    if (pendingRefreshes.length > 0) {
                        var latestGpsRecordTs = getLatestGpsRecordTs($scope.data);
                        var completedInThisCycle = 0;
                        pendingRefreshes = pendingRefreshes.filter(function (requestInfo) {
                            if (latestGpsRecordTs > requestInfo.baselineTs) {
                                completedInThisCycle += 1;
                                return false;
                            }
                            return true;
                        });
                        if (completedInThisCycle > 0) {
                            completedRefreshCount += completedInThisCycle;
                            $scope.successMessage = localization.localize('success.plugin.deviceinfo.refresh.completed') ||
                                "Latest GPS data received from device.";
                        }
                        updateRefreshState();
                        if (!pendingRefreshes.length) {
                            stopPostRefreshPolling();
                        }
                    }

                    if ($scope.data && $scope.data.length > 0) {
                        $scope.mapLat = null;
                        $scope.mapLon = null;
                        for (var i = 0; i < $scope.data.length; i++) {
                            var _lat = parseFloat($scope.data[i] && $scope.data[i].gpsLat);
                            var _lon = parseFloat($scope.data[i] && $scope.data[i].gpsLon);
                            if (isFinite(_lat) && isFinite(_lon)) {
                                $scope.mapLat = _lat;
                                $scope.mapLon = _lon;
                                break;
                            }
                        }
                        updateMap($scope.data);
                    } else {
                        $scope.mapLat = null;
                        $scope.mapLon = null;
                    }
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, function () {
                loading = false;
                spinnerService.close('spinner2');
                $scope.errorMessage = localization.localize("error.request.failure");
            });
        };

        $scope.successMessage = undefined;
        $scope.errorMessage = undefined;

        $scope.parseDynamicInfoRecord = parseDynamicInfoRecord;

        $scope.deviceFields = [].concat(DEVICE_PARAMS);
        $scope.wifiFields = [].concat(WIFI_PARAMS);
        $scope.gpsFields = [].concat(GPS_PARAMS);
        $scope.mobile1Fields = [].concat(MOBILE1_PARAMS);
        $scope.mobile2Fields = [].concat(MOBILE2_PARAMS);
        $scope.allFields = [].concat(DEVICE_PARAMS).concat(WIFI_PARAMS).concat(GPS_PARAMS).concat(MOBILE1_PARAMS).concat(MOBILE2_PARAMS);

        $scope.dateFormat = localization.localize('format.date.plugin.deviceinfo.datePicker');
        $scope.createTimeFormat = localization.localize('format.date.plugin.deviceinfo.createTime');
        $scope.datePickerOptions = { 'show-weeks': false };
        $scope.openDatePickers = {
            'dateFrom': false,
            'dateTo': false
        };

        var defaultFormData = {
            deviceNumber: $stateParams.deviceNumber,
            useFixedInterval: false,
            fixedInterval: -1,
            dateFrom: new Date(),
            dateTo: new Date(),
            timeFrom: new Date(),
            timeTo: new Date(),
            intervalSelectionTouched: false,
            pageSize: 50,
            pageNum: 1
        };

        var storageFormDataAttrName = 'hmdm-plugin-deviceinfo-formData';
        var storedFormData = $window.localStorage.getItem(storageFormDataAttrName);
        if (storedFormData) {
            try {
                var formData = JSON.parse(storedFormData);
                formData.deviceNumber = $stateParams.deviceNumber;
                formData.fixedInterval = parseInt(formData.fixedInterval);
                if (!isFinite(formData.fixedInterval)) {
                    formData.fixedInterval = -1;
                }
                // Migrate old saved filters: if user has never explicitly changed interval selection,
                // default to showing complete history instead of a narrow fixed interval.
                if (!formData.hasOwnProperty('intervalSelectionTouched')) {
                    formData.fixedInterval = -1;
                    formData.useFixedInterval = false;
                    formData.intervalSelectionTouched = false;
                } else {
                    formData.useFixedInterval = formData.fixedInterval > 0;
                }
                if (formData.dateFrom) {
                    formData.dateFrom = new Date(formData.dateFrom);
                } else {
                    formData.dateFrom = new Date();
                }
                if (formData.dateTo) {
                    formData.dateTo = new Date(formData.dateTo);
                } else {
                    formData.dateTo = new Date();
                }
                if (formData.timeFrom) {
                    formData.timeFrom = new Date(formData.timeFrom);
                } else {
                    formData.timeFrom = new Date();
                }
                if (formData.timeTo) {
                    formData.timeTo = new Date(formData.timeTo);
                } else {
                    formData.timeTo = new Date();
                }
                formData.pageSize = 50;
                formData.pageNum = 1;

                $scope.formData = formData;
            } catch (e) {
                $scope.formData = defaultFormData;
            }
        } else {
            $scope.formData = defaultFormData;
        }

        var defaultFieldsSelection = {
            "deviceBatteryCharging": true,
            "wifiSsid": true,
            "wifiState": true,
            "wifiRssi": true,
            "mobile1State": true,
            "mobile1Rssi": true,
        };
        var storageSelectionAttrName = 'hmdm-plugin-deviceinfo-fieldsSelection';
        var storedSelection = $window.localStorage.getItem(storageSelectionAttrName);
        if (storedSelection) {
            try {
                $scope.fieldsSelection = JSON.parse(storedSelection);
            } catch (e) {
                $scope.fieldsSelection = defaultFieldsSelection;
            }
        } else {
            $scope.fieldsSelection = defaultFieldsSelection;
        }

        var defaultCollapseState = {
            main: true,
            device: false,
            wifi: false,
            gps: false,
            mobile1: false,
            mobile2: false,
        };
        var storageCollapseAttrName = 'hmdm-plugin-deviceinfo-collapseState';
        var storedCollapseState = $window.localStorage.getItem(storageCollapseAttrName);
        if (storedCollapseState) {
            try {
                $scope.collapseState = JSON.parse(storedCollapseState);
            } catch (e) {
                $scope.collapseState = defaultCollapseState;
            }
        } else {
            $scope.collapseState = defaultCollapseState;
        }

        var copyFormData = function () {
            var request = {};
            for (var p in $scope.formData) {
                if ($scope.formData.hasOwnProperty(p)) {
                    request[p] = $scope.formData[p];
                }
            }

            return request;
        };

        var saveFormData = function () {
            var copy = copyFormData();
            delete copy.pageSize;
            delete copy.pageNum;
            delete copy.totalItems;
            delete copy.deviceNumber;

            $window.localStorage.setItem(storageFormDataAttrName, JSON.stringify(copy));
        };

        $scope.fieldsSelectionChanged = function () {
            $window.localStorage.setItem(storageSelectionAttrName, JSON.stringify($scope.fieldsSelection));
        };

        $scope.fixedIntervalSelected = function () {
            $scope.formData.useFixedInterval = $scope.formData.fixedInterval > 0;
            $scope.formData.intervalSelectionTouched = true;
            if ($scope.formData.useFixedInterval) {
                $scope.formData.dateFrom = new Date();
                $scope.formData.dateTo = new Date();
                $scope.formData.timeFrom = new Date();
                $scope.formData.timeTo = new Date();
            }
            saveFormData();
        };

        $scope.timeParamsChanged = function () {
            saveFormData();
        };

        $scope.openDateCalendar = function( $event, isStartDate ) {
            $event.preventDefault();
            $event.stopPropagation();

            if ( isStartDate ) {
                $scope.openDatePickers.dateFrom = true;
            } else {
                $scope.openDatePickers.dateTo = true;
            }
        };

        $scope.search = function () {
            clearMessages();

            if ($scope.formData.dateFrom && $scope.formData.dateTo) {
                if ($scope.formData.dateFrom > $scope.formData.dateTo) {
                    $scope.errorMessage = localization.localize('error.plugin.deviceinfo.date.range.invalid');
                    return;
                }
            }

            $scope.formData.pageNum = 1;
            loadData();
        };

        $scope.toggleParamsVisibility = function (type) {
            $scope.collapseState[type] = !$scope.collapseState[type];
            $window.localStorage.setItem(storageCollapseAttrName, JSON.stringify($scope.collapseState));
        };

        $scope.$watch('formData.pageNum', function () {
            $window.scrollTo(0, 0);
            loadData();
        });

        $scope.doExport = function () {
            clearMessages();
            $scope.loading = true;
            $scope.successMessage = localization.localize('plugin.deviceinfo.exporting');

            var exportRequest = prepareRequestToServer();
            exportRequest.locale = localization.getLocale();
            exportRequest.fields = [];
            for (var p in $scope.fieldsSelection) {
                if ($scope.fieldsSelection.hasOwnProperty(p)) {
                    if ($scope.fieldsSelection[p] === true) {
                        exportRequest.fields.push(p);
                    }
                }
            }

            pluginDeviceInfoExportService.exportDynamicInfo(exportRequest, function (data) {
                $scope.loading = false;
                clearMessages();

                var downloadableBlob = URL.createObjectURL(data.response);

                var link = document.createElement('a');
                link.href = downloadableBlob;
                link.download = $scope.formData.deviceNumber + '.csv';

                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);
            }, function (response) {
                $scope.loading = false;
                clearMessages();
                alertService.onRequestFailure(response);
            });
        };


        loadData();


        const updateInterval = $interval(function () {
            loadData();
        }, 60 * 1000);
        $scope.$on('$destroy', function () {
            $interval.cancel(updateInterval);
            stopPostRefreshPolling();
        });

    })
    .run(function ($rootScope, $location, localization) {
        $rootScope.$on('plugin-deviceinfo-device-selected', function (event, device) {
            $location.url('/plugin-deviceinfo/' + device.number);
        });
        localization.loadPluginResourceBundles("deviceinfo");
    })
;


