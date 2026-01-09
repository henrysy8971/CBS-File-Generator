# Dynamic Batch Architecture

## Overview

CBS File Generator uses a **flexible dual-architecture approach**:

1. **Generic Dynamic Processing** (Default): Single configurable batch job that handles any interface type with variable columns and schema
2. **Specialized Processing** (Optional): Custom batch jobs for specific interface types requiring special handling (e.g., ORDER_INTERFACE with entity relationships)

## Architecture Components

### 1. Generic Dynamic Architecture (Recommended)

#### DynamicRecord
Generic data container that holds:
- Interface type identifier
- Columns with names and values (LinkedHashMap for order preservation)
- Column data types (VARCHAR, INTEGER, DECIMAL, TIMESTAMP, etc.)
- No predefined schema required

#### DynamicItemReader
- Executes **JPQL** queries from interface-config.json (native SQL not supported)
- Executes query against JPA EntityManager via `createQuery()`
- Automatically detects column names and types from first result row
- Returns DynamicRecord objects with metadata
- Batch processing (100 record batches by default)

#### DynamicItemProcessor
- Generic validation: ensures record has columns
- String trimming and basic transformations
- Error handling with graceful skipping
- Works with any column structure

#### DynamicItemWriter
- Generates XML without BeanIO mapping files
- Dynamically creates XML elements from column names
- Sanitizes column names for valid XML (e.g., `column_name` → `column_name`)
- Handles any number of columns
- Generates root element from interface type name
- Produces well-formed UTF-8 XML with proper escaping

#### DynamicBatchConfig
- Defines `dynamicFileGenerationJob` bean
- Creates `dynamicFileGenerationStep` that wires generic components
- Uses RunIdIncrementer for unique job executions
- Configurable chunk size (default 1000)

#### DynamicJobExecutionListener
- Tracks job status transitions (PROCESSING → COMPLETED/FAILED)
- Updates FILE_GENERATION table with results
- Finalizes files: renames .part to final name, generates SHA
- Captures error messages on failure

#### DynamicStepExecutionListener
- Extracts writer state after step completion
- Stores part file path and record count in execution context
- Allows job listener to access this information

### 2. Specialized Architecture (Optional)

For interface types requiring entity relationships and complex logic:

#### OrderBatchConfig (Example)
- Custom `orderFileGenerationJob` for ORDER_INTERFACE
- Wires specialized Order components instead of generic ones
- Handles Order entity with eager-loaded LineItem relationships

#### OrderItemReader
- JPA repository-based pagination
- Executes `OrderRepository.findAllActiveWithLineItems(Pageable)`
- Handles large datasets with page-based reading
- Loads Orders with LineItems in single query (JOIN FETCH avoids N+1)

#### OrderItemProcessor
- Custom validation for Order-specific rules
- Validates line items within orders
- Order-specific transformations (date formats, currency)

#### OrderItemWriter
- Generates nested XML with Order → LineItem structure
- BeanIO support for complex hierarchical mapping
- Order-specific formatting and validation

## Data Flow - Generic Approach

```
1. User sends POST /api/v1/file-generation/generate
   ↓
2. FileGenerationController:
   - Validates interface type exists in config
   - Creates FileGeneration record (status: PENDING)
   - Calls BatchJobLauncher.launchFileGenerationJob()
   ↓
3. BatchJobLauncher (async):
   - Routes to appropriate job (generic or specialized)
   - For most interfaces: uses dynamicFileGenerationJob
   - Builds JobParameters with interfaceType and outputFilePath
   ↓
4. DynamicBatchConfig.dynamicFileGenerationJob:
   - Creates step with generic components
   - Launches step execution
   ↓
5. DynamicItemReader (beforeStep callback):
   - Gets interfaceType from job parameters
   - Loads interface config
   - Gets JPQL query: "SELECT o FROM Order o WHERE ..."
   - Executes query and loads results
   - Extracts column metadata from first row
   ↓
6. For each chunk of records:
   a) DynamicItemReader.read():
      - Returns DynamicRecord with columns and values
   b) DynamicItemProcessor.process():
      - Validates record has columns
      - Applies transformations (trim strings)
      - Returns processed DynamicRecord
   c) DynamicItemWriter.write():
      - On first record: writes XML header
      - For each record: writes XML element with all columns
      - Closes with totals on final batch
   ↓
7. DynamicStepExecutionListener (afterStep):
   - Stores part file path in execution context
   - Stores record count in execution context
   ↓
8. DynamicJobExecutionListener (afterJob):
   - Updates FILE_GENERATION status to COMPLETED
   - Finalizes file: renames .part to final
   - Generates SHA checksum file
   - Updates record count
   ↓
9. User polls GET /api/v1/file-generation/status/{jobId}
   - Returns COMPLETED status with record count
   - Final XML file available at configured location
```

