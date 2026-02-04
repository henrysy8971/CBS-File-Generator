# JPA Entity Setup Guide

## Quick Answer

**Yes, you need a JPA entity class for every table/view you want to query.**

However, you **do NOT need a DTO** unless you need to:
- Transform/format the data (e.g., convert Date to String)
- Handle nested relationships (e.g., Order → LineItems)
- Denormalize data for specialized output
- Implement custom batch processing logic

---

## Decision Tree: Do I Need a DTO?

```
Start: Adding new interface (e.g., INVOICE_INTERFACE)
│
├─ Is the data simple and flat?
│  └─ Yes: Just create Entity class
│          Use DynamicItemReader
│          Add interface-config.json entry
│          Done! (example: CUSTOMER_INTERFACE)
│
└─ No: Does it have nested relationships or need transformation?
   ├─ Dates to format as strings? → Need DTO
   ├─ Nested objects (Order → LineItems)? → Need DTO
   ├─ Custom validations/enrichments? → Need DTO
   └─ All of above?
      → Create Entity + DTO + RowMapper
      → Create custom ItemReader/ItemWriter
      → Create custom BatchConfig
      → Example: ORDER_INTERFACE
```

---

## What Each File Does

| File Type      | Required?      | Purpose                                                  | Example                       | When Used                                                                                              |
|----------------|----------------|----------------------------------------------------------|-------------------------------|--------------------------------------------------------------------------------------------------------|
| **JPA Entity** | ✅ **YES**      | Maps database table/view to Java class for JPQL queries  | `Order.java`, `Customer.java` | Always                                                                                                 |
| **DTO**        | ⚠️ Conditional | Data transfer object for transformations/formatting      | `OrderDto.java`               | When you need to transform entity data (e.g., format dates, enrich fields, denormalize nested objects) |
| **RowMapper**  | ⚠️ Optional    | Converts Entity → DTO with custom logic                  | `OrderRowMapper.java`         | When transformation between Entity and DTO is needed                                                   |
| **Repository** | ⚠️ Optional    | Data access methods (custom queries beyond generic JPQL) | `OrderRepository.java`        | When you need repository methods for complex queries                                                   |

---

## When to Use Entity Only vs Entity + DTO

### ✅ Use Entity ONLY (Simple Case)

**Scenario**: Data goes directly from database to output file without transformation

```
Database → JPA Entity → Output File
```

**Example**: CUSTOMER_INTERFACE
- Just read from CUSTOMERS table and output as XML
- No transformation needed
- No nested objects

**Configuration**:
```json
{
  "CUSTOMER_INTERFACE": {
    "dataSourceQuery": "SELECT c FROM Customer c",
    "outputFormat": "XML"
  }
}
```

**Files needed**:
- `Customer.java` (entity only)

---

### ⚠️ Use Entity + DTO (Complex Case)

**Scenario**: Data needs transformation, formatting, or has nested relationships

```
Database → JPA Entity → DTO (transform) → Output File
```

**Example**: ORDER_INTERFACE
- Order entity has relationship with LineItem (nested)
- Need to format dates differently
- Need to enrich data or denormalize for output
- Specialized batch processing with custom ItemReader/Writer

**Configuration**: `OrderBatchConfig.java` (custom batch configuration)

**Files needed**:
- `Order.java` (entity)
- `OrderDto.java` (DTO with nested LineItemDto)
- `OrderRowMapper.java` (entity → DTO conversion)
- `OrderItemReader.java` (custom reader that uses DTO)
- `OrderItemWriter.java` (custom writer for DTO)

---

## Minimal JPA Entity Example

For a database table:
```
CUSTOMERS
├── CUSTOMER_ID (VARCHAR2)
├── CUSTOMER_NAME (VARCHAR2)
├── EMAIL (VARCHAR2)
└── ACTIVE (NUMBER)
```

Create `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/entity/Customer.java`:

