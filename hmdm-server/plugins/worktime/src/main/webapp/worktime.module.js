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
      templateUrl:
        "app/components/plugins/worktime/views/worktime_policies.html",
      controller: "WorkTimeAdminController",
    });

    $stateProvider.state("plugin-worktime-policies", {
      url: "/plugin-worktime/policies",
      templateUrl:
        "app/components/plugins/worktime/views/worktime_policies.html",
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
      },
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
        removeException: {
          method: "DELETE",
          url: "/rest/plugins/worktime/private/device/exception/:exceptionId",
        },
      },
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
      },
    );
  })
  .factory("WorkTimeHoliday", function ($resource) {
    var unwrapList = function (data) {
      var response = angular.fromJson(data);
      if (response && response.data) {
        return response.data;
      }
      return response || [];
    };

    return $resource(
      "/rest/plugins/worktime/private/holiday/:id",
      { id: "@id" },
      {
        list: {
          method: "GET",
          url: "/rest/plugins/worktime/private/holidays",
          isArray: true,
          transformResponse: unwrapList,
        },
        active: {
          method: "GET",
          url: "/rest/plugins/worktime/private/holidays/active",
          isArray: true,
          transformResponse: unwrapList,
        },
        save: {
          method: "POST",
          url: "/rest/plugins/worktime/private/holiday",
        },
        remove: { method: "DELETE" },
        importCsv: {
          method: "POST",
          url: "/rest/plugins/worktime/private/holidays/import",
        },
      },
    );
  })
  .directive("worktimeCsvFile", function () {
    return {
      restrict: "A",
      scope: { onFile: "&worktimeCsvFile" },
      link: function (scope, element) {
        element.on("change", function (event) {
          var file =
            event.target.files && event.target.files.length
              ? event.target.files[0]
              : null;
          if (!file) {
            return;
          }
          var reader = new FileReader();
          reader.onload = function () {
            scope.$apply(function () {
              scope.onFile({ content: reader.result, filename: file.name });
            });
          };
          reader.onerror = function () {
            scope.$apply(function () {
              scope.onFile({ content: null, filename: file.name });
            });
          };
          reader.readAsText(file);
          // Reset so selecting the same file again re-triggers change.
          event.target.value = "";
        });
      },
    };
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
      WorkTimeHoliday,
      localization,
      authService,
    ) {
      var POLICY_MODAL_TEMPLATE = "worktimePolicyModalTemplate.html";
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
      $scope.canEdit =
        authService.isSuperAdmin() || authService.hasPermission("settings");
      $scope.days = [
        { id: 1, label: "Mon" },
        { id: 2, label: "Tue" },
        { id: 4, label: "Wed" },
        { id: 8, label: "Thu" },
        { id: 16, label: "Fri" },
        { id: 32, label: "Sat" },
        { id: 64, label: "Sun" },
      ];
      $scope.policyTime = null;
      $scope.exceptionTime = null;

      // General (organization-wide) holidays
      $scope.holidays = [];
      $scope.activeHolidays = [];
      $scope.holidayForm = null;
      $scope.holidayError = null;
      $scope.holidaySaving = false;
      $scope.holidaysLoading = false;
      $scope.csvError = null;
      $scope.csvResult = null;
      $scope.csvFileName = null;
      $scope.csvImporting = false;

      var modalInstance = null;
      var holidayModalInstance = null;
      var viewHolidaysModalInstance = null;
      var refreshPromise = null;

      function padTimePart(value) {
        return ("0" + (parseInt(value, 10) || 0)).slice(-2);
      }

      $scope.timeHourOptions = Array.apply(null, Array(24)).map(
        function (_, hour) {
          var value = padTimePart(hour);
          return { value: value, label: value };
        },
      );

      $scope.timeMinuteOptions = Array.apply(null, Array(60)).map(
        function (_, minute) {
          var value = padTimePart(minute);
          return { value: value, label: value };
        },
      );

      function parsePolicyTimeParts(timeValue, fallbackHour, fallbackMinute) {
        var parts = String(timeValue || "").split(":");
        var hour = parseInt(parts[0], 10);
        var minute = parseInt(parts[1], 10);

        if (isNaN(hour) || hour < 0 || hour > 23) {
          hour = fallbackHour;
        }
        if (isNaN(minute) || minute < 0 || minute > 59) {
          minute = fallbackMinute;
        }

        return {
          hour: padTimePart(hour),
          minute: padTimePart(minute),
        };
      }

      function composePolicyTime(hour, minute) {
        return padTimePart(hour) + ":" + padTimePart(minute);
      }

      function showSuccess(message) {
        $scope.success = message;
        $timeout(function () {
          $scope.success = null;
        }, 3000);
      }

      function getAppSearchText(app) {
        return [app && app.name, app && app.applicationName, app && app.pkg]
          .filter(function (value) {
            return !!value;
          })
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

        var selected = Object.keys(selectedApps).filter(function (pkg) {
          return pkg !== "*" && selectedApps[pkg];
        });

        return selected.join(",");
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

      function areAllAppsSelected(selectedApps) {
        var apps = $scope.applications || [];
        if (apps.length === 0) {
          return false;
        }
        return apps.every(function (app) {
          return !!selectedApps[app.pkg];
        });
      }

      function applyWildcardSelection(selectedApps) {
        if (!selectedApps || !selectedApps["*"]) {
          return;
        }
        ($scope.applications || []).forEach(function (app) {
          selectedApps[app.pkg] = true;
        });
      }

      function syncAllAppsFlags() {
        // Applications are loaded asynchronously. Until the list is available we cannot know whether
        // "all" apps are selected, so do nothing here — otherwise an explicit wildcard ("*") selection
        // parsed from a saved policy would be wrongly downgraded to false before the apps arrive,
        // and the "All" state would be lost when re-opening the editor.
        if (!$scope.applications || $scope.applications.length === 0) {
          return;
        }
        $scope.selectedAppsDuringWork["*"] = areAllAppsSelected(
          $scope.selectedAppsDuringWork,
        );
        $scope.selectedAppsOutsideWork["*"] = areAllAppsSelected(
          $scope.selectedAppsOutsideWork,
        );
        // sync 24h derived state
        var apps = $scope.applications || [];
        apps.forEach(function (app) {
          $scope.app24h[app.pkg] =
            !!$scope.selectedAppsDuringWork[app.pkg] &&
            !!$scope.selectedAppsOutsideWork[app.pkg];
        });
        $scope.app24h['*'] =
          apps.length > 0 &&
          apps.every(function (app) {
            return $scope.app24h[app.pkg];
          });
      }

      function normalizePolicy(device, policy) {
        var merged = angular.extend({}, DEFAULT_POLICY, policy || {});
        merged.deviceId = device.deviceId;
        merged.deviceName =
          device.deviceName ||
          (policy && policy.deviceName) ||
          "Device " + device.deviceId;
        return merged;
      }

      function parseLocalDate(value) {
        if (!value) {
          return null;
        }
        var stringValue = String(value);
        if (/^\d{4}-\d{2}-\d{2}$/.test(stringValue)) {
          var parts = stringValue.split("-");
          return new Date(
            parseInt(parts[0], 10),
            parseInt(parts[1], 10) - 1,
            parseInt(parts[2], 10),
            0,
            0,
            0,
            0,
          );
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
            parsed.setHours(
              parseInt(parts[0], 10) || 0,
              parseInt(parts[1], 10) || 0,
              0,
              0,
            );
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
        return [
          date.getFullYear(),
          ("0" + (date.getMonth() + 1)).slice(-2),
          ("0" + date.getDate()).slice(-2),
        ].join("-");
      }

      function toTimePart(timeValue) {
        if (!timeValue) {
          return null;
        }
        if (angular.isDate(timeValue) && !isNaN(timeValue.getTime())) {
          return (
            ("0" + timeValue.getHours()).slice(-2) +
            ":" +
            ("0" + timeValue.getMinutes()).slice(-2)
          );
        }
        if (typeof timeValue === "string") {
          var parts = timeValue.split(":");
          if (parts.length >= 2) {
            return (
              ("0" + (parseInt(parts[0], 10) || 0)).slice(-2) +
              ":" +
              ("0" + (parseInt(parts[1], 10) || 0)).slice(-2)
            );
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
        var tzOffset = new Date().getTimezoneOffset();
        var absOffset = Math.abs(tzOffset);
        var offsetHours = Math.floor(absOffset / 60);
        var offsetMinutes = absOffset % 60;
        var offsetSign = tzOffset > 0 ? "-" : "+";
        var offsetString = offsetSign + ("0" + offsetHours).slice(-2) + ":" + ("0" + offsetMinutes).slice(-2);
        return datePart + "T" + timePart + ":00.000" + offsetString;
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
          from.setHours(
            parseInt(fromParts[0], 10) || 0,
            parseInt(fromParts[1], 10) || 0,
            0,
            0,
          );
        }
        if (exception.timeTo) {
          var toParts = String(exception.timeTo).split(":");
          to.setHours(
            parseInt(toParts[0], 10) || 0,
            parseInt(toParts[1], 10) || 0,
            59,
            999,
          );
        }
        return { from: from, to: to };
      }

      function createDefaultHolidayDraft() {
        var defaultStart = new Date();
        defaultStart.setMinutes(defaultStart.getMinutes() + 1, 0, 0);
        var defaultEnd = new Date(defaultStart.getTime() + 60 * 60 * 1000);

        return {
          dateFrom: defaultStart,
          dateTo: defaultEnd,
          timeFrom:
            ("0" + defaultStart.getHours()).slice(-2) +
            ":" +
            ("0" + defaultStart.getMinutes()).slice(-2),
          timeTo:
            ("0" + defaultEnd.getHours()).slice(-2) +
            ":" +
            ("0" + defaultEnd.getMinutes()).slice(-2),
        };
      }

      function tryParseDate(value) {
        if (value == null) return null;
        var d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
      }

      function decorateException(exception) {
        // Prefer raw epoch-ms / ISO timestamps (from server or payload) for accurate
        // UTC-based active/expiry checks.  The server sends dateFrom/timeFrom in
        // WORKTIME_ZONE which may differ from the browser's local timezone, so
        // rebuilding dates from those strings alone would shift times by the zone
        // offset and cause active exceptions to disappear.
        var startDate = tryParseDate(exception.startDateTime);
        var endDate   = tryParseDate(exception.endDateTime);

        if (startDate && endDate) {
          var now = new Date();
          // Overwrite display fields using browser-local time so the UI shows what
          // the admin actually typed, regardless of server timezone.
          exception.dateFrom = startDate;
          exception.dateTo   = endDate;
          exception.timeFrom = ("0" + startDate.getHours()).slice(-2) + ":" +
                               ("0" + startDate.getMinutes()).slice(-2);
          exception.timeTo   = ("0" + endDate.getHours()).slice(-2) + ":" +
                               ("0" + endDate.getMinutes()).slice(-2);
          exception.active   = now >= startDate && now <= endDate;
          exception.upcoming = now < startDate;
          return exception;
        }

        // Fallback: reconstruct from dateFrom/dateTo/timeFrom/timeTo strings
        var range = getExceptionRange(exception);
        if (!range) {
          return exception;
        }
        var now = new Date();
        exception.dateFrom = range.from;
        exception.dateTo = range.to;
        exception.active = now >= range.from && now <= range.to;
        exception.upcoming = now < range.from;
        return exception;
      }

      function sortExceptions(left, right) {
        if (!!left.active !== !!right.active) {
          return left.active ? -1 : 1;
        }

        var leftRange = getExceptionRange(left);
        var rightRange = getExceptionRange(right);

        if (!leftRange && !rightRange) {
          return 0;
        }
        if (!leftRange) {
          return 1;
        }
        if (!rightRange) {
          return -1;
        }

        var leftStart = leftRange.from.getTime();
        var rightStart = rightRange.from.getTime();
        if (leftStart !== rightStart) {
          return leftStart - rightStart;
        }

        return leftRange.to.getTime() - rightRange.to.getTime();
      }

      function normalizeExceptions(device) {
        var exceptions = angular.isArray(device.exceptions)
          ? angular.copy(device.exceptions)
          : [];
        var now = new Date();

        if (
          exceptions.length === 0 &&
          device.startDateTime &&
          device.endDateTime
        ) {
          var start = new Date(device.startDateTime);
          var end = new Date(device.endDateTime);
          if (!isNaN(start.getTime()) && !isNaN(end.getTime()) && now <= end) {
            exceptions.push({
              id: device.id,
              dateFrom: start,
              dateTo: end,
              timeFrom:
                ("0" + start.getHours()).slice(-2) +
                ":" +
                ("0" + start.getMinutes()).slice(-2),
              timeTo:
                ("0" + end.getHours()).slice(-2) +
                ":" +
                ("0" + end.getMinutes()).slice(-2),
            });
          }
        }

        return exceptions
          .map(function (exception) {
            return decorateException(exception);
          })
          .filter(function (exception) {
            // After decorateException, exception.dateTo is a UTC-accurate Date when
            // startDateTime/endDateTime epoch-ms were available; use it directly.
            var effectiveEnd = tryParseDate(exception.endDateTime) || exception.dateTo;
            if (!effectiveEnd) {
              var range = getExceptionRange(exception);
              if (!range) return true;
              effectiveEnd = range.to;
            }
            return now <= effectiveEnd;
          })
          .sort(sortExceptions);
      }

      function pickFeaturedException(exceptions) {
        if (!angular.isArray(exceptions) || exceptions.length === 0) {
          return null;
        }

        for (var i = 0; i < exceptions.length; i++) {
          if (exceptions[i] && exceptions[i].active) {
            return exceptions[i];
          }
        }

        return exceptions[0];
      }

      function applyExceptionState(device) {
        device.exceptions = normalizeExceptions(device);
        device.hasActiveException = device.exceptions.some(function (exception) {
          return !!exception.active;
        });
        device.displayException = pickFeaturedException(device.exceptions);
        return device;
      }

      function buildExceptionEntryFromPayload(payload, responseData) {
        var start = parseLocalDate(payload.startDateTime);
        var end = parseLocalDate(payload.endDateTime);
        var entry = {
          id: responseData && responseData.id ? responseData.id : payload.id,
          dateFrom: start,
          dateTo: end,
          timeFrom: null,
          timeTo: null,
          startDateTime: payload.startDateTime,
          endDateTime: payload.endDateTime,
        };

        if (start && !isNaN(start.getTime())) {
          entry.timeFrom =
            ("0" + start.getHours()).slice(-2) +
            ":" +
            ("0" + start.getMinutes()).slice(-2);
        }
        if (end && !isNaN(end.getTime())) {
          entry.timeTo =
            ("0" + end.getHours()).slice(-2) +
            ":" +
            ("0" + end.getMinutes()).slice(-2);
        }

        return decorateException(entry);
      }

      function buildDeviceRows(overrides, policies) {
        var policyMap = {};
        (policies || []).forEach(function (policy) {
          policyMap[policy.deviceId] = policy;
        });

        return (overrides || [])
          .map(function (device) {
            var row = angular.copy(device);
            row.deviceName = row.deviceName || "Device " + row.deviceId;
            row.policy = normalizePolicy(row, policyMap[row.deviceId]);
            applyExceptionState(row);
            return row;
          })
          .sort(function (left, right) {
            var leftName = (left.deviceName || "").toLowerCase();
            var rightName = (right.deviceName || "").toLowerCase();
            return leftName.localeCompare(rightName);
          });
      }

      function handleLoadError(error, fallbackMessage) {
        console.error(fallbackMessage, error);
        $scope.error =
          (error && error.data && error.data.message) || fallbackMessage;
        $scope.loading = false;
        $scope.refreshing = false;
      }

      $scope.formatDays = function (daysOfWeek) {
        var value = angular.isNumber(daysOfWeek)
          ? daysOfWeek
          : DEFAULT_POLICY.daysOfWeek;
        var labels = $scope.days
          .filter(function (day) {
            return (value & day.id) === day.id;
          })
          .map(function (day) {
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
          return (
            (device.deviceName || "").toLowerCase().indexOf(search) !== -1 ||
            String(device.deviceId || "").indexOf(search) !== -1
          );
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
        if (!$scope.editingPolicy) { return; }
        if ($scope.editingPolicy.daysOfWeek == null) { $scope.editingPolicy.daysOfWeek = 0; }
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
          ($scope.applications || []).forEach(function (app) {
            $scope.selectedAppsDuringWork[app.pkg] = true;
          });
        } else {
          $scope.selectedAppsDuringWork = {};
        }
        syncAllAppsFlags();
      };

      $scope.toggleAllAppsOutsideWork = function () {
        if ($scope.selectedAppsOutsideWork["*"]) {
          ($scope.applications || []).forEach(function (app) {
            $scope.selectedAppsOutsideWork[app.pkg] = true;
          });
        } else {
          $scope.selectedAppsOutsideWork = {};
        }
        syncAllAppsFlags();
      };

      $scope.toggleIndividualAppDuringWork = function () {
        syncAllAppsFlags();
      };

      $scope.toggleIndividualAppOutsideWork = function () {
        syncAllAppsFlags();
      };

      // 24 Hrs column — checks/unchecks both during + after for one app
      $scope.toggle24h = function (pkg) {
        var checked = !!$scope.app24h[pkg];
        $scope.selectedAppsDuringWork[pkg] = checked;
        $scope.selectedAppsOutsideWork[pkg] = checked;
        syncAllAppsFlags();
      };

      // 24 Hrs header — checks/unchecks both columns for all apps
      $scope.toggleAll24h = function () {
        var checked = !!$scope.app24h['*'];
        ($scope.applications || []).forEach(function (app) {
          $scope.selectedAppsDuringWork[app.pkg] = checked;
          $scope.selectedAppsOutsideWork[app.pkg] = checked;
          $scope.app24h[app.pkg] = checked;
        });
        if (checked) {
          $scope.selectedAppsDuringWork["*"] = areAllAppsSelected($scope.selectedAppsDuringWork);
          $scope.selectedAppsOutsideWork["*"] = areAllAppsSelected($scope.selectedAppsOutsideWork);
        } else {
          $scope.selectedAppsDuringWork = {};
          $scope.selectedAppsOutsideWork = {};
          $scope.app24h = {};
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
                $scope.devices = buildDeviceRows(
                  deviceResponse,
                  policyResponse,
                );
                $scope.lastRefreshAt = new Date();
                $scope.loading = false;
                $scope.refreshing = false;
              },
              function (error) {
                handleLoadError(error, "Failed to load device policies");
              },
            );
          },
          function (error) {
            handleLoadError(error, "Failed to load devices");
          },
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
            var leftName = (
              left.name ||
              left.applicationName ||
              left.pkg
            ).toLowerCase();
            var rightName = (
              right.name ||
              right.applicationName ||
              right.pkg
            ).toLowerCase();
            return leftName.localeCompare(rightName);
          });
      }

      $scope.loadApplications = function () {
        $scope.appsLoading = true;

        var assignApps = function (apps) {
          $scope.applications = apps || [];
          $scope.app24h = $scope.app24h || {};
          applyWildcardSelection($scope.selectedAppsDuringWork || {});
          applyWildcardSelection($scope.selectedAppsOutsideWork || {});
          if ($scope.selectedAppsDuringWork && $scope.selectedAppsOutsideWork) {
            syncAllAppsFlags();
          }
          $scope.appsLoading = false;
        };

        var loadFromConfigurationEndpoint = function () {
          WorkTimeApplications.getAllFromConfigurations(
            {},
            function (configurationResponse) {
              assignApps(normalizeApplicationsResponse(configurationResponse));
            },
            function (configurationError) {
              console.error(
                "Failed to load applications via configuration endpoint",
                configurationError,
              );
              assignApps([]);
            },
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
            },
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
              console.error(
                "Failed to load applications via admin search",
                adminError,
              );
              loadFromStandardSearch();
            },
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
            $scope.app24h = $scope.app24h || {};
            applyWildcardSelection($scope.selectedAppsDuringWork || {});
            applyWildcardSelection($scope.selectedAppsOutsideWork || {});
            if ($scope.selectedAppsDuringWork && $scope.selectedAppsOutsideWork) {
              syncAllAppsFlags();
            }
            $scope.appsLoading = false;
          },
          function (error) {
            console.error("Failed to load device applications", error);
            $scope.applications = [];
            $scope.appsLoading = false;
          },
        );
      };

      $scope.refreshPolicyApplications = function () {
        if (!$scope.editingDevice || !$scope.editingDevice.deviceId || $scope.appsLoading) {
          return;
        }
        $scope.loadApplicationsForDevice($scope.editingDevice.deviceId);
      };

      $scope.openPolicyModal = function (device) {
        if (!$scope.canEdit) {
          return;
        }

        $scope.loadApplicationsForDevice(device.deviceId);

        $scope.error = null;
        $scope.editingDevice = device;
        $scope.editingPolicy = angular.copy(device.policy);
        var startParts = parsePolicyTimeParts(
          $scope.editingPolicy.startTime,
          9,
          0,
        );
        var endParts = parsePolicyTimeParts(
          $scope.editingPolicy.endTime,
          17,
          0,
        );
        $scope.policyTime = {
          startHour: startParts.hour,
          startMinute: startParts.minute,
          endHour: endParts.hour,
          endMinute: endParts.minute,
        };
        $scope.selectedAppsDuringWork = parseAppsString(
          $scope.editingPolicy.allowedAppsDuringWork,
        );
        $scope.selectedAppsOutsideWork = parseAppsString(
          $scope.editingPolicy.allowedAppsOutsideWork,
        );
        $scope.app24h = {};
        $scope.policyAppsSearchText = "";
        syncAllAppsFlags();
        $scope.exceptionSaving = false;
        $scope.editingException = createDefaultHolidayDraft();
        $scope.exceptionTime = {
          fromHour: parsePolicyTimeParts($scope.editingException.timeFrom, 9, 0).hour,
          fromMinute: parsePolicyTimeParts($scope.editingException.timeFrom, 9, 0).minute,
          toHour: parsePolicyTimeParts($scope.editingException.timeTo, 10, 0).hour,
          toMinute: parsePolicyTimeParts($scope.editingException.timeTo, 10, 0).minute,
        };

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
          $scope.policyTime = null;
          $scope.editingException = null;
          $scope.exceptionTime = null;
          $scope.exceptionSaving = false;
        });
      };

      $scope.savePolicy = function () {
        if (!$scope.editingPolicy || $scope.policySaving) {
          return;
        }

        $scope.policySaving = true;
        $scope.error = null;

        var payload = angular.copy($scope.editingPolicy);
        payload.startTime = composePolicyTime(
          $scope.policyTime && $scope.policyTime.startHour,
          $scope.policyTime && $scope.policyTime.startMinute,
        );
        payload.endTime = composePolicyTime(
          $scope.policyTime && $scope.policyTime.endHour,
          $scope.policyTime && $scope.policyTime.endMinute,
        );
        payload.allowedAppsDuringWork = buildAppsString(
          $scope.selectedAppsDuringWork,
        );
        payload.allowedAppsOutsideWork = buildAppsString(
          $scope.selectedAppsOutsideWork,
        );

        WorkTimePolicy.save(
          payload,
          function (response) {
            $scope.policySaving = false;
            if (response && response.status === "OK") {
              if (modalInstance) {
                modalInstance.close();
              }
              showSuccess(
                "Policy updated for " +
                  ($scope.editingDevice.deviceName ||
                    "Device " + $scope.editingDevice.deviceId),
              );
              $scope.refresh(true);
            } else {
              $scope.error =
                (response && response.message) || "Failed to save policy";
            }
          },
          function (error) {
            $scope.policySaving = false;
            $scope.error =
              (error && error.data && error.data.message) ||
              localization.localize("error.request.failure");
          },
        );
      };

      $scope.closePolicyModal = function () {
        if (modalInstance) {
          modalInstance.close();
        }
      };

      $scope.saveException = function () {
        if (
          !$scope.editingDevice ||
          !$scope.editingException ||
          $scope.exceptionSaving
        ) {
          return;
        }

        $scope.exceptionSaving = true;
        $scope.error = null;

        var fromTime = composePolicyTime(
          $scope.exceptionTime && $scope.exceptionTime.fromHour,
          $scope.exceptionTime && $scope.exceptionTime.fromMinute,
        );
        var toTime = composePolicyTime(
          $scope.exceptionTime && $scope.exceptionTime.toHour,
          $scope.exceptionTime && $scope.exceptionTime.toMinute,
        );
        var startDateTime = toApiDateTimeString(
          $scope.editingException.dateFrom,
          fromTime,
        );
        var endDateTime = toApiDateTimeString(
          $scope.editingException.dateTo,
          toTime,
        );

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
              var savedEntry = buildExceptionEntryFromPayload(
                payload,
                response.data,
              );
              if (!$scope.editingDevice.exceptions) {
                $scope.editingDevice.exceptions = [];
              }
              $scope.editingDevice.exceptions.push(savedEntry);
              applyExceptionState($scope.editingDevice);
              $scope.editingException = createDefaultHolidayDraft();
              $scope.exceptionTime = {
                fromHour: parsePolicyTimeParts(
                  $scope.editingException.timeFrom,
                  9,
                  0,
                ).hour,
                fromMinute: parsePolicyTimeParts(
                  $scope.editingException.timeFrom,
                  9,
                  0,
                ).minute,
                toHour: parsePolicyTimeParts(
                  $scope.editingException.timeTo,
                  10,
                  0,
                ).hour,
                toMinute: parsePolicyTimeParts(
                  $scope.editingException.timeTo,
                  10,
                  0,
                ).minute,
              };
              showSuccess(
                "Holiday added for " +
                  ($scope.editingDevice.deviceName ||
                    "Device " + $scope.editingDevice.deviceId),
              );
            } else {
              $scope.error =
                (response && response.message) || "Failed to save holiday";
            }
          },
          function (error) {
            $scope.exceptionSaving = false;
            $scope.error =
              (error && error.data && error.data.message) ||
              localization.localize("error.request.failure");
          },
        );
      };

      $scope.deleteException = function (device, exception) {
        if (!$scope.canEdit || !exception || !exception.id) {
          return;
        }

        if (!confirm("Delete this holiday?")) {
          return;
        }

        WorkTimeDevice.removeException(
          { exceptionId: exception.id },
          function () {
            if (device && angular.isArray(device.exceptions)) {
              device.exceptions = device.exceptions.filter(function (item) {
                return item.id !== exception.id;
              });
              applyExceptionState(device);
            }
            showSuccess(
              "Holiday removed for " +
                (device.deviceName || "Device " + device.deviceId),
            );
          },
          function (error) {
            $scope.error =
              (error && error.data && error.data.message) ||
              localization.localize("error.request.failure");
          },
        );
      };

      $scope.getDeviceHolidayBadgeLabel = function (device) {
        if (!device) {
          return "No Holidays";
        }
        if (device.hasActiveException) {
          return "Active Holiday";
        }
        if (device.displayException) {
          return "Upcoming Holiday";
        }
        return "No Holidays";
      };

      $scope.getDeviceHolidayBadgeClass = function (device) {
        if (!device) {
          return "state-ok";
        }
        if (device.hasActiveException) {
          return "state-alert";
        }
        return device.displayException ? "state-alert" : "state-ok";
      };

      // ================================================================
      // General (organization-wide) holidays
      // ================================================================

      $scope.holidayDate = function (value) {
        return parseLocalDate(value);
      };

      $scope.activeHoliday = function () {
        return $scope.activeHolidays && $scope.activeHolidays.length
          ? $scope.activeHolidays[0]
          : null;
      };

      $scope.loadActiveHolidays = function () {
        WorkTimeHoliday.active(
          function (list) {
            $scope.activeHolidays = angular.isArray(list) ? list : [];
          },
          function () {
            // Non-fatal: keep the page usable even if the active holiday lookup fails.
          },
        );
      };

      $scope.loadHolidays = function () {
        $scope.holidaysLoading = true;
        WorkTimeHoliday.list(
          function (list) {
            $scope.holidays = angular.isArray(list) ? list : [];
            $scope.holidaysLoading = false;
          },
          function (error) {
            $scope.holidaysLoading = false;
            $scope.error =
              (error && error.data && error.data.message) ||
              "Failed to load holidays";
          },
        );
      };

      function createHolidayDraft() {
        var today = new Date();
        today.setHours(0, 0, 0, 0);
        return { id: null, name: "", dateFrom: today, dateTo: today };
      }

      function validateHolidayForm(form) {
        if (!form) {
          return "Holiday details are required";
        }
        var name = (form.name || "").trim();
        if (!name) {
          return "Holiday name is required";
        }
        var fromStr = toDatePart(form.dateFrom);
        var toStr = toDatePart(form.dateTo);
        if (!fromStr) {
          return "From date is required";
        }
        if (!toStr) {
          return "To date is required";
        }
        // yyyy-MM-dd strings compare correctly lexicographically.
        var todayStr = toDatePart(new Date());
        if (fromStr < todayStr) {
          return "From date cannot be before today";
        }
        if (toStr < todayStr) {
          return "To date cannot be before today";
        }
        if (toStr < fromStr) {
          return "To date cannot be earlier than From date";
        }
        return null;
      }

      function openHolidayFormModal(form) {
        $scope.holidayForm = form;
        $scope.holidayError = null;
        $scope.holidaySaving = false;
        $scope.csvError = null;
        $scope.csvResult = null;
        $scope.csvFileName = null;
        $scope.csvImporting = false;
        $scope.todayStr = toDatePart(new Date());

        holidayModalInstance = $uibModal.open({
          templateUrl: "worktimeAddHolidayModal.html",
          scope: $scope,
          windowClass: "worktime-policy-modal",
          backdrop: "static",
          keyboard: true,
        });

        holidayModalInstance.result.finally(function () {
          holidayModalInstance = null;
          $scope.holidayForm = null;
          $scope.holidayError = null;
          $scope.csvError = null;
          $scope.csvResult = null;
        });
      }

      $scope.openAddHolidayModal = function () {
        if (!$scope.canEdit) {
          return;
        }
        openHolidayFormModal(createHolidayDraft());
      };

      $scope.closeHolidayModal = function () {
        if (holidayModalInstance) {
          holidayModalInstance.close();
        }
      };

      $scope.isEditingHoliday = function () {
        return !!($scope.holidayForm && $scope.holidayForm.id);
      };

      $scope.saveHoliday = function () {
        if ($scope.holidaySaving) {
          return;
        }
        var err = validateHolidayForm($scope.holidayForm);
        if (err) {
          $scope.holidayError = err;
          return;
        }
        $scope.holidayError = null;
        $scope.holidaySaving = true;

        var payload = {
          id: $scope.holidayForm.id || null,
          name: ($scope.holidayForm.name || "").trim(),
          startDate: toDatePart($scope.holidayForm.dateFrom),
          endDate: toDatePart($scope.holidayForm.dateTo),
        };

        WorkTimeHoliday.save(
          payload,
          function (response) {
            $scope.holidaySaving = false;
            if (response && response.status === "OK") {
              if (holidayModalInstance) {
                holidayModalInstance.close();
              }
              showSuccess(payload.id ? "Holiday updated" : "Holiday added");
              $scope.loadHolidays();
              $scope.loadActiveHolidays();
            } else {
              $scope.holidayError =
                (response && response.message) || "Failed to save holiday";
            }
          },
          function (error) {
            $scope.holidaySaving = false;
            $scope.holidayError =
              (error && error.data && error.data.message) ||
              "Failed to save holiday";
          },
        );
      };

      $scope.downloadSampleCsv = function () {
        var sample =
          "name,from,to\n" +
          "Diwali,03/11/2026,05/11/2026\n" +
          "Christmas,25/12/2026,25/12/2026\n";
        try {
          var blob = new Blob([sample], { type: "text/csv;charset=utf-8" });
          var url = URL.createObjectURL(blob);
          var link = document.createElement("a");
          link.href = url;
          link.download = "holidays-sample.csv";
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          URL.revokeObjectURL(url);
        } catch (e) {
          console.error("Failed to generate sample CSV", e);
        }
      };

      $scope.handleCsvFile = function (content, filename) {
        $scope.csvError = null;
        $scope.csvResult = null;
        $scope.csvFileName = filename || null;

        if (content == null) {
          $scope.csvError = "Could not read the file '" + (filename || "") + "'";
          return;
        }
        if (!String(content).trim()) {
          $scope.csvError = "The CSV file is empty";
          return;
        }

        // Client-side header validation (the server validates again).
        var lines = String(content)
          .replace(/\r\n/g, "\n")
          .replace(/\r/g, "\n")
          .split("\n")
          .filter(function (line) {
            return line.trim() !== "";
          });
        var firstLine = lines.length ? lines[0] : "";
        var headers = firstLine.split(",").map(function (header) {
          return header
            .replace(/^\uFEFF/, "")
            .trim()
            .replace(/^"|"$/g, "")
            .toLowerCase();
        });
        if (
          headers.length !== 3 ||
          headers[0] !== "name" ||
          headers[1] !== "from" ||
          headers[2] !== "to"
        ) {
          $scope.csvError = "CSV headers must be exactly: name, from, to";
          return;
        }

        $scope.csvImporting = true;
        WorkTimeHoliday.importCsv(
          { content: content },
          function (response) {
            $scope.csvImporting = false;
            if (response && response.status === "OK" && response.data) {
              $scope.csvResult = response.data;
              if (response.data.imported > 0) {
                showSuccess(response.data.imported + " holiday(s) imported");
                $scope.loadHolidays();
                $scope.loadActiveHolidays();
              } else if (
                !response.data.failed ||
                !response.data.failed.length
              ) {
                $scope.csvError = "No holiday rows found in the CSV";
              }
            } else {
              $scope.csvError =
                (response && response.message) || "Failed to import CSV";
            }
          },
          function (error) {
            $scope.csvImporting = false;
            $scope.csvError =
              (error && error.data && error.data.message) ||
              "Failed to import CSV";
          },
        );
      };

      $scope.openViewHolidaysModal = function () {
        $scope.loadHolidays();
        viewHolidaysModalInstance = $uibModal.open({
          templateUrl: "worktimeViewHolidaysModal.html",
          scope: $scope,
          windowClass: "worktime-policy-modal",
          backdrop: "static",
          keyboard: true,
        });
        viewHolidaysModalInstance.result.finally(function () {
          viewHolidaysModalInstance = null;
        });
      };

      $scope.closeViewHolidaysModal = function () {
        if (viewHolidaysModalInstance) {
          viewHolidaysModalInstance.close();
        }
      };

      $scope.editHoliday = function (holiday) {
        if (!$scope.canEdit || !holiday || !holiday.editable) {
          return;
        }
        if (viewHolidaysModalInstance) {
          viewHolidaysModalInstance.close();
        }
        openHolidayFormModal({
          id: holiday.id,
          name: holiday.name,
          dateFrom: parseLocalDate(holiday.startDate),
          dateTo: parseLocalDate(holiday.endDate),
        });
      };

      $scope.deleteHoliday = function (holiday) {
        if (!$scope.canEdit || !holiday || !holiday.id) {
          return;
        }
        if (!confirm("Delete holiday '" + holiday.name + "'?")) {
          return;
        }
        WorkTimeHoliday.remove(
          { id: holiday.id },
          function () {
            showSuccess("Holiday deleted");
            $scope.loadHolidays();
            $scope.loadActiveHolidays();
          },
          function (error) {
            $scope.error =
              (error && error.data && error.data.message) ||
              "Failed to delete holiday";
          },
        );
      };

      $scope.holidayStatusLabel = function (holiday) {
        if (!holiday) {
          return "";
        }
        if (holiday.status === "active") {
          return "Active";
        }
        if (holiday.status === "upcoming") {
          return "Upcoming";
        }
        if (holiday.status === "expired") {
          return "Expired";
        }
        return holiday.status || "";
      };

      $scope.holidayStatusClass = function (holiday) {
        if (holiday && holiday.status === "active") {
          return "state-alert";
        }
        return "state-ok";
      };

      refreshPromise = $interval(function () {
        if (modalInstance || holidayModalInstance || viewHolidaysModalInstance) {
          return;
        } // don't clobber modal scope during auto-refresh
        $scope.refresh(true);
        $scope.loadActiveHolidays();
      }, 15000);

      $scope.$on("$destroy", function () {
        if (refreshPromise) {
          $interval.cancel(refreshPromise);
        }
      });

      $scope.refresh();
      $scope.loadActiveHolidays();
    },
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
