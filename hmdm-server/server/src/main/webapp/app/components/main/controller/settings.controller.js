// Localization completed
angular.module('headwind-kiosk')
    .controller('SettingsTabController', function ($scope, $rootScope, $timeout, $modal, hintService, settingsService,
                                                   localization, authService, userService, confirmModal, Idle,
                                                   groupService, configurationService, twoFactorAuthService, $http) {
        $scope.settings = {};
        $scope.userRoleSettings = {};
        $scope.loading = false;

        var userRoleSettings = {};

        $scope.formData = {
            userRoleId: authService.getUser().userRole.id
        };

        var onRequestFailure = function () {
            $scope.loading = false;
            $scope.errorMessage = localization.localize('error.request.failure');
        };

        var clearMessages = function () {
            $scope.successMessage = undefined;
            $scope.errorMessage = undefined;
        };

        $scope.init = function () {
            $rootScope.settingsTabActive = true;
            $rootScope.pluginsTabActive = false;

            clearMessages();

            $scope.loading = true;

            groupService.getAllGroups(function (response) {
                $scope.groups = response.data;

                configurationService.getAllConfigurations(function (response) {
                    $scope.configurations = response.data;

                    settingsService.getSettings(function (response) {
                        if (response.data) {
                            $scope.settings = response.data;
                            $scope.initTwoFactor($scope.settings);
                        }
                        $scope.loading = false;
                    }, onRequestFailure);
                }, onRequestFailure);
            }, onRequestFailure);

        };

        var user = authService.getUser();
        $scope.twoFactor = {
            success: null,
            error: null,
            accepted: user.twoFactorAccepted,
            qrCodeUrl: 'rest/private/twofactor/qr/' + user.id,
            code: ''
        };

        $scope.initTwoFactor = function(settings) {
            $scope.twoFactor.use = settings.twoFactor;
        };

        $scope.twoFactorToggled = function() {
            if (!$scope.twoFactor.use) {
                $scope.twoFactor.use = true;
                confirmModal.getUserConfirmation(localization.localize('form.two.factor.auth.off.confirm'), function () {
                    twoFactorAuthService.reset(function(response) {
                        if (response.status === 'OK') {
                            var user = authService.getUser();
                            user.twoFactorSecret = null;
                            user.twoFactorAccepted = false;
                            authService.update(user);
                            $scope.settings.twoFactor = false;
                            $scope.twoFactor.accepted = false;
                            $scope.twoFactor.code = '';
                            $scope.twoFactor.error = '';
                            $scope.twoFactor.success = localization.localize('form.two.factor.auth.reset');
                            $scope.twoFactor.use = false;
                            $timeout(function () {
                                $scope.twoFactor.success = null;
                            }, 5000);
                        } else {
                            $scope.twoFactor.error = localization.localizeServerResponse(response);
                        }
                    });
                });
            } else {
                // Force QR code to reload and re-generate the secret
                $scope.twoFactor.qrCodeUrl = 'rest/private/twofactor/qr/' + authService.getUser().id +
                    '?' + new Date().getTime();
            }
        };

        $scope.verifyTwoFactor = function() {
            if ($scope.twoFactor.code.length != 6 || !/^\d+$/.test($scope.twoFactor.code)) {
                $scope.twoFactor.error = localization.localize('form.two.factor.auth.code.error');
                return;
            }

            var data = {
                user: authService.getUser().id,
                code: $scope.twoFactor.code
            };
            twoFactorAuthService.verify(data, function (response) {
                if (response.status === 'OK') {
                    var user = authService.getUser();
                    user.twoFactorAccepted = true;
                    authService.update(user);
                    twoFactorAuthService.set(function(response) {
                        if (response.status === 'OK') {
                            $scope.settings.twoFactor = true;
                            $scope.twoFactor.accepted = true;
                            $scope.twoFactor.code = '';
                            $scope.twoFactor.error = '';
                            $scope.twoFactor.success = localization.localize('form.two.factor.auth.set');
                            $timeout(function () {
                                $scope.twoFactor.success = null;
                            }, 5000);
                        } else {
                            $scope.twoFactor.error = localization.localizeServerResponse(response);
                        }
                    });
                } else if (response.status === 'ERROR') {
                    if (response.message === 'error.permission.denied') {
                        $scope.twoFactor.error = localization.localize('form.two.factor.auth.code.invalid');
                    } else {
                        $scope.twoFactor.error = localization.localizeServerResponse(response);
                    }
                }
            });
        };

        $scope.desktopHeaderTemplatePlaceholder = localization.localize('form.configuration.settings.design.desktop.header.template.placeholder') + ' deviceId, description, custom1, custom2, custom3';

        $scope.initCommonSettings = function () {
            clearMessages();

            var roleId = authService.getUser().userRole.id;
            $scope.loading = true;
            settingsService.getUserRoleSettings({roleId: roleId}, function (response) {
                if (response.status === 'OK') {
                    $scope.userRoleSettings = response.data;
                    userRoleSettings[roleId] = response.data;

                    userService.getUserRoles(function (response) {
                        if (response.status === 'OK') {
                            $scope.userRoles = response.data;
                        } else {
                            $scope.errorMessage = localization.localizeServerResponse(response);
                        }
                        $scope.loading = false;
                    }, onRequestFailure);
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, onRequestFailure);
        };

        $scope.userRoleChanged = function () {
            clearMessages();

            var roleId = $scope.formData.userRoleId;
            if (!userRoleSettings[roleId]) {
                $scope.loading = true;
                settingsService.getUserRoleSettings({roleId: roleId}, function (response) {
                    if (response.status === 'OK') {
                        $scope.userRoleSettings = response.data;
                        userRoleSettings[roleId] = response.data;
                    } else {
                        $scope.errorMessage = localization.localizeServerResponse(response);
                    }
                    $scope.loading = false;
                }, onRequestFailure);
            } else {
                $scope.userRoleSettings = userRoleSettings[roleId];
            }
        };

        $scope.uploadBackground = function () {
            var modalInstance = $modal.open({
                templateUrl: 'app/components/main/view/modal/file.html',
                // Defined in files.controller.js
                controller: 'FileModalController'
            });

            modalInstance.result.then(function (data) {
                if (data) {
                    $scope.settings.backgroundImageUrl = data.url;
                }
            });
        };

        $scope.saveDefaultDesignSettings = function () {
            clearMessages();
            settingsService.updateDefaultDesignSettings($scope.settings, function (response) {
                if (response.status === 'OK') {
                    $scope.successMessage = localization.localize('success.settings.design.saved');
                    $timeout(function () {
                        $scope.successMessage = '';
                    }, 2000);
                }
            });
        };

        $scope.saveCommonSettings = function () {
            clearMessages();
            var settings = [];
            for (var p in userRoleSettings) {
                if (userRoleSettings.hasOwnProperty(p)) {
                    settings.push(userRoleSettings[p]);
                }
            }

            settingsService.updateUserRolesCommonSettings(settings, function (response) {
                if (response.status === 'OK') {
                    $scope.successMessage = localization.localize('success.settings.common.saved');
                    $timeout(function () {
                        $scope.successMessage = '';
                    }, 2000);
                    $rootScope.$broadcast('aero_COMMON_SETTINGS_UPDATED', settings);
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            });
        };

        $scope.saveLanguageSettings = function () {
            clearMessages();

            if ($scope.settings.createNewDevices && !$scope.settings.newDeviceConfigurationId) {
                $scope.errorMessage = localization.localize('error.empty.configuration');
                return;
            }

            if ($scope.settings.idleLogout) {
                Idle.setIdle($scope.settings.idleLogout);
                Idle.setTimeout(10);
                Idle.watch();
            } else {
                $scope.settings.idleLogout = null;  // Change 0 to null
                Idle.unwatch();
            }

            settingsService.updateMiscSettings($scope.settings, function (response) {
                if (response.status === 'OK') {
                    settingsService.updateLanguageSettings($scope.settings, function (response) {
                        if (response.status === 'OK') {
                            $rootScope.$broadcast('aero_LANGUAGE_SETTINGS_UPDATED', $scope.settings);
                            $scope.successMessage = localization.localize('success.settings.saved');
                            $timeout(function () {
                                $scope.successMessage = '';
                            }, 2000);
                        }
                    });
                }
            });
        };

        $scope.enableHints = function () {
            clearMessages();
            hintService.enableHints(function (response) {
                if (response.status === 'OK') {
                    $scope.successMessage = localization.localize('success.settings.hints.enabled');
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, function () {
                $scope.errorMessage = localization.localize('error.request.failure');
            });
        };

        $scope.disableHints = function () {
            clearMessages();
            hintService.disableHints(function (response) {
                if (response.status === 'OK') {
                    $scope.successMessage = localization.localize('success.settings.hints.disabled');
                } else {
                    $scope.errorMessage = localization.localizeServerResponse(response);
                }
            }, function () {
                $scope.errorMessage = localization.localize('error.request.failure');
            });
        };

        $scope.dbExport = {
            loading: false,
            error: null,
            success: null
        };

        $scope.dbImport = {
            loading: false,
            error: null,
            success: null,
            showConfirm: false,
            selectedFile: null,
            fileName: null
        };

        $scope.exportDatabase = function () {
            $scope.dbExport.loading = true;
            $scope.dbExport.error = null;
            $scope.dbExport.success = null;
            $http({
                method: 'GET',
                url: 'rest/private/settings/db/export',
                responseType: 'blob'
            }).then(function (response) {
                $scope.dbExport.loading = false;
                var contentDisposition = response.headers('Content-Disposition') || '';
                var match = contentDisposition.match(/filename="?([^"]+)"?/);
                var filename = match ? match[1] : 'hmdm_backup.sql';
                var blob = new Blob([response.data], {type: 'application/octet-stream'});
                var url = window.URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.style.display = 'none';
                a.href = url;
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
            }, function (error) {
                $scope.dbExport.loading = false;
                $scope.dbExport.error = 'Export failed. Check server logs.';
            });
        };

        $scope.onImportFileSelected = function (element) {
            var file = element.files[0];
            if (file) {
                $scope.$apply(function () {
                    $scope.dbImport.selectedFile = file;
                    $scope.dbImport.fileName = file.name;
                    $scope.dbImport.error = null;
                });
            }
        };

        $scope.showImportConfirm = function () {
            if (!$scope.dbImport.selectedFile) {
                $scope.dbImport.error = 'Please select a SQL file first.';
                return;
            }
            $scope.dbImport.showConfirm = true;
        };

        $scope.cancelImport = function () {
            $scope.dbImport.showConfirm = false;
        };

        $scope.confirmImport = function () {
            $scope.dbImport.showConfirm = false;
            $scope.dbImport.loading = true;
            $scope.dbImport.error = null;
            $scope.dbImport.success = null;

            var formData = new FormData();
            formData.append('file', $scope.dbImport.selectedFile);

            settingsService.importDatabase(formData, function (response) {
                $scope.dbImport.loading = false;
                if (response.status === 'OK') {
                    $scope.dbImport.success = 'Database imported successfully. Please refresh the page.';
                    $scope.dbImport.selectedFile = null;
                    $scope.dbImport.fileName = null;
                } else {
                    $scope.dbImport.error = 'Import failed: ' + (response.message || 'Unknown error');
                }
            }, function () {
                $scope.dbImport.loading = false;
                $scope.dbImport.error = 'Import request failed. Check server logs.';
            });
        };

        $scope.init();

    });