# FileGenerationService - Usage Map

## Overview

`FileGenerationService` is the central service for managing file generation job records in the database. It handles CRUD operations on the `FILE_GENERATION` table.

---

## Where FileGenerationService is Called

### 1. FileGenerationController (REST API)

**File**: `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/controller/FileGenerationController.java`

**Usage**:

#### a. Create File Generation Record
```java
// Line 76
FileGeneration fileGen = fileGenerationService.createFileGeneration(
    fileName,
    outputDirectory,
    userName != null ? userName : "SYSTEM",
    interfaceType
);
```
**When**: User makes POST request to `/api/v1/file-generation/generate`
**Purpose**: Creates initial job record with PENDING status

#### b. Get File Generation Status
```java
// Line 108
Optional<FileGeneration> fileGen = fileGenerationService.getFileGeneration(jobId);
```
**When**: User makes GET request to `/api/v1/file-generation/status/{jobId}`
**Purpose**: Retrieves job details for status check

#### c. Get Pending File Generations
```java
// Line 137
List<FileGeneration> pendingFiles = fileGenerationService.getPendingFileGenerations();
```
**When**: User makes GET request to `/api/v1/file-generation/pending`
**Purpose**: Lists all jobs with PENDING status

---

### 2. DynamicJobExecutionListener (Batch Listener)

**File**: `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/batch/DynamicJobExecutionListener.java`

**Usage**:

#### a. Update Status to PROCESSING
```java
// Line 30
fileGenerationService.markProcessing(jobId);
```
**When**: Batch job starts
**Purpose**: Mark job as currently processing

#### b. Update with Error
```java
// Line 54
fileGenerationService.markFailed(jobId, exception.getMessage());
```
**When**: Batch job fails
**Purpose**: Record error message and set status to FAILED

#### c. Update Status to COMPLETED
```java
// Line 60
fileGenerationService.markCompleted(jobId);
```
**When**: Batch job completes successfully
**Purpose**: Mark job as complete

#### d. Update Record Metrics
```java
// Line 77
fileGenerationService.updateFileMetrics(jobId, recordCount, skippedRecordcount, invalidRecordCount);
```
**When**: Batch job finishes
**Purpose**: Store total records processed

#### e. Update with Error (Final)
```java
// Line 82
fileGenerationService.markFailed(jobId, errorMessage.toString());
```
**When**: Batch job encounters error during finalization
**Purpose**: Record final error message

---

### 3. BatchJobLauncher (Job Execution)

**File**: `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/service/BatchJobLauncher.java`

**Usage**:

#### a. Update with Error (Job Launch Failure)
```java
// Line 53
fileGenerationService.markFailed(jobId, error);
```
**When**: Job fails to launch
**Purpose**: Record job launch error

#### b. Update with Error (Job Execution Failure)
```java
// Line 84
fileGenerationService.markFailed(jobId, e.getMessage());
```
**When**: Job execution throws exception
**Purpose**: Record execution error

---

### 4. FileGenerationScheduler (Quartz Scheduler)

**File**: `src/main/java/com/silverlakesymmetri/cbs/fileGenerator/scheduler/FileGenerationScheduler.java`

**Usage**:

#### Get Pending Jobs for Scheduled Processing
```java
// Line 38
fileGenerationService.getPendingFileGenerations().forEach(fileGen -> {
    // Process each pending file generation
});
```
**When**: Scheduled job runs (Quartz trigger)
**Purpose**: Fetch pending jobs to retry/process

---

## Data Flow Summary

```
User REST API Request
    ↓
FileGenerationController.generateFile()
    ↓
fileGenerationService.createFileGeneration()  [Create initial record]
    ↓
BatchJobLauncher.launchFileGenerationJob()
    ↓
Batch Job Executes (DynamicItemReader/Processor/Writer)
    ↓
DynamicJobExecutionListener.beforeJob()
    ↓
fileGenerationService.markProcessing(jobId)  [Update status]
    ↓
Batch Job Processing...
    ↓
DynamicJobExecutionListener.afterJob() or onError()
    ↓
fileGenerationService.markCompleted(jobId)
    ↓ OR ↓
fileGenerationService.markFailed(jobId, errMsg)
    ↓
fileGenerationService.updateFileMetrics(jobId, recordCount, skippedRecordCount, invalidRecordCount)
    ↓
User queries status
    ↓
FileGenerationController.getFileGenerationStatus()
    ↓
fileGenerationService.getFileGeneration(jobId)
    ↓
Return status to user
```

---

## FileGenerationService Methods

| Method                         | Called From                                       | Purpose                                           |
|--------------------------------|---------------------------------------------------|---------------------------------------------------|
| `createFileGeneration()`       | FileGenerationController                          | Create initial job record                         |
| `getFileGeneration(jobId)`     | FileGenerationController                          | Retrieve job by ID                                |
| `getPendingFileGenerations()`  | FileGenerationController, FileGenerationScheduler | List jobs with PENDING status                     |
| `updateFileGenerationStatus()` | DynamicJobExecutionListener                       | Update job status (PROCESSING, COMPLETED, FAILED) |
| `markFailed()`                 | BatchJobLauncher, DynamicJobExecutionListener     | Record error and update updateFileMetrics         |
| `updateFileMetrics()`          | DynamicJobExecutionListener                       | Store processed record count                      |

---

## Key Observations

1. **REST API Entry Point**: FileGenerationController triggers everything via FileGenerationService.createFileGeneration()

2. **Batch Lifecycle**: DynamicJobExecutionListener calls FileGenerationService multiple times to track job status

3. **Error Tracking**: Both BatchJobLauncher and DynamicJobExecutionListener can record errors via FileGenerationService.markFailed()

4. **Status Updates**: Job status changes are tracked in sequence: PENDING → PROCESSING → COMPLETED/FAILED

5. **Scheduler Integration**: FileGenerationScheduler retrieves pending jobs to potentially retry them

6. **Central Hub**: FileGenerationService is the single point of contact for all file generation record management
