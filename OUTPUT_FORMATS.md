# Output Format Configuration Guide

## Overview

CBS File Generator supports **multiple output formats**:

**Default Format**:
- ✅ **XML** - Automatically generated if no BeanIO mapping is specified

**Custom Formats** (via BeanIO mapping files):
- ✅ CSV (comma-separated values)
- ✅ Delimited (pipe, tab, custom separators)
- ✅ Fixed-Length text files
- ✅ Custom formats

**Note**: If `beanioMappingFile` is NOT specified in interface-config.json, output defaults to XML regardless of `outputFormat` value.

## Quick Start

### Option 1: Generic XML (No Config Needed)

**Use when**: You want automatic XML generation without mapping files

**Configuration** (`interface-config.json`):
```json
{
  "CUSTOMER_INTERFACE": {
    "name": "CUSTOMER_INTERFACE",
    "dataSourceQuery": "SELECT c FROM Customer c WHERE c.active = true",
    "chunkSize": 1000,
    "outputFormat": "XML",
    "outputFileExtension": "xml",
    "enabled": true
  }
}
```

**Note**: `dataSourceQuery` must be a **JPQL query** (uses JPA entity classes like `Customer`, not raw SQL table names). Native SQL is not supported.

**Result**: Automatically generates XML without mapping file
```xml
<?xml version="1.0" encoding="UTF-8"?>
<customer xmlns="http://www.example.com/customer">
  <records>
    <customer>
      <customer_id>CUST001</customer_id>
      <customer_name>Acme Corp</customer_name>
      <email>contact@acme.com</email>
      ...
    </customer>
  </records>
  <totalRecords>100</totalRecords>
</customer>
```

**Components Used**:
- `DynamicItemWriter` (automatic XML generation)

---

### Option 2: BeanIO with Custom Format

**Use when**: You need specific output format (CSV, Fixed-Length, etc.)

**Step 1**: Add mapping file reference to `interface-config.json`:
```json
{
  "CUSTOMER_INTERFACE": {
    "name": "CUSTOMER_INTERFACE",
    "dataSourceQuery": "SELECT c FROM Customer c WHERE c.active = true",
    "beanioMappingFile": "customer-mapping.xml",
    "chunkSize": 1000,
    "outputFormat": "CSV",
    "outputFileExtension": "csv",
    "enabled": true
  }
}
```

**Step 2**: Define format in mapping file (`src/main/resources/beanio/customer-mapping.xml`):
```xml
<stream name="customer_interfaceStream-csv" format="delimited" delimiter=",">
  <record name="customer" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
    <field name="customer_id" type="string" position="1"/>
    <field name="customer_name" type="string" position="2"/>
    <field name="email" type="string" position="3"/>
    <field name="phone" type="string" position="4"/>
  </record>
</stream>
```

**Components Used**:
- `DynamicItemWriter` → `BeanIOFormatWriter` → BeanIO StreamFactory

---

## Format Examples

### XML Format

**Mapping** (`customer-mapping.xml`):
```xml
<stream name="customer_interfaceStream" format="xml">
  <record name="customer" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
    <field name="customer_id" type="string"/>
    <field name="customer_name" type="string"/>
    <field name="email" type="string"/>
  </record>
</stream>
```

**Output**:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<customer>
  <customer>
    <customer_id>CUST001</customer_id>
    <customer_name>Acme Corp</customer_name>
    <email>contact@acme.com</email>
  </customer>
  <customer>
    <customer_id>CUST002</customer_id>
    <customer_name>Beta Inc</customer_name>
    <email>info@beta.com</email>
  </customer>
</customer>
```

---

### CSV Format

**Mapping**:
```xml
<stream name="customer_interfaceStream-csv" format="delimited" delimiter=",">
  <record name="customer" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
    <field name="customer_id" type="string" position="1"/>
    <field name="customer_name" type="string" position="2"/>
    <field name="email" type="string" position="3"/>
  </record>
</stream>
```

**Output**:
```
CUST001,Acme Corp,contact@acme.com
CUST002,Beta Inc,info@beta.com
CUST003,Charlie Ltd,hello@charlie.org
```

---

### Pipe-Delimited Format

**Mapping**:
```xml
<stream name="customer_interfaceStream-pipe" format="delimited" delimiter="|">
  <record name="customer" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
    <field name="customer_id" type="string" position="1"/>
    <field name="customer_name" type="string" position="2"/>
    <field name="email" type="string" position="3"/>
  </record>
</stream>
```

**Output**:
```
CUST001|Acme Corp|contact@acme.com
CUST002|Beta Inc|info@beta.com
CUST003|Charlie Ltd|hello@charlie.org
```

---

### Fixed-Length Format

**Mapping**:
```xml
<stream name="customer_interfaceStream-fixed" format="fixedlength">
  <record name="customer" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
    <field name="customer_id" type="string" position="1" length="10"/>
    <field name="customer_name" type="string" position="11" length="30" align="left" padding=" "/>
    <field name="email" type="string" position="41" length="50" align="left" padding=" "/>
  </record>
