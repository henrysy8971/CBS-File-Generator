# CBS File Generator - Template Application Summary

## Overview

This is a **production-ready template application** for batch file generation from Oracle database sources. It serves as a foundation for building similar applications with clear documentation and reusable architecture patterns.

---

### Core Documentation

- **AGENTS.md** - Developer guidelines
  - Build commands (JAR, WAR, with/without tests)
  - Code style guidelines
  - Architecture and codebase structure
  - Key components and classes

- **BATCH_ARCHITECTURE.md** - Deep dive into batch processing
  - Generic vs Specialized architecture explained
  - Data flow diagrams for both approaches
  - JPQL requirement clarification (not native SQL)
  - Configuration structure (interface-config.json)
  - When to use each approach
  - Examples for adding new interfaces

- **JPA_ENTITY_SETUP.md** - Entity and DTO creation guide
  - Decision tree: Entity only vs Entity + DTO
  - When DTOs are needed (transformation, nested objects)
  - Step-by-step entity creation
  - Annotation reference
  - Real example with ORDER_INTERFACE
  - Data type mapping table

- **OUTPUT_FORMATS.md** - Output format configuration
  - XML as default format (no config needed)
  - BeanIO mapping for custom formats (CSV, fixed-length, delimited)
  - Format examples and configurations
  - Stream naming conventions
  - Field type support
  - Troubleshooting guide

- **README.md** - Overview and technology stack
  - High-level architecture
  - Technology stack (Spring Boot 1.5.22, Quartz, Spring Batch, Oracle)
  - Key features explained
  - Links to additional resources

- **DEPLOYMENT.md** - Build and deployment guide
  - Prerequisites (Java 8, Maven 3.6+, Oracle 11g+)
  - Building JAR (embedded Tomcat) and WAR (external Tomcat)
  - Database setup and schema
  - Application startup instructions
  - Post-deployment configuration
  - Testing examples with curl
  - Monitoring and troubleshooting

- **XSD_VALIDATION.md** - Optional schema validation
  - Configuration setup
  - Strict vs lenient modes
  - Example XSD schemas
  - Performance considerations
  - Integration with processors
  - Best practices

---

## Code Architecture ✅

### Generic Approach (Recommended for Simple Data)

**Components**:
- `DynamicBatchConfig` - Batch job configuration
- `DynamicItemProcessor` - Basic validation and transformation
- `DynamicItemReader` - Executes JPQL query, detects columns automatically
- `DynamicItemWriter` - Auto-generates XML without mapping files
- `DynamicJobExecutionListener` - Job status tracking
- `DynamicStepExecutionListener` - Step status tracking

**When to use**:
- ✅ Flat table structure (no nesting)
- ✅ No date/format transformations
- ✅ Output as-is from database
- ✅ Quick setup (just add config, no code changes)

**Example**: CUSTOMER_INTERFACE
```json
{
  "CUSTOMER_INTERFACE": {
    "dataSourceQuery": "SELECT c FROM Customer c",
    "outputFormat": "XML",
    "chunkSize": 1000
  }
}
```

---

### Specialized Approach (For Complex Data)

**Components**:
- `Order.java` + `LineItem.java` - JPA entities
- `OrderDto.java` + `LineItemDto.java` - Data transfer objects
- `OrderBatchConfig.java` - Custom batch configuration
- `OrderItemProcessor.java` - Order-specific validation
- `OrderItemReader.java` - Pagination-aware reader
- `OrderItemWriter.java` - Hierarchical XML generation
- `OrderRowMapper.java` - Entity → DTO conversion with transformations
- `OrderStepExecutionListener.java` - Step status tracking
- `OrderRepository.java` - Custom JPA queries

**When to use**:
- ⚠️ Nested relationships (Order → LineItems)
- ⚠️ Date/timestamp formatting
- ⚠️ Data enrichment or denormalization
- ⚠️ Custom validation logic
- ⚠️ Hierarchical output structure

**Example**: ORDER_INTERFACE with custom processing

---

## Key Design Patterns

### 1. Configuration-Driven Processing

**Concept**: Define new interfaces in `interface-config.json` without code changes

**File**: `src/main/resources/interface-config.json`
```json
{
  "interfaces": {
    "INTERFACE_NAME": {
      "name": "INTERFACE_NAME",
      "dataSourceQuery": "SELECT i FROM InterfaceEntity i",
      "chunkSize": 1000,
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "enabled": true
    }
  }
}
```

### 2. JPQL-Only Query Language

**Concept**: All queries use JPQL (Java Persistence Query Language), not native SQL

**Requirement**: JPA entity classes required for every table/view

