# Portfolio Performance Service

## 1. Project Overview

The Portfolio Performance Service is a Spring Boot REST microservice that calculates daily portfolio returns for investment management workflows. It serves financial advisors and operations teams who need to measure how a portfolio performed on a given valuation date relative to a benchmark index. For each request the service computes the portfolio return percentage, the excess return versus the benchmark, and applies a rule-based validation pipeline that classifies every result as `VALID`, `REVIEW_REQUIRED`, or `INVALID_INPUT`. Every request — whether it passes validation or not — is persisted to an audit table so that operations teams retain a complete, tamper-evident processing history.

---

## 2. Architecture & Approach

The service follows a standard Spring layered architecture:

```
HTTP Client
    │
    ▼
PerformanceController          (REST layer — input validation, HTTP status mapping)
    │
    ▼
PerformanceCalculationService  (interface)
    │
    ▼
PerformanceCalculationServiceImpl  (business rules, BigDecimal arithmetic, status decision)
    │
    ▼
DailyReturnAuditRepository     (Spring Data JPA)
    │
    ▼
H2 In-Memory Database          (DAILY_RETURN_AUDIT table)
```

**Key design decisions:**

- All monetary and percentage arithmetic is performed using `java.math.BigDecimal` with `scale=2` and `RoundingMode.HALF_UP` to avoid floating-point rounding errors that would be unacceptable in financial calculations.
- `PerformanceCalculationServiceImpl` owns the complete rule evaluation pipeline: input validation, return calculation, review-trigger detection, and status determination.
- Every request is persisted to `DailyReturnAudit` regardless of outcome (including `INVALID_INPUT` cases), ensuring an unbroken audit trail for compliance and operations review.
- The `GlobalExceptionHandler` (`@RestControllerAdvice`) intercepts Bean Validation failures (`@NotBlank`, `@NotNull`) and returns a structured `ErrorResponse` with HTTP 400 before the service layer is reached.

---

## 3. Business Rules Summary

Rules are evaluated in the following order. Evaluation stops at the first `INVALID_INPUT` finding; `REVIEW_REQUIRED` checks only run when all inputs are valid.

1. **Negative market values (INVALID_INPUT)** — If `beginMarketValue` or `endMarketValue` is less than zero, the request is rejected immediately. No return is calculated.

2. **Unsupported or missing currency (INVALID_INPUT)** — If `currency` is blank or is not one of the supported values (`USD`, `GBP`, `EUR`, `CAD`, `AUD`), the request is rejected.

3. **Zero begin value with non-zero end value (INVALID_INPUT)** — If `beginMarketValue` is zero and `endMarketValue` is non-zero, a percentage return cannot be meaningfully defined. The request is rejected.

4. **Benchmark deviation threshold (REVIEW_REQUIRED)** — If the absolute difference between `portfolioReturnPct` and `benchmarkReturnPct` exceeds 5 percentage points, the result is flagged for review with reason: `"Portfolio return deviates from benchmark by more than 5%"`.

5. **Cash flow threshold (REVIEW_REQUIRED)** — If the absolute value of `netCashFlow` exceeds 20% of `beginMarketValue`, large external flows may distort the return calculation. The result is flagged for review with reason: `"Net cash flow exceeds 20% of begin market value"`.

6. **Status determination** — If no review triggers fire, status is `VALID`. If one or more review triggers fire, status is `REVIEW_REQUIRED`. Both conditions are evaluated; all applicable reasons are collected (see Assumptions).

**Calculation formula:**

```
portfolioReturnPct = ((endMarketValue - beginMarketValue - netCashFlow) / beginMarketValue) × 100
excessReturnPct    = portfolioReturnPct - benchmarkReturnPct
```

---

## 4. Assumptions Made

| Assumption | Detail |
|---|---|
| **Supported currencies are a fixed list** | `USD`, `GBP`, `EUR`, `CAD`, `AUD`. Any other value, including null or blank, triggers `INVALID_INPUT`. This list is defined as a constant in `PerformanceCalculationServiceImpl`. |
| **Both REVIEW_REQUIRED conditions are always evaluated** | The service does NOT short-circuit after the first review trigger. All applicable reasons are collected and returned in the `reasons` list. A single response may carry both messages simultaneously. |
| **`netCashFlow` may be negative** | Negative values represent withdrawals. The absolute value (`Math.abs`) is used when checking the 20% cash flow threshold so that large withdrawals are flagged the same way large deposits are. |
| **`processedAt` is UTC** | The `processedAt` timestamp is set in the service layer using `Instant.now()`, which is always UTC. It is serialized to ISO-8601 format (`yyyy-MM-dd'T'HH:mm:ss'Z'`). |
| **H2 is in-memory by design** | Data stored in `DAILY_RETURN_AUDIT` does not survive application restarts. This is intentional for this implementation phase. A production deployment would replace the H2 datasource configuration with a persistent RDBMS (PostgreSQL, Oracle, etc.) with no code changes required. |
| **Bean Validation fires before service logic** | `@NotBlank` and `@NotNull` annotations on `DailyReturnRequest` are enforced by Spring's `@Valid` before `PerformanceCalculationServiceImpl` is invoked. A blank `portfolioId` or missing `valuationDate` returns HTTP 400 and never reaches the service. |

---

## 5. API Contract

### Endpoint

```
POST /api/performance/daily-return
Content-Type: application/json
```

> **Note:** The application runs with context path `/portfolio-performance-service`, so the full URL is:
> `http://localhost:8080/portfolio-performance-service/api/performance/daily-return`

### Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `portfolioId` | String | Yes | Unique identifier for the portfolio (e.g. `PF-1001`) |
| `valuationDate` | String (`yyyy-MM-dd`) | Yes | The date for which the daily return is calculated |
| `beginMarketValue` | Number | Yes | Portfolio market value at the start of the day (must be ≥ 0) |
| `endMarketValue` | Number | Yes | Portfolio market value at the end of the day (must be ≥ 0) |
| `netCashFlow` | Number | Yes | Net external cash flow during the day (may be negative) |
| `benchmarkReturnPct` | Number | Yes | Benchmark return percentage for the same date |
| `currency` | String | Yes | ISO currency code; must be one of: `USD`, `GBP`, `EUR`, `CAD`, `AUD` |
| `requestedBy` | String | Yes | Identifier of the requesting user or system |

### Example Request

```json
{
  "portfolioId": "PF-1001",
  "valuationDate": "2026-06-14",
  "beginMarketValue": 1000000,
  "endMarketValue": 1035000,
  "netCashFlow": 10000,
  "benchmarkReturnPct": 1.8,
  "currency": "USD",
  "requestedBy": "advisor01"
}
```

### Response Fields

| Field | Type | Description |
|---|---|---|
| `portfolioId` | String | Echoed from request |
| `valuationDate` | String (`yyyy-MM-dd`) | Echoed from request |
| `portfolioReturnPct` | Number | Calculated portfolio return (`null` for `INVALID_INPUT`) |
| `benchmarkReturnPct` | Number | Echoed from request |
| `excessReturnPct` | Number | `portfolioReturnPct - benchmarkReturnPct` (`null` for `INVALID_INPUT`) |
| `status` | String | `VALID`, `REVIEW_REQUIRED`, or `INVALID_INPUT` |
| `reasons` | Array | Empty for `VALID`; one or more messages for `REVIEW_REQUIRED` / `INVALID_INPUT` |
| `processedAt` | String (ISO-8601 UTC) | Timestamp when the service processed the request |

### Example Response — HTTP 200 VALID

```json
{
  "portfolioId": "PF-1001",
  "valuationDate": "2026-06-14",
  "portfolioReturnPct": 2.50,
  "benchmarkReturnPct": 1.8,
  "excessReturnPct": 0.70,
  "status": "VALID",
  "reasons": [],
  "processedAt": "2026-06-14T10:30:00Z"
}
```

### Example Response — HTTP 422 INVALID_INPUT

```json
{
  "portfolioId": "PF-1002",
  "valuationDate": "2026-06-14",
  "portfolioReturnPct": null,
  "benchmarkReturnPct": 1.0,
  "excessReturnPct": null,
  "status": "INVALID_INPUT",
  "reasons": ["Begin or end market value cannot be negative"],
  "processedAt": "2026-06-14T10:30:01Z"
}
```

### Example Response — HTTP 400 Validation Failed

```json
{
  "timestamp": "2026-06-14T10:30:02.123456Z",
  "status": 400,
  "error": "Validation Failed",
  "message": "portfolioId: must not be blank",
  "path": "/portfolio-performance-service/api/performance/daily-return"
}
```

### HTTP Status Code Summary

| HTTP Status | Condition |
|---|---|
| `200 OK` | `status` is `VALID` or `REVIEW_REQUIRED` |
| `400 Bad Request` | Bean Validation failure (`@NotBlank`, `@NotNull`) or malformed JSON |
| `422 Unprocessable Entity` | `status` is `INVALID_INPUT` (business rule violation) |

---

## 6. How to Run

### Prerequisites

- **Java 21** (e.g. Eclipse Adoptium Temurin 21)
- **Maven 3.9+**

### Build

```bash
mvn clean install
```

### Start the Application

```bash
mvn spring-boot:run
```

The service starts on **port 8080** with context path `/portfolio-performance-service`.

Startup log confirmation:

```
Tomcat started on port 8080 (http) with context path '/portfolio-performance-service'
Started PortfolioPerformanceApplication in X seconds
```

### H2 Console

| Property | Value |
|---|---|
| URL | `http://localhost:8080/portfolio-performance-service/h2-console` |
| JDBC URL | `jdbc:h2:mem:portfolio-performance-service` |
| Username | `sa` |
| Password | _(leave blank)_ |

### Example curl — Happy Path (VALID)

```bash
curl -X POST http://localhost:8080/portfolio-performance-service/api/performance/daily-return \
  -H "Content-Type: application/json" \
  -d '{
    "portfolioId": "PF-1001",
    "valuationDate": "2026-06-14",
    "beginMarketValue": 1000000,
    "endMarketValue": 1035000,
    "netCashFlow": 10000,
    "benchmarkReturnPct": 1.8,
    "currency": "USD",
    "requestedBy": "advisor01"
  }'
```

Expected response (HTTP 200):

```json
{
  "portfolioId": "PF-1001",
  "valuationDate": "2026-06-14",
  "portfolioReturnPct": 2.50,
  "benchmarkReturnPct": 1.8,
  "excessReturnPct": 0.70,
  "status": "VALID",
  "reasons": [],
  "processedAt": "2026-06-14T10:30:00Z"
}
```

---

## 7. How to Run Tests

```bash
mvn test
```

This command runs the full test suite in a single pass:

- **Unit tests** — service logic (`PerformanceCalculationServiceImplTest`), request validation (`DailyReturnRequestValidationTest`), repository queries (`DailyReturnAuditRepositoryTest`), controller slice (`PerformanceControllerTest` via `@WebMvcTest`)
- **Integration tests** — full Spring context with random port and in-memory H2 (`PerformanceControllerIntegrationTest` via `@SpringBootTest`)

Expected output:

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Surefire HTML Reports

After running `mvn test`, individual test class reports are available at:

```
target/surefire-reports/
```

Open any `.txt` file for a plain-text summary. The HTML report is automatically generated during `mvn test` and is written to:

```
target/reports/surefire.html
```

Open that file in any browser for a formatted, per-class test result view.
