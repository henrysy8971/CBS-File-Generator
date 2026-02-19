# CBS File Generator - Spring Boot Application

## Overview
A Spring Boot microservice application for generating XML interface files from Oracle database sources with Quartz scheduling, Spring Batch processing, and token-based authentication.

## Architecture

### Project Structure

```
src/main/java/com/silverlakesymmetri/cbs/fileGenerator/
├── FileGeneratorApplication.java           <- Main entry point
├── batch/
│   ├── DynamicBatchConfig.java             <- Generic batch config
│   ├── DynamicItemProcessor.java           <- Generic processor
│   ├── DynamicItemReader.java              <- Generic reader
│   ├── DynamicItemWriter.java              <- Generic writer
│   ├── GenericBeanIOWriter.java            <- Generic BeanIO writer
│   ├── GenericXMLWriter.java               <- Generic XML writer
│   ├── OutputFormatWriter.java             <- Generic output format writer
│   ├── OutputFormatWriterFactory.java      <- Factory for selecting appropriate output format writer
│   ├── listeners/
│   │   ├── FileGenerationJobListener.java  <- Job listener for all file generation jobs
│   │   └── FileGenerationStepListener.java <- Step listener for all file generation jobs
│   └── order/
│       ├── OrderBatchConfig.java           <- Example: specialized batch config
│       ├── OrderItemProcessor.java         <- Example: specialized processor
│       ├── OrderItemReader.java            <- Example: specialized reader
│       ├── OrderItemWriter.java            <- Example: specialized writer
│       └── OrderRowMapper.java             <- Example: entity->DTO mapper
├── config/
│   ├── AsyncConfig.java                    <- ThreadPool for @Async tasks
│   ├── AutowiringSpringBeanJobFactory.java <- Injects Spring beans into Quartz
│   ├── BatchInfrastructureConfig.java      <- Core Batch engine config
│   ├── DatabaseConfig.java                 <- Database configuration
│   ├── FilterConfig.java                   <- Configures ???
│   ├── InterfaceConfigLoader.java          <- Interface configuration loader
│   ├── MaintenanceBatchConfig.java         <- Configures the cleanup job
│   ├── MdcTaskDecorator.java               <- 
│   ├── QuartzConfig.java                   <- Quartz scheduler configuration
│   ├── TomcatConfig.java                   <- Tomcat configuration
│   ├── WebSecurityConfig.java              <- Web Security configuration
│   └── model/
│       ├── InterfaceConfig.java            <- Interface Config model
│       └── InterfaceConfigWrapper.java     <- Wrapper for interface-config.json
├── constants/
│   ├── FileGenerationConstants.java        <- Global app constants
│   ├── FileGenerationStatus.java           <- File Generation Status Constants
│   └── FinalizationResult.java             <- File finalization results constants
├── controller/
│   ├── AdminController.java                <- Admin REST endpoints
│   ├── DashboardController.java            <- Landing page REST endpoint
│   └── FileGenerationController.java       <- File Generation REST endpoints
├── dto/
│   ├── ApiResponse.java                    <- Standard HTTP response wrapper
│   ├── ColumnType.java                     <- Enum for dynamic data types
│   ├── DynamicRecord.java                  <- Generic record holder for dynamic data
│   ├── FileGenerationRequest.java          <- API request
│   ├── FileGenerationResponse.java         <- API response
│   ├── PagedResponse.java                  <- Wrapper for paginated results
│   ├── RecordSchema.java                   <- Metadata for dynamic columns
│   └── order/
│       ├── LineItemDto.java                <- Example: nested DTO
│       ├── OrderDto.java                   <- Example: complex DTO
│       └── package-info.java               <- Example: complex DTO
├── entity/
│   ├── AppConfig.java                      <- Application config
│   ├── DbToken.java                        <- Auth tokens
│   ├── FileGeneration.java                 <- Tracks file generation job execution
│   ├── FileGenerationAudit.java            <- Tracks file generation status transition
│   └── order/
│       ├── LineItem.java                   <- Example: JPA entity
│       └── Order.java                      <- Example: JPA entity
├── exception/
│   ├── ConfigurationException.java         <- Invalid config error
│   ├── ConflictException.java              <- HTTP 409 Conflict error
│   ├── ForbiddenException.java             <- HTTP 403 Forbidden error
│   ├── GlobalExceptionHandler.java         <- Global exception handler
│   ├── GoneException.java                  <- HTTP 410 Gone error
│   ├── LifecycleException.java             <- Job state transition error
│   └── NotFoundException.java              <- HTTP 404 Not Found error
├── health/
│   ├── BatchQueueHealthIndicator.java      <- Monitors pending job queue
│   └── QuartzHealthIndicator.java          <- Monitors scheduler status
├── repository/
│   ├── AppConfigRepository.java            <- Config access
│   ├── DbTokenRepository.java              <- Token access
│   ├── FileGenerationAuditRepository.java  <- Job status change audit
│   ├── FileGenerationRepository.java       <- Job tracking
│   └── OrderRepository.java                <- Example: custom queries
├── retry/
│   └── DbRetryable.java                    <- common jpe retry annotation
├── scheduler/
│   ├── BatchJobLauncherJob.java            <- Quartz job triggering Batch
│   ├── FileGenerationScheduler.java        <- Job scheduling
│   └── MaintenanceScheduler.java           <- Quartz job triggering Cleanup
├── security/
│   ├── CorrelationIdFilter.java            <- Adds Request ID to MDC logs
│   ├── TokenAuthenticationFilter.java      <- Token authentication filter
│   └── TokenValidator.java                 <- Token validator
├── service/
│   ├── AppConfigService.java               <- App Config service
│   ├── BatchJobLauncher.java               <- Job routing
│   ├── FileFinalizationService.java        <- Finalizes file generation
│   ├── FileGenerationService.java          <- Business logic
│   └── RateLimiterService.java             <- User request rate limiting logic
├── tasklets/
│   ├── BatchCleanupTasklet.java            <- Deletes stale files & DB rows
│   └── FileValidationTasklet.java          <- Validates output against XSD
└─ validation/
    └── XsdValidator.java                   <- Optional XSD validation

src/main/resources/
├── application.properties                  <- App configuration
├── interface-config.json                   <- Interface definitions
├── logback-spring.xml                      <-
├── beanio/
│   └── (mapping files)
├── db/
│   └── schema.sql                          <- Database schema
├─── static/
├─── template/
│   └── dashboard.html                      <- Application landing page
└── xsd/
    ├── order_schema.xsd                    <- Example: XSD schema
    └── (other schemas)

src/test/java/com/silverlakesymmetri/cbs/fileGenerator/  <- Unit tests
```