**Example**:
```
✅ Correct:  SELECT o FROM Order o WHERE o.status = 'ACTIVE'
❌ Wrong:    SELECT * FROM orders WHERE status = 'ACTIVE'
```

### 3. REST API with Async Batch Processing

**Concept**: API triggers batch jobs asynchronously, client polls for status

**Endpoints**:
```
POST   /api/v1/file-generation/generate        - Trigger job
GET    /api/v1/file-generation/status/{jobId}  - Check status
GET    /api/v1/file-generation/pending         - List pending jobs
GET    /api/v1/file-generation/health          - Health check
```

### 4. Auto-Generated Filenames and Paths

**Concept**: Filenames and output directories are not configurable per request

**Why**: Security and consistency

**Setup**: Configured in `application.properties`
```properties
file.generation.output-directory=/opt/cbs/generated-files/
```

**Filename format**: `{interfaceType}_{timestamp}.{extension}`

### 5. Entity-Only vs Entity+DTO Pattern

**Decision**:
- **Simple flat data** → Entity only (DynamicItemReader handles it)
- **Complex nested/transformed data** → Entity + DTO (custom reader/writer)

---

## How to Build Applications From This Template

### Step 1: Create JPA Entity Classes

For each table/view you need to query, create a JPA entity:

```java
@Entity
@Table(name = "TABLE_NAME")
public class TableEntity {
    @Id
    @Column(name = "ID")
    private String id;

    @Column(name = "COLUMN_NAME")
    private String columnName;

    // getters/setters
}
```

### Step 2: Add Interface Configuration

Add entry to `src/main/resources/interface-config.json`:

```json
{
  "NEW_INTERFACE": {
    "name": "NEW_INTERFACE",
    "dataSourceQuery": "SELECT t FROM TableEntity t",
    "chunkSize": 1000,
    "outputFormat": "XML",
    "outputFileExtension": "xml",
    "enabled": true
  }
}
```

### Step 3: Deploy and Test

```bash
# Build
mvn clean package

# Run
java -jar target/file-generator-1.0.0-exec.jar --DB_PASSWORD=<your-db-password>

# Test API
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-token" \
  -d '{"interfaceType": "NEW_INTERFACE"}'
```

### Step 4 (Optional): For Complex Data, Create DTO Classes

If you need transformation or nested structures:

1. Create DTO classes (`NewInterfaceDto.java`)
2. Create RowMapper (`NewInterfaceRowMapper.java`)
3. Create custom ItemReader/Processor/Writer
4. Create custom BatchConfig
5. Update BatchJobLauncher routing logic

---

## Technology Stack

| Component            | Version                          | Purpose                                  |
|----------------------|----------------------------------|------------------------------------------|
| **Spring Boot**      | 1.5.22                           | Application framework                    |
| **Spring Batch**     | -                                | Batch processing                         |
| **Spring Data JPA**  | -                                | Database access                          |
| **Quartz Scheduler** | 2.3.0                            | Job scheduling                           |
| **BeanIO**           | 2.1.0                            | Format mapping (CSV, fixed-length, etc.) |
| **Oracle JDBC**      | 21.1.0                           | Database driver                          |
| **Java**             | 8                                | Language                                 |
| **Maven**            | 3.6+                             | Build tool                               |
| **Tomcat**           | Embedded (JAR) or External (WAR) | Web server                               |

--- 

## Build Options

### JAR File (Embedded Tomcat - for Testing)

```bash
mvn clean package
# Output: target/file-generator-1.0.0-exec.jar
# Run: java -jar target/file-generator-1.0.0-exec.jar --DB_PASSWORD=<your-db-password>
```

- ✅ Includes embedded Tomcat
- ✅ Ready to run immediately
- ✅ Good for development and testing

### WAR File (External Tomcat - for Production)

```bash
mvn clean package -Pwar
# Output: target/file-generator.war
# Deploy to: $CATALINA_HOME/webapps/
```

- ✅ Optimized for external Tomcat
- ✅ Smaller file size (no embedded server)
- ✅ Good for production deployment

---

## File Structure

