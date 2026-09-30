# Gateway Security

How the Gateway authenticates and authorizes requests under the Project A JWT security model: RS256 only,
separate User and Service JWTs, and a new Gateway-signed JWT for every forwarded request.

## Request flow

```text
request ─► RequestTraceFilter (X-Request-ID) ─► AccessLogFilter ─► route match (404 if none)
        ─► JwtAuthenticationFilter (authenticate + authorize by route policy)
        ─► GatewayTokenRelayFilter (replace Authorization with a NEW Gateway JWT)
        ─► backend
```

Implementation: `kln.ams.apigateway.security.JwtAuthenticationFilter`,
`kln.ams.apigateway.route.RouteAccessPolicy` and `kln.ams.apigateway.security.GatewayTokenRelayFilter`.

## Token validation

1. `Authorization: Bearer <JWT>` is required, except on public routes.
2. The JWT structure is parsed, and the header `alg` must be exactly `RS256` (checked before and after
   verification).
3. The verification key is chosen from the unverified `type` (and, for services, `sub`). This is only a hint
   for picking the key:
   - `type=user` → Identity Access public key;
   - `type=service` → the registered public key of the claimed service. An unknown service gets
     `UNREGISTERED_SERVICE`.
4. The signature is verified. Only then are the claims trusted.
5. Required claims: `sub`, `type` (exactly `user` or `service`), `iat`, and an unexpired `exp`.
   User tokens also need a UUID `sub` and a `roles` array containing only canonical roles:
   `SYSTEM_ADMINISTRATOR`, `APARTMENT_MANAGER`, `OWNER`, `TENANT_RESIDENT`, `FINANCE_OFFICER`,
   `MAINTENANCE_COORDINATOR`, `TECHNICIAN`, `SERVICE_STAFF`, `SECURITY_OFFICER`.
6. Route authorization: user routes accept only User JWTs; internal routes accept only Service JWTs from
   callers on the route's `allowed-callers` list.
7. The Gateway signs a new JWT (RS256, `typ: JWT`, Gateway private key, 5 minutes by default) containing
   only `sub`, `type`, `roles` (users only), `iat` and `exp`, and forwards it. The original token is never
   forwarded. On public routes, any caller `Authorization` header is removed.

## Error codes

| Status | `error.code` | When |
|---|---|---|
| 401 | `MISSING_TOKEN` | No Bearer token, or an empty one |
| 401 | `INVALID_TOKEN` | Malformed JWT |
| 401 | `UNSUPPORTED_ALGORITHM` | `alg` is not RS256 (including `none`) |
| 401 | `INVALID_SIGNATURE` | Signature doesn't verify with the selected key |
| 401 | `TOKEN_EXPIRED` | `exp` is in the past |
| 401 | `INVALID_TOKEN_TYPE` | `type` is missing or not exactly `user`/`service` |
| 401 | `INVALID_TOKEN_CLAIMS` | Missing `sub`/`iat`/`exp`, non-UUID user `sub`, or missing/non-canonical `roles` |
| 401 | `UNREGISTERED_SERVICE` | Service JWT from a service without a registered key |
| 403 | `FORBIDDEN` | User JWT on an internal route |
| 403 | `SERVICE_NOT_ALLOWED` | Service not on the route's allow-list, or a Service JWT on a user route |
| 404 | `ROUTE_NOT_FOUND` | No route for the method + path, or a blocked (retired) path |
| 503 | `DEPENDENCY_UNAVAILABLE` | Backend unreachable, dropped the connection, or slower than the read timeout; `details.service` names it |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected Gateway failure |

Clients get generic messages ("Authentication failed", "Access denied"). The specific reason goes to the
log (`JWT_VALIDATION_FAILURE` / `SERVICE_AUTHORIZATION_DENIED`), without any token contents.

## Header handling

- `Authorization` is always replaced, or removed on public routes.
- `Cookie`, `X-User-ID`, `X-Role`, `X-Roles`, `X-Service-Name`, `X-Authenticated-User` and `X-Admin` are
  stripped from every request. Identity comes only from the Gateway JWT.
- `X-Request-ID` is kept if it matches `[A-Za-z0-9._:-]{1,128}`; otherwise it's replaced with a UUID. It is
  returned on every response.
- Access logs never contain headers, tokens or request bodies.

## Keys

| Key | Setting | Held by |
|---|---|---|
| Identity Access public key | `IDENTITY_PUBLIC_KEY` | Gateway (verifies User JWTs) |
| Gateway private key (PKCS#8) | `GATEWAY_PRIVATE_KEY` | Gateway only |
| Gateway public key | `GATEWAY_PUBLIC_KEY` (optional; checked against the private key) | Gateway and every backend |
| Service public keys | `<SERVICE>_SERVICE_PUBLIC_KEY` × 8 | Gateway (verifies Service JWTs) |

Each value is a location (`file:/...`) or the PEM text itself. All keys are loaded at startup; a missing,
unreadable or mismatched key stops the Gateway. Private keys are never committed, logged, built into the
Docker image, or written in `application.yml`.

## CORS

Only the origins in `FRONTEND_ALLOWED_ORIGINS` are allowed; wildcards are rejected at startup. Allowed
request headers are `Authorization`, `Content-Type`, `Accept` and `X-Request-ID`, and `X-Request-ID` is
exposed to the browser.

## Not implemented yet

These are optional in the Gateway specification: rate limiting, request-size limits, security response
headers and circuit breaking. No retries are configured, so non-idempotent requests (such as payments)
are never sent twice.
