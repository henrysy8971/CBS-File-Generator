# Interface Setup Guide

## Quick Answer

**Do I need to write Java code?**

*   **NO**, if the data is flat (rows/columns) and requires no complex formatting.
   *   *Solution:* Use **Dynamic Configuration**. Just write SQL in `interface-config.json`.
*   **YES**, if you have nested data (Parent-Child), complex formatting, or specific business logic.
   *   *Solution:* Use **Custom Implementation**. Create Entities, DTOs, and specific Batch Configs.

---

## Decision Tree

```
Start: Adding new interface (e.g., INVOICE_INTERFACE)
│
├─ Is the data simple/flat (like a spreadsheet)?
│  └─ Yes: 
│       1. Write SQL query (e.g., "SELECT * FROM INVOICES")
│       2. Add entry to interface-config.json
│       3. DONE. (No Java classes needed)
│
└─ No: Does it have nested relationships or complex logic?
   ├─ Nested objects (Order → LineItems)? → Needs Custom Logic
   ├─ Complex Date/Number formatting? → Needs Custom Logic
   └─ Result:
      → Create JPA Entity (Java)
      → Create DTO (Java)
      → Create Custom Batch Config (Java)
      → Example: ORDER_INTERFACE
```

---

## Path 1: The Dynamic Way (No Java Code)

**Scenario**: You want to dump a database table or view to a CSV/XML file.

**Steps**:
1.  Open `src/main/resources/interface-config.json`.
2.  Add your interface definition.
3.  Use **Native SQL** (not JPQL).

**Configuration Example**:
```json
{
  "CUSTOMER_INTERFACE": {
    "name": "CUSTOMER_INTERFACE",
    "dataSourceQuery": "SELECT CUSTOMER_ID, CUSTOMER_NAME, EMAIL FROM CUSTOMERS WHERE ACTIVE = 1",
    "keySetColumn": "CUSTOMER_ID",
    "outputFormat": "CSV",
    "outputFileExtension": "csv",
    "enabled": true
  }
}
```

**Why no Entity?**
The `DynamicItemReader` inspects the SQL result set at runtime and builds the output automatically. You do not need to recompile the application.

---

## Path 2: The Custom Way (Entities + DTOs)

**Scenario**: Data has complex relationships (One-to-Many) or requires transformation logic that SQL cannot handle easily.

### 1. Create JPA Entity
**Purpose**: Maps the database table to a Java Object.

*File*: `src/main/java/.../entity/Order.java`
```java
@Entity
@Table(name = "ORDERS")
public class Order {
    @Id
    @Column(name = "ORDER_ID")
    private Long orderId;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    private List<LineItem> lineItems;
    
    // ... getters/setters
}
```

### 2. Create DTO
**Purpose**: Defines exactly what the output file should look like.

*File*: `src/main/java/.../dto/OrderDto.java`
```java
public class OrderDto {
    private String id;
    private String formattedDate;
    private List<LineItemDto> items;
}
```

### 3. Create Custom Batch Config
**Purpose**: Tells Spring Batch to use your custom Reader/Writer instead of the dynamic ones.

*File*: `src/main/java/.../batch/custom/OrderBatchConfig.java`

---

## Data Type Mapping (For Reference)

When creating Entities for **Custom Jobs**, use this mapping:

| Oracle SQL  | Java Type       | JPA Annotation                      |
|-------------|-----------------|-------------------------------------|
| VARCHAR2(n) | String          | `@Column(name = "...")`             |
| NUMBER(p,s) | BigDecimal      | `@Column(name = "...")`             |
| INTEGER     | Long            | `@Column(name = "...")`             |
| DATE        | java.sql.Date   | `@Column(name = "...")`             |
| TIMESTAMP   | java.sql.Timestamp | `@Column(name = "...")`          |

---

## Common Mistakes

### ❌ Mistake 1: Using JPQL in a Dynamic Interface
*   **Wrong:** `"dataSourceQuery": "SELECT c FROM Customer c"`
*   **Why:** The Dynamic Reader uses `createNativeQuery`. It expects raw SQL.
*   **Fix:** `"dataSourceQuery": "SELECT * FROM CUSTOMERS"`

### ❌ Mistake 2: Missing KeySet Column
*   **Wrong:** Query returns 1M rows, but no `keySetColumn` defined.
*   **Result:** Performance will be slow (Offset pagination) or might crash memory.
*   **Fix:** Ensure your SQL selects a unique ID and define `"keySetColumn": "ID"` in JSON.

### ❌ Mistake 3: Entity Table Name Mismatch
*   **Wrong:** `@Table(name = "orders")` (Lowercase)
*   **Why:** Oracle is case-sensitive and stores tables as `ORDERS` (Uppercase) by default.
*   **Fix:** `@Table(name = "ORDERS")`
```