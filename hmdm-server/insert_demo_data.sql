DO $$
DECLARE
    v_device_id INT;
    v_customer_id INT;
    v_param_id INT;
    v_ts BIGINT;
BEGIN
    -- Get the first device
    SELECT id, customerid INTO v_device_id, v_customer_id FROM devices LIMIT 1;
    
    IF v_device_id IS NOT NULL THEN
        RAISE NOTICE 'Inserting demo GPS data for device ID %', v_device_id;
        
        -- Insert Point 1 (Current)
        v_ts := (extract(epoch from now()) * 1000)::bigint;
        INSERT INTO plugin_deviceinfo_deviceParams (deviceId, customerId, ts) 
        VALUES (v_device_id, v_customer_id, v_ts) RETURNING id INTO v_param_id;
        
        INSERT INTO plugin_deviceinfo_deviceParams_gps (recordId, state, lat, lon, alt, speed, course)
        VALUES (v_param_id, 'on', 40.7128, -74.0060, 10.0, 5.0, 90.0);
        
        -- Insert Point 2 (-10 mins)
        v_ts := (extract(epoch from now() - interval '10 minutes') * 1000)::bigint;
        INSERT INTO plugin_deviceinfo_deviceParams (deviceId, customerId, ts) 
        VALUES (v_device_id, v_customer_id, v_ts) RETURNING id INTO v_param_id;
        
        INSERT INTO plugin_deviceinfo_deviceParams_gps (recordId, state, lat, lon, alt, speed, course)
        VALUES (v_param_id, 'on', 40.7138, -74.0070, 10.0, 5.0, 95.0);

        -- Insert Point 3 (-20 mins)
        v_ts := (extract(epoch from now() - interval '20 minutes') * 1000)::bigint;
        INSERT INTO plugin_deviceinfo_deviceParams (deviceId, customerId, ts) 
        VALUES (v_device_id, v_customer_id, v_ts) RETURNING id INTO v_param_id;
        
        INSERT INTO plugin_deviceinfo_deviceParams_gps (recordId, state, lat, lon, alt, speed, course)
        VALUES (v_param_id, 'on', 40.7148, -74.0080, 10.0, 5.0, 100.0);
        
    ELSE
        RAISE NOTICE 'No devices found to attach demo data to.';
    END IF;
END $$;