## Data Flow - Specialized Approach (ORDER_INTERFACE)

```
1. User sends POST /api/v1/file-generation/generate
   (interfaceType: "ORDER_INTERFACE")
   ↓
2. FileGenerationController: (same as generic)
   - Creates FileGeneration record
   - Calls BatchJobLauncher.launchFileGenerationJob()
   ↓
3. BatchJobLauncher.selectJobByInterfaceType():
   - Detects "ORDER_INTERFACE"
   - Routes to orderFileGenerationJob (if available)
   ↓
4. OrderBatchConfig.orderFileGenerationJob:
   - Uses OrderItemReader (JPA pagination)
   - Uses OrderItemProcessor (Order validation)
   - Uses OrderItemWriter (nested XML)
   ↓
5. OrderItemReader.beforeStep():
   - Initializes pagination (page 0)
   - Sets PAGE_SIZE = 1000
   ↓
6. For each chunk:
   a) OrderItemReader.read():
      - Calls OrderRepository.findAllActiveWithLineItems(Pageable)
      - Loads next page of Orders with LineItems
      - Maps Order entity to OrderDto (includes lineItems)
      - Returns OrderDto
   b) OrderItemProcessor.process():
      - Validates Order fields (ID, number, amount)
      - Validates LineItem fields
      - Applies Order-specific transformations
      - Returns OrderDto
   c) OrderItemWriter.write():
      - Writes Order XML with nested LineItem elements
      - Generates structured hierarchical XML
   ↓
7-9. Job/Step listeners and finalization (same as generic)
```

## Configuration

### interface-config.json Structure

```json
{
  "interfaces": {
    "ORDER_INTERFACE": {
      "name": "ORDER_INTERFACE",
      "dataSourceQuery": "SELECT o FROM Order o LEFT JOIN FETCH o.lineItems WHERE o.status = 'ACTIVE'",
      "beanioMappingFile": "customer-mapping.xml",
      "xsdSchemaFile": "order_schema.xsd",
      "chunkSize": 1000,
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "enabled": true,
      "description": "Order interface - uses dynamic generic processing"
    },
    "INVOICE_INTERFACE": {
      "name": "INVOICE_INTERFACE",
      "dataSourceQuery": "SELECT i FROM Invoice i WHERE i.status = 'ACTIVE'",
      "chunkSize": 500,
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "enabled": true,
      "description": "Invoice interface - dynamic columns supported"
    }
  }
}
```

**Required Fields**:
- `name`: Unique interface identifier
- `dataSourceQuery`: **JPQL query** to execute (native SQL not supported)
  - Examples: `SELECT o FROM Order o WHERE o.status = 'ACTIVE'`
  - Note: Uses JPA entity classes, not raw SQL table names
- `chunkSize`: Batch size for processing
- `outputFormat`: Output format (XML, CSV, etc.)
- `outputFileExtension`: File extension

**Optional Fields**:
- `beanioMappingFile`: BeanIO mapping file for output format (If `beanioMappingFile` is NOT specified in interface-config.json, output defaults to XML regardless of `outputFormat` value.)
- `xsdSchemaFile`: XSD Validation file used to validate xml output
- `description`: Human-readable description
- `enabled`: Enable/disable interface (default true)

## When to Use Each Approach

### Use Generic Dynamic (Default)
✅ Simple data queries with flat structures
✅ Variable number of columns
✅ Different column names per interface
✅ Quick interface setup (just add config entry)
✅ No custom logic needed
✅ Automatic XML generation

### Use Specialized (Custom Config + Code)
✅ Entity relationships (Order → LineItems)
✅ Complex nested structures
✅ Custom validation logic per entity
✅ Special transformations (domain rules)
✅ Performance optimization needed
✅ Structured XML with hierarchy

## Routing Logic

`BatchJobLauncher.selectJobByInterfaceType()`:

```java
if ("ORDER_INTERFACE".equals(interfaceType)) {
    if (orderFileGenerationJob != null) {
        return orderFileGenerationJob;  // Use specialized
    }
    return dynamicFileGenerationJob;    // Fall back to generic
} else {
    return dynamicFileGenerationJob;    // Generic for all others
}
```

**Result**:
- ORDER_INTERFACE can use either approach (specialized if available, falls back to generic)
- All other interfaces automatically use generic approach

## Query Language: JPQL vs Native SQL

### JPQL Only - JPA Entity Classes Required

