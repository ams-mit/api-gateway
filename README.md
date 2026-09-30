# Project A — API Gateway

Central entry point of the Project A Apartment Management System (University of Kelaniya).
Java 21 · Spring Boot 4.1.1 · Spring Cloud Gateway (WebFlux) · Maven · package `kln.ams.apigateway`.

Canonical contract: [API-GATEWAY.md](API-GATEWAY.md), together with
[PROJECT-A-CONTRACT-DECISIONS.md](PROJECT-A-CONTRACT-DECISIONS.md),
[PROJECT-A-GLOBAL-API-STANDARD.md](PROJECT-A-GLOBAL-API-STANDARD.md),
[PROJECT-A-JWT-SECURITY-STANDARD.md](PROJECT-A-JWT-SECURITY-STANDARD.md) and
[PROJECT-A-CROSS-SERVICE-API-REGISTRY.md](PROJECT-A-CROSS-SERVICE-API-REGISTRY.md).

## 1. Purpose

Routes every frontend and service-to-service API call to the owning backend, verifies the caller's JWT,
and forwards a new short-lived Gateway-signed JWT. The Gateway owns no business data and contains no
business logic.

## 2. Project role

Jointly governed integration component (Groups 1–4). The frontend uses only the Gateway
(`API_GATEWAY_URL + /api/v1/...`); backend services call each other only through the Gateway.

## 3. Service inventory

| Group | Service | URI variable |
|---|---|---|
| 1 | identity-access-service | `IDENTITY_SERVICE_URI` |
| 1 | resident-management-service | `RESIDENT_SERVICE_URI` |
| 2 | property-unit-service | `PROPERTY_SERVICE_URI` |
| 2 | lease-occupancy-service | `LEASE_SERVICE_URI` |
| 3 | billing-payment-service | `BILLING_SERVICE_URI` |
| 3 | utility-charge-service | `UTILITY_SERVICE_URI` |
| 4 | operations-service | `OPERATIONS_SERVICE_URI` |
| 4 | community-service | `COMMUNITY_SERVICE_URI` |

## 4. Route architecture

Routes are static configuration in `src/main/resources/application.yml`, matched by HTTP method + path.
Specific routes are ordered before broad prefixes (internal routes first, then
`/api/v1/units/{unitId}/active-occupancy` before `/api/v1/units/**`). There is no catch-all route and no
generic proxy endpoint. Paths are forwarded unchanged. Full table: [docs/gateway-routes.md](docs/gateway-routes.md).

## 5. Authentication

Only `POST /api/v1/auth/login` (and the read-only backend OpenAPI documents) are public. All other
`/api/v1` routes require a User JWT; all `/api/v1/internal/**` routes require a Service JWT.
Details: [docs/security.md](docs/security.md).

## 6. User JWT flow

Frontend → `Authorization: Bearer <User JWT>` (signed by Identity Access) → Gateway verifies RS256
signature with the Identity Access public key, `type=user`, UUID `sub`, canonical `roles`, `iat`, `exp`
→ Gateway forwards a new Gateway User JWT.

## 7. Service JWT flow

Service A → `Authorization: Bearer <Service JWT>` (signed with its own key) → Gateway selects Service A's
registered public key, verifies the signature, `type=service`, `iat`, `exp`, and that Service A is allowed on
the target route → Gateway forwards a new Gateway Service JWT to Service B.

## 8. Gateway JWT flow

The Gateway signs `{sub, type, roles (users only), iat, exp}` with its private key (RS256, `typ: JWT`,
lifetime `GATEWAY_JWT_EXPIRES_IN`, default 5m). Backends verify it with the Gateway public key.
The original User/Service JWT is never forwarded.

## 9. Key configuration

| Variable | Purpose |
|---|---|
| `IDENTITY_PUBLIC_KEY` | Identity Access public key (verifies User JWTs) |
| `GATEWAY_PRIVATE_KEY` | Gateway private key, PKCS#8 (signs Gateway JWTs) |
| `GATEWAY_PUBLIC_KEY` | Optional; startup checks it matches the private key. Distribute to all backends |
| `IDENTITY_SERVICE_PUBLIC_KEY` … `COMMUNITY_SERVICE_PUBLIC_KEY` | One Service JWT key per backend (all 8 required) |

A value is a location (`file:/app/keys/x.pem`) or the PEM text itself. Local layout (git-ignored):

```
keys/
├── identity_public.pem            (+ identity_private.pem: dev only, for signing test tokens)
├── gateway_private.pem, gateway_public.pem
└── services/<service-name>_public.pem   (+ _private.pem: dev only)
```

