In this project, the**Token Security System**acts as a "Gatekeeper" that ensures only authorized systems can trigger file generation. Since you are not using standard Spring Security, you have implemented a**Custom Stateful Token Filter**that validates against your database.

Here is the step-by-step breakdown of how it works in your application:

**1\. Registration Phase (SecurityConfig.java)**

The system doesn't know the filter exists until it is registered. You use a Filter Registration Bean to tell the web server (Tomcat) two things:

*   **The Filter:**Use the TokenAuthenticationFilter bean.
*   **The Scope:**Only intercept requests that start with/api/\*.
*   **The Priority:**SetOrder(1)so it runs before any business logic or controllers.

**2\. Interception Phase (TokenAuthenticationFilter.java)**

Every time a request hits your API (e.g.,POST /api/v1/file-generation/generate), the following happens:

1.  **Early Exit:**The filter checks if validation is enabled in application.properties. If false, it lets the request through (useful for dev).
2.  **Bypass Check:**It checks if the URL is a "Public" endpoint (like/actuator/health). If so, it skips validation.
3.  **Extraction:**it looks for a specific header (defined in properties as**X-DB-Token**).
4.  **Verification:**If the header is missing, it immediately blocks the request and returns a**JSON error response**(which we refactored earlier).

**3\. Validation Phase (TokenValidator.java)**

The filter hands the token string to theTokenValidator, which performs a database lookup:

1.  **Database Query:**It calls db TokenRepository.findByTokenValueAndActiveTrue(token).
2.  **State Check:**It verifies:

*   Is the token**Active**?
*   Is the current date before the**Expiry Date**?

3.  **The "Last Used" Optimization:**If the token is valid, it updates theLAST_USED_DATEin the database.

*   _Note:_As we reviewed earlier, it only performs this update once per minute to prevent the database from being hammered by high-frequency API calls.

**4\. Controller Access**

If theTokenValidator returns true, the filter calls filterChain.doFilter(request, response).

*   This "unlocks" the request and allows it to reach yourFileGenerationController.
*   Inside the controller, you can trust that the request is authentic.

* * *

**Summary of the "Chain of Command"**

| Step | Component        | Action                                                     |
|------|------------------|------------------------------------------------------------|
| 1    | Incoming Request | Client sendsX-DB-Token: ABC-123header.                     |
| 2    | Filter           | Captures the request and extracts the header.              |
| 3    | Validator        | Queries DB_TOKEN table forABC-123.                         |
| 4    | Repository       | Returns the Db Token entity if found and active.           |
| 5    | Filter           | If valid, allows the request to proceed to the Controller. |
| 6    | Controller       | Executes the file generation logic.                        |

* * *

**Why this is a good design for your project:**

1.  **Lightweight:**It avoids the massive overhead of the full Spring Security stack, which is often overkill for internal banking microservices.
2.  **Instant Revocation:**If a token is compromised, an admin can setACTIVE = 0in the database, and the very next API call will be blocked immediately without needing to restart the server.
3.  **Traceability:**Because you updateLAST_USED_DATE, you can look at your database and see exactly when a specific external system (like a portal or middleware) last communicated with the file generator.
4.  **Standardized Response:**By writing a JSON error body in the filter (as we refactored), you ensure that automated clients can parse the error correctly if their token expires.

To set up the security system, you need to populate the IF_DB_TOKEN table with valid entries and then ensure your clients (like Postman or a frontend app) send that exact value in the HTTP header.

**1\. How to set up the Token in the Database**

Since you are using database token, you can manually insert a record for a new application. The TOKEN_VALUE should be a long, unpredictable string (like a UUID).

**Example SQL Insert:**

\-- Assuming you have a sequence named IF_DB_TOKEN_SEQ

INSERT INTO IF_DB_TOKEN (

TOKEN_ID,

TOKEN_VALUE,

APPLICATION_NAME,

ISSUED_BY,

ISSUED_DATE,

EXPIRY_DATE,

ACTIVE

) VALUES (

IF_DB_TOKEN_SEQ.NEXTVAL,

'a1b2c3d4-e5f6-4789-abcd-ef1234567890', -- This is your actual token

'CBS_PORTAL_WEB', -- Name of the calling app

'ADMIN_USER', -- Who created it

SYSTIMESTAMP, -- Created now

SYSTIMESTAMP + 365, -- Valid for 1 year

1 -- 1 = Active

);

COMMIT;

**Field Best Practices:**

*   **TOKEN_VALUE**: Use a randomly generated UUID. Do not use simple words like "password123".
*   **EXPIRY_DATE**: If this is null, the code assumes the token never expires. If set, the TokenValidator will reject the request if the current time is past this date.
*   **ACTIVE**: Set to 1 for the token to work. If you need to "kill" access for an app instantly, change this to0.

* * *

**2\. How to set the HTTP Request Header**

When a client wants to call your API, it must include the token in the headers of the HTTP request.

**A. Using cURL (Command Line)**

If you want to test from a terminal:

curl -X POST http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate \\

-H "Content-Type: application/json" \\

-H "X-DB-Token: a1b2c3d4-e5f6-4789-abcd-ef1234567890" \\

-d '{"interfaceType": "ORDER_INTERFACE"}'

**B. Using Postman (GUI)**

1.  Enter your URL:http://localhost:8080/cbs-file-generator/api/v1/file-generation/generate
2.  Set the Method to**POST**.
3.  Go to the**Headers**tab.
4.  Add a new row:

*   **Key:**X-DB-Token(Must match the value in your application.properties)
*   **Value:**a1b2c3d4-e5f6-4789-abcd-ef1234567890(Must match the value in your DB table)

5.  Go to the**Body**tab, select**raw**and**JSON**, and enter your request payload.

**C. In a Java Client (using RestTemplate)**

If another Java service is calling your File Generator:

HttpHeaders headers = new HttpHeaders();

headers.set("X-DB-Token", "a1b2c3d4-e5f6-4789-abcd-ef1234567890");

headers.setContentType(MediaType.APPLICATION_JSON);

HttpEntity<FileGenerationRequest> entity = new HttpEntity<>(requestDto, headers);

restTemplate.postForEntity(url, entity, FileGenerationResponse. Class);

* * *

**3\. Verification of the Connection**

When you send the request, your code performs this logic:

1.  **Header Extraction:**TokenAuthenticationFilter looks for X-DB-Token.
2.  **DB Lookup:**TokenValidator runs:sql SELECT \* FROM IF_DB_TOKEN WHERE TOKEN_VALUE = 'a1b2c3d4-e5f6-4789-abcd-ef1234567890' AND ACTIVE = 1;
3.  **Audit:**If found, the LAST_USED_DATE column in your table will be updated to the current timestamp. You can check this in DB to see if your request was successful:sql SELECT APPLICATION_NAME, LAST_USED_DATE FROM IF_DB_TOKEN;