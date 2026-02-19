# CBS File Generator - Template Application Summary

## Overview

This is a **production-ready template application** for batch file generation from Oracle database sources. It serves as a foundation for building similar applications with clear documentation and reusable architecture patterns.

---

## Code Architecture

### 1. Generic Approach (Recommended for Simple Data)

**Components**:
- `DynamicBatchConfig` - Batch job configuration
- `DynamicItemProcessor` - Basic validation and transformation
- `DynamicItemReader` - **Executes Native SQL**, detects columns automatically (No Java Entities needed)
- `DynamicItemWriter` - Auto-generates XML without mapping files
- `DynamicJobExecutionListener` - Job status tracking

**When to use**:
- ✅ Flat table structure (no nesting)
- ✅ No complex transformations
- ✅ Output as-is from database
- ✅ **No Java Code Required** (Config only)

**Example Configuration**:
```json
{
  "CUSTOMER_INTERFACE": {
    "dataSourceQuery": "SELECT CUSTOMER_ID, NAME, EMAIL FROM CUSTOMERS",
    "keySetColumn": "CUSTOMER_ID",
    "outputFormat": "XML",
    "chunkSize": 1000
  }
}
```

---

### 2. Specialized Approach (For Complex Data)

**Components**:
- `Order.java` + `LineItem.java` - **JPA Entities**
- `OrderDto.java` - Data Transfer Object
- `OrderBatchConfig.java` - Custom Spring Batch configuration
- `OrderItemReader.java` - **Executes JPQL** with Two-Step fetching
- `OrderItemWriter.java` - Hierarchical XML generation

**When to use**:
- ⚠️ Nested relationships (Order → LineItems)
- ⚠️ Date/timestamp formatting
- ⚠️ Data enrichment or denormalization
- ⚠️ Hierarchical output structure

---

## Key Design Patterns

### 1. Configuration-Driven Processing
Define new interfaces in `interface-config.json` without recompiling code.

### 2. Hybrid Query Strategy
*   **Dynamic Jobs:** Use **Native SQL** (Table Names).
  *   *Example:* `SELECT * FROM ORDERS WHERE STATUS = 'A'`
*   **Custom Jobs:** Use **JPQL** (Entity Names).
  *   *Example:* `SELECT o FROM Order o JOIN FETCH o.lineItems`

### 3. Async Batch Processing
API triggers jobs asynchronously. The client receives a `202 Accepted` and polls for status.

### 4. Atomic State Machine
Job status transitions (PENDING -> QUEUED -> PROCESSING -> COMPLETED) are handled atomically in the database to prevent race conditions in a clustered environment.

---

## How to Add New Interfaces (The Workflow)

### Path A: The "Low Code" Way (Dynamic)
*Use for 90% of interfaces.*

1.  **Write SQL**: Construct a valid Native SQL query for your database.
2.  **Update Config**: Add entry to `interface-config.json`.
3.  **Reload**: Call the `POST /api/v1/admin/reload-config` endpoint.
4.  **Done**: The interface is live.

### Path B: The "Java" Way (Custom)
*Use for complex/nested data.*

1.  **Create Entity**: Map your table to a Java class (`src/main/java/.../entity/MyTable.java`).
2.  **Create DTO**: Define your output structure.
3.  **Create Batch Config**: Implement a `Configuration` class defining your Step/Job beans.
4.  **Deploy**: Recompile and deploy the WAR/JAR.

---

## Build & Deploy

### Prerequisites
*   Java 8
*   Maven 3.6+
*   Oracle Database

### Commands

**Build JAR (Embedded Tomcat)**
```bash
mvn clean package
# Run
java -jar target/file-generator-1.0.0-exec.jar
```

**Build WAR (External Tomcat)**
```bash
mvn clean package -Pwar
# Deploy target/file-generator.war to Tomcat webapps/
```

---

## Documentation Index

- **AGENTS.md** - Developer guidelines & Code Style
- **BATCH_ARCHITECTURE.md** - Deep dive into Dynamic vs Specialized logic
- **JPA_ENTITY_SETUP.md** - Guide for creating Entities (for Custom jobs)
- **OUTPUT_FORMATS.md** - How to configure XML vs CSV vs Fixed-Length
- **XSD_VALIDATION.md** - Setting up post-generation validation
- **DEPLOYMENT.md** - Installation guide

---

## File Structure Snapshot

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