Generate a pair (Git Bash):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/services/NAME_private.pem
```

```bash
openssl pkey -in keys/services/NAME_private.pem -pubout -out keys/services/NAME_public.pem
```

In production, generate fresh keys in the deployment's secret store; each private key stays with its owner.

## 10. Route configuration

Each route has an `id`, `Method` and `Path` predicates, a target `uri`, and `metadata`:
`access` (`public` | `user` | `service` | `blocked`; missing means `user`), `service`, `api-ids` and, for
internal routes, `allowed-callers`. Change routes only through the process in section 23.

## 11. Internal authorization

Each internal route lists its allowed calling services, taken from the consumer lists in
`PROJECT-A-CROSS-SERVICE-API-REGISTRY.md`. A registered service that is not listed gets
403 `SERVICE_NOT_ALLOWED`; a User JWT gets 403 `FORBIDDEN`.

## 12. Request ID

`X-Request-ID` is preserved if valid (`[A-Za-z0-9._:-]{1,128}`), otherwise generated (UUID); it is
forwarded downstream, returned as a response header, and included in every Gateway error body and access log.

## 13. Error handling

Gateway errors use the standard envelope (`success`, `message`, `error.code`, `error.details`,
`timestamp`, `requestId`). Backend responses, including backend business errors, pass through unchanged.
Codes: 401 `MISSING_TOKEN`/`INVALID_TOKEN`/`INVALID_SIGNATURE`/`UNSUPPORTED_ALGORITHM`/`TOKEN_EXPIRED`/
`INVALID_TOKEN_TYPE`/`INVALID_TOKEN_CLAIMS`/`UNREGISTERED_SERVICE`; 403 `FORBIDDEN`/`SERVICE_NOT_ALLOWED`;
404 `ROUTE_NOT_FOUND`; 503 `DEPENDENCY_UNAVAILABLE` (unreachable or timed-out backend; `details.service`);
500 `INTERNAL_SERVER_ERROR`. Timeouts: connect 2 s, read 5 s. No automatic retries.

## 14. Health

`GET /actuator/health` returns the Gateway status plus a `services` component that checks every backend's
`/actuator/health` (2 s timeout, cached 10 s) and lists each as `UP`/`DOWN`. If any backend is down,
`services` is `DEGRADED` but the Gateway stays `UP` (HTTP 200), so Docker does not restart a healthy Gateway
because of a backend outage. `GET /actuator/info` shows the application name only.

## 15. CORS

Only the origins in `FRONTEND_ALLOWED_ORIGINS` (comma-separated) are allowed; wildcards are rejected at
startup. Preflight requests are handled for every route.

## 16. Local setup

Prerequisites: Java 21, Git Bash with OpenSSL. Keys in `keys/` as in section 9. Run with the `dev`
profile, which supplies localhost service URIs (ports 3001–3008), local origins and `./keys` paths:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Without a profile every value must come from environment variables; a missing value stops startup.

## 17. Docker

```powershell
Copy-Item .env.example .env
```

```powershell
docker compose up -d --build
```

The image contains no keys; `./keys` is mounted read-only at `/app/keys`. Compose refuses to start if a
required variable is missing. In the integrated Project A compose, put the backends on the same network
and set the `*_SERVICE_URI` values to their service names.

## 18. Testing

```powershell
.\mvnw.cmd test
```

Unit tests cover JWT validation (every error code), route policy, key loading, signing, request IDs, error
mapping, configuration validation and health. `GatewayIntegrationTest` boots the Gateway against one stub
backend per service and checks every contract route, every internal allow-list (allowed and denied),
removed routes, method restrictions, JWT replacement, header stripping, CORS, health and Swagger.
`GatewayDependencyFailureIntegrationTest` checks 503 behaviour for unreachable and slow backends.

## 19. Swagger / API documentation

`/swagger-ui.html` shows the Gateway route documentation (`/v3/api-docs`, generated from the route
configuration) and, via the selector, each backend's own OpenAPI (`/api-docs/<service>`, proxied from
`<service>/v3/api-docs`). Backend schemas remain owned by the backends. Snapshot: `docs/openapi/api-gateway.json`.

## 20. Environment variables

See [.env.example](.env.example). Required: the 8 `*_SERVICE_URI`, `FRONTEND_ALLOWED_ORIGINS`,
`IDENTITY_PUBLIC_KEY`, `GATEWAY_PRIVATE_KEY` and the 8 `*_SERVICE_PUBLIC_KEY`. Optional with defaults:
`SERVER_PORT` (8080), `GATEWAY_PUBLIC_KEY`, `GATEWAY_JWT_EXPIRES_IN` (5m), `GATEWAY_CONNECT_TIMEOUT_MS`
(2000), `GATEWAY_READ_TIMEOUT_MS` (5000), `DOWNSTREAM_HEALTH_TIMEOUT` (2s), `HEALTH_CACHE_TTL` (10s).

## 21. Security notes

Never commit `.env` or `*.pem` (both git-ignored). Use HTTPS in deployed environments. JWTs, Authorization
headers and bodies are never logged. The Gateway has no database and no business logic.

## 22. Related service contracts

Provider contracts (`10-IDENTITY-ACCESS-SERVICE.md` … `17-COMMUNITY-SERVICE.md`) are owned by the service
teams. Open inconsistencies between the shared documents are listed in
[docs/gateway-routes.md](docs/gateway-routes.md#open-contract-questions).

## 23. Change management

Before changing a route: identify the provider and consumers, check the cross-service registry, update the
provider contract, update `application.yml`, `docs/gateway-routes.md` and the integration tests, test
through the Gateway, and notify the affected teams (API-GATEWAY.md §97).

## 24. Ownership

Jointly owned by Groups 1–4 as shared integration infrastructure.
