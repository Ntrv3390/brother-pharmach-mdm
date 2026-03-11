// Call Log Plugin Module
angular.module('plugin-calllog', ['ngResource', 'ui.bootstrap', 'ui.router', 'ngTagsInput', 'ncy-angular-breadcrumb'])
    .config(function ($stateProvider) {
        // No separate routes needed - modal-based interface
    })
    .factory('pluginCallLogService', function ($resource) {
        return $resource('', {}, {
            getCallLogs: {
                url: '/rest/plugins/calllog/private/device/:deviceId',
                method: 'GET'
            },
            getSettings: {
                url: '/rest/plugins/calllog/private/settings',
                method: 'GET'
            },
            saveSettings: {
                url: '/rest/plugins/calllog/private/settings',
                method: 'POST'
            },
            deleteCallLogs: {
                url: '/rest/plugins/calllog/private/device/:deviceId',
                method: 'DELETE'
            }
        });
    })
    .controller('PluginCallLogSettingsController', function ($scope, $rootScope, pluginCallLogService, alertService, localization) {
        $scope.loading = true;
        $scope.settings = {
            enabled: true,
            retentionDays: 90
        };

        $scope.init = function () {
            pluginCallLogService.getSettings(function (response) {
                $scope.loading = false;
                if (response.status === 'OK' && response.data) {
                    $scope.settings = response.data;
                }
            }, function (error) {
                $scope.loading = false;
                alertService.showAlertMessage(localization.localize('error.request.failure'));
            });
        };

        $scope.save = function () {
            $scope.saving = true;
            pluginCallLogService.saveSettings($scope.settings, function (response) {
                $scope.saving = false;
                if (response.status === 'OK') {
                    alertService.showAlertMessage(localization.localize('success.settings.saved'));
                } else {
                    alertService.showAlertMessage(localization.localize('error.request.failure'));
                }
            }, function (error) {
                $scope.saving = false;
                alertService.showAlertMessage(localization.localize('error.request.failure'));
            });
        };

        $scope.init();
    })
    .run(function (localization) {
        localization.loadPluginResourceBundles("calllog");
    });

// Register the modal controller on the main app module so it's accessible from devices page
angular.module('headwind-kiosk')
    .controller('PluginCallLogModalController', function ($scope, $modalInstance, device, $injector, 
                                                          alertService, localization) {
        // Inject pluginCallLogService dynamically to avoid dependency issues if plugin not loaded
        try {
            var pluginCallLogService = $injector.get('pluginCallLogService');
        } catch (e) {
            console.error('Failed to load pluginCallLogService', e);
            alertService.showAlertMessage('Call Log plugin not loaded. Please refresh the page.');
            $modalInstance.dismiss();
            return;
        }
        
        $scope.device = device;
        $scope.loading = true;
        $scope.callLogs = [];
        $scope.pagination = {
            page: 0,
            pageSize: 50,
            total: 0
        };

        // Call type mapping
        $scope.getCallTypeName = function (type) {
            switch (type) {
                case 1: return localization.localize('plugin.calllog.type.incoming');
                case 2: return localization.localize('plugin.calllog.type.outgoing');
                case 3: return localization.localize('plugin.calllog.type.missed');
                case 5: return localization.localize('plugin.calllog.type.rejected');
                case 6: return localization.localize('plugin.calllog.type.blocked');
                default: return localization.localize('plugin.calllog.type.unknown');
            }
        };

        // Format duration (seconds to readable format)
        $scope.formatDuration = function (seconds) {
            if (seconds === 0) return '0s';
            var hours = Math.floor(seconds / 3600);
            var minutes = Math.floor((seconds % 3600) / 60);
            var secs = seconds % 60;
            
            var parts = [];
            if (hours > 0) parts.push(hours + 'h');
            if (minutes > 0) parts.push(minutes + 'm');
            if (secs > 0) parts.push(secs + 's');
            
            return parts.join(' ');
        };

        // Format timestamp to readable date
        $scope.formatDate = function (timestamp) {
            var date = new Date(timestamp);
            return date.toLocaleString();
        };

        $scope.loadCallLogs = function () {
            $scope.loading = true;
            pluginCallLogService.getCallLogs({
                deviceId: device.id,
                page: $scope.pagination.page,
                pageSize: $scope.pagination.pageSize
            }, function (response) {
                $scope.loading = false;
                if (response.status === 'OK' && response.data) {
                    $scope.callLogs = response.data.items || [];
                    $scope.pagination.total = response.data.total || 0;
                }
            }, function (error) {
                $scope.loading = false;
                alertService.showAlertMessage(localization.localize('error.request.failure'));
            });
        };

        $scope.nextPage = function () {
            if (($scope.pagination.page + 1) * $scope.pagination.pageSize < $scope.pagination.total) {
                $scope.pagination.page++;
                $scope.loadCallLogs();
            }
        };

        $scope.previousPage = function () {
            if ($scope.pagination.page > 0) {
                $scope.pagination.page--;
                $scope.loadCallLogs();
            }
        };

        $scope.getTotalPages = function () {
            return Math.ceil($scope.pagination.total / $scope.pagination.pageSize);
        };

        $scope.deleteAllLogs = function () {
            if (confirm(localization.localize('plugin.calllog.confirm.delete'))) {
                pluginCallLogService.deleteCallLogs({ deviceId: device.id }, function (response) {
                    if (response.status === 'OK') {
                        alertService.showAlertMessage(localization.localize('success.deleted'));
                        $scope.loadCallLogs();
                    } else {
                        alertService.showAlertMessage(localization.localize('error.request.failure'));
                    }
                }, function (error) {
                    alertService.showAlertMessage(localization.localize('error.request.failure'));
                });
            }
        };

        $scope.close = function () {
            $modalInstance.dismiss();
        };

        // Load data on init
        $scope.loadCallLogs();
    });