```java
package com.silverlakesymmetri.cbs.fileGenerator.entity;

import javax.persistence.*;

@Entity
@Table(name = "CUSTOMERS")
public class Customer {

    @Id
    @Column(name = "CUSTOMER_ID")
    private String customerId;

    @Column(name = "CUSTOMER_NAME")
    private String customerName;

    @Column(name = "EMAIL")
    private String email;

    @Column(name = "ACTIVE")
    private Integer active;

    // Getters and setters
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getActive() {
        return active;
    }

    public void setActive(Integer active) {
        this.active = active;
    }
}
```

---

## Annotation Reference

### @Entity
```java
@Entity
public class Customer { ... }
```
Marks class as a JPA entity (database record)

### @Table
```java
@Table(name = "CUSTOMERS")
public class Customer { ... }
```
Maps entity to database table name (case-sensitive on some databases)

### @Id
```java
@Id
@Column(name = "CUSTOMER_ID")
private String customerId;
```
Marks field as primary key

### @Column
```java
@Column(name = "CUSTOMER_NAME")
private String customerName;
```
Maps field to database column name

### Optional: Common Annotations

```java
// For database-generated IDs
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq")
@SequenceGenerator(name = "customer_seq", sequenceName = "CUSTOMER_SEQ")
private Long id;

// For relationships
@OneToMany(mappedBy = "customer")
private List<Order> orders;

@ManyToOne
@JoinColumn(name = "CUSTOMER_ID")
private Customer customer;

// For dates
@Temporal(TemporalType.Timestamp)
@Column(name = "CREATED_DATE")
private Date createdDate;

// For large text
@Lob
@Column(name = "DESCRIPTION")
private String description;
```

---

## Step-by-Step: Add New Interface

### 1. Create Entity Class

File: `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/entity/Invoice.java`

```java
@Entity
@Table(name = "INVOICES")
public class Invoice {
    @Id
    @Column(name = "INVOICE_ID")
    private String invoiceId;

    @Column(name = "INVOICE_NUMBER")
    private String invoiceNumber;

    @Column(name = "INVOICE_AMOUNT")
    private BigDecimal amount;

    @Column(name = "STATUS")
    private String status;

    // Getters and setters...
}
```

### 2. Add to interface-config.json

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

### 3. Rebuild and Deploy

```bash
mvn clean package
java -jar target/file-generator-1.0.0-exec.jar --DB_PASSWORD=<your-db-password>
```

### 4. Test API

```bash
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: your-token" \
  -d '{
    "interfaceType": "INVOICE_INTERFACE"
  }'
```

---

## Common Mistakes

### ❌ Mistake 1: Using table name in JPQL instead of entity class name

```java
// WRONG
"dataSourceQuery": "SELECT * FROM invoices"

// CORRECT
"dataSourceQuery": "SELECT i FROM Invoice i"
```

### ❌ Mistake 2: Column name in JPQL instead of entity field name

```java
// WRONG
"dataSourceQuery": "SELECT invoice_id, invoice_number FROM Invoice i"

// CORRECT
"dataSourceQuery": "SELECT i.invoiceId, i.invoiceNumber FROM Invoice i"
```

### ❌ Mistake 3: Case mismatch in @Table annotation

```java
// May fail on case-sensitive databases
@Table(name = "invoices")  // Table is actually "INVOICES"

// CORRECT
@Table(name = "INVOICES")
```

### ❌ Mistake 4: Missing @Column annotation

```java
// Without @Column, assumes field name matches column name (may fail)
private String invoice_id;  // Column is INVOICE_ID

// CORRECT
@Column(name = "INVOICE_ID")
private String invoiceId;
```

---

## Data Type Mapping

| Oracle SQL  | Java Type       | JPA Annotation                      |
|-------------|-----------------|-------------------------------------|
| VARCHAR2(n) | String          | `@Column(name = "...")`             |
| NUMBER(p,s) | BigDecimal      | `@Column(name = "...")`             |
| INTEGER     | Integer or Long | `@Column(name = "...")`             |
| DATE        | Date            | `@Temporal(TemporalType.DATE)`      |
| Timestamp   | Timestamp       | `@Temporal(TemporalType.Timestamp)` |
| CLOB        | String          | `@Lob`                              |
| BLOB        | byte[]          | `@Lob`                              |

