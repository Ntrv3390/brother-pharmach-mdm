angular
  .module("plugin-worktime", ["ngResource", "ui.bootstrap", "ui.router"])
  .config(function ($stateProvider) {
    try {
      $stateProvider.state("plugin-worktime", {
        url: "/plugin-worktime",
        templateUrl: "app/components/main/view/content.html",
        controller: "TabController",
        resolve: {
          openTab: function () {
            return "plugin-worktime";
          },
        },
      });
    } catch (e) {
      console.log("Error adding state plugin-worktime", e);
    }

    $stateProvider.state("plugin-worktime-devices", {
      url: "/plugin-worktime/devices",
      templateUrl: "app/components/plugins/worktime/views/worktime_devices.html",
      controller: "WorkTimeDevicesController",
    });

    $stateProvider.state("plugin-worktime-policies", {
      url: "/plugin-worktime/policies",
      templateUrl: "app/components/plugins/worktime/views/worktime_policies.html",
      controller: "WorkTimePoliciesController",
    });
  })
  .factory("WorkTimePolicy", function ($resource) {
    return $resource(
      "/rest/plugins/worktime/private/policy",
      {},
      {
        get: { method: "GET" },
        save: { method: "POST" },
      }
    );
  })
  .factory("WorkTimeDevice", function ($resource) {
    return $resource("/rest/plugins/worktime/private/device/:deviceId", { deviceId: '@deviceId' }, {
      list: {
        method: 'GET',
        url: '/rest/plugins/worktime/private/devices',
        isArray: true,
        transformResponse: function(data) {
          var response = angular.fromJson(data);
          if (response && response.data) {
            return response.data;
          }
          return response || [];
        }
      },
      save: { 
          method: 'POST',
          url: '/rest/plugins/worktime/private/device'
      },
      remove: { method: 'DELETE' }
    });
  })
  .factory("WorkTimeApplications", function ($resource) {
    return $resource("/rest/private/applications/search/:value", { value: '@value' }, {
      getAll: { method: 'GET' }
    });
  })
  .controller(
    "WorkTimePoliciesController",
    function ($scope, $timeout, WorkTimePolicy, WorkTimeApplications, WorkTimeDevice, localization) {
      $scope.policy = null;
      $scope.error = null;
      $scope.success = null;
      $scope.isSaving = false;
      $scope.applications = [];
      $scope.appsLoading = true;
      $scope.selectedAppsDuringWork = {};
      $scope.selectedAppsOutsideWork = {};
      $scope.duringWorkSearchText = '';
      $scope.outsideWorkSearchText = '';
      $scope.activeExceptionsCount = 0;
      $scope.hasActiveExceptions = false;

      $scope.days = [
        { id: 1, label: "Mon" },
        { id: 2, label: "Tue" },
        { id: 4, label: "Wed" },
        { id: 8, label: "Thu" },
        { id: 16, label: "Fri" },
        { id: 32, label: "Sat" },
        { id: 64, label: "Sun" },
      ];

      $scope.toggleDay = function (dayMask) {
        if (($scope.policy.daysOfWeek & dayMask) === dayMask) {
          $scope.policy.daysOfWeek &= ~dayMask;
        } else {
          $scope.policy.daysOfWeek |= dayMask;
        }
      };

      $scope.hasDay = function (dayMask) {
        return ($scope.policy.daysOfWeek & dayMask) === dayMask;
      };

      // Load available applications
      $scope.loadApplications = function () {
        $scope.appsLoading = true;
        WorkTimeApplications.getAll(
          {},
          function (response) {
            if (response && response.status === 'OK' && response.data) {
              $scope.applications = response.data.filter(function(app) {
                return app.pkg; // Only include apps with package names
              }).sort(function(a, b) {
                var nameA = (a.name || a.pkg).toLowerCase();
                var nameB = (b.name || b.pkg).toLowerCase();
                return nameA.localeCompare(nameB);
              });
            }
            $scope.appsLoading = false;
          },
          function (error) {
            console.error('Failed to load applications:', error);
            $scope.error = 'Failed to load applications';
            $scope.appsLoading = false;
          }
        );
      };

      // Convert comma-separated string to selected apps object
      $scope.parseAppsString = function (appsString) {
        var selected = {};
        if (appsString) {
          if (appsString.trim() === '*') {
            selected['*'] = true;
          } else {
            var packages = appsString.split(',');
            packages.forEach(function (pkg) {
              var trimmed = pkg.trim();
              if (trimmed) {
                selected[trimmed] = true;
              }
            });
          }
        }
        return selected;
      };

      // Convert selected apps object to comma-separated string
      $scope.buildAppsString = function (selectedApps) {
        if (selectedApps['*']) {
          return '*';
        }
        var packages = [];
        for (var pkg in selectedApps) {
          if (selectedApps.hasOwnProperty(pkg) && selectedApps[pkg] && pkg !== '*') {
            packages.push(pkg);
          }
        }
        return packages.join(',');
      };

      // Toggle "All Apps" selection
      $scope.toggleAllAppsDuringWork = function () {
        if ($scope.selectedAppsDuringWork['*']) {
          // When "All Apps" is selected, clear individual selections
          $scope.selectedAppsDuringWork = { '*': true };
        }
      };

      $scope.toggleAllAppsOutsideWork = function () {
        if ($scope.selectedAppsOutsideWork['*']) {
          // When "All Apps" is selected, clear individual selections
          $scope.selectedAppsOutsideWork = { '*': true };
        }
      };

      // When individual app is selected, uncheck "All Apps"
      $scope.toggleIndividualAppDuringWork = function (pkg) {
        if ($scope.selectedAppsDuringWork['*']) {
          delete $scope.selectedAppsDuringWork['*'];
        }
      };

      $scope.toggleIndividualAppOutsideWork = function (pkg) {
        if ($scope.selectedAppsOutsideWork['*']) {
          delete $scope.selectedAppsOutsideWork['*'];
        }
      };

      // Get filtered apps for during work
      $scope.getFilteredAppsDuringWork = function() {
        if (!$scope.applications) return [];
        if (!$scope.duringWorkSearchText) return $scope.applications;
        var search = $scope.duringWorkSearchText.toLowerCase();
        return $scope.applications.filter(function(app) {
          var name = (app.name || '').toLowerCase();
          var pkg = (app.pkg || '').toLowerCase();
          return name.indexOf(search) !== -1 || pkg.indexOf(search) !== -1;
        });
      };

      // Get filtered apps for outside work
      $scope.getFilteredAppsOutsideWork = function() {
        if (!$scope.applications) return [];
        if (!$scope.outsideWorkSearchText) return $scope.applications;
        var search = $scope.outsideWorkSearchText.toLowerCase();
        return $scope.applications.filter(function(app) {
          var name = (app.name || '').toLowerCase();
          var pkg = (app.pkg || '').toLowerCase();
          return name.indexOf(search) !== -1 || pkg.indexOf(search) !== -1;
        });
      };

      // Count selected apps (excluding 'All' option)
      $scope.countSelected = function (selectedApps) {
        if (selectedApps['*']) return 'All';
        var count = 0;
        for (var pkg in selectedApps) {
          if (selectedApps.hasOwnProperty(pkg) && selectedApps[pkg] && pkg !== '*') {
            count++;
          }
        }
        return count > 0 ? count : 'None';
      };

      $scope.loadActiveExceptionsStatus = function () {
        WorkTimeDevice.list(
          function (response) {
            var devices = angular.isArray(response) ? response : [];
            var now = new Date();
            var activeCount = 0;

            devices.forEach(function (device) {
              var hasActive = false;

              if (device.startDateTime && device.endDateTime) {
                var start = new Date(device.startDateTime);
                var end = new Date(device.endDateTime);
                if (!isNaN(start.getTime()) && !isNaN(end.getTime()) && now >= start && now <= end) {
                  hasActive = true;
                }
              }

              if (!hasActive && device.exceptions && device.exceptions.length > 0) {
                hasActive = device.exceptions.some(function (exc) {
                  var dateFrom = exc.dateFrom ? new Date(exc.dateFrom) : null;
                  var dateTo = exc.dateTo ? new Date(exc.dateTo) : null;
                  if (!dateFrom || !dateTo || isNaN(dateFrom.getTime()) || isNaN(dateTo.getTime())) {
                    return !!exc.active;
                  }

                  if (exc.timeFrom && typeof exc.timeFrom === 'string') {
                    var fromParts = exc.timeFrom.split(':');
                    if (fromParts.length >= 2) {
                      dateFrom.setHours(parseInt(fromParts[0], 10) || 0, parseInt(fromParts[1], 10) || 0, 0, 0);
                    }
                  }

                  if (exc.timeTo && typeof exc.timeTo === 'string') {
                    var toParts = exc.timeTo.split(':');
                    if (toParts.length >= 2) {
                      dateTo.setHours(parseInt(toParts[0], 10) || 0, parseInt(toParts[1], 10) || 0, 59, 999);
                    }
                  }

                  return now >= dateFrom && now <= dateTo;
                });
              }

              if (hasActive) {
                activeCount++;
              }
            });

            $scope.activeExceptionsCount = activeCount;
            $scope.hasActiveExceptions = activeCount > 0;
          },
          function () {
            $scope.activeExceptionsCount = 0;
            $scope.hasActiveExceptions = false;
          }
        );
      };

      $scope.refresh = function () {
        WorkTimePolicy.get(
          function (response) {
            if (response && response.status === "OK") {
              $scope.policy = response.data || {
                startTime: "09:00",
                endTime: "18:00",
                daysOfWeek: 31,
                allowedAppsDuringWork: "",
                allowedAppsOutsideWork: "*",
                enabled: true,
              };
            } else {
              $scope.policy = {
                startTime: "09:00",
                endTime: "18:00",
                daysOfWeek: 31,
                allowedAppsDuringWork: "",
                allowedAppsOutsideWork: "*",
                enabled: true,
              };
            }
            // Parse the apps string into selected apps
            $scope.selectedAppsDuringWork = $scope.parseAppsString($scope.policy.allowedAppsDuringWork);
            $scope.selectedAppsOutsideWork = $scope.parseAppsString($scope.policy.allowedAppsOutsideWork);
            $scope.loadActiveExceptionsStatus();
          },
          function () {
            $scope.error = localization.localize("error.request.failure");
          }
        );
      };

      $scope.save = function () {
        if ($scope.isSaving) {
          return;
        }

        $scope.error = null;
        $scope.success = null;
        $scope.isSaving = true;

        // Convert selected apps to comma-separated strings
        var policyToSave = angular.copy($scope.policy);
        policyToSave.allowedAppsDuringWork = $scope.buildAppsString($scope.selectedAppsDuringWork);
        policyToSave.allowedAppsOutsideWork = $scope.buildAppsString($scope.selectedAppsOutsideWork);

        WorkTimePolicy.save(
          policyToSave,
          function (response) {
            $scope.isSaving = false;
            if (response && response.status === "OK") {
              $scope.policy = response.data;
              $scope.selectedAppsDuringWork = $scope.parseAppsString($scope.policy.allowedAppsDuringWork);
              $scope.selectedAppsOutsideWork = $scope.parseAppsString($scope.policy.allowedAppsOutsideWork);
              
              // Show success message
              $scope.success = "Work Time Policy saved successfully!";
              $scope.loadActiveExceptionsStatus();
              
              // Auto-hide success message after 3 seconds
              $timeout(function() {
                $scope.success = null;
              }, 3000);
            } else if (response && response.message) {
              $scope.error = response.message;
            }
          },
          function () {
            $scope.isSaving = false;
            $scope.error = localization.localize("error.request.failure");
          }
        );
      };

      // Initialize
      $scope.loadApplications();
      $scope.refresh();
    }
  )

  .controller("WorkTimeDevicesController", function($scope, $uibModal, WorkTimePolicy, WorkTimeDevice, localization, authService) {
    $scope.devices = [];
    $scope.globalPolicy = { enabled: true, startTime: '09:00', endTime: '18:00' };
    $scope.error = null;
    $scope.canEdit = authService.isSuperAdmin() || authService.hasPermission('settings');
    $scope.editingDevice = null;
    $scope.editingException = null;
    var modalInstance = null;

    var parseLocalDate = function(value) {
      if (!value) return null;
      var s = String(value);
      if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
        var parts = s.split('-');
        var y = parseInt(parts[0], 10);
        var m = parseInt(parts[1], 10) - 1;
        var dNum = parseInt(parts[2], 10);
        var localDate = new Date(y, m, dNum, 0, 0, 0, 0);
        if (!isNaN(localDate.getTime())) {
          return localDate;
        }
      }
      var d = new Date(s);
      if (!isNaN(d.getTime())) return d;
      return null;
    };

    var toLocalDateTimeString = function(dateValue) {
      if (!dateValue || isNaN(dateValue.getTime())) return null;
      var yyyy = dateValue.getFullYear();
      var mm = ('0' + (dateValue.getMonth() + 1)).slice(-2);
      var dd = ('0' + dateValue.getDate()).slice(-2);
      var hh = ('0' + dateValue.getHours()).slice(-2);
      var mi = ('0' + dateValue.getMinutes()).slice(-2);
      var ss = ('0' + dateValue.getSeconds()).slice(-2);
      return yyyy + '-' + mm + '-' + dd + 'T' + hh + ':' + mi + ':' + ss;
    };

    var toDatePart = function(dateValue) {
      var d = parseLocalDate(dateValue);
      if (isNaN(d.getTime())) return null;
      var yyyy = d.getFullYear();
      var mm = ('0' + (d.getMonth() + 1)).slice(-2);
      var dd = ('0' + d.getDate()).slice(-2);
      return yyyy + '-' + mm + '-' + dd;
    };

    var toLocalDayDate = function(dateValue) {
      var parsed = parseLocalDate(dateValue);
      if (!parsed || isNaN(parsed.getTime())) return null;
      return new Date(parsed.getFullYear(), parsed.getMonth(), parsed.getDate(), 0, 0, 0, 0);
    };

    var toTimePart = function(timeValue) {
      if (!timeValue) return null;
      if (angular.isDate(timeValue) && !isNaN(timeValue.getTime())) {
        return ('0' + timeValue.getHours()).slice(-2) + ':' + ('0' + timeValue.getMinutes()).slice(-2);
      }
      if (typeof timeValue === 'string') {
        var parts = timeValue.split(':');
        if (parts.length >= 2) {
          return ('0' + (parseInt(parts[0], 10) || 0)).slice(-2) + ':' + ('0' + (parseInt(parts[1], 10) || 0)).slice(-2);
        }
      }
      return null;
    };

    var toApiDateTimeString = function(dateValue, timeValue) {
      var datePart = toDatePart(dateValue);
      var timePart = toTimePart(timeValue);
      if (!datePart || !timePart) return null;
      return datePart + 'T' + timePart + ':00';
    };

    var isEndDateTimeInFuture = function(dateValue, timeValue) {
      var endDatePart = toDatePart(dateValue);
      var endTimePart = toTimePart(timeValue);
      if (!endDatePart || !endTimePart) return false;

      var now = new Date();
      var nowDatePart =
        now.getFullYear() + '-' +
        ('0' + (now.getMonth() + 1)).slice(-2) + '-' +
        ('0' + now.getDate()).slice(-2);
      var nowTimePart =
        ('0' + now.getHours()).slice(-2) + ':' +
        ('0' + now.getMinutes()).slice(-2);

      if (endDatePart > nowDatePart) return true;
      if (endDatePart < nowDatePart) return false;
      return endTimePart > nowTimePart;
    };

    var combineDateTime = function(dateValue, timeValue) {
      if (!dateValue || !timeValue) return null;
      var d = toLocalDayDate(dateValue);
      if (isNaN(d.getTime())) return null;
      var hours;
      var minutes;
      if (angular.isDate(timeValue)) {
        hours = timeValue.getHours();
        minutes = timeValue.getMinutes();
      } else if (typeof timeValue === 'string') {
        var parts = timeValue.split(':');
        hours = parseInt(parts[0], 10) || 0;
        minutes = parseInt(parts[1], 10) || 0;
      } else {
        return null;
      }
      d.setHours(hours, minutes, 0, 0);
      return d;
    };

    var parseTimeToDate = function(timeValue) {
      if (!timeValue) return null;
      if (angular.isDate(timeValue) && !isNaN(timeValue.getTime())) {
        return timeValue;
      }
      if (typeof timeValue === 'string') {
        var parts = timeValue.split(':');
        if (parts.length >= 2) {
          var parsed = new Date();
          parsed.setHours(parseInt(parts[0], 10) || 0, parseInt(parts[1], 10) || 0, 0, 0);
          return parsed;
        }
      }
      return null;
    };

    var getExceptionRange = function(exc) {
      if (!exc) return null;
      var dateFrom = exc.dateFrom ? parseLocalDate(exc.dateFrom) : null;
      var dateTo = exc.dateTo ? parseLocalDate(exc.dateTo) : null;
      if (!dateFrom || !dateTo || isNaN(dateFrom.getTime()) || isNaN(dateTo.getTime())) {
        return null;
      }

      var from = new Date(dateFrom);
      var to = new Date(dateTo);

      if (exc.timeFrom && typeof exc.timeFrom === 'string') {
        var fromParts = exc.timeFrom.split(':');
        if (fromParts.length >= 2) {
          from.setHours(parseInt(fromParts[0], 10) || 0, parseInt(fromParts[1], 10) || 0, 0, 0);
        }
      }

      if (exc.timeTo && typeof exc.timeTo === 'string') {
        var toParts = exc.timeTo.split(':');
        if (toParts.length >= 2) {
          to.setHours(parseInt(toParts[0], 10) || 0, parseInt(toParts[1], 10) || 0, 59, 999);
        }
      }

      return { from: from, to: to };
    };

    // Load global policy
    $scope.loadGlobalPolicy = function() {
      WorkTimePolicy.get(function(response) {
        if (response && response.status === 'OK' && response.data) {
          $scope.globalPolicy = response.data;
        }
        $scope.loadDevices();
      });
    };

    var buildExceptionFromRange = function(device) {
      if (!device || !device.startDateTime || !device.endDateTime) return null;
      var start = new Date(device.startDateTime);
      var end = new Date(device.endDateTime);
      if (isNaN(start.getTime()) || isNaN(end.getTime())) return null;
      if (new Date() > end) return null;
      return {
        dateFrom: start,
        dateTo: end,
        timeFrom: ("0" + start.getHours()).slice(-2) + ':' + ("0" + start.getMinutes()).slice(-2),
        timeTo: ("0" + end.getHours()).slice(-2) + ':' + ("0" + end.getMinutes()).slice(-2),
        active: (new Date() >= start && new Date() <= end)
      };
    };

    var hasScheduledException = function(device) {
      return device && device.startDateTime && device.endDateTime;
    };

    var isActiveException = function(device, now) {
      var current = now || new Date();
      if (device.exceptions && device.exceptions.length > 0) {
        return device.exceptions.some(function(exc) {
          var range = getExceptionRange(exc);
          if (!range) {
            return !!exc.active;
          }
          var active = current >= range.from && current <= range.to;
          exc.active = active;
          return active;
        });
      }
      if (hasScheduledException(device)) {
        var start = new Date(device.startDateTime);
        var end = new Date(device.endDateTime);
        if (!isNaN(start.getTime()) && !isNaN(end.getTime())) {
          return current >= start && current <= end;
        }
      }
      return false;
    };

    // Load devices list
    $scope.loadDevices = function() {
      WorkTimeDevice.list(function(response) {
        var now = new Date();
        $scope.devices = angular.isArray(response) ? response : [];
        $scope.devices.forEach(function(device) {
          if (!device.exceptions) {
            device.exceptions = [];
          }
          device.exceptions = device.exceptions.filter(function(exc) {
            var range = getExceptionRange(exc);
            if (!range) {
              return true;
            }
            exc.dateFrom = range.from;
            exc.dateTo = range.to;
            exc.active = (now >= range.from && now <= range.to);
            return now <= range.to;
          });
          if (device.exceptions.length === 0 && device.enabled === false) {
            var fallback = buildExceptionFromRange(device);
            if (fallback) {
              device.exceptions.push(fallback);
            }
          }
          var hasActive = (device.exceptions || []).some(function(exc) {
            var range = getExceptionRange(exc);
            if (!range) {
              return !!exc.active;
            }
            var active = now >= range.from && now <= range.to;
            exc.active = active;
            return active;
          });
          device.hasActiveException = hasActive;
          if (hasScheduledException(device)) {
            device.toggleOn = false;
          } else {
            device.toggleOn = !!$scope.globalPolicy.enabled && !hasActive;
          }
          // Ensure deviceName is set
          if (!device.deviceName) device.deviceName = "Device " + device.deviceId;
        });
      }, function(error) {
        $scope.error = 'Failed to load devices: ' + (error && error.data ? error.data : 'Unknown error');
        console.error('Error loading devices:', error);
      });
    };

    // Toggle device exception (turn off means create exception)
    $scope.toggleDeviceException = function(device) {
      if (!$scope.canEdit || !$scope.globalPolicy.enabled) return;

      if (device.toggleOn) {
        if (device.exceptions && device.exceptions.length > 0) {
          WorkTimeDevice.remove({ deviceId: device.deviceId }, function() {
            $scope.loadDevices();
          });
        }
        return;
      }

      $scope.editingDevice = device;
      var existing = (device.exceptions && device.exceptions.length > 0) ? device.exceptions[0] : null;
      var defaultFrom = new Date();
      defaultFrom.setMinutes(defaultFrom.getMinutes() + 1, 0, 0);
      var defaultTo = new Date();
      defaultTo.setHours(defaultTo.getHours() + 1, 0, 0, 0);
      $scope.editingException = existing ? angular.copy(existing) : {
        dateFrom: defaultFrom,
        dateTo: defaultTo,
        timeFrom: ('0' + defaultFrom.getHours()).slice(-2) + ':' + ('0' + defaultFrom.getMinutes()).slice(-2),
        timeTo: ('0' + defaultTo.getHours()).slice(-2) + ':' + ('0' + defaultTo.getMinutes()).slice(-2)
      };
      $scope.editingException.timeFromInput = parseTimeToDate($scope.editingException.timeFrom) || parseTimeToDate(toTimePart(defaultFrom));
      $scope.editingException.timeToInput = parseTimeToDate($scope.editingException.timeTo) || parseTimeToDate(toTimePart(defaultTo));
      $scope.openExceptionModal();
    };

    // Edit existing exception
    $scope.editException = function(device) {
      if (!$scope.canEdit || !$scope.globalPolicy.enabled) return;
      $scope.editingDevice = device;
      $scope.editingException = angular.copy(device.exceptions[0]) || {};
      $scope.editingException.timeFromInput = parseTimeToDate($scope.editingException.timeFrom);
      $scope.editingException.timeToInput = parseTimeToDate($scope.editingException.timeTo);
      $scope.openExceptionModal();
    };

    // Save exception
    $scope.saveException = function() {
      if (!$scope.editingDevice || !$scope.editingException) return;

      var startDateTime = combineDateTime($scope.editingException.dateFrom, $scope.editingException.timeFromInput || $scope.editingException.timeFrom);
      var endDateTime = combineDateTime($scope.editingException.dateTo, $scope.editingException.timeToInput || $scope.editingException.timeTo);
      if (!startDateTime || !endDateTime) {
        $scope.error = 'Start and end date/time are required';
        return;
      }
      if (endDateTime < startDateTime) {
        $scope.error = 'End time must be after start time';
        return;
      }
      if (!isEndDateTimeInFuture($scope.editingException.dateTo, $scope.editingException.timeToInput || $scope.editingException.timeTo)) {
        $scope.error = 'End time must be in the future';
        return;
      }

      var override = {
        deviceId: $scope.editingDevice.deviceId,
        enabled: false,
        startDateTime: toApiDateTimeString($scope.editingException.dateFrom, $scope.editingException.timeFromInput || $scope.editingException.timeFrom),
        endDateTime: toApiDateTimeString($scope.editingException.dateTo, $scope.editingException.timeToInput || $scope.editingException.timeTo)
      };

      if (!override.startDateTime || !override.endDateTime) {
        $scope.error = 'Start and end date/time are required';
        return;
      }

      WorkTimeDevice.save({ deviceId: $scope.editingDevice.deviceId }, override, function(response) {
        if (response && response.status === 'OK') {
          $scope.closeExceptionModal();
          $scope.loadDevices();
        } else {
          $scope.error = response.message || 'Failed to save exception';
        }
      }, function() {
        $scope.error = localization.localize('error.request.failure');
      });
    };

    $scope.cancelException = function() {
      if ($scope.editingDevice) {
        $scope.editingDevice.toggleOn = true;
      }
      $scope.editingDevice = null;
      $scope.editingException = null;
      $scope.closeExceptionModal();
    };

    $scope.openExceptionModal = function() {
      if (modalInstance) return;
      modalInstance = $uibModal.open({
        templateUrl: 'worktimeExceptionModalTemplate.html',
        scope: $scope,
        backdrop: 'static',
        keyboard: true
      });
      modalInstance.result.finally(function() {
        modalInstance = null;
      });
    };

    $scope.closeExceptionModal = function() {
      if (modalInstance) {
        modalInstance.close();
        modalInstance = null;
      }
    };

    // Remove exception
    $scope.removeException = function(device, exception) {
      if (!confirm('Delete this exception?')) return;
      WorkTimeDevice.remove({ deviceId: device.deviceId }, function() {
        $scope.loadDevices();
      });
    };

    $scope.loadGlobalPolicy();
  })
  .run(function (localization) {
    localization.loadPluginResourceBundles("worktime");
  });
