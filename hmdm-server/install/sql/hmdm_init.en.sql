UPDATE userroles SET name='Super-Admin', description='Can sign in as any user. In shared mode, manages corporate accounts' WHERE id=1;
UPDATE userroles SET name='Admin', description='Full access to the control panel' WHERE id=2;
UPDATE userroles SET name='User', description='Limited access to the control panel' WHERE id=3;
UPDATE userroles SET name='Observer', description='Read-only access to the control panel' WHERE id=100;

UPDATE users SET email='_ADMIN_EMAIL_', passwordReset=true, passwordResetToken=md5(random()::text) WHERE id=1;

UPDATE groups SET name='General' WHERE id=1;

UPDATE permissions SET description='Super-administrator functions for the whole system' WHERE id=1;
UPDATE permissions SET description='Access to system settings' WHERE id=2;
UPDATE permissions SET description='Access to configurations, applications and files' WHERE id=3;
UPDATE permissions SET description='Access to devices' WHERE id=4;
UPDATE permissions SET description='Access to image removal (image plugin)' WHERE id=100;

UPDATE plugins SET name='Images', description='Retrieve images from devices' WHERE id=1;

INSERT INTO settings (id, backgroundcolor, textcolor, backgroundimageurl, iconsize, desktopheader, customerid, usedefaultlanguage, language) VALUES (1, '#678ca6', '#ffffff', NULL, 'SMALL', 'NO_HEADER', 1, true, NULL);

INSERT INTO userrolesettings (id, roleid, customerid, columndisplayeddevicestatus, columndisplayeddevicedate, columndisplayeddevicenumber, columndisplayeddevicemodel, columndisplayeddevicepermissionsstatus, columndisplayeddeviceappinstallstatus, columndisplayeddeviceconfiguration, columndisplayeddeviceimei, columndisplayeddevicephone, columndisplayeddevicedesc, columndisplayeddevicegroup, columndisplayedlauncherversion) VALUES 
(1, 1, 1, true, true, true, NULL, true, true, true, NULL, NULL, NULL, NULL, NULL),
(2, 2, 1, true, true, true, NULL, true, true, true, NULL, NULL, NULL, NULL, NULL),
(3, 3, 1, true, true, true, NULL, true, true, true, NULL, NULL, NULL, NULL, NULL),
(4, 100, 1, true, true, true, NULL, true, true, true, NULL, NULL, NULL, NULL, NULL);

SELECT pg_catalog.setval('public.settings_id_seq', 1, true);

ALTER TABLE applications DROP CONSTRAINT applications_latestversion_fkey;

INSERT INTO applications (id, pkg, name, showicon, customerid, system, latestversion, runafterinstall) VALUES 
    (46, 'com.brother.pharmach.mdm.launcher', 'Brother Pharmamach MDM', false, 1, false, 10045, false),
    (48, 'com.hmdm.pager', 'Brother Pharmamach MDM Pager Plugin', true, 1, false, 10047, false),
    (49, 'com.hmdm.phoneproxy', 'Dialer Helper', true, 1, false, 10048, false),
    (50, 'com.hmdm.emuilauncherrestarter', 'Brother Pharmamach MDM update helper', false, 1, false, 10049, false);

SELECT pg_catalog.setval('public.applications_id_seq', 50, true);

INSERT INTO applicationversions (id, applicationid, version, url) VALUES 
    (10045, 46, '_HMDM_VERSION_', '_HMDM_APK_URL_'),
    (10047, 48, '1.02', 'https://brothers-mdm.com/files/pager-1.02.apk'),
    (10048, 49, '1.02', 'https://brothers-mdm.com/files/phoneproxy-1.02.apk'),
    (10049, 50, '1.04', 'https://brothers-mdm.com/files/LauncherRestarter-1.04.apk');
    
SELECT pg_catalog.setval('public.applicationversions_id_seq', 10049, true);

ALTER TABLE applications ADD CONSTRAINT applications_latestversion_fkey FOREIGN KEY (latestversion) REFERENCES applicationversions(id) ON DELETE SET NULL;
    
DELETE FROM configurations;
INSERT INTO configurations (id, name, description, type, password, backgroundcolor, textcolor, backgroundimageurl, iconsize, desktopheader, usedefaultdesignsettings, customerid, gps, bluetooth, wifi, mobiledata, mainappid, eventreceivingcomponent, kioskmode, qrcodekey, contentappid,autoupdate, blockstatusbar, systemupdatetype, systemupdatefrom, systemupdateto, pushoptions, keepalivetime, rundefaultlauncher, permissive, kioskexit) VALUES 
(1, 'Managed Launcher', 'Displays a set of application icons predefined by the administrator. To show or hide applications, use the Applications tab.', 0, '12345678', '', '', NULL, 'SMALL', 'NO_HEADER', true, 1, NULL, NULL, NULL, NULL, 10045, 'com.brother.pharmach.mdm.launcher.AdminReceiver', false, '6fb9c8dc81483173a0c0e9f8b2e46be1', NULL, false, false, 0, NULL, NULL, 'mqttAlarm', 300, NULL, NULL, true);

SELECT pg_catalog.setval('public.configurations_id_seq', 1, true);

INSERT INTO configurationapplications (id, configurationid, applicationid, remove, showicon, applicationversionid) VALUES 
    (1, 1, 46, false, false, 10045),
    (2, 1, 48, false, true, 10047),
    (3, 1, 49, false, true, 10048),
    (4, 1, 50, false, false, 10049);
    
SELECT pg_catalog.setval('public.configurationapplications_id_seq', 4, true);

SELECT pg_catalog.setval('public.devices_id_seq', 1, false);

INSERT INTO plugin_devicelog_settings_rules (id, settingid, name, active, applicationid, severity) VALUES (1, 1, 'Brother Pharmamach MDM', true, 46, 'VERBOSE');
SELECT pg_catalog.setval('public.plugin_devicelog_settings_rules_id_seq', 1, true);


-- Register Work Time Plugin
-- NOTE: Work Time plugin is provisioned by Liquibase. Do not insert here.

-- Register Call Log Plugin
-- NOTE: Call Log plugin registration is handled by plugin migrations.

-- Call Log plugin tables
CREATE TABLE IF NOT EXISTS plugin_calllog_data (
    id SERIAL PRIMARY KEY,
    deviceid INT NOT NULL,
    phonenumber VARCHAR(50),
    contactname VARCHAR(255),
    calltype INT NOT NULL,
    duration BIGINT NOT NULL DEFAULT 0,
    calltimestamp BIGINT NOT NULL,
    calldate VARCHAR(50),
    createtime BIGINT,
    customerid INT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_calllog_device ON plugin_calllog_data(deviceid, customerid);
CREATE INDEX IF NOT EXISTS idx_calllog_timestamp ON plugin_calllog_data(calltimestamp);
CREATE INDEX IF NOT EXISTS idx_calllog_customer ON plugin_calllog_data(customerid);

CREATE TABLE IF NOT EXISTS plugin_calllog_settings (
    id SERIAL PRIMARY KEY,
    customerid INT UNIQUE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    retentiondays INT NOT NULL DEFAULT 90
);