## Technology Stack

- **Framework**: Spring Boot 1.5.22 (Java 8 compatible)
- **Database**: Oracle Database (JDBC)
- **Concurrency**: JPA Optimistic Locking (@Version)
- **Integrity**: SHA-256 Checksum Verification
- **Job Scheduling**: Quartz Scheduler 2.3.0
- **Batch Processing**: Spring Batch
- **File Formatting**: BeanIO 2.1.0
- **Validation**: XSD Schema validation
- **Authentication**: DB Token-based (custom)
- **Web Server**: Apache Tomcat (embedded in JAR, external for WAR)
- **Build Tool**: Maven

## Key Features

### 1. Database Token Authentication
- Custom DB token-based authentication mechanism
- Tokens stored in `IF_DB_TOKEN` table
- Token validation via `TokenValidator` component
- Automatic token expiry checking
- Last used date tracking

### 2. Application Configuration Registry
- Configuration stored in `IF_APP_CONFIG` table
- Dynamic configuration loading
- Support for different configuration types
- Audit trail with created/updated dates

### 3. Spring Batch File Generation
- **Memory-Efficient Batch Reading:** Implements `ItemStreamReader` with key set pagination to process millions of records without exhausting memory, while supporting batch restarts.
- **Chunking:** Processes 1000 records per transaction chunk by default, balancing memory usage and performance. In case of failure, only the current chunk is rolled back.
- Configurable item processors for validation
- Multiple output format support (CSV, XML, etc.)
- Error handling and recovery

### 4. Quartz Job Scheduling
- Database-backed job store
- JDBC persistent scheduling
- Support for cron expressions
- Transaction management

### 5. XSD Validation
- XML Schema validation framework
- Strict/lenient mode support
- Item-level validation in batch processing
- Schema loading from classpath

