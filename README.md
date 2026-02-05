# CBS File Generator - Spring Boot Application

## Overview
A Spring Boot microservice application for generating XML interface files from Oracle database sources with Quartz scheduling, Spring Batch processing, and token-based authentication.

## Architecture

### Project Structure

```
src/main/java/com/silverlakesymmetri/cbs/fileGenerator/
├── FileGeneratorApplication.java           ← Main entry point
├── batch/
│   ├── BatchCleanupTasklet.java            ←
│   ├── BeanIOFormatWriter.java             ← Generic BeanIO writer
│   ├── DynamicBatchConfig.java             ← Generic batch config
│   ├── DynamicItemProcessor.java           ← Generic processor
│   ├── DynamicItemReader.java              ← Generic reader
│   ├── DynamicItemWriter.java              ← Generic writer
│   ├── DynamicJobExecutionListener.java    ← Job listener
│   ├── DynamicStepExecutionListener.java   ← Step listener
│   ├── FileValidationTasklet.java          ←
│   ├── GenericXMLWriter.java               ← Generic XML writer
│   ├── MaintenanceBatchConfig.java         ←
│   ├── OutputFormatWriter.java             ← Generic output format writer
│   ├── OutputFormatWriterFactory.java      ← Factory for selecting appropriate output format writer
│   └── custom/
│       ├── OrderBatchConfig.java           ← Example: specialized batch config
│       ├── OrderItemProcessor.java         ← Example: specialized processor
│       ├── OrderItemReader.java            ← Example: specialized reader
│       ├── OrderItemWriter.java            ← Example: specialized writer
│       ├── OrderRowMapper.java             ← Example: entity→DTO mapper
│       └── OrderStepExecutionListener.java ← Example: specialized step listener
├── config/
│   ├── AsyncConfig.java                    ←
│   ├── AutowiringSpringBeanJobFactory.java ←
│   ├── BatchInfrastructureConfig.java      ←
│   ├── DatabaseConfig.java                 ← Database configuration
│   ├── InterfaceConfigLoader.java          ← Interface configuration loader
│   ├── QuartzConfiguration.java            ← Quartz scheduler configuration
│   ├── SchedulerStartupRunner.jav          ←
│   ├── SecurityConfig.java                 ← Security configuration
│   ├── TomcatConfig.java                   ← Tomcat configuration
│   └── model/
│       ├── InterfaceConfig.java            ← Interface Config model
│       └── InterfaceConfigWrapper.java     ← Wrapper for interface-config.json
├── constants/
│   ├── BatchMetricsConstants.java          ←
│   └── FileGenerationConstants.java        ←
├── controller/
│   ├── AdminController.java                ← REST endpoints
│   └── FileGenerationController.java       ← REST endpoints
├── dto/
│   ├── ApiResponse.java                    ←
│   ├── ColumnType.java                     ←
│   ├── DynamicRecord.java                  ← Generic record holder for dynamic data
│   ├── FileGenerationRequest.java          ← API request
│   ├── FileGenerationResponse.java         ← API response
│   ├── LineItemDto.java                    ← Example: nested DTO
│   ├── OrderDto.java                       ← Example: complex DTO
│   └── PagedResponse.java                  ←
│   └── RecordSchema.java                   ←
├── entity/
│   ├── AppConfig.java                      ← Application config
│   ├── DbToken.java                        ← Auth tokens
│   ├── FileGeneration.java                 ← Tracks file generation job execution
│   ├── LineItem.java                       ← Example: JPA entity
│   └── Order.java                          ← Example: JPA entity
├── exception/
│   ├── ConfigurationException.java         ←
│   ├── ConflictException.java              ←
│   ├── ForbiddenException.java             ←
│   ├── GlobalExceptionHandler.java         ← Global exception handler
│   ├── GoneException.java                  ←
│   ├── LifecycleException.java             ←
│   └── NotFoundException.java              ←
├── health/
│   ├── BatchQueueHealthIndicator.java      ←
│   └── QuartzHealthIndicator.java          ←
├── repository/
│   ├── AppConfigRepository.java            ← Config access
│   ├── DbTokenRepository.java              ← Token access
│   ├── FileGenerationRepository.java       ← Job tracking
│   └── OrderRepository.java                ← Example: custom queries
├── scheduler/
│   └── BatchJobLauncherJob.java            ←
│   └── FileGenerationScheduler.java        ← Job scheduling
│   └── MaintenanceScheduler.java           ←
├── security/
│   ├── CorrelationIdFilter.java            ←
│   ├── TokenAuthenticationFilter.java      ← Token authentication filter
│   └── TokenValidator.java                 ← Token validator
├── service/
│   ├── AppConfigService.java               ← App Config service
│   ├── BatchJobLauncher.java               ← Job routing
│   ├── FileFinalizationService.java        ← Finalizes file generation
│   ├── FileGenerationService.java          ← Business logic
│   └── FileGenerationStatus.java           ← 
── validation/
    └── XsdValidator.java                   ← Optional XSD validation

src/main/resources/
├── application.properties                  ← App configuration
├── interface-config.json                   ← Interface definitions
├── beanio/
│   └── (mapping files)
├── db/
│   └── schema.sql                          ← Database schema
└── xsd/
    ├── order_schema.xsd                    ← Example: XSD schema
    └── (other schemas)

src/test/java/com/silverlakesymmetri/cbs/fileGenerator/  ← Unit tests
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
- **Memory-Efficient Batch Reading:** Implements `ItemStreamReader` with keyset pagination to process millions of records without exhausting memory, while supporting batch restarts.
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
- **Checksum Verification:** The system calculates a SHA-256 hash post-generation and verifies it before marking the job as `COMPLETED`.

### 8. Race Condition Protection
- **Optimistic Locking:** Uses JPA `@Version` to prevent duplicate batch executions for the same jobId.
- **Async Guarding:** The `BatchJobLauncher` performs a status pre-check to ensure only `PENDING` jobs can transition to `PROCESSING`.

### 9. REST API Endpoints
All endpoints (except health) require a valid `X-DB-Token` in the header.

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| **POST** | `/api/v1/file-generation/generate` | Queues a new job (Returns 202 Accepted) |
| **GET** | `/api/v1/file-generation/status/{jobId}` | Returns status, metrics, and error messages |
| **GET** | `/api/v1/file-generation/pending` | Lists all jobs currently in `PENDING` state |
| **GET** | `/api/v1/file-generation/health` | Service and interface status |

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
# File Generation
file.generation.output-directory=/opt/cbs/generated-files/
file.generation.archive-directory=/opt/cbs/archived-files/
file.generation.temp-directory=/opt/cbs/temp-files/
file.generation.log-directory=/opt/cbs/logs/
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
