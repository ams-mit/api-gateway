# Project A — API Gateway Microservice

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-green.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-5.0-blue.svg)](https://spring.io/projects/spring-cloud-gateway)
[![License](https://img.shields.io/badge/License-Proprietary-red.svg)]()

The **API Gateway** serves as the single unified entry point for all client applications interacting with the **Project A Apartment Management System**. Built on Spring Cloud Gateway WebFlux, it provides reactive high-performance routing, RS256 JWT signature verification, gateway token re-signing, request tracing correlation (`X-Request-ID`), and standardized error envelopes.

---

## 🌟 Core Features

- **Reactive Non-Blocking Routing**: Asynchronous HTTP routing powered by Netty and Spring WebFlux.
- **RS256 Signature Verification**: Only RS256 is accepted. User JWTs are verified with the Identity Access public key; Service JWTs with the registered public key of the calling service (unregistered services are rejected). `exp` is required, and User JWTs must carry a `roles` list.
- **Gateway Token Re-Signing**: Strips external client tokens and re-signs downstream requests with Gateway Private Key (RS256) to ensure downstream microservices only trust Gateway-issued context. Caller-supplied `Authorization` headers are removed on public routes.
- **Request Tracing & Correlation**: Generates or propagates `X-Request-ID` on every request, including unknown routes and error responses.
- **Centralized Error Envelopes**: `401 UNAUTHORIZED`, `404 ROUTE_NOT_FOUND`, `503 DEPENDENCY_UNAVAILABLE` (backend unreachable or timed out), `500 INTERNAL_SERVER_ERROR`; exception details are never returned to clients.
- **Health Monitoring**: `/actuator/health` reports the Gateway's own status plus a `services` component listing every downstream service as `UP`/`DOWN` (checked via each service's `/actuator/health`, 2 s timeout, results cached 10 s). A down backend marks `services` as `DEGRADED` but the Gateway stays `UP` (HTTP 200), so Docker does not restart it.
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
   The Gateway refuses to start without its keys. Provide them either as files or as PEM text in env vars:

   | Variable | Purpose |
   |---|---|
   | `IDENTITY_JWT_PUBLIC_KEY` | Identity Access public key (verifies User JWTs) |
   | `GATEWAY_JWT_PRIVATE_KEY` | Gateway private key, PKCS#8 (signs Gateway JWTs) |
   | `GATEWAY_JWT_EXPIRES_IN` | Gateway JWT lifetime (default `5m`) |
   | `<SERVICE>_SERVICE_PUBLIC_KEY` | Public key per registered calling service (verifies Service JWTs) |

   Each key value may be a location (`file:/path/key.pem`, `classpath:keys/key.pem`) or the PEM itself. For Docker Compose, place the files in `./keys/` (mounted at `/app/keys`). Key files and `.env` are git-ignored.

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
- `/actuator/health`, `/actuator/info` (served by the Gateway itself, not routed)

### Routes
Routes are defined in `application.yml` under `spring.cloud.gateway.server.webflux.routes` (the prefix required by Spring Cloud Gateway 5).
- Group 1: `/api/v1/auth/**`, `/api/v1/profile/**`, `/api/v1/admin/users/**`, `/api/v1/admin/roles/**` → identity-access; `/api/v1/residents|owners|tenants|staff/**` → resident-management.
- Groups 2–4: placeholder routes pending those teams' API contracts.

### Standard Response Envelope (Error Example)
```json
{
  "success": false,
  "message": "Authentication failed",
  "error": {
    "code": "UNAUTHORIZED",
    "details": null
  },
  "timestamp": "2026-09-25T21:30:00Z",
  "requestId": "1486f99e-b3b4-4185-9742-c4789ea14ed3"
}
```

---

## 🧪 Testing Coverage

The project contains unit and integration tests covering:
- `JwtAuthenticationFilterTest`: RS256 enforcement (RS512/PS256/`alg:none` rejected), missing `exp`, roles, token type, per-service key selection, unregistered/impersonating services.
- `GatewayJwtSignerTest`: Re-signing User and Service JWTs with RS256 and the configured TTL.
- `GatewayTokenRelayFilterTest`: Authorization replacement, and stripping on public routes.
- `RequestTraceFilterTest`: Correlation ID generation, propagation, and sanitising.
- `GlobalErrorWebExceptionHandlerTest`: Status/code mappings and no leaked exception details.
- `KeyResolverServiceTest`: Loading keys from files and inline PEM.
- `GatewayIntegrationTest`: Boots the real Gateway against a stub backend — routing, re-signed JWT reaching the backend, request ID propagation, unknown route 404, unreachable/slow backend 503, spoofed headers, health.

Run tests:
```bash
./mvnw clean test
```

---

## 📄 License
Proprietary — All Rights Reserved — Project A Team.
