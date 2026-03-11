# Call Log Plugin - API Testing Guide

## Quick Test Commands

### 1. Check Plugin Status

```bash
# Check if call log is enabled for a device
curl -X GET "http://localhost:8080/rest/plugins/calllog/public/enabled/YOUR_DEVICE_NUMBER"
```

### 2. Submit Test Call Logs (Android Device)

```bash
curl -X POST "http://localhost:8080/rest/plugins/calllog/public/submit/YOUR_DEVICE_NUMBER" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "phoneNumber": "+1234567890",
      "contactName": "John Doe",
      "callType": 1,
      "duration": 120,
      "callTimestamp": 1707476400000,
      "callDate": "2024-02-09 10:00:00"
    },
    {
      "phoneNumber": "+0987654321",
      "contactName": "Jane Smith",
      "callType": 2,
      "duration": 300,
      "callTimestamp": 1707480000000,
      "callDate": "2024-02-09 11:00:00"
    },
    {
      "phoneNumber": "+1122334455",
      "contactName": null,
      "callType": 3,
      "duration": 0,
      "callTimestamp": 1707483600000,
      "callDate": "2024-02-09 12:00:00"
    }
  ]'
```

### 3. Get Call Logs (Admin - Requires Authentication)

```bash
# Login first to get JWT token
curl -X POST "http://localhost:8080/rest/public/login" \
  -H "Content-Type: application/json" \
  -d '{
    "login": "admin",
    "password": "admin"
  }'

# Use the token from response
TOKEN="YOUR_JWT_TOKEN_HERE"

# Get call logs for device
curl -X GET "http://localhost:8080/rest/plugins/calllog/private/device/1?page=0&pageSize=50" \
  -H "Authorization: Bearer $TOKEN"
```

### 4. Get Plugin Settings

```bash
curl -X GET "http://localhost:8080/rest/plugins/calllog/private/settings" \
  -H "Authorization: Bearer $TOKEN"
```

### 5. Update Plugin Settings (Admin Only)

```bash
curl -X POST "http://localhost:8080/rest/plugins/calllog/private/settings" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "retentionDays": 90
  }'
```

### 6. Delete Call Logs for a Device

```bash
curl -X DELETE "http://localhost:8080/rest/plugins/calllog/private/device/1" \
  -H "Authorization: Bearer $TOKEN"
```

## Call Type Reference

- `1` = Incoming call
- `2` = Outgoing call
- `3` = Missed call
- `4` = Rejected call
- `5` = Blocked call

## Testing Workflow

### Step 1: Verify Plugin is Active
Access the web panel at http://localhost:8080 and:
1. Login as admin
2. Go to Plugins section
3. Verify "Call Log" plugin is listed and enabled

### Step 2: Test from UI
1. Go to Devices page
2. Find a device in the list
3. Click the three-dot menu (⋮) next to the device
4. Click "View Call Logs"
5. Modal should open (may be empty if no logs submitted yet)

### Step 3: Submit Test Data
Use the curl command above to submit test call logs for your device number.

### Step 4: Verify Data Appears
1. Refresh the call logs modal
2. You should see the test data
3. Try pagination if you submitted many logs
4. Test the "Delete All" button

## Database Direct Access (For Debugging)

```sql
-- Check if plugin is registered
SELECT * FROM plugins WHERE identifier = 'calllog';

-- Check call log data
SELECT * FROM plugin_calllog_data ORDER BY callTimestamp DESC LIMIT 10;

-- Check settings
SELECT * FROM plugin_calllog_settings;

-- Count logs per device
SELECT deviceId, COUNT(*) as log_count 
FROM plugin_calllog_data 
GROUP BY deviceId;

-- Get recent logs
SELECT 
  d.deviceNumber,
  c.phoneNumber,
  c.contactName,
  c.callType,
  c.duration,
  to_timestamp(c.callTimestamp / 1000) as call_time
FROM plugin_calllog_data c
JOIN devices d ON c.deviceId = d.id
ORDER BY c.callTimestamp DESC
LIMIT 20;
```