CBS File Generator uses **JPQL (Java Persistence Query Language)** exclusively. Native SQL queries are **NOT supported**.

**Important**: JPQL operates on **JPA entity classes**, not raw database tables. For every table/view you want to retrieve data from, you must create a corresponding JPA entity class with proper `@Entity` and `@Column` mappings.

**Requirement Summary**:

| Item                 | Requirement     | Purpose                                           |
|----------------------|-----------------|---------------------------------------------------|
| **JPA Entity Class** | ✅ **REQUIRED**  | Maps to database table/view for JPQL queries      |
| **DTO Class**        | ⚠️ **Optional** | Only needed for REST API request/response objects |

**Key Differences**:

| Aspect    | JPQL                                   | Native SQL                          |
|-----------|----------------------------------------|-------------------------------------|
| Syntax    | Uses JPA entities and relationships    | Uses table and column names         |
| Example   | `SELECT o FROM Order o WHERE o.id = ?` | `SELECT * FROM orders WHERE id = ?` |
| Supported | ✅ Yes                                  | ❌ No                                |

**JPQL Examples**:
```
# Simple select
SELECT c FROM Customer c

# With WHERE clause
SELECT o FROM Order o WHERE o.status = 'ACTIVE'

# With JOIN
SELECT o FROM Order o JOIN FETCH o.lineItems WHERE o.customerId = 1

# With aggregation
SELECT COUNT(o) FROM Order o GROUP BY o.customerId
```

**What NOT to do** (native SQL - will fail):
```
❌ SELECT * FROM customers
❌ SELECT order_id, order_date FROM orders
❌ SELECT * FROM orders WHERE customer_id = 123
```

---

## Creating JPA Entity Classes for New Tables

### Step 1: Create Entity Class

For a new table `INVOICES`, create `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/entity/Invoice.java`:

```java
@Entity
@Table(name = "INVOICES")
public class Invoice {

    @Id
    @Column(name = "INVOICE_ID")
    private String invoiceId;

    @Column(name = "INVOICE_NUMBER")
    private String invoiceNumber;

    @Column(name = "CUSTOMER_ID")
    private String customerId;

    @Column(name = "INVOICE_AMOUNT")
    private BigDecimal invoiceAmount;

    @Column(name = "INVOICE_DATE")
    private Date invoiceDate;

    @Column(name = "STATUS")
    private String status;

    // Getters and setters
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    // ... other getters/setters
}
```

### Step 2: Create Repository (Optional)

```java
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    List<Invoice> findByStatus(String status);
}
```

### Step 3: Add Interface Config

```json
{
  "INVOICE_INTERFACE": {
    "name": "INVOICE_INTERFACE",
    "dataSourceQuery": "SELECT i FROM Invoice i WHERE i.status = 'ACTIVE'",
    "chunkSize": 1000,
    "outputFormat": "XML",
    "outputFileExtension": "xml",
    "enabled": true
  }
}
```

### Step 4: Deploy

No code changes needed for the framework! The `DynamicItemReader` will automatically:
- Execute your JPQL query
- Detect column names and types
- Generate output in your configured format

---

## Adding New Interfaces

### Option 1: Generic Only (5 minutes)

1. Add entry to `interface-config.json`:
```json
{
  "PRODUCT_INTERFACE": {
    "name": "PRODUCT_INTERFACE",
    "dataSourceQuery": "SELECT p FROM Product p WHERE p.active = true",
    "chunkSize": 1000,
    "outputFormat": "XML",
    "outputFileExtension": "xml",
    "enabled": true
  }
}
```

1. Deploy updated config - no code changes needed!
2. Generic components handle everything dynamically

### Option 2: Generic + Specialized (for complex cases)

1. Add config entry (as above)
2. Create `ProductBatchConfig` extending from `OrderBatchConfig` pattern
3. Create `ProductItemReader`, `ProductItemProcessor`, `ProductItemWriter`
4. Update `BatchJobLauncher.selectJobByInterfaceType()` to route to new job
5. Recompile and deploy

## Performance Considerations

### Chunk Size
- Larger chunks = faster processing but more memory: Try 5000 for large files
- Smaller chunks = more database calls but less memory: Use 100 for memory-constrained systems
- Default 1000 balances both

### JPA Entity Graph
- Generic approach: Queries executed as-is from config
- Specialized approach: Use JOIN FETCH to avoid N+1 queries
- Example: `LEFT JOIN FETCH o.lineItems` (already in ORDER config)

### Database Connection Pool
```properties
spring.datasource.initial-size=10
spring.datasource.max-active=30
spring.datasource.max-idle=15
```

