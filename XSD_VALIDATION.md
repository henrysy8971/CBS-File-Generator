# XSD Validation Configuration

## Overview
CBS File Generator supports **Post-Generation XSD schema validation**. This ensures that the generated output file adheres to a specific contract before it is marked as `COMPLETED`.

Unlike record-level validation, this process validates the **entire file** after it has been written to the disk.

## Features
- ✅ **Post-Processing Safety:** Validates the final artifact on disk.
- ✅ **Per-Interface Config:** Toggle validation on/off via `interface-config.json`.
- ✅ **Fail-Fast vs. Log-Only:** Configurable via `application.properties`.
- ✅ **Secure:** XXE protection enabled by default.
- ✅ **Efficient:** Uses Stream-based validation (StAX/SAX) to handle large files without memory spikes.

## Configuration

### 1. Add Schema File
Place XSD schema files in the resources directory:
`src/main/resources/xsd/`

### 2. Configure Interface
Update `interface-config.json`:

```json
{
  "interfaces": {
    "ORDER_INTERFACE": {
      "name": "ORDER_INTERFACE",
      "xsdSchemaFile": "order_schema.xsd",
      "outputFormat": "XML",
      "outputFileExtension": "xml",
      "description": "Validation enabled"
    },
    "CUSTOMER_INTERFACE": {
      "name": "CUSTOMER_INTERFACE",
      "xsdSchemaFile": null,
      "description": "Validation disabled"
    }
  }
}
```

### 3. Set Strict Mode (Global)
In `application.properties`:

```properties
# true  = Job marked FAILED if XML is invalid
# false = Job marked COMPLETED even if XML is invalid (Logs Warning)
validation.xsd.strict-mode=false
```

---

## How It Works (The Lifecycle)

Validation is implemented as a **Spring Batch Tasklet** that runs only after the file generation step succeeds.

**Flow:**
1.  **Step 1 (Generation):** Reader -> Processor -> Writer generate the `.part` file.
2.  **Transition:** If Step 1 is `COMPLETED`, the Job moves to `dynamicValidationStep`.
3.  **Step 2 (Validation):** `FileValidationTasklet` executes:
   *   Locates the `.part` file using the Job Context.
   *   Loads the configured XSD schema.
   *   Streams the file from disk through the `XsdValidator`.
4.  **Outcome:**
   *   **Valid:** Job Status = `COMPLETED`.
   *   **Invalid (Strict):** Job Status = `FAILED`.
   *   **Invalid (Lenient):** Logs error, Job Status = `COMPLETED`.

---

## Implementation Details

### The Validator (`XsdValidator.java`)
This component wraps Java's `javax.xml.validation` API. It caches loaded schemas for performance and ensures thread safety.

### The Tasklet (`FileValidationTasklet.java`)
This acts as the bridge between the Batch Job and the Validator.

```java
// Logic inside FileValidationTasklet.execute()
File file = new File(partFilePath);
String xsdSchema = interfaceConfig.getXsdSchemaFile();

// Validate the full file on disk
boolean isValid = xsdValidator.validateFullFile(file, xsdSchema);

if (!isValid) {
    // If strict-mode is true, validateFullFile returns false, 
    // and we throw exception to fail the job.
    throw new ValidationException("XSD validation failed...");
}
```

---

## Validation Modes Behavior

| Scenario            | `strict-mode=true`                                | `strict-mode=false` (Default)                         |
|:--------------------|:--------------------------------------------------|:------------------------------------------------------|
| **Schema Missing**  | **Job Fails**. Error logged.                      | **Job Completes**. Warning logged.                    |
| **Invalid XML Tag** | **Job Fails**. Error logged with Line/Col number. | **Job Completes**. Error logged with Line/Col number. |
| **Valid XML**       | **Job Completes**.                                | **Job Completes**.                                    |

**Use Cases:**
*   **Strict Mode:** Use in UAT/QA to ensure developers catch structure errors.
*   **Lenient Mode:** Use in Production if you want to ensure the file is delivered even if minor validation issues exist (monitoring logs should be alerted).

---

## Troubleshooting

### "Schema not found"
*   **Cause:** The filename in `interface-config.json` does not exactly match the file in `src/main/resources/xsd/`.
*   **Fix:** Check casing (`Order.xsd` vs `order.xsd`) and ensure `mvn clean install` copied the resources.

### "Validation failed: File not readable"
*   **Cause:** The OS permissions on the output directory prevent reading.
*   **Fix:** Ensure the user running the JAR has Read/Write access to `file.generation.output-directory`.

### "cvc-elt.1: Cannot find the declaration of element"
*   **Cause:** The namespace in your generated XML (from `GenericXMLWriter`) does not match the `targetNamespace` in your XSD.
*   **Fix:** Ensure `InterfaceConfig.namespace` matches your XSD definition.

---

## Example Schema (`order_schema.xsd`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <xs:element name="orderInterface">
        <xs:complexType>
            <xs:sequence>
                <xs:element name="records">
                    <xs:complexType>
                        <xs:sequence>
                            <xs:element name="orderItem" maxOccurs="unbounded">
                                <xs:complexType>
                                    <xs:sequence>
                                        <xs:element name="orderId" type="xs:string"/>
                                        <xs:element name="amount" type="xs:decimal"/>
                                    </xs:sequence>
                                </xs:complexType>
                            </xs:element>
                        </xs:sequence>
                    </xs:complexType>
                </xs:element>
                <xs:element name="totalRecords" type="xs:integer"/>
            </xs:sequence>
        </xs:complexType>
    </xs:element>
</xs:schema>
```
```