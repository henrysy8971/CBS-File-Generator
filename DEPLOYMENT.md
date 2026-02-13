# CBS File Generator - Deployment Guide

## 📋 Prerequisites

Before deploying the application, ensure the target environment meets these requirements:

*   **Operating System**: Linux (RHEL/CentOS recommended) or Windows Server.
*   **Java Runtime**: **JDK 8** (Required for Spring Boot 1.5 compatibility).
*   **Database**: Oracle 12c or 19c.
*   **Disk Space**: Sufficient storage for generated files (`/u1/symmetri/products/cbs-file-generator/data/generated-files`) and logs.
*   **Network**: Access to the Oracle DB port (1521) and allow inbound traffic on the App port (8080).

---

## 🛠️ Build Instructions

You can build the application in two modes: **Standalone JAR** (Embedded Tomcat) or **WAR** (External Tomcat).

### Option A: Standalone JAR (Recommended for Microservices/Containers)
Contains the server inside the artifact. Easiest to run.

```bash
mvn clean package -DskipTests
# Artifact created at: target/cbs-file-generator-1.0.0.jar
```

### Option B: WAR File (Legacy Enterprise)
Use this if deploying to an existing standalone Apache Tomcat 8.5/9.0 server.

```bash
mvn clean package -Pwar -DskipTests
# Artifact created at: target/cbs-file-generator-1.0.0.war
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
Insert a token for your client application, so it can access the API immediately:
```sql
INSERT INTO IF_DB_TOKEN ( TOKEN_VALUE, APPLICATION_NAME, ISSUED_BY, ISSUED_DATE)
VALUES ('a1b2c3d4-e5f6-4789-abcd-ef1234567890', 'CBS_PORTAL_WEB', 'ADMIN_USER', SYSTIMESTAMP);
COMMIT;
```

---

## 📂 Production Directory Layout

We recommend creating a structured directory on the server (e.g., `/u1/symmetri/products/cbs-file-generator/`) to separate the binary, config, and data.

```text
/u1/symmetri/products/cbs-file-generator/
├── bin/
│   └── cbs-file-generator-1.0.0.jar    (The application binary)
├── config/
│   ├── application.properties          (Environment overrides)
│   ├── interface-config.json           (Interface definitions)
│   └── logback-spring.xml              (Optional: Custom logging)
│       ├── beanio                      (beanIO mapping files)
│       ├── templates                   (HTML Templates files)
│       │   └── dashboard.html          Landing page
│       └── xsd                         (XSD Validation files)
├── logs/                               (App logs go here)
└── data/
    ├── generated-files/                (Output files appear here)
    └── temp/                           (Temporary processing)
```

---

## ⚙️ Configuration (Externalizing Properties)

Do not rely on the `application.properties` inside the JAR. Create a production override file at `/u1/symmetri/products/cbs-file-generator/config/application.properties`.

**Minimal Production Configuration:**

```properties
# Server Port
server.port=8080

# Database Connection
spring.datasource.url=jdbc:oracle:thin:@PROD_DB_HOST:1521:PROD_SID
spring.datasource.username=CBS_APP_USER
spring.datasource.password=ComplexPassword123

# File Paths (Ensure OS User has Write Permissions!)
base.dir=/u1/symmetri/products/cbs-file-generator
file.generation.external.config-dir=${base.dir}/config
file.generation.output-directory=${base.dir}/data/generated-files
file.generation.interface-config-path=${file.generation.external.config-dir}/interface-config.json
LOG_PATH=${LOG_PATH:${base.dir}/logs}

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
java -jar /u1/symmetri/products/cbs-file-generator/bin/cbs-file-generator.jar \
  --spring.config.location=file:/u1/symmetri/products/cbs-file-generator/config/application.properties
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
                 value="file:/u1/symmetri/products/cbs-file-generator/config/application.properties" 
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

1.  Edit `/u1/symmetri/products/cbs-file-generator/config/interface-config.json`.
2.  Call the reload endpoint:
    ```bash
    curl -X POST http://localhost:8080/cbs-file-generator/api/v1/admin/reload-config \
      -H "X-DB-Token: <your-token>"
    ```

### Cleaning Stale Files
The application has a built-in Quartz job (`MaintenanceQuartzJob`) that runs every **Sunday at Midnight**.
*   It deletes files older than `file.generation.max-file-age-in-days` (Default: 30).
*   It cleans up old Batch metadata from the database.

---

### Troubleshooting Common Errors

| Error                                      | Cause                                    | Solution                                                                                      |
|:-------------------------------------------|:-----------------------------------------|:----------------------------------------------------------------------------------------------|
| `ORA-01000: maximum open cursors exceeded` | Chunk size too small or connection leak. | Increase `file.generation.chunk-size` or check DB `open_cursors` setting.                     |
| `AccessDeniedException` (File I/O)         | Linux permissions.                       | Ensure the user running Java owns the output directory: `chown -R cbsuser:cbsgroup /opt/cbs/` |
| `OutOfMemoryError: Java heap space`        | Large XML generation.                    | Increase `-Xmx` or reduce `chunkSize` for XML interfaces.                                     |
| `JobInstanceAlreadyCompleteException`      | Re-running a job with same params.       | The system handles this via `RunIdIncrementer`. If manual, ensure `time` param is unique.     |

---

To run your application in **Debug Mode** and specify a **Custom Port**, you need to add specific arguments to your command line.

Here is the breakdown:

1.  **Debug Flags** (`-agentlib...`): Must go **BEFORE** `-jar`.
2.  **Application Arguments** (`--server.port...`): Must go **AFTER** `-jar`.

### The Complete Command

Here is the full command to run on port **9090** with the Debugger listening on port **5005**:

```bash
java \
  -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
  -jar /u1/symmetri/products/cbs-file-generator/bin/cbs-file-generator.jar \
  --spring.config.location=file:/u1/symmetri/products/cbs-file-generator/config/application.properties \
  --server.port=9090
```

---

### Detailed Explanation

#### 1. Debug Mode Settings
The flag `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005` enables remote debugging.

*   **`transport=dt_socket`**: Uses a standard network socket.
*   **`server=y`**: The application acts as the server waiting for the debugger to connect.
*   **`address=5005`**: The port where your IDE (IntelliJ/Eclipse) will connect. You can change this if 5005 is busy.
*   **`suspend=n`** (Recommended): The application starts immediately without waiting.
*   **`suspend=y`** (For Startup Issues): The application **pauses** at startup and waits for you to connect the debugger. Use this if you need to debug `InterfaceConfigLoader` or `@PostConstruct` logic.

#### 2. Setting the HTTP Port
You can override the port defined in `application.properties` by adding this argument at the end:

*   **`--server.port=9090`**: Sets the web server (Tomcat) to listen on port 9090.

---

### How to Connect (IntelliJ IDEA)

1.  Open your project in IntelliJ.
2.  Go to **Run** -> **Edit Configurations**.
3.  Click **+** and select **Remote JVM Debug**.
4.  Set **Host**: `localhost` (or the IP of the server `/u1/...` if remote).
5.  Set **Port**: `5005`.
6.  Start the application on the server using the command above.
7.  Click **Debug** in IntelliJ.