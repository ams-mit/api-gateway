# Gateway Security

Contracts: `PROJECT-A-JWT-SECURITY-STANDARD.md`, `API-GATEWAY.md` §7–§27, §42–§45, §121–§123.

## Request flow

```text
request ─► RequestTraceFilter (X-Request-ID) ─► AccessLogFilter ─► route match (404 if none)
        ─► JwtAuthenticationFilter (authenticate + authorize by route policy)
        ─► GatewayTokenRelayFilter (replace Authorization with a NEW Gateway JWT)
        ─► backend
```

Implementation: `kln.ams.apigateway.security.JwtAuthenticationFilter`,
`kln.ams.apigateway.route.RouteAccessPolicy`, `kln.ams.apigateway.security.GatewayTokenRelayFilter`.

## Token validation

1. `Authorization: Bearer <JWT>` required (except public routes).
2. JWT structure parsed; header `alg` must be exactly `RS256` (checked before and after verification).
3. Verification key selected from the unverified `type` (and, for services, `sub`) — a key-selection hint only:
   - `type=user` → Identity Access public key;
   - `type=service` → the registered public key of the claimed service; unknown service → `UNREGISTERED_SERVICE`.
4. Signature verified. Only then are claims trusted.
5. Required claims: `sub`, `type` (`user`/`service`, exact), `iat`, `exp` (not expired).
   User tokens additionally require a UUID `sub` and a `roles` array of canonical roles.
6. Route authorization: user routes accept only User JWTs; internal routes accept only Service JWTs whose
   caller is on the route's `allowed-callers` list.
7. The Gateway signs a new JWT (RS256, `typ: JWT`, Gateway private key, 5 minutes by default) with only
   `sub`, `type`, `roles` (users), `iat`, `exp`, and forwards it. The original token is never forwarded.
   On public routes, any caller `Authorization` header is removed.

## Error codes

| Status | `error.code` | When |
|---|---|---|
| 401 | `MISSING_TOKEN` | no/empty Bearer token |
| 401 | `INVALID_TOKEN` | malformed JWT |
| 401 | `UNSUPPORTED_ALGORITHM` | `alg` is not RS256 (including `none`) |
| 401 | `INVALID_SIGNATURE` | signature does not verify with the selected key |
| 401 | `TOKEN_EXPIRED` | `exp` in the past |
| 401 | `INVALID_TOKEN_TYPE` | `type` missing or not exactly `user`/`service` |
| 401 | `INVALID_TOKEN_CLAIMS` | missing `sub`/`iat`/`exp`, non-UUID user `sub`, missing or non-canonical `roles` |
| 401 | `UNREGISTERED_SERVICE` | Service JWT from a service without a registered key |
| 403 | `FORBIDDEN` | User JWT on an internal route |
| 403 | `SERVICE_NOT_ALLOWED` | service not on the route's allow-list, or Service JWT on a user route |
| 404 | `ROUTE_NOT_FOUND` | no route for method + path, or a blocked (retired) path |
| 503 | `DEPENDENCY_UNAVAILABLE` | backend unreachable, aborted, or slower than the read timeout; `details.service` names it |
| 500 | `INTERNAL_SERVER_ERROR` | unexpected Gateway failure |

Client messages are generic ("Authentication failed", "Access denied"); the specific reason is logged
(`JWT_VALIDATION_FAILURE` / `SERVICE_AUTHORIZATION_DENIED`) without token contents.

## Header handling

- `Authorization` is always replaced (or removed on public routes).
- `Cookie`, `X-User-ID`, `X-Role`, `X-Roles`, `X-Service-Name`, `X-Authenticated-User` and `X-Admin` are
  stripped from every request; identity comes only from the Gateway JWT.
- `X-Request-ID` is accepted if it matches `[A-Za-z0-9._:-]{1,128}`, otherwise replaced with a UUID, and is
  returned on every response.
- Access logs never contain headers, tokens or bodies.

## Keys

| Key | Setting | Held by |
|---|---|---|
| Identity Access public key | `IDENTITY_PUBLIC_KEY` | Gateway (verifies User JWTs) |
| Gateway private key (PKCS#8) | `GATEWAY_PRIVATE_KEY` | Gateway only |
| Gateway public key | `GATEWAY_PUBLIC_KEY` (optional; checked against the private key) | Gateway + every backend |
| Service public keys | `<SERVICE>_SERVICE_PUBLIC_KEY` × 8 | Gateway (verifies Service JWTs) |

Each value is a location (`file:/...`) or the PEM itself. All keys are loaded at startup; a missing,
unreadable or mismatched key stops the Gateway (fail fast, §121). Private keys are never committed, logged,
baked into the image, or placed in `application.yml`.

## CORS

Only origins in `FRONTEND_ALLOWED_ORIGINS` are allowed (wildcards are rejected at startup). Allowed request
headers: `Authorization`, `Content-Type`, `Accept`, `X-Request-ID`; `X-Request-ID` is exposed to the browser.

## Not implemented (optional in the contract)

Rate limiting (§39), request-size limits (§74), security response headers (§72), circuit breaking (§38).
No retries are configured, so non-idempotent requests are never replayed (§37).