```
src/main/java/com/silverlakesymmetri/cbs/fileGenerator/
├── batch/
│   ├── custom/
│   │   ├── OrderBatchConfig.java           ← Example: specialized batch config
│   │   ├── OrderItemProcessor.java         ← Example: specialized processor
│   │   ├── OrderItemReader.java            ← Example: specialized reader
│   │   ├── OrderItemWriter.java            ← Example: specialized writer
│   │   └── OrderRowMapper.java             ← Example: entity→DTO mapper
│   │   └── OrderStepExecutionListener.java ← Example: specialized step execution listener
│   ├── BeanIOFormatWriter.java             ← Generic BeanIO writer
│   ├── DynamicBatchConfig.java             ← Generic batch config
│   ├── DynamicItemProcessor.java           ← Generic processor
│   ├── DynamicItemReader.java              ← Generic reader
│   ├── DynamicItemWriter.java              ← Generic writer
│   ├── DynamicJobExecutionListener.java    ← Job listener
│   ├── DynamicStepExecutionListener.java   ← Step listener
│   ├── GenericXMLWriter.java               ← Generic XML writer
│   ├── OutputFormatWriter.java             ← Generic output format writer
│   └── OutputFormatWriterFactory.java      ← Factory for selecting appropriate output format writer
├── config/
│   ├── model/
│   │   ├── InterfaceConfig.java            ← Config model
│   │   └── InterfaceConfigWrapper.java     ← Wrapper for interface-config.json
│   ├── DatabaseConfig.java                 ← Database configuration
│   ├── InterfaceConfigLoader.java          ← Interface configuration loader
│   ├── QuartzConfig.java                   ← Quartz scheduler configuration
│   ├── SecurityConfig.java                 ← Security configuration
│   └── TomcatConfig.java                   ← Tomcat configuration
├── controller/
│   └── FileGenerationController.java       ← REST endpoints
├── dto/
│   ├── DynamicRecord.java                  ← Generic record holder for dynamic data
│   ├── FileGenerationRequest.java          ← API request (interfaceType only)
│   ├── FileGenerationResponse.java         ← API response
│   ├── LineItemDto.java                    ← Example: nested DTO
│   └── OrderDto.java                       ← Example: complex DTO
├── entity/
│   ├── AppConfig.java                      ← Application config
│   ├── DbToken.java                        ← Auth tokens
│   ├── FileGeneration.java                 ← Tracks job execution
│   ├── LineItem.java                       ← Example: JPA entity
│   └── Order.java                          ← Example: JPA entity
├── exception/
│   └── GlobalExceptionHandler.java         ← Global exception handler
├── repository/
│   ├── AppConfigRepository.java            ← Config access
│   ├── DbTokenRepository.java              ← Token access
│   ├── FileGenerationRepository.java       ← Job tracking
│   └── OrderRepository.java                ← Example: custom queries
├── service/
│   ├── AppConfigService.java               ← Config service
│   ├── BatchJobLauncher.java               ← Job routing
│   ├── FileFinalizationService.java        ← Finalizes file generation
│   └── FileGenerationService.java          ← Business logic
├── scheduler/
│   └── FileGenerationScheduler.java        ← Job scheduling
├── security/
│   ├── TokenAuthenticationFilter.java      ← Token authentication filter
│   └── TokenValidator.java                 ← Token validator
├── service/
│   ├── AppConfigService.java               ← Config service
│   ├── BatchJobLauncher.java               ← Job routing
│   ├── FileFinalizationService.java        ← Finalizes file generation
│   └── FileGenerationService.java          ← Business logic
├── util/
├── validation/
│   └── XsdValidator.java                   ← Optional XSD validation
└── FileGeneratorApplication.java           ← Main entry point

src/main/resources/
├── application.properties                  ← App configuration
├── interface-config.json                   ← Interface definitions
├── db/
│   └── schema.sql                          ← Database schema
├── beanio/
│   ├── order-mapping.xml                   ← Example: BeanIO mapping
│   └── (other format mappings)
└── xsd/
    ├── order_schema.xsd                    ← Example: XSD schema
    └── (other schemas)
```

---

## Next Steps for Your Template

You now have a **complete, documented, production-ready template** with:

1. ✅ Clear architecture (generic and specialized approaches)
2. ✅ Comprehensive documentation (7 markdown files)
3. ✅ Working examples (Order/LineItem with full implementation)
4. ✅ Reusable components (DynamicItemReader/Processor/Writer)
5. ✅ Configuration-driven extensibility (interface-config.json)
6. ✅ Multiple build options (JAR and WAR)

### To Use This Template for New Applications:

1. **Clone the repository**
2. **Update database connection** in `application.properties`
3. **Create JPA entities** for your tables (follow JPA_ENTITY_SETUP.md)
4. **Add interface configs** to `interface-config.json`
5. **Build and deploy** using provided commands
6. **Test via REST API** using provided examples

Everything else is already handled by the framework!

---

## Support Resources

- **JPA_ENTITY_SETUP.md** - How to create entities
- **BATCH_ARCHITECTURE.md** - How the system works
- **DEPLOYMENT.md** - How to build and deploy
- **OUTPUT_FORMATS.md** - Output format options
- **XSD_VALIDATION.md** - Validation setup (optional)
- **AGENTS.md** - Developer commands

All documentation is current and reflects the actual implementation.
