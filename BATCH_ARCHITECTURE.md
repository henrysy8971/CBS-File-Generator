# Dynamic Batch Architecture

## Overview

CBS File Generator uses a **flexible dual-architecture approach**:

1. **Generic Dynamic Processing** (Default): Single configurable batch job that handles any interface type using **Native SQL** and dynamic schema detection.
2. **Specialized Processing** (Optional): Custom batch jobs for specific interface types requiring hierarchical handling (e.g., `ORDER_INTERFACE` with JPA Entity relationships).

---

## Architecture Components

### 1. Generic Dynamic Architecture (Default)

#### DynamicRecord
Generic data container holding:
- Interface type identifier
- Columns with names and values
- Column data types (mapped from SQL types)
- **No predefined Java schema/Entity required**

#### DynamicItemReader
- Executes **Native SQL** queries from `interface-config.json`.
- Uses `entityManager.createNativeQuery()` returning `Tuple` objects.
- Automatically detects column names and types from the first result row.
- **Keyset Pagination**: Uses `WHERE KEY > :lastId` for high-performance paging on large tables.

#### DynamicItemProcessor
- Generic validation: ensures record has columns.
- String trimming and basic transformations.
- Error handling with graceful skipping (metrics updated in `StepContext`).

#### DynamicItemWriter
- Generates XML (via StAX) or Flat Files (via BeanIO) based on config.
- **Auto-XML**: Dynamically creates elements from column aliases (e.g., `TOTAL_AMT` → `<total_amt>`).
- **Restart-Safe**: Detects existing `.part` files and appends data instead of overwriting.

#### DynamicBatchConfig
- Configures `dynamicFileGenerationJob`.
- Uses `@StepScope` to ensure thread safety for concurrent jobs.
- Wires the generic Reader, Processor, and Writer.

#### DynamicJobExecutionListener
- **Atomic Finalization**: Renames `.part` to final filename safely.
- **Integrity**: Generates SHA-256 checksums.
- **Status Sync**: Updates `IF_FILE_GENERATION` table to `COMPLETED` or `FAILED`.

---

### 2. Specialized Architecture (Optional)

#### OrderBatchConfig
- Custom Spring Configuration for `ORDER_INTERFACE`.
- Defines specialized beans (`orderItemReader`, `orderItemWriter`) that override the generic ones.

#### OrderItemReader (JPA)
- Uses **JPQL** and **JPA Entities** (`Order.java`).
- Implements **Two-Step Fetching** (Fetch IDs -> Fetch Graph) to handle One-to-Many relationships (`Order -> LineItems`) efficiently.

#### OrderItemWriter
- Generates hierarchical XML (Nested elements) which the generic flat writer cannot handle.

---

## Routing Logic

The `BatchJobLauncher` uses a **Convention-over-Configuration** lookup:

```java
public Job selectJobByInterfaceType(String interfaceType) {
    // 1. Check if a Spring Bean exists with the exact name (e.g., "ORDER_INTERFACE")
    if (allJobs.containsKey(interfaceType.toUpperCase())) {
        return allJobs.get(interfaceType.toUpperCase());
    }
    // 2. Fallback to generic dynamic engine
    return defaultJob; 
}
```

---

## Configuration (`interface-config.json`)

```json
{
  "interfaces": {
    "INVOICE_INTERFACE": {
      "name": "INVOICE_INTERFACE",
      "description": "Dynamic Job (Native SQL)",
      "dataSourceQuery": "SELECT INVOICE_ID, AMOUNT, STATUS FROM INVOICES WHERE STATUS = 'ACTIVE'",
      "keySetColumn": "INVOICE_ID",
      "beanioMappingFile": "invoice_mapping.xml",
      "haveHeaders": false,
      "outputFormat": "CSV",
      "outputFileExtension": "csv",
      "dynamic": true,
      "enabled": true
    },
    "ORDER_INTERFACE": {
      "name": "ORDER_INTERFACE",
      "description": "Specialized Job (Custom Logic)",
      "dataSourceQuery": "IGNORED_BY_CUSTOM_BEAN",
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "enabled": true
    }
  }
}
```

**Key Configuration Fields**:
*   `dataSourceQuery`:
    *   For **Dynamic Jobs**: Must be **Native SQL** (Table Names). Must include `:lastId` parameter if using keyset paging.
    *   For **Specialized Jobs**: Usually ignored (logic is in Java code), but a placeholder is required.
*   `keySetColumn`: Required for Dynamic Jobs to handle pagination logic.
*   `beanioMappingFile`: If present, uses BeanIO (CSV/Fixed). If absent, defaults to Generic XML.

---

## Query Language Guide: Dynamic vs. Specialized

It is critical to use the correct query language for the architecture you are targeting.

| Feature | Generic Dynamic Job | Specialized Custom Job |
|:---|:---|:---|
| **Query Language** | **Native SQL** | **JPQL** |
| **Refers To** | Database Tables (`INVOICES`) | Java Entities (`Order`) |
| **Selects** | Columns (`INVOICE_ID`) | Objects (`i`) |
| **Java Code** | None required | Entity, DTO, Repository required |
| **Relationships** | Flat (Joins flatten data) | Hierarchical (Parent/Child) |

**Dynamic Example (Correct):**
`SELECT * FROM CUSTOMERS WHERE ACTIVE = 1`

**Specialized Example (Correct):**
`SELECT c FROM Customer c JOIN FETCH c.orders`

---

## How to Add a New Interface

### Scenario A: Flat Data (The Easy Way)
*Goal: Dump the `PRODUCTS` table to XML.*

1.  **Do NOT** create any Java classes.
2.  **Edit** `interface-config.json`:
    ```json
    "PRODUCT_INTERFACE": {
      "dataSourceQuery": "SELECT PRODUCT_ID, NAME, PRICE FROM PRODUCTS",
      "keySetColumn": "PRODUCT_ID",
      "outputFormat": "XML",
      "outputFileExtension": "xml"
    }
    ```
3.  **Restart/Reload** Config.
4.  **Run**: `POST /generate` with `PRODUCT_INTERFACE`.

### Scenario B: Complex/Nested Data (The Hard Way)
*Goal: Generate complex XML for `TRANSACTIONS` with child `ENTRIES`.*

1.  Create **JPA Entities** (`Transaction.java`, `Entry.java`).
2.  Create **Batch Config** (`TransactionBatchConfig.java`) defining a Job bean named `"TRANSACTION_INTERFACE"`.
3.  Implement Custom **Reader/Writer**.
4.  Add entry to `interface-config.json` (mainly for file extension and enabling/disabling).

---

## Performance Tuning

### Chunk Size
*   **XML Generation**: Keep `chunkSize` around **1000**. XML is verbose and memory-heavy; large chunks causes OutOfMemory errors.
*   **CSV/Flat File**: Can increase `chunkSize` to **5000+** for speed.

### Fetch Size
*   The `DynamicItemReader` automatically sets the JDBC Fetch Size to match the Chunk Size. This ensures optimal network traffic between the App and Database (1 round trip per chunk).

### Keyset Pagination
*   For Dynamic Jobs, **always** define `keySetColumn`.
*   Without it, the reader might default to "Offset Pagination" (if implemented) or load all data, which is incredibly slow for large tables.
*   Ensure the column used for keyset is **Indexed** in the database.
```