## Common Issues & Solutions

### Issue: "Plugin not found"
**Solution:** 
- Check if plugin is built: `ls /home/mohammed/hmdm-server/plugins/calllog/core/target/`
- Check if WAR contains plugin: `jar -tf server/target/launcher.war | grep calllog`
- Check Tomcat logs: `tail -f /opt/tomcat9/logs/catalina.out`

### Issue: "Permission denied"
**Solution:**
- Ensure user has `plugin_calllog_access` permission
- Check if JWT token is valid
- Verify user belongs to correct customer

### Issue: "Device not found"
**Solution:**
- Use correct device number (not device ID)
- Ensure device is enrolled in the system
- Check: `SELECT * FROM devices WHERE deviceNumber = 'YOUR_DEVICE_NUMBER';`

### Issue: "Empty call logs"
**Solution:**
- Verify data was submitted: `SELECT COUNT(*) FROM plugin_calllog_data;`
- Check customer ID matches: Device and call logs must have same customer ID
- Check if plugin is enabled: `SELECT * FROM plugin_calllog_settings;`

## Postman Collection

Import this JSON into Postman for quick testing:

```json
{
  "info": {
    "name": "Call Log Plugin API",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Login",
      "request": {
        "method": "POST",
        "header": [{"key": "Content-Type", "value": "application/json"}],
        "body": {
          "mode": "raw",
          "raw": "{\"login\":\"admin\",\"password\":\"admin\"}"
        },
        "url": "http://localhost:8080/rest/public/login"
      }
    },
    {
      "name": "Submit Call Logs",
      "request": {
        "method": "POST",
        "header": [{"key": "Content-Type", "value": "application/json"}],
        "body": {
          "mode": "raw",
          "raw": "[{\"phoneNumber\":\"+1234567890\",\"contactName\":\"Test User\",\"callType\":1,\"duration\":120,\"callTimestamp\":1707476400000,\"callDate\":\"2024-02-09 10:00:00\"}]"
        },
        "url": "http://localhost:8080/rest/plugins/calllog/public/submit/{{deviceNumber}}"
      }
    },
    {
      "name": "Get Call Logs",
      "request": {
        "method": "GET",
        "header": [{"key": "Authorization", "value": "Bearer {{token}}"}],
        "url": "http://localhost:8080/rest/plugins/calllog/private/device/{{deviceId}}"
      }
    }
  ]
}
```

## Performance Testing

### Load Test: Submit 1000 Call Logs

```bash
#!/bin/bash
# Generate and submit 1000 call logs

DEVICE_NUMBER="test-device-001"
BASE_TIME=1707476400000

for i in {1..1000}; do
  TIMESTAMP=$((BASE_TIME + i * 60000))
  
  curl -s -X POST "http://localhost:8080/rest/plugins/calllog/public/submit/$DEVICE_NUMBER" \
    -H "Content-Type: application/json" \
    -d "[{
      \"phoneNumber\":\"+123456$i\",
      \"contactName\":\"Contact $i\",
      \"callType\":$((i % 3 + 1)),
      \"duration\":$((i % 600)),
      \"callTimestamp\":$TIMESTAMP,
      \"callDate\":\"2024-02-09 10:$((i % 60)):00\"
    }]"
  
  if [ $((i % 100)) -eq 0 ]; then
    echo "Submitted $i logs..."
  fi
done

echo "Load test complete!"
```

## Monitoring

### Check Tomcat Logs
```bash
# Real-time monitoring
tail -f /opt/tomcat9/logs/catalina.out | grep -i calllog

# Check for errors
grep -i "error.*calllog" /opt/tomcat9/logs/catalina.out
```

### Check Database Growth
```sql
-- Monitor table size
SELECT 
  pg_size_pretty(pg_total_relation_size('plugin_calllog_data')) as total_size,
  COUNT(*) as record_count
FROM plugin_calllog_data;
```
