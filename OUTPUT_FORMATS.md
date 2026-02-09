# Output Format Configuration Guide

## Overview
CBS File Generator supports **multiple output formats**:

**Default Format**:
- ✅ **XML** - Automatically generated using StAX (Stream-based) if no mapping is provided.

**Custom Formats** (via BeanIO):
- ✅ CSV, Pipe-Delimited, Fixed-Length, JSON (if supported by BeanIO version).

---

## Quick Start

### Option 1: Generic XML (Dynamic)
**Use when:** You want a quick dump of a database query without writing any Java code or mapping files.

**Configuration** (`interface-config.json`):
```json
{
  "CUSTOMER_INTERFACE": {
    "name": "CUSTOMER_INTERFACE",
    "dataSourceQuery": "SELECT CUSTOMER_ID, CUSTOMER_NAME, EMAIL FROM CUSTOMERS WHERE ACTIVE = 1",
    "keySetColumn": "CUSTOMER_ID",
    "outputFormat": "XML",
    "outputFileExtension": "xml",
    "enabled": true
  }
}
```

**⚠️ Important SQL Rule:**
For Dynamic Jobs, `dataSourceQuery` must be **Native SQL** (table names, not entity names).
*   **Correct:** `SELECT * FROM CUSTOMERS`
*   **Incorrect:** `SELECT c FROM Customer c` (This is for Custom Java Jobs only)

---

### Option 2: BeanIO (CSV / Fixed-Length)
**Use when:** You need a specific format layout.

**Step 1: Update Config**
```json
{
  "CUSTOMER_INTERFACE": {
    "name": "CUSTOMER_INTERFACE",
    "dataSourceQuery": "SELECT CUSTOMER_ID, NAME, EMAIL FROM CUSTOMERS",
    "keySetColumn": "CUSTOMER_ID",
    "beanioMappingFile": "customer-mapping.xml",
    "streamName": "customer_csv_stream",
    "outputFormat": "CSV",
    "outputFileExtension": "csv",
    "enabled": true
  }
}
```

**Step 2: Create Mapping File**
File: `src/main/resources/beanio/customer-mapping.xml`

```xml
<beanio xmlns="http://www.beanio.org/2012/03">
  <!-- 'name' must match the 'streamName' defined in JSON above -->
  <stream name="customer_csv_stream" format="delimited" delimiter=",">
    
    <!-- Use java.util.Map to handle DynamicRecord objects -->
    <record name="customer" class="java.util.Map" minOccurs="0" maxOccurs="unbounded">
      <!-- 'name' must match the column alias from your SQL query (Case Insensitive) -->
      <field name="CUSTOMER_ID" type="string" />
      <field name="NAME" type="string" />
      <field name="EMAIL" type="string" />
    </record>
    
  </stream>
</beanio>
```

---

## Format Reference

### 1. XML (Generic)
No mapping file needed. The system generates XML tags based on SQL column aliases.
*   **Root Element:** Derived from Interface Name (e.g., `ORDER_INTERFACE` -> `<order>`) or configurable via `rootElement` in JSON.
*   **Item Element:** `{rootElement}Item`

### 2. CSV / Delimited
Requires `beanioMappingFile`.
```xml
<stream name="my_stream" format="delimited" delimiter="|">
    <!-- definitions -->
</stream>
```

### 3. Fixed Length
Requires `beanioMappingFile`.
```xml
<stream name="my_stream" format="fixedlength">
    <record name="data" class="java.util.Map">
        <field name="ID" length="10" align="right" padding="0" />
        <field name="NAME" length="50" align="left" padding=" " />
    </record>
</stream>
```

---

## Troubleshooting Common Errors

### "BeanIO configuration not found"
*   **Cause:** The file referenced in `beanioMappingFile` does not exist in `src/main/resources/beanio/`.
*   **Fix:** Check the filename spelling and build the project (`mvn clean install`) to ensure resources are copied.

### "Stream name not found"
*   **Cause:** The `<stream name="...">` inside your XML does not match the `streamName` field in `interface-config.json`.
*   **Fix:** Ensure they match exactly.

### "Invalid Column Mapping" (Empty fields in output)
*   **Cause:** The `<field name="...">` in BeanIO does not match the SQL Column Alias.
*   **Fix:** Run the SQL query manually. If it returns `CUST_ID`, your BeanIO field must be named `CUST_ID`.

### "Infinite Loop" / "Job Stuck"
*   **Cause:** You configured `keySetColumn` but forgot to add the pagination clause to your SQL.
*   **Fix:** Update SQL to include: `AND (:lastId IS NULL OR COLUMN_NAME > :lastId) ORDER BY COLUMN_NAME`.

---

## Advanced Configuration

### Date Formatting
In BeanIO XML:
```xml
<field name="DOB" type="date" format="yyyy-MM-dd" />
```

### Number Formatting
In BeanIO XML:
```xml
<field name="AMOUNT" type="decimal" format="#0.00" />
```

### Performance Tuning
In `interface-config.json` (or `application.properties` globally):
*   `chunkSize`: Set to **5000** for flat files (CSV/Fixed) for better speed.
*   `chunkSize`: Set to **1000** for XML (to manage memory usage).
```