### 6. BeanIO XML Formatting
- Declarative format mapping via XML configuration
- Support for XML and delimited formats
- Field-level configuration
- Complex record structure support

### 7. Atomic File Finalization & Integrity
- **Safety First:** Files are written with a `.part` extension during processing.
- **Atomic Rename:** Files are only moved to their final name upon successful Batch completion.
- **Checksum Verification:** The system calculates an SHA-256 hash post-generation and verifies it before marking the job as `COMPLETED`.

### 8. Race Condition Protection
- **Optimistic Locking:** Uses JPA `@Version` to prevent duplicate batch executions for the same jobId.
- **Async Guarding:** The `BatchJobLauncher` performs a status pre-check to ensure only `PENDING` jobs can transition to `PROCESSING`.

### 9. REST API Endpoints

### File Generation Endpoints
*Base Path:* `/api/v1/file-generation`

| Method   | Endpoint                           | Description                                                                                                                                                    |
|:---------|:-----------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **POST** | `/generate`                        | Queues a new file generation job based on the interface type. Checks for existing running jobs and validates configuration. Returns `202 Accepted` on success. |
| **GET**  | `/getFileGenerationStatus/{jobId}` | Retrieves the current status, record counts, and any error messages for a specific job ID.                                                                     |
| **GET**  | `/getFileGenerationsByStatus`      | Lists jobs filtered by status (e.g., `PENDING`, `COMPLETED`, `FAILED`) with pagination support.                                                                |
| **GET**  | `/downloadFileByJobId/{jobId}`     | Downloads the generated file for a `COMPLETED` job. Supports HTTP Range requests for resumable downloads.                                                      |
| **GET**  | `/interfaces`                      | Returns a list of all currently enabled interface configurations available in the system.                                                                      |
| **GET**  | `/getConfigInfo/{interfaceType}`   | Returns the detailed configuration settings (format, schema, extension, etc.) for a specific interface type.                                                   |

### Admin & Scheduler Endpoints
*Base Path:* `/api/v1/admin`

| Method   | Endpoint                             | Description                                                                                                          |
|:---------|:-------------------------------------|:---------------------------------------------------------------------------------------------------------------------|
| **POST** | `/scheduler/trigger/{interfaceType}` | Manually triggers an immediate Quartz job for a specific interface, bypassing the defined Cron schedule.             |
| **POST** | `/scheduler/force-run`               | Forces the Poller job (`FILE_GEN_POLL_JOB`) to run immediately to pick up pending requests.                          |
| **POST** | `/scheduler/pause`                   | Pauses the entire Quartz scheduler (stops all future triggers).                                                      |
| **POST** | `/scheduler/resume`                  | Resumes the Quartz scheduler from a paused state.                                                                    |
| **GET**  | `/scheduler/jobs`                    | Lists all jobs currently registered in the Quartz scheduler with their next fire times.                              |
| **GET**  | `/scheduler/status`                  | Returns detailed status of all scheduled triggers, including previous run time, next run time, and Cron expressions. |
| **POST** | `/cleanup`                           | Manually triggers the system maintenance job (`cleanupJob`) to purge stale files and database records.               |
| **POST** | `/reload-config`                     | Hot-reloads the `interface-config.json` file from the classpath without restarting the application.                  |
## Database Schema

### IF_APP_CONFIG Table
Stores application configuration parameters
- CONFIG_ID (Primary Key)
- CONFIG_KEY (Unique)
- CONFIG_VALUE (CLOB)
- CONFIG_TYPE
- DESCRIPTION
- ACTIVE flag
- CREATED_DATE
- UPDATED_DATE

### IF_DB_TOKEN Table
Manages authentication tokens
- TOKEN_ID (Primary Key)
- TOKEN_VALUE (Unique)
- APPLICATION_NAME
- ISSUED_BY
- ISSUED_DATE
- EXPIRY_DATE
- ACTIVE flag
- LAST_USED_DATE

