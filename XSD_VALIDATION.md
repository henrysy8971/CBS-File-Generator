# XSD Validation Configuration

## Overview

CBS File Generator supports **optional XSD schema validation** for output data. When configured, records are validated against XSD schemas before being written to output files.

## Features

- ✅ Per-interface validation (configure in interface-config.json)
- ✅ Optional validation (skip if schema not configured or not found)
- ✅ Strict/Lenient modes (fail job or skip invalid records)
- ✅ Schema caching (avoid repeated file I/O)
- ✅ Clear separation between validation and output formatting

## Configuration

### 1. Add Schema File to Project

Place XSD schema files in `src/main/resources/xsd/`:
```
src/main/resources/xsd/
├── order_schema.xsd       # Order interface schema
├── customer_schema.xsd    # Customer interface schema
└── product_schema.xsd     # Product interface schema
```

### 2. Configure Interface in interface-config.json

Add `xsdSchemaFile` to interface configuration:

```json
{
  "interfaces": {
    "ORDER_INTERFACE": {
      "name": "ORDER_INTERFACE",
      "dataSourceQuery": "SELECT o FROM Order o",
      "beanioMappingFile": "customer-mapping.xml",
      "xsdSchemaFile": "order_schema.xsd",
      "chunkSize": 1000,
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "enabled": true
    }
  }
}
```

**Fields**:
- `xsdSchemaFile` (optional): File name of XSD schema in `xsd/` folder
- If omitted: Validation is skipped (default behavior)

### 3. Set Validation Mode (Optional)

In `application.properties`:
```properties
# Validation mode:
# true = strict mode: fail job if validation fails
# false = lenient mode: skip invalid records and continue (default)
validation.xsd.strict-mode=false
```

## How It Works

### Validation Flow

1. **DynamicItemProcessor** (or OrderItemProcessor):
   - Checks if `xsdSchemaFile` is configured in interface-config.json
   - On `@BeforeStep`: Loads schema file name from config
   - Checks if schema file exists on classpath (`xsd/` folder)

2. **Record Processing**:
   - Each record is transformed to minimal XML representation
   - XML is validated against schema using XsdValidator
   - Invalid records are skipped (lenient) or fail job (strict)

3. **XsdValidator**:
   - Caches loaded schemas to improve performance
   - Supports multiple schema files simultaneously
   - Returns validation result, not exception

### Example: ORDER_INTERFACE

**interface-config.json**:
```json
{
  "ORDER_INTERFACE": {
    "xsdSchemaFile": "order_schema.xsd",
    ...
  }
}
```

**Processing Logic**:
```
1. OrderItemProcessor.beforeStep()
   └─ Load xsdSchemaFile = "order_schema.xsd"
   
2. For each order:
   a) Validate order structure (required fields)
   b) Validate line items
   c) Apply transformations
   d) Convert to XML: <order><orderId>...</orderId>...
   e) Validate XML against order_schema.xsd
      ├─ Valid → Write to output
      └─ Invalid → Skip (or fail if strict mode)
```

## XSD Schema Examples

### Simple Order Schema

File: `src/main/resources/xsd/order_schema.xsd`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
  
  <xs:element name="order">
    <xs:complexType>
      <xs:sequence>
        <xs:element name="orderId" type="xs:string"/>
        <xs:element name="orderNumber" type="xs:string"/>
        <xs:element name="orderAmount" type="xs:decimal" minOccurs="0"/>
        <xs:element name="customerName" type="xs:string" minOccurs="0"/>
        <xs:element name="lineItems" minOccurs="0">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="lineItem" maxOccurs="unbounded">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="lineItemId" type="xs:string"/>
                    <xs:element name="productId" type="xs:string"/>
                    <xs:element name="quantity" type="xs:integer"/>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

</xs:schema>
```

### Customer Schema (Strict)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
  
  <xs:element name="customer">
    <xs:complexType>
      <xs:sequence>
        <xs:element name="customerId" type="xs:string"/>
        <xs:element name="customerName" type="xs:string"/>
        <xs:element name="email" type="xs:string"/>
        <xs:element name="phone" type="xs:string"/>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

</xs:schema>
```

