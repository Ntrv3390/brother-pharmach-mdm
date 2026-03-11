-- Insert Record 1
WITH inserted_param AS (
    INSERT INTO plugin_deviceinfo_deviceParams (deviceId, customerId, ts) 
    VALUES (1, 1, (extract(epoch from now()) * 1000)::bigint) 
    RETURNING id
)
INSERT INTO plugin_deviceinfo_deviceParams_gps (recordId, state, lat, lon, alt, speed, course)
SELECT id, 'on', 40.7128, -74.0060, 10.0, 5.0, 90.0 FROM inserted_param;

-- Insert Record 2 (10 mins ago)
WITH inserted_param AS (
    INSERT INTO plugin_deviceinfo_deviceParams (deviceId, customerId, ts) 
    VALUES (1, 1, (extract(epoch from now() - interval '10 minutes') * 1000)::bigint) 
    RETURNING id
)
INSERT INTO plugin_deviceinfo_deviceParams_gps (recordId, state, lat, lon, alt, speed, course)
SELECT id, 'on', 40.7138, -74.0070, 10.0, 5.0, 95.0 FROM inserted_param;
