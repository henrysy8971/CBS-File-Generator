# Deployment Verification Guide

**Purpose**: Step-by-step verification checklist before and after production deployment

**Status**: Ready for Execution

---

## Pre-Deployment Verification (Run Locally)

### 1. Build Verification

```bash
# Clean build without tests
mvn clean package -DskipTests -Pjar

# Expected Output
# [INFO] BUILD SUCCESS
# [INFO] Total time: XX.XXXs
# [INFO] Final Memory: XXM/XXXXm
# Output: target/file-generator-1.0.0-exec.jar
```

### 2. Test Suite Execution

```bash
# Run all unit tests
mvn clean test

# Expected Result
# [INFO] Tests run: NN, Failures: 0, Errors: 0, Skipped: 0
# [INFO] BUILD SUCCESS
```

### 3. Code Quality Checks

```bash
# Build with compiler warnings as errors
mvn clean compile -Werror

# Expected: No warnings
# [INFO] BUILD SUCCESS
```

### 4. Integration Tests

```bash
# Run integration tests
mvn clean verify -Pit

# Expected: All tests pass
# [INFO] BUILD SUCCESS
```

---

## Build Verification Commands

```bash
# Build JAR (embedded Tomcat)
mvn clean package -Pjar -DskipTests

# Build WAR (external Tomcat)
mvn clean package -Pwar -DskipTests

# Verify output
ls -lh target/file-generator-*.jar
ls -lh target/file-generator.war

# Expected:
# -rw-r--r--  XX.X M file-generator-1.0.0-exec.jar
# -rw-r--r--  XX.X M file-generator.war
```

---

## Staging Environment Tests

### 1. Application Startup

```bash
# Start application
java -jar target/file-generator-1.0.0-exec.jar

# Expected Log Output (within 30 seconds)
# [INFO] Started FileGeneratorApplication in XX.XXX seconds
# [INFO] Output directory validated: /opt/cbs/generated-files
# [INFO] Quartz scheduler initialized
```

### 2. Health Check Endpoint

```bash
curl http://localhost:8080/cbs-file-generator/actuator/health

# Expected Response (HTTP 200)
{
  "status": "UP"
}
```

### 3. File Generation API

```bash
# Create file generation job
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-valid-token" \
  -H "Idempotency-Key: test-key-123" \
  -d '{
    "interfaceType": "ORDER_INTERFACE",
    "idempotencyKey": "test-key-123"
  }'

# Expected Response (HTTP 202 ACCEPTED)
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "fileName": "ORDER_INTERFACE_550e8400-e29b-41d4-a716-446655440000.xml",
  "interfaceType": "ORDER_INTERFACE",
  "recordCount": 0,
  "message": "File generation job queued successfully"
}
```

### 4. Status Polling

```bash
# Check job status
curl -X GET http://localhost:8080/cbs-file-generator/api/v1/file-generation/getFileGenerationStatus/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-DB-Token: your-valid-token"

# Expected Response (HTTP 200)
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PROCESSING",
  "recordCount": 250,
  "message": null
}

# Poll until status is COMPLETED
```

### 5. File Download

```bash
# Download completed file
curl -X GET http://localhost:8080/cbs-file-generator/api/v1/file-generation/downloadFileByJobId/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-DB-Token: your-valid-token" \
  -o downloaded_file.xml

# Expected Result
# File saved as downloaded_file.xml with correct content
```

### 6. Idempotency Verification

```bash
# Send same request twice with same idempotency key
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-valid-token" \
  -d '{
    "interfaceType": "ORDER_INTERFACE",
    "idempotencyKey": "idempotent-test-456"
  }'

# First request: returns 202 ACCEPTED with new jobId

# Second request (same idempotencyKey):
# Expected: 202 ACCEPTED with SAME jobId (no duplicate created)
```

### 7. Rate Limiting Test

```bash
# Send rapid requests (should exceed rate limit)
for i in {1..50}; do
  curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
    -H "Content-Type: application/json" \
    -H "X-DB-Token: your-valid-token" \
    -d '{"interfaceType": "ORDER_INTERFACE"}'
done

# Expected: After N requests, 403 FORBIDDEN
# Message: "Rate limit exceeded. Please try again in a few seconds."
```

### 8. Database Audit Trail Verification

```sql
-- Connect to Oracle database
sqlplus CBSDEV/password@CBSDEVPDB

-- Check FILE_GENERATION_AUDIT table
SELECT AUDIT_ID, JOB_ID, OLD_STATUS, NEW_STATUS, CHANGED_BY, CHANGED_DATE, REASON
FROM FILE_GENERATION_AUDIT
ORDER BY CHANGED_DATE DESC
FETCH FIRST 10 ROWS ONLY;

-- Expected: Rows showing PENDING -> PROCESSING -> COMPLETED transitions
```

