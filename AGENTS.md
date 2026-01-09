# CBS File Generator - Agent Guidelines

## Build & Test Commands

- **Build JAR (default, with embedded Tomcat)**: `mvn clean package`
- **Build WAR (for external Tomcat)**: `mvn clean package -Pwar`
- **Build JAR without tests**: `mvn clean package -DskipTests`
- **Build WAR without tests**: `mvn clean package -Pwar -DskipTests`
- **Run tests**: `mvn test`
- **Run single test**: `mvn test -Dtest=TestClassName`
- **Run single test method**: `mvn test -Dtest=TestClassName#methodName`
- **Run locally**: `mvn spring-boot:run`

## Architecture & Codebase Structure

**Package**: `com.silverlakesymmetri.cbs.fileGenerator`

**Stack**: Spring Boot 1.5.22, Spring Batch, Spring Data JPA, Oracle JDBC, Quartz 2.3.2, BeanIO 2.1.0, Java 8

**Key Components**:
- **Dynamic Batch Processing**: Single configurable batch job handles all interface types via `interface-config.json`
- **Async API**: REST controller (`FileGenerationController`) triggers batch jobs asynchronously
- **Database**: Oracle with JPA entities (AppConfig, FileGeneration, DbToken)
- **Configuration**: Centralized in `interface-config.json` (dataSourceQuery, beanioMappingFile, xsdSchemaFile, transformRules, chunkSize)
- **Scheduling**: Quartz with Autowiring Job Factory & Startup Runner
- **Authentication**: Token-based via X-DB-Token header, validated against DB_TOKEN table

**Key Classes**: InterfaceConfigLoader, DynamicBatchConfig, DynamicItemReader/Processor/Writer, DynamicJobExecutionListener, BatchJobLauncher, FileGenerationService, AppConfigService

## Code Style Guidelines

- **Naming**: PascalCase for classes, camelCase for methods/variables. Service classes use @Service, Configs use @Configuration, Repositories use @Repository
- **Logging**: Use SLF4J (LoggerFactory.getLogger), log at INFO/WARN/DEBUG levels appropriately
- **Imports**: Alphabetical order; use Spring annotations (@Autowired, @Bean, @Value)
- **Error Handling**: Catch exceptions, log with context, re-throw as RuntimeException or custom exception with cause
- **JPA**: Use Optional for single results, handle with isPresent()/get(); update timestamps with new Timestamp(System.currentTimeMillis())
- **Formatting**: 2-space indentation, max 120 chars per line, JavaDoc for public methods
- **Dependencies**: Inject via @Autowired on fields or constructor (field injection used in codebase)