</stream>
```

**Output** (fixed column widths):
```
CUST001   Acme Corp                 contact@acme.com
CUST002   Beta Inc                  info@beta.com
CUST003   Charlie Ltd               hello@charlie.org
```

---

## BeanIO Stream Names

**Important**: Stream name must match interface type pattern: `{interfacetype_lowercase}Stream`

| Interface Type       | Stream Name                |
|----------------------|----------------------------|
| `CUSTOMER_INTERFACE` | `customer_interfaceStream` |
| `ORDER_INTERFACE`    | `order_interfaceStream`    |
| `INVOICE_INTERFACE`  | `invoice_interfaceStream`  |
| `PRODUCT_INTERFACE`  | `product_interfaceStream`  |

Example for ORDER_INTERFACE:
```xml
<stream name="order_interfaceStream" format="xml">
  <!-- record definitions -->
</stream>
```

---

## Field Type Support

BeanIO supports these field types:

| Type      | Description      | Example             |
|-----------|------------------|---------------------|
| `string`  | Text value       | "John Doe"          |
| `int`     | Integer          | 42                  |
| `long`    | Long integer     | 9223372036854775807 |
| `double`  | Floating point   | 3.14159             |
| `decimal` | Big decimal      | 1234.56             |
| `date`    | Date with format | format="yyyy-MM-dd" |
| `boolean` | True/false       | true                |

---

## Advanced: Date Formatting

```xml
<field name="created_date" type="date" format="yyyy-MM-dd"/>
<field name="last_modified" type="date" format="MM/dd/yyyy HH:mm:ss"/>
<field name="timestamp" type="date" format="yyyy-MM-dd'T'HH:mm:ss.SSS"/>
```

---

## Advanced: Decimal Precision

```xml
<field name="price" type="decimal" pattern="#,##0.00"/>
<field name="percentage" type="double" pattern="0.00%"/>
<field name="currency" type="decimal" pattern="$#,##0.00"/>
```

---

## Advanced: Alignment & Padding (Fixed-Length)

```xml
<!-- Left-aligned with space padding -->
<field name="name" type="string" length="20" align="left" padding=" "/>

<!-- Right-aligned with zero padding -->
<field name="number" type="string" length="10" align="right" padding="0"/>

<!-- Centered -->
<field name="title" type="string" length="30" align="center" padding="."/>
```

---

## Adding a New Interface with Custom Format

### Step 1: Add to `interface-config.json`
```json
{
  "PRODUCT_INTERFACE": {
    "name": "PRODUCT_INTERFACE",
    "dataSourceQuery": "SELECT p FROM Product p WHERE p.status = 'ACTIVE'",
    "beanioMappingFile": "product-mapping.xml",
    "chunkSize": 1000,
    "outputFormat": "CSV",
    "outputFileExtension": "csv",
    "enabled": true
  }
}
```

### Step 2: Create mapping file `src/main/resources/beanio/product-mapping.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<beanio xmlns="http://www.beanio.org/2012/03">
  <stream name="product_interfaceStream" format="delimited" delimiter=",">
    <record name="product" class="java.util.LinkedHashMap" minOccurs="0" maxOccurs="unbounded">
      <field name="product_id" type="string" position="1"/>
      <field name="product_name" type="string" position="2"/>
      <field name="price" type="decimal" pattern="0.00" position="3"/>
      <field name="quantity" type="int" position="4"/>
    </record>
  </stream>
</beanio>
```

### Step 3: Deploy
No code changes needed! Just add config and mapping file.

---

## Troubleshooting

### Output is always XML even though I specified outputFormat

- **Cause**: The `outputFormat` field is informational only. Actual format depends on whether `beanioMappingFile` is configured
- **Solution**:
  - To use XML (default): Remove `beanioMappingFile` from config
  - To use custom format: Add `beanioMappingFile` entry with path to mapping file and create the mapping file
  - The `outputFormat` and `outputFileExtension` fields should match your mapping file format

### Error: "beanioMappingFile not configured"
- **Cause**: You referenced BeanIO in interface-config.json but mapping file doesn't exist
- **Solution**: Either create the mapping file or remove the `beanioMappingFile` entry to use generic XML

### Error: "Stream name not found in mapping"
- **Cause**: Stream name in mapping doesn't match interface name pattern
- **Example**: Interface is `CUSTOMER_INTERFACE` but stream is named `customerStream`
- **Solution**: Rename stream to `customer_interfaceStream`

### Data not appearing in output
- **Cause**: Field names in mapping don't match column names in query result
- **Solution**: Check exact column names returned by query and update mapping field names

### Fixed-length output not aligned
- **Cause**: Field positions/lengths overlap or have gaps
- **Solution**: Verify sum of all lengths equals total line length; positions should be continuous

---

## Performance Tips

1. **Chunk Size**: Larger chunks = fewer writes, better performance
   ```json
   "chunkSize": 5000  // Good for CSV, Fixed-Length
   "chunkSize": 1000  // Good for XML (file sizes get large)
   ```

2. **Format Choice**:
   - XML: Slower, larger files, best for structured data
   - CSV: Faster, smaller files, best for analysis
   - Fixed-Length: Fastest, smallest, best for legacy systems

3. **String Escaping**: XML requires more processing due to escaping

---

## Reference: BeanIO Documentation

Full BeanIO documentation: https://beanio.org/

Common attributes:
- `class`: Java class for record mapping (use `java.util.LinkedHashMap` for dynamic data)
- `format`: Output format (xml, delimited, fixedlength)
- `delimiter`: Character separator (for delimited format)
- `position`: Field column number (for delimited/fixed)
- `length`: Field width (for fixed-length format)
- `type`: Field data type (string, int, date, decimal, etc.)
- `pattern`: Number/date formatting pattern
- `align`: Text alignment (left, right, center for fixed-length)
- `padding`: Pad character for fixed-length fields
