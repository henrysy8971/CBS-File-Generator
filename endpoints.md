# 📂 CBS File Generator - API Documentation

This document lists all available endpoints for the CBS File Generator application.

## 🚀 Environment Configuration (Local)

* **Base URL:** `http://localhost:8080/cbs-file-generator`
* **API Root:** `/api/v1`
* **Auth Header:** `X-DB-Token` (Required for all `/api/` calls)

---

## 1. Web Dashboards (UI)

These endpoints return HTML views and are accessible directly via a browser.

| Method | Path | Description | Access |
| --- | --- | --- | --- |
| **GET** | `/dashboard` | Main Job Monitoring Dashboard | Public (No Token) |
| **GET** | `/` | Redirects to Dashboard | Public (No Token) |

---

## 2. Admin Endpoints (`AdminController`)

**Base Path:** `/api/v1/admin`

| Method | Path | Description | Notes |
| --- | --- | --- | --- |
| **POST** | `/cleanup` | Triggers the Spring Batch maintenance cleanup. | Deletes old metadata. |
| **POST** | `/scheduler/pause` | Pauses the entire Quartz scheduler. | Stops all polling. |
| **POST** | `/scheduler/resume` | Resumes the Quartz scheduler. | Restarts polling. |
| **POST** | `/scheduler/force-run` | Forces the Poller to check for PENDING files. | Immediate execution. |
| **POST** | `/reload-config` | Reloads `interface-config.json` into memory. | No restart needed. |
| **POST** | `/scheduler/trigger/{type}` | Manually triggers a specific generation. | e.g., `ORDER_INTERFACE` |
| **GET** | `/scheduler/jobs` | List of all registered Quartz jobs. | Query: `page`, `size` |
| **GET** | `/scheduler/status` | Real-time status of active jobs. | Query: `page`, `size` |

---

## 3. File Generation Endpoints (`FileGenerationController`)

**Base Path:** `/api/v1/file-generation`

| Method | Path | Description | Parameters |
| --- | --- | --- | --- |
| **POST** | `/generate` | Submits a file generation request. | Body: `FileGenerationRequest` |
| **GET** | `/getFileGenerationStatus/{id}` | Gets metrics/status of a specific job. | `{id}` = Job UUID or Long ID |
| **GET** | `/getFileGenerationsByStatus` | List of jobs filtered by status. | Query: `status`, `page` |
| **GET** | `/interfaces` | Returns all enabled interface types. | List of Strings. |
| **GET** | `/getConfigInfo/{type}` | Returns config details for an interface. | e.g., `XML` vs `BeanIO` |
| **GET** | `/downloadFileByJobId/{id}` | Downloads the finalized file. | Requires `COMPLETED` status. |

---

## 4. System Monitoring (Actuator)

These are standard Spring Boot monitoring endpoints.

| Method | Path | Description |
| --- | --- | --- |
| **GET** | `/actuator/health` | Service and Database health status. |
| **GET** | `/actuator/info` | Application version and info. |
| **GET** | `/actuator/logfile` | Streams the current log file to browser. |

---

## 🛡️ Security & Authentication

### Token Validation

Every request to an `/api/v1/` path must include a valid, active token from the `IF_DB_TOKEN` table.

* **Header Name:** `X-DB-Token`
* **Header Value:** `[Your_Active_Token]`

### Example Request (cURL)

```bash
curl -X POST http://localhost:8080/cbs-file-generator/api/v1/admin/scheduler/force-run \
  -H "X-DB-Token: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json"

```

### JSON Error Response

If a token is missing or invalid, the API returns:

```json
{
  "status": "AUTH_ERROR",
  "message": "Invalid or expired token"
}

```

---

## 📝 Usage Notes

1. **Context Path:** Ensure all calls begin with `/cbs-file-generator`.
2. **File Status:** Files cannot be downloaded via `/downloadFileByJobId` while they are in `PROCESSING` status (as they are still `.part` files).
3. **Local Testing:** To disable token checks for testing, set `auth.token.enable-validation=false` in `application.properties`.
