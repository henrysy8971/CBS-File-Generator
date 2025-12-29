# FileGenerationScheduler Guide

## Overview

`FileGenerationScheduler` is a Quartz-based scheduled job component that enables **periodic, unattended file generation** from pending batch jobs stored in the database. It runs on a configurable schedule (via cron expressions) to automatically process file generation requests.

---

## Purpose

Instead of triggering file generation through REST API calls, you can schedule jobs to run automatically at specific times:

- ✅ Daily batch processing (e.g., 2 AM nightly)
- ✅ Hourly incremental updates
- ✅ Custom schedules via cron expressions
- ✅ Database-persistent scheduling (survives application restarts)

---

## Architecture

### Component Location

**Package**: `com.silverlakesymmetri.cbs.fileGenerator.scheduler`

**Class**: `FileGenerationScheduler extends QuartzJobBean`

### Dependencies

```java
@Autowired
private AppConfigService appConfigService;      // Reads job config from database

@Autowired
private FileGenerationService fileGenerationService; // Executes pending jobs
```

### Key Annotation

```java
@DisallowConcurrentExecution
```

Prevents multiple instances from running simultaneously (important for database-backed job store in clustered environments).

---

## How It Works

### Execution Flow

1. **Trigger fires** - Quartz scheduler fires at scheduled time
2. **Read config** - `executeInternal()` reads APP_CONFIG table entries:
   - `JOB_TYPE` - Type of file generation job
   - `BATCH_SIZE` - Records per batch (default: 1000)
3. **Fetch pending jobs** - Retrieves all `PENDING` records from FILE_GENERATION table
4. **Process each job** - Iterates through pending jobs and triggers processing
5. **Log results** - Logs completion or errors

### Code Flow

```java
protected void executeInternal(JobExecutionContext context) {
    // 1. Get config from database
    String jobType = appConfigService.getConfigValue("JOB_TYPE", "DEFAULT");
    String batchSize = appConfigService.getConfigValue("BATCH_SIZE", "1000");

    // 2. Get pending jobs
    fileGenerationService.getPendingFileGenerations().forEach(fileGen -> {
        // 3. Process each job
        logger.info("Processing file generation: {}", fileGen.getJobId());
    });
}
```

---

## Configuration

### 1. Quartz Configuration in QuartzConfig.java

Define the job and trigger:

```java
@Bean
public JobDetail fileGenerationJobDetail() {
    return JobBuilder.newJob(FileGenerationScheduler.class)
        .withIdentity("fileGenerationJob", "file-generation-group")
        .storeDurably()
        .build();
}

@Bean
public Trigger fileGenerationTrigger() {
    return TriggerBuilder.newTrigger()
        .forJob(fileGenerationJobDetail())
        .withIdentity("fileGenerationTrigger", "file-generation-group")
        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?")) // 2 AM daily
        .build();
}
```

### 2. Cron Expression Examples

```
"0 0 2 * * ?"           → 2:00 AM every day
"0 0 0 * * ?"           → Midnight every day
"0 0 * * * ?"           → Every hour
"0 */30 * * * ?"        → Every 30 minutes
"0 0 2 ? * MON"         → 2 AM every Monday
"0 0 2 1 * ?"           → 2 AM on 1st of month
```

### 3. Database Configuration

Store job settings in APP_CONFIG table:

```sql
INSERT INTO APP_CONFIG (CONFIG_KEY, CONFIG_VALUE, CONFIG_TYPE, DESCRIPTION, ACTIVE, CREATED_DATE)
VALUES ('JOB_TYPE', 'ORDER_INTERFACE', 'SCHEDULER', 'Type of job to process', 1, SYSDATE);

INSERT INTO APP_CONFIG (CONFIG_KEY, CONFIG_VALUE, CONFIG_TYPE, DESCRIPTION, ACTIVE, CREATED_DATE)
VALUES ('BATCH_SIZE', '1000', 'SCHEDULER', 'Records per batch', 1, SYSDATE);
```

### 4. Application Properties

Configure Quartz in `application.properties`:

```properties
# Quartz - JDBC Job Store (persistent)
spring.quartz.job-store-type=jdbc
spring.quartz.jdbc.initialize-schema=never
spring.quartz.properties.org.quartz.scheduler.instanceName=FileGeneratorScheduler
spring.quartz.properties.org.quartz.scheduler.instanceId=AUTO
spring.quartz.properties.org.quartz.threadPool.threadCount=10
spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.StdJdbcDelegate
spring.quartz.properties.org.quartz.jobStore.useProperties=true
spring.quartz.properties.org.quartz.jobStore.isClustered=false
```

---

## Usage Patterns

### Pattern 1: Automatic Scheduling (Recommended)

**Setup once in QuartzConfig**, then forget:

```java
@Configuration
public class QuartzConfig {

    @Bean
    public Trigger dailyFileGenerationTrigger() {
        return TriggerBuilder.newTrigger()
            .forJob(fileGenerationJobDetail())
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?"))
            .build();
    }
}
```

**Result**: Job runs automatically at 2 AM every day. No REST calls, no manual intervention.

---

### Pattern 2: REST API + Scheduler

**Combine both approaches**:

1. **REST API** - For on-demand, immediate processing
   ```bash
   POST /api/v1/file-generation/generate
   ```

2. **Scheduler** - For periodic background processing
   - Runs on schedule (e.g., nightly)
   - Catches any jobs not processed during the day

---

### Pattern 3: Programmatic Scheduling

**Schedule a job programmatically** (less common):