### Memory
- Generic: Minimal overhead (LinkedHashMap per record)
- Specialized: Higher overhead (full entity objects in memory)
- Tune JVM: `-Xms512m -Xmx2048m`

## XML Output Examples

### Generic Output (Dynamic Columns)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<order xmlns="http://www.example.com/order">
  <records>
    <order>
      <order_id>ORD001</order_id>
      <order_number>ON-2024-001</order_number>
      <order_amount>1500.00</order_amount>
      <customer_id>CUST123</customer_id>
      <customer_name>Acme Corp</customer_name>
      <status>ACTIVE</status>
    </order>
    <order>
      <order_id>ORD002</order_id>
      <order_number>ON-2024-002</order_number>
      ...
    </order>
  </records>
  <totalRecords>2</totalRecords>
</order>
```

### Specialized Output (Hierarchical)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<orderInterface>
  <orders>
    <order>
      <orderId>ORD001</orderId>
      <orderNumber>ON-2024-001</orderNumber>
      <orderAmount>1500.00</orderAmount>
      <customerName>Acme Corp</customerName>
      <lineItems>
        <lineItem>
          <lineItemId>LI001</lineItemId>
          <productId>PROD-100</productId>
          <quantity>10</quantity>
          <unitPrice>100.00</unitPrice>
        </lineItem>
        <lineItem>
          <lineItemId>LI002</lineItemId>
          <productId>PROD-200</productId>
          <quantity>5</quantity>
          <unitPrice>100.00</unitPrice>
        </lineItem>
      </lineItems>
    </order>
  </orders>
  <totalRecords>1</totalRecords>
</orderInterface>
```

## API Examples

**Note**: Output files are written to the directory specified in `application.properties` (`file.generation.output-directory`). Filenames are auto-generated using the format `{interfaceType}_{timestamp}.{extension}` to ensure uniqueness and consistency. The request only requires `interfaceType`. Neither the file path nor filename are configurable per request for security and consistency.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-token" \
  -d '{
    "interfaceType": "ORDER_INTERFACE"
  }'
```

### Response (Queued)

```json
{
  "jobId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "PENDING",
  "fileName": "orders_export.xml",
  "interfaceType": "ORDER_INTERFACE",
  "message": "File generation job queued successfully"
}
```

### Status Check

```bash
curl http://localhost:8080/api/v1/file-generation/status/a1b2c3d4-e5f6-7890-abcd-ef1234567890 \
  -H "X-DB-Token: your-token"
```

### Status Response

```json
{
  "jobId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "fileName": "orders_export.xml",
  "interfaceType": "ORDER_INTERFACE",
  "recordCount": 1250,
  "message": "File generation completed successfully"
}
```

## Error Handling

### Configuration Not Found
- Returns 400 BAD_REQUEST
- Message: "Interface type '...' not configured"
- Check interface-config.json

### Query Execution Error
- Status = FAILED
- Error message captured in FILE_GENERATION.ERROR_MESSAGE
- Check logs for SQL/JPQL syntax errors

### Record Processing Error
- Item is skipped gracefully
- Processing continues with next records
- Error count logged

### File Write Error
- Job fails, status = FAILED
- Part file is cleaned up
- Error message in database

## Monitoring

### Database Queries

```sql
-- Pending jobs
SELECT * FROM FILE_GENERATION WHERE STATUS = 'PENDING';

-- Failed jobs with errors
SELECT JOB_ID, INTERFACE_TYPE, ERROR_MESSAGE FROM FILE_GENERATION
WHERE STATUS = 'FAILED'
ORDER BY CREATED_DATE DESC;

-- Statistics by interface
SELECT INTERFACE_TYPE, COUNT(*) AS TOTAL,
       SUM(CASE WHEN STATUS = 'COMPLETED' THEN 1 ELSE 0 END) AS COMPLETED,
       SUM(CASE WHEN STATUS = 'FAILED' THEN 1 ELSE 0 END) AS FAILED
FROM FILE_GENERATION
GROUP BY INTERFACE_TYPE;
```

### Log Files
```bash
tail -f logs/cbs-file-generator.log
grep "DynamicItemReader\|DynamicItemWriter" logs/cbs-file-generator.log
```

## Future Enhancements

- **Partitioned Steps**: Parallel processing for very large files
- **Remote Chunking**: Distributed processing across multiple nodes
- **Change Data Capture**: Incremental file generation
- **Format Support**: Add CSV, JSON output generators
- **Streaming XML**: Reduce memory footprint for huge datasets
- **REST Hooks**: Webhook notifications on completion