### IF_FILE_GENERATION Table
Tracks file generation job execution
- FILE_GEN_ID (Primary Key)
- JOB_ID (Unique UUID for tracking.)
- INTERFACE_TYPE
- FILE_NAME
- FILE_PATH
- STATUS State machine(PENDING, PROCESSING, STOPPED, FINALIZING, COMPLETED, FAILED)
- RECORD_COUNT
- SKIPPED_RECORD_COUNT
- INVALID_RECORD_COUNT
- ERROR_MESSAGE
- CREATED_BY
- CREATED_DATE
- COMPLETED_DATE
- VERSION: Integer for JPA Optimistic Locking.

## Configuration Properties

Key configuration properties in `application.properties`:

```properties
# Hibernate dialect for Oracle 12c
spring.jpa.database-platform=org.hibernate.dialect.Oracle12cDialect

# Fully qualified Oracle JDBC driver class
# Required for connecting to Oracle databases
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver

# JDBC connection URL for Oracle (SID-based)
# Uses environment variables with sensible defaults:
# - DB_HOST : database server hostname or IP
# - DB_PORT : listener port (default 1521)
# - DB_SID  : Oracle SID
spring.datasource.url=jdbc:oracle:thin:@${DB_HOST:10.253.182.53}:${DB_PORT:1521}:${DB_SID:CBSEXIM}

# Database username
# Can be overridden using the DB_USERNAME environment variable
spring.datasource.username=${DB_USERNAME:CBSDEV}

# Database password
# Should be provided via environment variable in non-dev environments
spring.datasource.password=${DB_PASSWORD:CBSDEV}

# SQL query used to validate connections before using them
# Simple lightweight query for Oracle
spring.datasource.hikari.connection-test-query=SELECT 1 FROM DUAL

# File Generation
file.generation.output-directory=/opt/cbs/generated-files/
file.generation.chunk-size=1000

# Authentication
auth.token.header-name=X-DB-Token
auth.token.enable-validation=true

# Quartz
spring.quartz.job-store-type=jdbc
spring.quartz.properties.org.quartz.threadPool.threadCount=10

# Logging
logging.level.com.silverlakesymmetri.cbs.fileGenerator=DEBUG
```

## Deployment

**See DEPLOYMENT.md for detailed instructions**

1. Build application:
   - JAR (embedded Tomcat): `mvn clean package`
   - WAR (external Tomcat): `mvn clean package -Pwar`
2. Deploy to server
3. Configure database credentials in `application.properties`
4. Set environment variables as needed

## Security Considerations

1. All API endpoints (except /health, /info) require valid DB token
2. Tokens are validated against IF_DB_TOKEN table
3. Token expiry is enforced
4. CORS is enabled for cross-origin requests
5. Character encoding filter configured for UTF-8

## Error Handling

- Global exception handler for REST endpoints
- Detailed error logging
- File generation error tracking in database
- Graceful batch processing error recovery

## Logging

Logs are written to:
- Console: INFO level
- File: `logs/cbs-file-generator.log`
- Component-specific debug logging available

## Documentation

Comprehensive documentation for understanding and using this template:

### Getting Started

- **TEMPLATE_APPLICATION_SUMMARY.md** - Complete overview of this template application, architecture, and how to use it as a starting point for new projects

### Architecture & Design

- **AGENTS.md** - Developer guidelines
  - Build and test commands
  - Code style guidelines
  - Architecture overview
  - Key components and their roles

- **BATCH_ARCHITECTURE.md** - Detailed batch processing architecture
  - Generic vs Specialized approaches explained
  - JPQL requirement and examples
  - Configuration structure (interface-config.json)
  - Data flow diagrams
  - When to use each approach

### Implementation Guides

- **JPA_ENTITY_SETUP.md** - Creating JPA entity classes
  - Decision tree: Entity only vs Entity + DTO
  - When DTOs are needed
  - Step-by-step entity creation
  - Annotation reference
  - Real-world examples (Order/LineItem)
  - Data type mapping

### Configuration & Output

- **OUTPUT_FORMATS.md** - Output format configuration
  - XML as default format (no configuration needed)
  - BeanIO mapping for custom formats (CSV, fixed-length, delimited)
  - Configuration examples
  - Stream naming conventions
  - Field type support
  - Troubleshooting common issues

- **XSD_VALIDATION.md** - Optional schema validation
  - Configuration setup (optional)
  - Strict vs lenient validation modes
  - XSD schema examples
  - Performance considerations
  - Integration with batch processors
