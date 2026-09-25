# Project A — API Gateway Microservice

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-4.2.0-blue.svg)](https://spring.io/projects/spring-cloud-gateway)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)]()

The **API Gateway** serves as the single unified entry point for all client applications interacting with the **Project A Apartment Management System**. Built on Spring Cloud Gateway WebFlux, it provides reactive high-performance routing, RS256 JWT signature verification, gateway token re-signing, request tracing correlation (`X-Request-ID`), and standardized error envelopes.

---

## 🌟 Core Features

- **Reactive Non-Blocking Routing**: Asynchronous HTTP routing powered by Netty and Spring WebFlux.
- **RS256 Signature Verification**: Validates incoming User and Service JWT tokens against public keys (`identity-access-service`).
- **Gateway Token Re-Signing**: Strips external client tokens and re-signs downstream requests with Gateway Private Key (RS256) to ensure downstream microservices only trust Gateway-issued context.
- **Request Tracing & Correlation**: Generates or propagates `X-Request-ID` HTTP headers across all incoming requests and outgoing responses.
- **Centralized Error Envelopes**: Intercepts gateway-level exceptions (`401`, `403`, `404`, `503`, `504`) and formats error payloads according to the Project A API standard.
- **Health & Actuator Monitoring**: `/actuator/health` and `/actuator/metrics` endpoints for observability and Docker healthchecks.
- **Container Readiness**: Multi-stage `Dockerfile` and `docker-compose.yml` configuration for seamless container orchestration.

---

## 🏗️ Architectural Overview

```
+------------------+         +---------------------+         +----------------------------+
|                  |  HTTP   |                     |  HTTP   |                            |
| Client / Frontend|-------->|  Project A Gateway  |-------->| Microservices (Group 1--4) |
| (User/Service JWT)          |  (Port 8080)        |         | (Identity, Resident, etc.) |
+------------------+         +---------------------+         +----------------------------+
                                  |           ^
                    Verify RS256  |           | Re-sign RS256 Token
                    Identity Key  v           | (Gateway Private Key)
                             +--------------------+
                             | JwtAuth & Re-Sign  |
                             +--------------------+
```

---

## 🚀 Getting Started

### Prerequisites

- **Java 21** or higher
- **Maven 3.9+** (or use included `./mvnw`)
- **Docker & Docker Compose** (optional for containerized execution)

### Local Environment Setup

1. **Clone repository and configure environment:**
   ```bash
   cp .env.example .env
   ```

2. **Compile project:**
   ```bash
   ./mvnw clean compile
   ```

3. **Run unit & integration tests:**
   ```bash
   ./mvnw clean test
   ```

4. **Launch API Gateway locally:**
   ```bash
   ./mvnw spring-boot:run
   ```

---

## 🐳 Docker Deployment

### Building & Running with Docker Compose

```bash
# Build and start container in background
docker-compose up -d --build

# View container logs
docker-compose logs -f api-gateway

# Check health status
docker-compose ps
```

### Manual Docker Build

```bash
# Build image
docker build -t projecta/api-gateway:latest .

# Run container
docker run -d -p 8080:8080 --name api-gateway projecta/api-gateway:latest
```

---

## 🔒 Security & Route Configuration

### Public Routes (Authentication Bypassed)
- `/api/v1/auth/login`
- `/api/v1/auth/register`
- `/actuator/health`
- `/actuator/info`

### Standard Response Envelope (Error Example)
```json
{
  "success": false,
  "message": "Authentication token expired",
  "data": null,
  "error": {
    "code": "UNAUTHORIZED",
    "details": null
  },
  "requestId": "1486f99e-b3b4-4185-9742-c4789ea14ed3",
  "timestamp": "2026-09-25T21:30:00Z"
}
```

---

## 🧪 Testing Coverage

The project contains unit and integration tests covering:
- `JwtAuthenticationFilterTest`: Validates RS256 signatures, exp checks, and public route handling.
- `GatewayJwtSignerTest`: Validates re-signing User and Service JWT tokens with RS256.
- `GatewayTokenRelayFilterTest`: Validates header stripping and Bearer token replacement.
- `RequestTraceFilterTest`: Validates correlation ID propagation.
- `GlobalErrorWebExceptionHandlerTest`: Validates status code mappings and JSON structure.
- `GatewayIntegrationTest`: Verifies filter pipeline behavior with `WebTestClient`.

Run tests:
```bash
./mvnw clean test
```

---

## 📄 License
Proprietary — All Rights Reserved — Project A Team.