---

## Real Example: ORDER_INTERFACE with DTO

The ORDER_INTERFACE in this codebase uses both Entity and DTO because:

1. **Complex nested structure**: Order has many LineItems
2. **Custom formatting**: Dates need to be formatted as strings
3. **Specialized output**: Hierarchical XML with Order → LineItems

**Entity Classes**:
```java
// Order.java
@Entity
@Table(name = "ORDERS")
public class Order {
    @Id
    private String orderId;
    @Column(name = "ORDER_DATE")
    private Date orderDate;  // Database: Date object

    @OneToMany(mappedBy = "order")
    private List<LineItem> lineItems;
}

// LineItem.java
@Entity
@Table(name = "ORDER_LINES")
public class LineItem {
    @Id
    private String lineItemId;
    @ManyToOne
    @JoinColumn(name = "ORDER_ID")
    private Order order;
}
```

**DTO Classes** (for output):
```java
// OrderDto.java
public class OrderDto {
    private String orderId;
    private String orderDate;  // String: formatted as "yyyy-MM-dd HH:mm:ss"
    private List<LineItemDto> lineItems;  // Nested DTOs
}

// LineItemDto.java
public class LineItemDto {
    private String lineItemId;
    private String productId;
    // ...
}
```

**Conversion** (OrderRowMapper.java):
```java
public OrderDto mapRow(Order order) {
    OrderDto dto = new OrderDto();
    dto.setOrderId(order.getOrderId());

    // Transform: Date → String
    dto.setOrderDate(dateFormat.format(order.getOrderDate()));

    // Transform: List<LineItem> → List<LineItemDto>
    for (LineItem item : order.getLineItems()) {
        dto.addLineItem(mapLineItem(item));
    }
    return dto;
}
```

**Why this architecture**:
- ✅ Entities handle database concerns (JPA, columns, relationships)
- ✅ DTOs handle output concerns (formatting, nesting, de-normalization)
- ✅ Mapper handles transformation logic
- ✅ Custom ItemReader/ItemWriter handle specialized batch processing

---

## Repository (Optional)

You can create a repository for custom queries, but it's optional for the file generator:

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    // Custom query with eager loading of line items
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.lineItems WHERE o.status = 'ACTIVE'")
    Page<Order> findAllActiveWithLineItems(Pageable pageable);
}
```

---

## Best Practices

1. **Entity class names should match table names**
   - Table: `CUSTOMERS` → Entity: `Customer`
   - Table: `ORDER_LINES` → Entity: `OrderLine`

2. **Use proper Java naming conventions**
   - Table: `CUSTOMER_ID` → Field: `customerId`
   - Table: `INVOICE_AMOUNT` → Field: `invoiceAmount`

3. **Always use @Id for primary key**
   - Helps Hibernate manage the entity

4. **Use appropriate data types**
   - `BigDecimal` for monetary values
   - `Date` or `Timestamp` for dates
   - `String` for VARCHAR2

5. **Keep entities simple**
   - Just map database columns
   - Complex logic goes in services

---

## File Structure

After adding Invoice interface, your entity structure should look like:

```
src/main/java/com/silverlakesymmetri/cbs/fileGenerator/
├── entity/
│   ├── Order.java
│   ├── Customer.java
│   ├── Invoice.java          ← New entity
│   ├── AppConfig.java
│   ├── FileGeneration.java
│   └── DbToken.java
├── repository/
│   ├── OrderRepository.java
│   ├── CustomerRepository.java
│   ├── InvoiceRepository.java ← New (optional)
│   └── ...
├── batch/
│   ├── DynamicItemReader.java
│   └── ...
└── ...
```