```java
@Service
public class SchedulerManager {

    @Autowired
    private Scheduler scheduler;

    public void scheduleJob(String jobName, String cronExpression)
            throws SchedulerException {

        JobDetail jobDetail = JobBuilder.newJob(FileGenerationScheduler.class)
            .withIdentity(jobName, "file-generation-group")
            .build();

        CronTrigger trigger = TriggerBuilder.newTrigger()
            .withIdentity(jobName + "-trigger", "file-generation-group")
            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
            .build();

        scheduler.scheduleJob(jobDetail, trigger);
        logger.info("Job scheduled: {} with cron: {}", jobName, cronExpression);
    }
}
```

Usage:
```java
schedulerManager.scheduleJob("nightly-processing", "0 0 2 * * ?");
```

---

## Data Flow

### Database Tables Involved

#### 1. APP_CONFIG (Input)
Scheduler reads job configuration:
```
CONFIG_KEY          | CONFIG_VALUE  | CONFIG_TYPE
JOB_TYPE            | ORDER_INTF    | SCHEDULER
BATCH_SIZE          | 1000          | SCHEDULER
```

#### 2. FILE_GENERATION (Input/Output)
Scheduler reads pending jobs and updates status:
```
JOB_ID              | STATUS        | INTERFACE_TYPE
JOB-20250112-001    | PENDING       | ORDER_INTERFACE
JOB-20250112-002    | PENDING       | ORDER_INTERFACE

After processing:
JOB-20250112-001    | PROCESSING    | ORDER_INTERFACE
JOB-20250112-001    | COMPLETED     | ORDER_INTERFACE
```

#### 3. QUARTZ_TRIGGERS, QUARTZ_JOBS (Quartz internal)
Persists job and trigger information:
```
TRIGGER_NAME        | JOB_NAME              | CRON_EXPRESSION
fileGenerationTrig  | fileGenerationJob     | 0 0 2 * * ?
```

---

## Error Handling

### Scenario: Job Fails

```java
catch (Exception e) {
    logger.error("Error in file generation scheduled job: {}", e.getMessage(), e);
    throw new JobExecutionException(e);
}
```

**Behavior**:
- Exception is logged
- Quartz marks job as failed
- Retries based on Quartz configuration (if configured)
- Application continues running

### Best Practice: Monitor Logs

```
[INFO] File generation scheduled job started at: 2025-01-12 02:00:00
[INFO] Executing job - type: ORDER_INTERFACE, batchSize: 1000
[INFO] Processing file generation: JOB-20250112-001
[INFO] File generation scheduled job completed successfully
```

---

## Testing

### Manual Test: Trigger Job Immediately

Use REST API instead of waiting for schedule:

```bash
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-token" \
  -d '{"interfaceType": "ORDER_INTERFACE"}'
```

### Verify Scheduled Execution

Check Quartz tables:

```sql
-- List all scheduled jobs
SELECT TRIGGER_NAME, JOB_NAME, CRON_EXPRESSION
FROM QUARTZ_CRON_TRIGGERS;

-- Check trigger fire times
SELECT TRIGGER_NAME, NEXT_FIRE_TIME
FROM QUARTZ_TRIGGERS
WHERE JOB_NAME = 'fileGenerationJob';

-- Check job execution history (in logs)
tail -f logs/cbs-file-generator.log | grep "File generation scheduled"
```

---

## Best Practices

### 1. Use Cron Expressions, Not Code

❌ **Bad**: Hardcode schedules in code
```java
// Difficult to change without recompilation
```

✅ **Good**: Use cron expressions in QuartzConfig
```java
.withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 * * ?"))
```

### 2. Avoid Peak Hours

❌ Schedule during business hours (slow queries, heavy load)

✅ Schedule during off-peak hours (2-4 AM)

### 3. Monitor Execution

Add logging at key points:
```java
logger.info("Job started at: {}", new Date());
logger.info("Processing {} pending jobs", pendingCount);
logger.info("Job completed in {} ms", duration);
```

### 4. Use @DisallowConcurrentExecution

Prevents multiple instances from running simultaneously in clustered environments.

### 5. Keep Configuration in Database

Allows changing schedules without redeploying application.

---

## Troubleshooting

| Issue                        | Cause                         | Solution                              |
|------------------------------|-------------------------------|---------------------------------------|
| Job not running              | Trigger not registered        | Verify QuartzConfig bean is loaded    |
| Job runs twice               | Clustering enabled improperly | Set `isClustered=false` in properties |
| No pending jobs processed    | FILE_GENERATION table empty   | Create jobs via REST API first        |
| Config values not read       | APP_CONFIG table empty        | Insert configuration rows             |
| OutOfMemory during execution | Batch size too large          | Reduce BATCH_SIZE in APP_CONFIG       |

---

## Comparison: REST API vs Scheduler

| Feature         | REST API                  | Scheduler                |
|-----------------|---------------------------|--------------------------|
| **Trigger**     | On-demand via HTTP        | Time-based (cron)        |
| **Use case**    | Manual, ad-hoc processing | Periodic background jobs |
| **Response**    | Immediate job status      | Job runs asynchronously  |
| **Persistence** | Not needed                | Quartz tables required   |
| **Clustering**  | Works as-is               | Requires JDBC job store  |

---

## See Also

- **QuartzConfig.java** - Scheduler configuration
- **FileGenerationService** - Job execution logic
- **DEPLOYMENT.md** - Application startup and monitoring
- [Quartz Documentation](http://www.quartz-scheduler.org/documentation/)
