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
      templateUrl: "app/components/plugins/worktime/views/worktime_policies.html",
      controller: "WorkTimeAdminController",
    });

    $stateProvider.state("plugin-worktime-policies", {
      url: "/plugin-worktime/policies",
      templateUrl: "app/components/plugins/worktime/views/worktime_policies.html",
      controller: "WorkTimeAdminController",
    });
  })
  .factory("WorkTimePolicy", function ($resource) {
    var unwrapList = function (data) {
      var response = angular.fromJson(data);
      if (response && response.data) {
        return response.data;
      }
      return response || [];
    };

    return $resource(
      "/rest/plugins/worktime/private/policy/:deviceId",
      { deviceId: "@deviceId" },
      {
        get: { method: "GET" },
        list: {
          method: "GET",
          url: "/rest/plugins/worktime/private/policies",
          isArray: true,
          transformResponse: unwrapList,
        },
        save: { method: "POST", url: "/rest/plugins/worktime/private/policy" },
      }
    );
  })
  .factory("WorkTimeDevice", function ($resource) {
    var unwrapList = function (data) {
      var response = angular.fromJson(data);
      if (response && response.data) {
        return response.data;
      }
      return response || [];
    };

    return $resource(
      "/rest/plugins/worktime/private/device/:deviceId",
      { deviceId: "@deviceId" },
      {
        list: {
          method: "GET",
          url: "/rest/plugins/worktime/private/devices",
          isArray: true,
          transformResponse: unwrapList,
        },
        save: { method: "POST", url: "/rest/plugins/worktime/private/device" },
        remove: { method: "DELETE" },
      }
    );
  })
  .factory("WorkTimeApplications", function ($resource) {
    return $resource(
      "/rest/private/applications/search/:value",
      { value: "@value" },
      {
        getForDevice: {
          method: "GET",
          url: "/rest/plugins/worktime/private/device/:deviceId/applications",
          params: { deviceId: "@deviceId" },
        },
        getAll: {
          method: "GET",
          url: "/rest/private/applications/search",
        },
        getAllAdmin: {
          method: "GET",
          url: "/rest/private/applications/admin/search",
        },
        getAllFromConfigurations: {
          method: "GET",
          url: "/rest/private/configurations/applications",
        },
      }
    );
  })
  .controller(
    "WorkTimeAdminController",
    function (
      $scope,
      $uibModal,
      $timeout,
      $interval,
      WorkTimePolicy,
      WorkTimeDevice,
      WorkTimeApplications,
      localization,
      authService
    ) {
      var POLICY_MODAL_TEMPLATE = "worktimePolicyModalTemplate.html";
      var EXCEPTION_MODAL_TEMPLATE = "worktimeExceptionModalTemplate.html";
      var DEFAULT_POLICY = {
        startTime: "09:00",
        endTime: "17:00",
        daysOfWeek: 31,
        allowedAppsDuringWork: "",
        allowedAppsOutsideWork: "*",
        enabled: true,
      };

      $scope.error = null;
      $scope.success = null;
      $scope.loading = true;
      $scope.refreshing = false;
      $scope.appsLoading = true;
      $scope.policySaving = false;
      $scope.exceptionSaving = false;
      $scope.devices = [];
      $scope.applications = [];
      $scope.searchText = "";
      $scope.lastRefreshAt = null;
      $scope.canEdit = authService.isSuperAdmin() || authService.hasPermission("settings");
      $scope.days = [
        { id: 1, label: "Mon" },
        { id: 2, label: "Tue" },
        { id: 4, label: "Wed" },
        { id: 8, label: "Thu" },
        { id: 16, label: "Fri" },
        { id: 32, label: "Sat" },
        { id: 64, label: "Sun" },
      ];

      var modalInstance = null;
      var refreshPromise = null;

      function showSuccess(message) {
        $scope.success = message;
        $timeout(function () {
          $scope.success = null;
        }, 3000);
      }

      function getAppSearchText(app) {
        return [app && app.name, app && app.applicationName, app && app.pkg]
          .filter(function (value) { return !!value; })
          .join(" ")
          .toLowerCase();
      }

      function parseAppsString(appsString) {
        var selected = {};
        if (!appsString) {
          return selected;
        }
        if (String(appsString).trim() === "*") {
          selected["*"] = true;
          return selected;
        }
        String(appsString)
          .split(",")
          .forEach(function (pkg) {
            var trimmed = pkg.trim();
            if (trimmed) {
              selected[trimmed] = true;
            }
          });
        return selected;
      }

      function buildAppsString(selectedApps) {
        if (selectedApps["*"]) {
          return "*";
        }
        return Object.keys(selectedApps)
          .filter(function (pkg) {
            return pkg !== "*" && selectedApps[pkg];
          })
          .join(",");
      }

      function countSelected(selectedApps) {
        if (selectedApps["*"]) {
          return "All";
        }
        var count = Object.keys(selectedApps).filter(function (pkg) {
          return pkg !== "*" && selectedApps[pkg];
        }).length;
        return count > 0 ? count : "None";
      }

      function normalizePolicy(device, policy) {
        var merged = angular.extend({}, DEFAULT_POLICY, policy || {});
        merged.deviceId = device.deviceId;
        merged.deviceName = device.deviceName || (policy && policy.deviceName) || ("Device " + device.deviceId);
        return merged;
      }

      function parseLocalDate(value) {
        if (!value) {
          return null;
        }
        var stringValue = String(value);
        if (/^\d{4}-\d{2}-\d{2}$/.test(stringValue)) {
          var parts = stringValue.split("-");
          return new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10), 0, 0, 0, 0);
        }
        var parsed = new Date(stringValue);
        return isNaN(parsed.getTime()) ? null : parsed;
      }

      function parseTimeToDate(timeValue) {
        if (!timeValue) {
          return null;
        }
        if (angular.isDate(timeValue) && !isNaN(timeValue.getTime())) {
          return timeValue;
        }
        if (typeof timeValue === "string") {
          var parts = timeValue.split(":");
          if (parts.length >= 2) {
            var parsed = new Date();
            parsed.setHours(parseInt(parts[0], 10) || 0, parseInt(parts[1], 10) || 0, 0, 0);
            return parsed;
          }
        }
        return null;
      }

      function toDatePart(dateValue) {
        var date = parseLocalDate(dateValue);
        if (!date || isNaN(date.getTime())) {
          return null;
        }
        return [date.getFullYear(), ("0" + (date.getMonth() + 1)).slice(-2), ("0" + date.getDate()).slice(-2)].join("-");
      }

      function toTimePart(timeValue) {
        if (!timeValue) {
          return null;
        }
        if (angular.isDate(timeValue) && !isNaN(timeValue.getTime())) {
          return ("0" + timeValue.getHours()).slice(-2) + ":" + ("0" + timeValue.getMinutes()).slice(-2);
        }
        if (typeof timeValue === "string") {
          var parts = timeValue.split(":");
          if (parts.length >= 2) {
            return ("0" + (parseInt(parts[0], 10) || 0)).slice(-2) + ":" + ("0" + (parseInt(parts[1], 10) || 0)).slice(-2);
          }
        }
        return null;
      }

      function toApiDateTimeString(dateValue, timeValue) {
        var datePart = toDatePart(dateValue);
        var timePart = toTimePart(timeValue);
        if (!datePart || !timePart) {
          return null;
        }
        return datePart + "T" + timePart + ":00";
      }

      function getExceptionRange(exception) {
        if (!exception) {
          return null;
        }
        var from = parseLocalDate(exception.dateFrom);
        var to = parseLocalDate(exception.dateTo);
        if (!from || !to || isNaN(from.getTime()) || isNaN(to.getTime())) {
          return null;
        }
        if (exception.timeFrom) {
          var fromParts = String(exception.timeFrom).split(":");
          from.setHours(parseInt(fromParts[0], 10) || 0, parseInt(fromParts[1], 10) || 0, 0, 0);
        }
        if (exception.timeTo) {
          var toParts = String(exception.timeTo).split(":");
          to.setHours(parseInt(toParts[0], 10) || 0, parseInt(toParts[1], 10) || 0, 59, 999);
        }
        return { from: from, to: to };
      }

      function normalizeExceptions(device) {
        var exceptions = angular.isArray(device.exceptions) ? angular.copy(device.exceptions) : [];
        var now = new Date();

        if (exceptions.length === 0 && device.startDateTime && device.endDateTime) {
          var start = new Date(device.startDateTime);
          var end = new Date(device.endDateTime);
          if (!isNaN(start.getTime()) && !isNaN(end.getTime()) && now <= end) {
            exceptions.push({
              dateFrom: start,
              dateTo: end,
              timeFrom: ("0" + start.getHours()).slice(-2) + ":" + ("0" + start.getMinutes()).slice(-2),
              timeTo: ("0" + end.getHours()).slice(-2) + ":" + ("0" + end.getMinutes()).slice(-2),
            });
          }
        }

        return exceptions
          .map(function (exception) {
            var range = getExceptionRange(exception);
            if (!range) {
              return exception;
            }
            exception.dateFrom = range.from;
            exception.dateTo = range.to;
            exception.active = now >= range.from && now <= range.to;
            return exception;
          })
          .filter(function (exception) {
            var range = getExceptionRange(exception);
            return !range || now <= range.to;
          });
      }

      function buildDeviceRows(overrides, policies) {
        var policyMap = {};
        (policies || []).forEach(function (policy) {
          policyMap[policy.deviceId] = policy;
        });

        return (overrides || []).map(function (device) {
          var row = angular.copy(device);
          row.deviceName = row.deviceName || ("Device " + row.deviceId);
          row.policy = normalizePolicy(row, policyMap[row.deviceId]);
          row.exceptions = normalizeExceptions(row);
          row.hasActiveException = row.exceptions.some(function (exception) {
            return !!exception.active;
          });
          return row;
        }).sort(function (left, right) {
          var leftName = (left.deviceName || "").toLowerCase();
          var rightName = (right.deviceName || "").toLowerCase();
          return leftName.localeCompare(rightName);
        });
      }

      function handleLoadError(error, fallbackMessage) {
        console.error(fallbackMessage, error);
        $scope.error = (error && error.data && error.data.message) || fallbackMessage;
        $scope.loading = false;
        $scope.refreshing = false;
      }

      $scope.formatDays = function (daysOfWeek) {
        var value = angular.isNumber(daysOfWeek) ? daysOfWeek : DEFAULT_POLICY.daysOfWeek;
        var labels = $scope.days.filter(function (day) {
          return (value & day.id) === day.id;
        }).map(function (day) {
          return day.label;
        });
        return labels.length === 7 ? "Every day" : labels.join(", ");
      };

      $scope.describeApps = function (rawApps) {
        var selected = parseAppsString(rawApps);
        return countSelected(selected);
      };

      $scope.filteredDevices = function () {
        if (!$scope.searchText) {
          return $scope.devices;
        }
        var search = $scope.searchText.toLowerCase();
        return $scope.devices.filter(function (device) {
          return (device.deviceName || "").toLowerCase().indexOf(search) !== -1 ||
                 String(device.deviceId || "").indexOf(search) !== -1;
        });
      };

      $scope.getFilteredApps = function (searchText) {
        if (!$scope.applications) {
          return [];
        }
        if (!searchText || !String(searchText).trim()) {
          return $scope.applications;
        }
        var lowered = String(searchText).toLowerCase().trim();
        return $scope.applications.filter(function (app) {
          return getAppSearchText(app).indexOf(lowered) !== -1;
        });
      };

      $scope.togglePolicyDay = function (dayMask) {
        if (($scope.editingPolicy.daysOfWeek & dayMask) === dayMask) {
          $scope.editingPolicy.daysOfWeek &= ~dayMask;
        } else {
          $scope.editingPolicy.daysOfWeek |= dayMask;
        }
      };

      $scope.hasPolicyDay = function (dayMask) {
        return (($scope.editingPolicy.daysOfWeek || 0) & dayMask) === dayMask;
      };

      $scope.toggleAllAppsDuringWork = function () {
        if ($scope.selectedAppsDuringWork["*"]) {
          $scope.selectedAppsDuringWork = { "*": true };
        }
      };

      $scope.toggleAllAppsOutsideWork = function () {
        if ($scope.selectedAppsOutsideWork["*"]) {
          $scope.selectedAppsOutsideWork = { "*": true };
        }
      };

      $scope.toggleIndividualAppDuringWork = function () {
        if ($scope.selectedAppsDuringWork["*"]) {
          delete $scope.selectedAppsDuringWork["*"];
        }
      };

      $scope.toggleIndividualAppOutsideWork = function () {
        if ($scope.selectedAppsOutsideWork["*"]) {
          delete $scope.selectedAppsOutsideWork["*"];
        }
      };

      $scope.countSelectedApps = function (selectedApps) {
        return countSelected(selectedApps);
      };

      $scope.refresh = function (silent) {
        if (silent) {
          $scope.refreshing = true;
        } else {
          $scope.loading = true;
        }
        $scope.error = null;

        WorkTimeDevice.list(
          function (deviceResponse) {
            WorkTimePolicy.list(
              function (policyResponse) {
                $scope.devices = buildDeviceRows(deviceResponse, policyResponse);
                $scope.lastRefreshAt = new Date();
                $scope.loading = false;
                $scope.refreshing = false;
              },
              function (error) {
                handleLoadError(error, "Failed to load device policies");
              }
            );
          },
          function (error) {
            handleLoadError(error, "Failed to load devices");
          }
        );
      };

      function normalizeApplicationsResponse(response) {
        var list = [];
        if (response && angular.isArray(response.data)) {
          list = response.data;
        } else if (response && angular.isArray(response)) {
          list = response;
        }

        return list
          .filter(function (app) {
            return !!(app && app.pkg);
          })
          .sort(function (left, right) {
            var leftName = (left.name || left.applicationName || left.pkg).toLowerCase();
            var rightName = (right.name || right.applicationName || right.pkg).toLowerCase();
            return leftName.localeCompare(rightName);
          });
      }

      $scope.loadApplications = function () {
        $scope.appsLoading = true;

        var assignApps = function (apps) {
          $scope.applications = apps || [];
          $scope.appsLoading = false;
        };

        var loadFromConfigurationEndpoint = function () {
          WorkTimeApplications.getAllFromConfigurations(
            {},
            function (configurationResponse) {
              assignApps(normalizeApplicationsResponse(configurationResponse));
            },
            function (configurationError) {
              console.error("Failed to load applications via configuration endpoint", configurationError);
              assignApps([]);
            }
          );
        };

        var loadFromStandardSearch = function () {
          WorkTimeApplications.getAll(
            {},
            function (response) {
              var apps = normalizeApplicationsResponse(response);
              if (apps.length > 0) {
                assignApps(apps);
                return;
              }
              loadFromConfigurationEndpoint();
            },
            function (error) {
              console.error("Failed to load applications", error);
              loadFromConfigurationEndpoint();
            }
          );
        };

        var loadFromAdminSearch = function () {
          WorkTimeApplications.getAllAdmin(
            {},
            function (adminResponse) {
              var adminApps = normalizeApplicationsResponse(adminResponse);
              if (adminApps.length > 0) {
                assignApps(adminApps);
                return;
              }
              loadFromStandardSearch();
            },
            function (adminError) {
              console.error("Failed to load applications via admin search", adminError);
              loadFromStandardSearch();
            }
          );
        };

        // Prefer admin endpoint to get the full catalog for policy selection.
        loadFromAdminSearch();
      };

      $scope.loadApplicationsForDevice = function (deviceId) {
        if (!deviceId) {
          $scope.applications = [];
          $scope.appsLoading = false;
          return;
        }

        $scope.appsLoading = true;

        WorkTimeApplications.getForDevice(
          { deviceId: deviceId },
          function (response) {
            var apps = normalizeApplicationsResponse(response);
            $scope.applications = apps;
            $scope.appsLoading = false;
          },
          function (error) {
            console.error("Failed to load device applications", error);
            $scope.applications = [];
            $scope.appsLoading = false;
          }
        );
      };

      $scope.openPolicyModal = function (device) {
        if (!$scope.canEdit) {
          return;
        }

        $scope.loadApplicationsForDevice(device.deviceId);

        $scope.error = null;
        $scope.editingDevice = device;
        $scope.editingPolicy = angular.copy(device.policy);
        $scope.selectedAppsDuringWork = parseAppsString($scope.editingPolicy.allowedAppsDuringWork);
        $scope.selectedAppsOutsideWork = parseAppsString($scope.editingPolicy.allowedAppsOutsideWork);
        $scope.policyDuringSearchText = "";
        $scope.policyOutsideSearchText = "";

        modalInstance = $uibModal.open({
          templateUrl: POLICY_MODAL_TEMPLATE,
          scope: $scope,
          windowClass: "worktime-policy-modal",
          backdrop: "static",
          keyboard: true,
        });

        modalInstance.result.finally(function () {
          modalInstance = null;
          $scope.editingDevice = null;
          $scope.editingPolicy = null;
        });
      };

      $scope.savePolicy = function () {
        if (!$scope.editingPolicy || $scope.policySaving) {
          return;
        }

        $scope.policySaving = true;
        $scope.error = null;

        var payload = angular.copy($scope.editingPolicy);
        payload.allowedAppsDuringWork = buildAppsString($scope.selectedAppsDuringWork);
        payload.allowedAppsOutsideWork = buildAppsString($scope.selectedAppsOutsideWork);

        WorkTimePolicy.save(
          payload,
          function (response) {
            $scope.policySaving = false;
            if (response && response.status === "OK") {
              if (modalInstance) {
                modalInstance.close();
              }
              showSuccess("Policy updated for " + ($scope.editingDevice.deviceName || ("Device " + $scope.editingDevice.deviceId)));
              $scope.refresh(true);
            } else {
              $scope.error = (response && response.message) || "Failed to save policy";
            }
          },
          function (error) {
            $scope.policySaving = false;
            $scope.error = (error && error.data && error.data.message) || localization.localize("error.request.failure");
          }
        );
      };

      $scope.closePolicyModal = function () {
        if (modalInstance) {
          modalInstance.close();
        }
      };

      $scope.openExceptionModal = function (device) {
        if (!$scope.canEdit) {
          return;
        }

        var existing = device.exceptions && device.exceptions.length > 0 ? angular.copy(device.exceptions[0]) : null;
        var defaultStart = new Date();
        defaultStart.setMinutes(defaultStart.getMinutes() + 1, 0, 0);
        var defaultEnd = new Date(defaultStart.getTime() + 60 * 60 * 1000);

        $scope.error = null;
        $scope.editingDevice = device;
        $scope.editingException = existing || {
          dateFrom: defaultStart,
          dateTo: defaultEnd,
          timeFrom: ("0" + defaultStart.getHours()).slice(-2) + ":" + ("0" + defaultStart.getMinutes()).slice(-2),
          timeTo: ("0" + defaultEnd.getHours()).slice(-2) + ":" + ("0" + defaultEnd.getMinutes()).slice(-2),
        };
        $scope.editingException.timeFromInput = parseTimeToDate($scope.editingException.timeFrom) || parseTimeToDate("09:00");
        $scope.editingException.timeToInput = parseTimeToDate($scope.editingException.timeTo) || parseTimeToDate("10:00");

        modalInstance = $uibModal.open({
          templateUrl: EXCEPTION_MODAL_TEMPLATE,
          scope: $scope,
          windowClass: "worktime-exception-modal",
          backdrop: "static",
          keyboard: true,
        });

        modalInstance.result.finally(function () {
          modalInstance = null;
          $scope.editingDevice = null;
          $scope.editingException = null;
        });
      };

      $scope.saveException = function () {
        if (!$scope.editingDevice || !$scope.editingException || $scope.exceptionSaving) {
          return;
        }

        $scope.exceptionSaving = true;
        $scope.error = null;

        var startDateTime = toApiDateTimeString($scope.editingException.dateFrom, $scope.editingException.timeFromInput || $scope.editingException.timeFrom);
        var endDateTime = toApiDateTimeString($scope.editingException.dateTo, $scope.editingException.timeToInput || $scope.editingException.timeTo);

        if (!startDateTime || !endDateTime) {
          $scope.exceptionSaving = false;
          $scope.error = "Start and end date/time are required";
          return;
        }

        if (new Date(endDateTime) <= new Date(startDateTime)) {
          $scope.exceptionSaving = false;
          $scope.error = "End time must be after start time";
          return;
        }

        var payload = {
          deviceId: $scope.editingDevice.deviceId,
          enabled: false,
          startDateTime: startDateTime,
          endDateTime: endDateTime,
        };

        WorkTimeDevice.save(
          { deviceId: $scope.editingDevice.deviceId },
          payload,
          function (response) {
            $scope.exceptionSaving = false;
            if (response && response.status === "OK") {
              if (modalInstance) {
                modalInstance.close();
              }
              showSuccess("Exception saved for " + ($scope.editingDevice.deviceName || ("Device " + $scope.editingDevice.deviceId)));
              $scope.refresh(true);
            } else {
              $scope.error = (response && response.message) || "Failed to save exception";
            }
          },
          function (error) {
            $scope.exceptionSaving = false;
            $scope.error = (error && error.data && error.data.message) || localization.localize("error.request.failure");
          }
        );
      };

      $scope.deleteException = function (device) {
        if (!$scope.canEdit || !confirm("Delete this exception?")) {
          return;
        }

        WorkTimeDevice.remove(
          { deviceId: device.deviceId },
          function () {
            if (modalInstance) {
              modalInstance.close();
            }
            showSuccess("Exception removed for " + (device.deviceName || ("Device " + device.deviceId)));
            $scope.refresh(true);
          },
          function (error) {
            $scope.error = (error && error.data && error.data.message) || localization.localize("error.request.failure");
          }
        );
      };

      $scope.closeExceptionModal = function () {
        if (modalInstance) {
          modalInstance.close();
        }
      };

      refreshPromise = $interval(function () {
        $scope.refresh(true);
      }, 15000);

      $scope.$on("$destroy", function () {
        if (refreshPromise) {
          $interval.cancel(refreshPromise);
        }
      });

      $scope.refresh();
    }
  )
  .controller("WorkTimePoliciesController", function ($controller, $scope) {
    $controller("WorkTimeAdminController", { $scope: $scope });
  })
  .controller("WorkTimeDevicesController", function ($controller, $scope) {
    $controller("WorkTimeAdminController", { $scope: $scope });
  })
  .run(function (localization) {
    localization.loadPluginResourceBundles("worktime");
  });
