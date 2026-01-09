# CBS File Generator - Agent Guidelines

## Build & Test Commands

- **Build JAR (Standalone)**: `mvn clean package`
- **Build WAR (Deployment)**: `mvn clean package -Pwar`
- **Build JAR without tests**: `mvn clean package -DskipTests`
- **Build WAR without tests**: `mvn clean package -Pwar -DskipTests`
- **Run tests**: `mvn test`
- **Run locally**: `mvn spring-boot:run`
- - **Clean Oracle Schema**: `mvn liquibase:dropAll` (if applicable) or manual execution of `schema.sql`

## Architecture & Codebase Structure

**Package**: `com.silverlakesymmetri.cbs.fileGenerator`

**Stack**: Spring Boot 1.5.22 (Legacy), Java 8, Spring Batch 3.x, Quartz 2.3, Oracle 12c/19c, BeanIO 2.1.

**Key Components**:
- **Dynamic Batch Processing**: Single configurable batch job handles all interface types via `interface-config.json`
- **Async API**: REST controller (`FileGenerationController`) triggers batch jobs asynchronously
- **Database**: Oracle with JPA entities (AppConfig, FileGeneration, DbToken)
- **Configuration**: Centralized in `interface-config.json` (dataSourceQuery, beanioMappingFile, xsdSchemaFile, transformRules, chunkSize)
- **Scheduling**: Quartz with Autowiring Job Factory & Startup Runner
- **Authentication**: Token-based via X-DB-Token header, validated against DB_TOKEN table

**Key Classes**: InterfaceConfigLoader, DynamicBatchConfig, DynamicItemReader/Processor/Writer, DynamicJobExecutionListener, BatchJobLauncher, FileGenerationService, AppConfigService

**Core Design Patterns**:
- **Atomic State Machine**: Status transitions use `updateStatusAtomic` in the Repository (WHERE status = expected) to prevent race conditions.
- **Seek Pagination (Keyset)**: Large dataset queries must use `ORDER BY ID` and `WHERE ID > :lastId`. Avoid offset-based paging for performance.
- **Part-File Lifecycle**: Files are written as `.part`. Only renamed to final filename via `FileFinalizationService` upon successful Job completion.
- **Hybrid Batching**:
    - `DynamicBatchConfig`: Metadata-driven for generic/flat reporting.
    - `OrderBatchConfig`: Specialized for complex hierarchical data (One-to-Many).

## Critical Implementation Rules

### 1. Persistence & Concurrency
- **Optimistic Locking**: Always use `@Version` on the `FileGeneration` entity to support the retry logic.
- **Status Updates**: Methods updating job status (`markCompleted`, `markFailed`) must be `@Retryable` to handle database row-lock contention.
- **Identity Generation**: Use `GenerationType.SEQUENCE` with `allocationSize = 1` to match Oracle sequences. Avoid `IDENTITY` columns to ensure compatibility with legacy Hibernate versions.
- **LOBs**: Ensure any `CLOB` column in Oracle is annotated with `@Lob` in the JPA entity.
- **Java 8 Time**: Avoid `LocalDateTime` in entities unless `hibernate-java8` is present. Use `java.sql.Timestamp` for maximum compatibility with Spring Boot 1.5.

### 2. Threading & Scoping
- **Step Scope**: Any class holding execution state (writers, readers, processors) **must** be annotated with `@StepScope`.
- **State Isolation**: Do not `@Autowire` step-scoped beans into Singleton factories. Use `applicationContext.getBean(Class)` to fetch fresh instances per step execution.
- **Thread Safety**: `SimpleDateFormat` is forbidden in static contexts. Use Java 8 `java.time.format.DateTimeFormatter`.

### 3. XML & File I/O
- **Stream-Based**: Use StAX (`XMLStreamWriter`) for dynamic XML generation to keep memory footprint low.
- **Security**: `XsdValidator` must have XXE-safe settings (Access External DTD/Schema set to empty string).
- **Integrity**: Every finalized file must have a corresponding `.sha256` checksum file generated post-rename.

## Code Style Guidelines

- **Naming**: PascalCase for classes, camelCase for methods/variables.
- **Logging**: Use SLF4J `LoggerFactory`. Log detailed record counts at `DEBUG`, lifecycle events at `INFO`, and data integrity issues at `WARN`.
- **Injection**: Constructor injection is preferred for mandatory dependencies; field injection is acceptable for existing legacy components.
- **Error Handling**: Catch specific exceptions (e.g., `SAXParseException`), log line/column numbers, and wrap in `RuntimeException` to trigger Batch rollbacks.
- **Formatting**: 4-space indentation, max 120 chars per line, JavaDoc required for all Public API methods.