## Validation Modes

### Lenient Mode (Default)

```properties
validation.xsd.strict-mode=false
```

**Behavior**:
- Schema not found → Log warning, continue
- Validation fails → Log warning, skip record
- Processing continues for remaining records

**Use case**: Production systems where a few invalid records are acceptable

### Strict Mode

```properties
validation.xsd.strict-mode=false
```

**Behavior**:
- Schema not found → Job fails immediately
- Validation fails → Record is skipped but job continues
- Job still completes to allow processing of other records

**Use case**: Development/QA where data quality is critical

## Validation Without Schema

If `xsdSchemaFile` is NOT configured:
```json
{
  "ORDER_INTERFACE": {
    // No xsdSchemaFile specified
    "dataSourceQuery": "...",
    ...
  }
}
```

**Result**: Validation is skipped entirely, records are processed as-is

## Integration with Processors

### FileGenerationItemProcessor

```java
// In FileGenerationItemProcessor.process()
// Validate item against XSD schema if XML content exists
if (item.getValue() != null && !item.getValue().isEmpty()) {
    if (!xsdValidator.validateRecord(item.getValue(), null)) {
        logger.warn("Item validation failed: {}", item.getId());
        return null; // Skip invalid items
    }
}
```

**Note**: The current implementation validates plain XML content from the item value. For more complex scenarios with DynamicRecord or OrderDto objects, implement custom validation in specialized processors.

### OrderItemProcessor

```java
// Same pattern as DynamicItemProcessor
// Validates Order XML against order_schema.xsd if configured
```

## Performance Considerations

1. **Schema Caching**: Schemas are cached after first load to improve performance

2. **Validation Overhead**: XML generation and validation adds ~5-10ms per record
   - Not significant for batch processing
   - Can be disabled by omitting `xsdSchemaFile`

3. **Memory**: Schema cache holds compiled Schema objects (typically <1MB per schema)

## Troubleshooting

### Schema Not Found

**Error**: `Schema file not found on classpath: xsd/order_schema.xsd`

**Solution**: 
- Ensure file exists in `src/main/resources/xsd/`
- Check exact filename matches in interface-config.json
- Rebuild project to include resources

### Validation Always Fails

**Check**:
1. Schema syntax is valid (use XSD validator online tool)
2. Element names in processor match schema definition
3. Check logs for specific validation errors

**Debug**: Set log level to DEBUG:
```properties
logging.level.com.silverlakesymmetri.cbs.fileGenerator.validation=DEBUG
```

### Performance Degradation

**If validation is slow**:
1. Check schema complexity (too many constraints?)
2. Consider lenient mode to skip invalid records faster
3. Increase chunk size to reduce validation calls

## Best Practices

1. **Make schemas lenient**: Allow optional fields, don't over-constrain
2. **Version schemas**: Name them `order_v1.xsd`, `order_v2.xsd` if schema evolves
3. **Document constraints**: Comment in schema why fields are required/optional
4. **Test schemas**: Validate test data before deploying
5. **Use lenient mode in production**: Let batch jobs complete even if some records are invalid

## Examples

### Example 1: Enable XSD Validation

interface-config.json:
```json
{
  "ORDER_INTERFACE": {
    "name": "ORDER_INTERFACE",
    "xsdSchemaFile": "order_schema.xsd",
    ...
  }
}
```

Result: Each order is validated against order_schema.xsd

### Example 2: Disable XSD Validation

interface-config.json:
```json
{
  "ORDER_INTERFACE": {
    "name": "ORDER_INTERFACE",
    // No xsdSchemaFile
    ...
  }
}
```

Result: Orders are not validated, all records written to output

### Example 3: Add New Interface with Validation

1. Create schema: `src/main/resources/xsd/product_schema.xsd`
2. Add to config:
   ```json
   {
     "PRODUCT_INTERFACE": {
       "name": "PRODUCT_INTERFACE",
       "xsdSchemaFile": "product_schema.xsd",
       ...
     }
   }
   ```
3. Deploy - validation is automatic!