### 9. Idempotency Key Verification

```sql
-- Check FILE_GENERATION table
SELECT JOB_ID, IDEMPOTENCY_KEY, STATUS, CREATED_DATE
FROM FILE_GENERATION
WHERE IDEMPOTENCY_KEY = 'test-key-123'
ORDER BY CREATED_DATE DESC;

-- Expected: Only ONE row (no duplicate)
```

### 10. Logs Verification

```bash
# Check application logs
tail -f logs/cbs-file-generator.log

# Look for key log entries
grep "File generation request received" logs/cbs-file-generator.log
grep "Job selection - Requested" logs/cbs-file-generator.log
grep "STATUS_CHANGE" logs/cbs-file-generator.log
grep "Rate limit exceeded" logs/cbs-file-generator.log

# Expected: All key lifecycle events logged
```

---

## Production Deployment Verification

### 1. Pre-Deployment Checklist

```bash
# Verify database schema
sqlplus CBSDEV/password@CBSDEVPDB <<EOF
DESC FILE_GENERATION;
DESC FILE_GENERATION_AUDIT;
DESC APP_CONFIG;
DESC DB_TOKEN;
EOF

# Verify output directory
ls -ld /opt/cbs/generated-files
touch /opt/cbs/generated-files/test.txt  # Verify writable
rm /opt/cbs/generated-files/test.txt

# Verify application.properties
grep "file.generation.output-directory" src/main/resources/application.properties
grep "spring.datasource" src/main/resources/application.properties
```

### 2. Deployment

```bash
# Deploy to Tomcat
cp target/file-generator-1.0.0-exec.jar /opt/cbs/app/
chmod 755 /opt/cbs/app/file-generator-1.0.0-exec.jar

# Start application
java -Xmx2g -Xms1g -jar /opt/cbs/app/file-generator-1.0.0-exec.jar &

# Verify startup (check logs for 30 seconds)
tail -f /opt/cbs/logs/cbs-file-generator.log
```

### 3. Post-Deployment Health Check

```bash
# 1. HTTP health check
curl -s http://localhost:8080/cbs-file-generator/actuator/health | grep -q '"status":"UP"'
echo "Health check: PASSED" || echo "Health check: FAILED"

# 2. Database connectivity
curl -s http://localhost:8080/cbs-file-generator/api/v1/file-generation/interfaces | grep -q "interfaces"
echo "Database connectivity: PASSED" || echo "Database connectivity: FAILED"

# 3. Interface availability
curl -s http://localhost:8080/cbs-file-generator/api/v1/file-generation/interfaces | jq '.interfaces'
# Expected: ["ORDER_INTERFACE", "CUSTOMER_INTERFACE", ...]

# 4. Token validation
curl -s -H "X-DB-Token: INVALID_TOKEN" http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate -X POST | grep -q "Unauthorized"
echo "Token validation: PASSED" || echo "Token validation: FAILED"
```

### 4. Load Testing

```bash
# Install Apache Bench
apt-get install apache2-utils

# Warm-up requests
ab -n 10 -c 1 http://localhost:8080/cbs-file-generator/actuator/health

# Load test (100 requests, 10 concurrent)
ab -n 100 -c 10 -H "X-DB-Token: your-token" \
  http://localhost:8080/cbs-file-generator/api/v1/file-generation/interfaces

# Expected Results
# Requests per second: > 100 req/s
# Failed requests: 0
# Time per request: < 100 ms
```

---

## Monitoring Post-Deployment

### 1. Application Metrics

```bash
# Monitor CPU usage
top -b -n 1 | grep java

# Monitor memory usage
ps aux | grep java | grep -v grep | awk '{print $6 " KB"}'

# Monitor open file descriptors
lsof -p $(pgrep -f file-generator) | wc -l

# Expected
# CPU: < 50% under normal load
# Memory: < 2GB
# Open files: < 1000
```

### 2. Database Metrics

```sql
-- Check connection pool status
SELECT COUNT(*) as active_connections
FROM V$SESSION
WHERE USERNAME = 'CBSDEV';

-- Expected: < 20 connections

-- Check table sizes
SELECT TABLE_NAME, SEGMENT_SIZE_MB
FROM (
  SELECT SEGMENT_NAME AS TABLE_NAME, 
         ROUND(SUM(BYTES)/1024/1024) AS SEGMENT_SIZE_MB
  FROM DBA_SEGMENTS
  WHERE OWNER = 'CBSDEV'
  GROUP BY SEGMENT_NAME
)
WHERE TABLE_NAME IN ('FILE_GENERATION', 'FILE_GENERATION_AUDIT')
ORDER BY SEGMENT_SIZE_MB DESC;

-- Expected: Audit table growing at ~100KB per 1000 jobs
```

