**1\. Admin Endpoints (AdminController)**

**Base Path:****http://localhost:8080/api/v1/admin**

| HTTP Method | Endpoint Path             | Description                                                          | Parameters / Notes           |
|-------------|---------------------------|----------------------------------------------------------------------|------------------------------|
| POST        | /cleanup                  | Triggers the Spring Batch maintenance cleanup job manually.          |                              |
| POST        | /scheduler/pause          | Pauses the entire Quartz scheduler.                                  |                              |
| POST        | /scheduler/resume         | Resumes the Quartz scheduler.                                        |                              |
| POST        | /scheduler/force-run      | Forces the Poller job to run immediately (checks for PENDING files). |                              |
| POST        | /reload-config            | Reloads the interface-config.json from the classpath into memory.    |                              |
| POST        | /scheduler/trigger/{type} | Manually triggers a specific file generation via Quartz.             | {type}= e.g.,ORDER_INTERFACE |
| GET         | /scheduler/jobs           | Returns a paginated list of all registered Quartz jobs.              | Query params:page,size       |
| GET         | /scheduler/status         | Returns a paginated list of jobs with detailed run times.            | Query params:page,size       |

**How to call them (Examples):**

Since these arePOST requests, you cannot just paste them into a browser address bar. You must use a tool like**Postman**,**Insomnia**, or**cURL**.

**Example using cURL:**

curl -X POST **http://localhost:8080/cbs-file-generator/api/v1/admin**/scheduler/force-run \\

-H "X-DB-Token: **your_active_token_here**"

* * *

**2\. File Generation Endpoints (FileGenerationController)**

**Base Path:****http://localhost:8080/api/v1/file-generation**

| HTTP Method | Endpoint Path                 | Description                                                 | Parameters / Notes                             |
|-------------|-------------------------------|-------------------------------------------------------------|------------------------------------------------|
| POST        | /generate                     | Creates a request to generate a file.                       | Body:FileGenerationRequest, Header:X-User-Name |
| GET         | /getFileGenerationStatus/{id} | Retrieves the status and metrics of a specific job.         | {id}= UUID Job ID                              |
| GET         | /getFileGenerationsByStatus   | Returns a paginated list of jobs filtered by status.        | Query params:status,page,size,order            |
| GET         | /interfaces                   | Returns a list of all enabled interface types.              |                                                |
| GET         | /getConfigInfo/{type}         | Returns the configuration details for a specific interface. | {type}= e.g.,ORDER_INTERFACE                   |
| GET         | /downloadFileByJobId/{id}     | Downloads the final generated file if status is COMPLETED.  | {id}= UUID Job ID. Supports Stream/Range.      |

* * *

**Key Parameter Details**

*   **Pagination Defaults:**

*   page: default0
*   size: default10(Admin) or10(File Generation)

*   **Status Query values:**

*   Valid values:PENDING,PROCESSING,STOPPED,FINALIZING,COMPLETED,FAILED

*   **Special Headers:**

*   X-User-Name: Used in/generate to track who requested the file. Defaults toSYSTEM.
*   X-DB-Token: (Handled by yourTokenAuthenticationFilter) Required for all protected paths above.

All of these endpoints require a valid DB Token in the request header.

Here is the breakdown of why and how this is applied:

**1\. The Global Rule**

Since both theAdminController(/api/v1/admin) and theFileGenerationController(/api/v1/file-generation) fall under the/api/path, the**TokenAuthenticationFilter**will intercept**every single request**to those endpoints.

**2\. Required Header**

For any of those requests to succeed, you must include the header defined in **application.properties**:

*   **Header Name:****X-DB-Token**
*   **Value:**A valid token string that exists and is marked**ACTIVE**in **IF_DB_TOKEN**table.

**3\. The Exceptions (No Token Required)**

The only endpoints that**do not**require a token are the system health and info endpoints, because they are explicitly bypassed in theTokenAuthenticationFilter.java logic:

*   /actuator/\*\*
*   /health/\*\*
*   /info

**4\. The "Master Switch"**

If you are testing locally and want to disable the token requirement for all endpoints, you can change this setting in your application.properties:

**auth.token.enable-validation=false**

When this is false, the filter still runs, but it immediately calls filterChain.doFilter and skips the token check.

* * *

**Summary Table for Port 8080**

| Endpoint Path              | Requires Token? | Reason                             |
|----------------------------|-----------------|------------------------------------|
| /api/v1/admin/**           | YES             | Matches/api/*pattern               |
| /api/v1/file-generation/** | YES             | Matches/api/*pattern               |
| /actuator/health           | NO              | Explicitly skipped in Filter logic |
| /actuator/info             | NO              | Explicitly skipped in Filter logic |

**Security Note:**In a production banking environment, it is highly recommended to keep the token validation enabled for all/api/endpoints to prevent unauthorized users from triggering massive batch jobs or cleaning up system metadata.