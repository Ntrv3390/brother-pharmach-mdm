// SMS Log Plugin Module
angular.module('plugin-smslog', ['ngResource', 'ui.bootstrap', 'ui.router', 'ngTagsInput', 'ncy-angular-breadcrumb'])
    .config(function ($stateProvider) {
        // No separate routes needed - modal-based interface
    })
    .factory('pluginSmsLogService', function ($resource) {
        return $resource('', {}, {
            getSmsLogs: {
                url: '/rest/plugins/smslog/private/device/:deviceId',
                method: 'GET'
            },
            getSettings: {
                url: '/rest/plugins/smslog/private/settings',
                method: 'GET'
            },
            saveSettings: {
                url: '/rest/plugins/smslog/private/settings',
                method: 'POST'
            },
            deleteSmsLogs: {
                url: '/rest/plugins/smslog/private/device/:deviceId',
                method: 'DELETE'
            }
        });
    })
    .controller('PluginSmsLogSettingsController', function ($scope, $rootScope, pluginSmsLogService, alertService, localization) {
        $scope.loading = true;
        $scope.settings = {
            enabled: true,
            retentionDays: 90
        };

        $scope.init = function () {
            pluginSmsLogService.getSettings(function (response) {
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
            pluginSmsLogService.saveSettings($scope.settings, function (response) {
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
        localization.loadPluginResourceBundles("smslog");
    });

// Register the modal controller on the main app module so it's accessible from devices page
angular.module('headwind-kiosk')
    .controller('PluginSmsLogModalController', function ($scope, $modalInstance, device, $injector, 
                                                          alertService, localization) {
        // Inject pluginSmsLogService dynamically to avoid dependency issues if plugin not loaded
        try {
            var pluginSmsLogService = $injector.get('pluginSmsLogService');
        } catch (e) {
            console.error('Failed to load pluginSmsLogService', e);
            alertService.showAlertMessage('SMS Log plugin not loaded. Please refresh the page.');
            $modalInstance.dismiss();
            return;
        }
        
        $scope.device = device;
        $scope.loading = true;
        $scope.smsLogs = [];
        $scope.availableMessageTypes = [
            { value: 1, label: 'Incoming' },
            { value: 2, label: 'Outgoing' }
        ];
        $scope.availableSimSlots = [
            { value: 1, label: 'SIM 1' },
            { value: 2, label: 'SIM 2' }
        ];
        // Use object (dot notation) so ng-if child scopes don't shadow these
        $scope.filters = { messageType: '', simSlot: '', search: '' };

        $scope.pagination = {
            page: 0,
            pageSize: 50,
            total: 0
        };

        // Message type mapping
        $scope.getMessageTypeName = function (type) {
            switch (type) {
                case 1: return localization.localize('plugin.smslog.type.incoming');
                case 2: return localization.localize('plugin.smslog.type.outgoing');
                default: return localization.localize('plugin.smslog.type.unknown');
            }
        };

        $scope.getSimLabel = function (simSlot) {
            if (simSlot === null || simSlot === undefined || simSlot === '') {
                return localization.localize('plugin.smslog.sim.unknown') || 'Unknown';
            }
            return 'SIM ' + simSlot;
        };

        // Format timestamp to readable date
        $scope.formatDate = function (timestamp) {
            var date = new Date(timestamp);
            return date.toLocaleString();
        };

        $scope.applyFilter = function () {
            $scope.pagination.page = 0;
            $scope.loadSmsLogs();
        };

        var _searchDebounce;
        $scope.$watch('filters.search', function (newVal, oldVal) {
            if (newVal === oldVal) {
                return;
            }
            if (_searchDebounce) {
                clearTimeout(_searchDebounce);
            }
            _searchDebounce = setTimeout(function () {
                $scope.$apply(function () {
                    $scope.pagination.page = 0;
                    $scope.loadSmsLogs();
                });
            }, 400);
        });

        $scope.loadSmsLogs = function () {
            $scope.loading = true;
            pluginSmsLogService.getSmsLogs({
                deviceId: device.id,
                page: $scope.pagination.page,
                pageSize: $scope.pagination.pageSize,
                messageType: $scope.filters.messageType || undefined,
                simSlot: $scope.filters.simSlot || undefined,
                search: ($scope.filters.search || '').trim() || undefined
            }, function (response) {
                $scope.loading = false;
                if (response.status === 'OK' && response.data) {
                    $scope.smsLogs = response.data.items || [];
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
                $scope.loadSmsLogs();
            }
        };

        $scope.previousPage = function () {
            if ($scope.pagination.page > 0) {
                $scope.pagination.page--;
                $scope.loadSmsLogs();
            }
        };

        $scope.getTotalPages = function () {
            return Math.ceil($scope.pagination.total / $scope.pagination.pageSize);
        };

        $scope.deleteAllLogs = function () {
            if (confirm(localization.localize('plugin.smslog.confirm.delete'))) {
                pluginSmsLogService.deleteSmsLogs({ deviceId: device.id }, function (response) {
                    if (response.status === 'OK') {
                        alertService.showAlertMessage(localization.localize('success.deleted'));
                        $scope.loadSmsLogs();
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
        $scope.loadSmsLogs();
    });
