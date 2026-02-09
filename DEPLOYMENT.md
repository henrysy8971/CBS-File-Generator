# CBS File Generator - Deployment Guide

## 📋 Prerequisites

Before deploying the application, ensure the target environment meets these requirements:

*   **Operating System**: Linux (RHEL/CentOS recommended) or Windows Server.
*   **Java Runtime**: **JDK 8** (Required for Spring Boot 1.5 compatibility).
*   **Database**: Oracle 12c or 19c.
*   **Disk Space**: Sufficient storage for generated files (`/opt/cbs/generated-files/`) and logs.
*   **Network**: Access to the Oracle DB port (1521) and allow inbound traffic on the App port (8080).

---

## 🛠️ Build Instructions

You can build the application in two modes: **Standalone JAR** (Embedded Tomcat) or **WAR** (External Tomcat).

### Option A: Standalone JAR (Recommended for Microservices/Containers)
Contains the server inside the artifact. Easiest to run.

```bash
mvn clean package -DskipTests
# Artifact created at: target/cbs-file-generator.jar
```

### Option B: WAR File (Legacy Enterprise)
Use this if deploying to an existing standalone Apache Tomcat 8.5/9.0 server.

```bash
mvn clean package -Pwar -DskipTests
# Artifact created at: target/cbs-file-generator.war
```

---

## 🗄️ Database Setup

The application does **not** auto-create tables in Production (`initialize-schema=never`). You must run the schema scripts manually.

1.  **Locate Script**: `src/main/resources/db/schema.sql`
2.  **Execute**: Run the script against your Oracle schema using SQLPlus or SQL Developer.
3.  **Verify**: Ensure the following tables exist:
    *   **App Tables**: `IF_FILE_GENERATION`, `IF_DB_TOKEN`
    *   **Spring Batch**: `CUT_BT_JOB_INSTANCE`, `CUT_BT_JOB_EXECUTION`, etc.
    *   **Quartz**: `QRTZ_JOB_DETAILS`, `QRTZ_TRIGGERS`, etc.

**Initial Security Setup**:
Insert a token for your client application so it can access the API immediately:
```sql
INSERT INTO IF_DB_TOKEN (TOKEN_ID, TOKEN_VALUE, APPLICATION_NAME, ACTIVE, ISSUED_DATE)
VALUES (IF_DB_TOKEN_SEQ.NEXTVAL, 'your-production-secret-token-uuid', 'ADMIN_CLIENT', 1, SYSTIMESTAMP);
COMMIT;
```

---

## 📂 Production Directory Layout

We recommend creating a structured directory on the server (e.g., `/opt/cbs-file-generator`) to separate the binary, config, and data.

```text
/opt/cbs-file-generator/
├── bin/
│   └── cbs-file-generator.jar    (The application binary)
├── config/
│   ├── application.properties    (Environment overrides)
│   ├── interface-config.json     (Interface definitions)
│   └── logback-spring.xml        (Optional: Custom logging)
├── logs/                         (App logs go here)
└── data/
    ├── generated/                (Output files appear here)
    └── temp/                     (Temporary processing)
```

---

## ⚙️ Configuration (Externalizing Properties)

Do not rely on the `application.properties` inside the JAR. Create a production override file at `/opt/cbs-file-generator/config/application.properties`.

**Minimal Production Configuration:**

```properties
# Server Port
server.port=8080

# Database Connection
spring.datasource.url=jdbc:oracle:thin:@PROD_DB_HOST:1521:PROD_SID
spring.datasource.username=CBS_APP_USER
spring.datasource.password=ComplexPassword123

# File Paths (Ensure OS User has Write Permissions!)
file.generation.output-directory=/opt/cbs-file-generator/data/generated/

# Threading (Adjust based on CPU)
spring.task.execution.pool.max-size=20
file.generation.chunk-size=5000

# Security (Strict Mode for Prod)
auth.token.enable-validation=true
validation.xsd.strict-mode=false

# Clustering (Set to true if running multiple instances)
spring.quartz.properties.org.quartz.jobStore.isClustered=true
```

---

## 🚀 Startup Commands

### Method 1: Running the JAR (SystemD / Shell)

Run the JAR pointing to the external configuration folder.

```bash
java -jar /opt/cbs-file-generator/bin/cbs-file-generator.jar \
  --spring.config.location=file:/opt/cbs-file-generator/config/application.properties
```

**Memory Tuning Example:**
```bash
java -Xms1024m -Xmx2048m -jar cbs-file-generator.jar ...
```

### Method 2: Deploying to External Tomcat

1.  Copy `target/cbs-file-generator.war` to `$TOMCAT_HOME/webapps/`.
2.  Create a configuration file at `$TOMCAT_HOME/conf/Catalina/localhost/cbs-file-generator.xml`:

```xml
<Context>
    <Environment name="spring.config.location" 
                 value="file:/opt/cbs-file-generator/config/application.properties" 
                 type="java.lang.String"/>
</Context>
```
3.  Start Tomcat: `bin/startup.sh`.

---

## ✅ Verification & Health Check

After startup, verify the application is healthy.

**1. Check Health Endpoint**
```bash
curl -X GET http://localhost:8080/cbs-file-generator/actuator/health
```
*Expected Response:* `{"status":"UP", "db": ...}`

**2. Verify Interface Config Loaded**
```bash
curl -X GET http://localhost:8080/cbs-file-generator/api/v1/file-generation/interfaces
```

**3. Test Log File Creation**
Check that `cbs-file-generator.log` is created in the logs directory and shows `Started FileGeneratorApplication in X.XXX seconds`.

---

## 🔄 Operations & Maintenance

### Hot Reloading Configuration
If you need to add a new interface or change a SQL query **without restarting the server**:

1.  Edit `/opt/cbs-file-generator/config/interface-config.json`.
2.  Call the reload endpoint:
    ```bash
    curl -X POST http://localhost:8080/cbs-file-generator/api/v1/admin/reload-config \
      -H "X-DB-Token: <your-token>"
    ```

### Cleaning Stale Files
The application has a built-in Quartz job (`MaintenanceQuartzJob`) that runs every **Sunday at Midnight**.
*   It deletes files older than `file.generation.max-file-age-in-days` (Default: 30).
*   It cleans up old Batch metadata from the database.

### Troubleshooting Common Errors

| Error                                      | Cause                                    | Solution                                                                                      |
|:-------------------------------------------|:-----------------------------------------|:----------------------------------------------------------------------------------------------|
| `ORA-01000: maximum open cursors exceeded` | Chunk size too small or connection leak. | Increase `file.generation.chunk-size` or check DB `open_cursors` setting.                     |
| `AccessDeniedException` (File I/O)         | Linux permissions.                       | Ensure the user running Java owns the output directory: `chown -R cbsuser:cbsgroup /opt/cbs/` |
| `OutOfMemoryError: Java heap space`        | Large XML generation.                    | Increase `-Xmx` or reduce `chunkSize` for XML interfaces.                                     |
| `JobInstanceAlreadyCompleteException`      | Re-running a job with same params.       | The system handles this via `RunIdIncrementer`. If manual, ensure `time` param is unique.     |