### 3. Error Monitoring

```bash
# Monitor error rates
grep ERROR logs/cbs-file-generator.log | tail -20

# Monitor rate limit hits
grep "Rate limit exceeded" logs/cbs-file-generator.log | wc -l

# Expected: No ERROR logs; rate limit hits only during load tests

# Search for retry events
grep "Retrying" logs/cbs-file-generator.log | head -10

# Expected: Some retry events (transient failures); count should be < 1% of total requests
```

### 4. Business Metrics

```bash
-- Count completed jobs
SELECT COUNT(*) as total_jobs,
       SUM(CASE WHEN STATUS = 'COMPLETED' THEN 1 ELSE 0 END) as completed,
       SUM(CASE WHEN STATUS = 'FAILED' THEN 1 ELSE 0 END) as failed
FROM FILE_GENERATION;

-- Expected: Completed/Failed ratio > 99%

-- Average processing time
SELECT AVG((COMPLETED_DATE - CREATED_DATE) * 24 * 60) as avg_minutes
FROM FILE_GENERATION
WHERE STATUS = 'COMPLETED'
AND COMPLETED_DATE > SYSDATE - 1;

-- Expected: < 5 minutes average
```

---

## Rollback Procedures

### If Critical Issue Detected

```bash
# 1. Stop application
kill $(pgrep -f file-generator)

# 2. Verify stopped
sleep 5
pgrep -f file-generator || echo "Application stopped"

# 3. Rollback to previous version
cp /opt/cbs/app/file-generator-OLD.jar /opt/cbs/app/file-generator-1.0.0-exec.jar

# 4. Restart
java -Xmx2g -Xms1g -jar /opt/cbs/app/file-generator-1.0.0-exec.jar &

# 5. Verify
curl -s http://localhost:8080/cbs-file-generator/actuator/health
```

---

## Sign-Off Checklist

### Before Deployment

- [ ] All tests pass: `mvn clean verify`
- [ ] Code review approved
- [ ] Staging tests completed
- [ ] Performance baselines established
- [ ] Rollback plan documented
- [ ] Database backups verified
- [ ] Team notifications sent

### After Deployment

- [ ] Application started successfully
- [ ] Health check passed
- [ ] File generation test passed
- [ ] Idempotency verified
- [ ] Rate limiting verified
- [ ] Audit trail entries present
- [ ] Logs show no errors
- [ ] Load test completed
- [ ] Database metrics normal
- [ ] Stakeholders notified

### Sign-Off

```
Deployed By: _________________________
Deployment Date: _____________________
Verification Completed: _______________
Issues Encountered: ___________________
Approval: ____________________________
```

---

## Troubleshooting

### Application won't start

```bash
# Check for port conflict
netstat -tlnp | grep 8080

# Kill process on port
fuser -k 8080/tcp

# Check Java version
java -version
# Expected: openjdk 1.8 or Oracle JDK 8

# Check memory
free -m
# Expected: > 2GB available
```

### Database connection fails

```bash
# Test connection
sqlplus CBSDEV/password@CBSDEVPDB

# Check network
ping oracle-host
nslookup oracle-host

# Check firewall
telnet oracle-host 1521
```

### Rate limiting too aggressive

```bash
# Adjust in application.properties
# ratelimiter.requests-per-minute=100  (increase value)

# Redeploy
mvn clean package -Pjar
# Restart application
```

### Audit table growing too fast

```sql
-- Archive old entries
INSERT INTO FILE_GENERATION_AUDIT_ARCHIVE
SELECT * FROM FILE_GENERATION_AUDIT
WHERE CHANGED_DATE < TRUNC(SYSDATE) - 30;

-- Delete archived
DELETE FROM FILE_GENERATION_AUDIT
WHERE CHANGED_DATE < TRUNC(SYSDATE) - 30;

COMMIT;
```

---

## Support Contacts

- **Application Owner**: [Name/Team]
- **Database Owner**: [Name/Team]  
- **Infrastructure Owner**: [Name/Team]
- **On-Call Support**: [Phone/Slack]

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0-exec | 2025-02-12 | Production release with full refactoring |
| 0.9.0 | 2025-02-01 | Pre-refactoring version |

---

**Document Status**: Ready for Deployment  
**Last Updated**: 2025-02-12  
**Next Review**: After first production week
