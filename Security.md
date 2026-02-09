# Token Security Architecture

In this project, the **Token Security System** acts as a "Gatekeeper" that ensures only authorized systems can trigger file generation. Since we are not using standard Spring Security, we have implemented a **Custom Stateful Token Filter** that validates against our Oracle database.

## System Workflow

### 1. Registration Phase (`SecurityConfig.java`)
The system registers the filter using a `FilterRegistrationBean` to tell the web container:
*   **The Filter:** Use `TokenAuthenticationFilter`.
*   **The Scope:** Only intercept requests matching `/api/*`.
*   **The Priority:** Set Order to `1` (Runs after the CorrelationID filter, but before Controllers).

### 2. Interception Phase (`TokenAuthenticationFilter.java`)
Every time a request hits the API (e.g., `POST /api/v1/file-generation/generate`):
1.  **Early Exit:** Checks if `auth.token.enable-validation` is `true`.
2.  **Extraction:** Looks for the specific header defined in `auth.token.header-name` (Default: `X-DB-Token`).
3.  **Verification:** If the header is missing, the request is blocked immediately with a **403 Forbidden** JSON response.

### 3. Validation Phase (`TokenValidator.java`)
The filter hands the token string to the validator, which queries the database:
1.  **Lookup:** Calls `DbTokenRepository.findByTokenValueAndActiveTrue(token)`.
2.  **State Check:** Verifies the token is `ACTIVE=1` and `EXPIRY_DATE` is in the future.
3.  **Audit:** If valid, updates the `LAST_USED_DATE` timestamp in the database for tracking.

### 4. Controller Access
If validation passes, the request proceeds to the `FileGenerationController`. The Controller does not need to perform any further security checks.

---

## "Chain of Command" Summary

| Step | Component      | Action                                                  |
|:-----|:---------------|:--------------------------------------------------------|
| 1    | **Client**     | Sends HTTP request with header `X-DB-Token: ABC-123`.   |
| 2    | **Filter**     | Intercepts request at `/api/*` and extracts the header. |
| 3    | **Validator**  | Queries `IF_DB_TOKEN` table for `ABC-123`.              |
| 4    | **Repository** | Returns the Entity if found, active, and not expired.   |
| 5    | **Filter**     | Updates `LAST_USED_DATE` and allows request to proceed. |
| 6    | **Controller** | Executes the file generation business logic.            |

---

## Configuration & Setup

### 1. Database Setup
To allow a new client to access the API, insert a record into the `IF_DB_TOKEN` table.

**SQL Script (Oracle):**
```sql
INSERT INTO IF_DB_TOKEN (
    TOKEN_ID,
    TOKEN_VALUE,
    APPLICATION_NAME,
    ISSUED_BY,
    ISSUED_DATE,
    EXPIRY_DATE,
    ACTIVE
) VALUES (
    IF_DB_TOKEN_SEQ.NEXTVAL,               -- Sequence
    'a1b2c3d4-e5f6-4789-abcd-ef1234567890', -- The Secret Token (Use UUID)
    'CBS_PORTAL_WEB',                      -- Name of calling system
    'ADMIN_USER',                          -- Creator
    SYSTIMESTAMP,                          -- Created Now
    SYSTIMESTAMP + 365,                    -- Expires in 1 Year
    1                                      -- 1 = Active, 0 = Revoked
);
COMMIT;
```

### 2. Client Usage Examples

**A. cURL (Command Line)**
```bash
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \
  -H "Content-Type: application/json" \
  -H "X-DB-Token: a1b2c3d4-e5f6-4789-abcd-ef1234567890" \
  -d '{"interfaceType": "ORDER_INTERFACE"}'
```

**B. Postman**
1.  **URL:** `http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate`
2.  **Method:** `POST`
3.  **Headers:**
    *   Key: `X-DB-Token`
    *   Value: `a1b2c3d4-e5f6-4789-abcd-ef1234567890`
4.  **Body (JSON):** `{"interfaceType": "ORDER_INTERFACE"}`

**C. Java (RestTemplate)**
```java
String url = "http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate";

// 1. Setup Headers
HttpHeaders headers = new HttpHeaders();
headers.set("X-DB-Token", "a1b2c3d4-e5f6-4789-abcd-ef1234567890");
headers.setContentType(MediaType.APPLICATION_JSON);

// 2. Create Request
FileGenerationRequest requestDto = new FileGenerationRequest();
requestDto.setInterfaceType("ORDER_INTERFACE");

HttpEntity<FileGenerationRequest> entity = new HttpEntity<>(requestDto, headers);

// 3. Send
try {
    ResponseEntity<FileGenerationResponse> response = restTemplate.postForEntity(
        url, entity, FileGenerationResponse.class
    );
} catch (HttpClientErrorException.Forbidden e) {
    // Handle Invalid Token
}
```

## Security Design Benefits

1.  **Lightweight:** Avoids the overhead of the full Spring Security filter chain for internal microservices.
2.  **Instant Revocation:** Administrators can set `ACTIVE = 0` in the database to immediately block an application without restarting the server.
3.  **Traceability:** The `LAST_USED_DATE` column provides audit capabilities to see when specific clients last accessed the system.
```