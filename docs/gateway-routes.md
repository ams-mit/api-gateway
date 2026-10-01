# Gateway Route Registry

The routes are configured in `src/main/resources/application.yml`
(`spring.cloud.gateway.server.webflux.routes`). This page mirrors that configuration. The Gateway's own
OpenAPI document (`/v3/api-docs`) is generated from the live configuration, so it always matches.

Public/user routes come from each service's API contract. Internal routes are routed by resource
ownership, as defined by the Project A contracts. Request paths are forwarded unchanged. The only rewrite
is the documentation route (`/api-docs/<service>` → `<service>/v3/api-docs`).

## Access types

| Access | Token | What the Gateway enforces |
|---|---|---|
| public | none | The caller's `Authorization` header is removed before forwarding |
| user | User JWT | RS256, Identity Access key, `type=user`, UUID `sub`, canonical `roles`, `iat`, `exp`. Service tokens get 403 |
| service | Service JWT | RS256, registered key of the claimed service, `type=service`, `iat`, `exp`. Any registered service may call. User tokens get 403 |
| blocked | — | Retired path; answered with 404 `ROUTE_NOT_FOUND` |

A route without an `access` value is treated as `user`, so no route becomes public by accident. Any path
under `/api/v1/internal/` is always service-only, whatever its metadata says.

## Internal routes (any verified registered service)

**Who may call:** any of the eight registered services with a valid Service JWT may call any internal
route. There are no per-endpoint allow-lists; each backend applies its own endpoint-level caller rules if it
needs them. A route can still restrict callers by adding an `allowed-callers` metadata entry (403
`SERVICE_NOT_ALLOWED` for others), but no route does today. Unregistered services get 401
`UNREGISTERED_SERVICE`; User JWTs get 403 `FORBIDDEN`.

**Where it goes:** `/api/v1/internal/<resource>/...` is forwarded, with **any HTTP method**, to the service
that owns `<resource>`. If the owner implements the endpoint, it answers; if not, the owner's own 404 is
returned. A resource name that no service owns gets the Gateway's 404 `ROUTE_NOT_FOUND`.

Sub-resources on the shared `users/{id}` and `units/{id}` prefixes belong to other services, so they are
matched first (`order: -30`):

| Route ID | Paths (each also with `/**`) | Owner | Registry API |
|---|---|---|---|
| resident-internal-user-relationships | `/api/v1/internal/users/{userId}/relationships` | resident-management-service | RES-INT-002 |
| lease-internal-user-occupancy | `/api/v1/internal/users/{userId}/occupancy`, `/api/v1/internal/units/{unitId}/occupancy`, `/api/v1/internal/units/{unitId}/occupants` | lease-occupancy-service | LEASE-INT-001..003 |
| billing-internal-unit-user-balance | `/api/v1/internal/units/{unitId}/balance`, `/api/v1/internal/users/{userId}/balance-status` | billing-payment-service | BILL-INT-001, 002 |
| utility-internal-unit-charges | `/api/v1/internal/units/{unitId}/charges` | utility-charge-service | UTIL-INT-001 |

Everything else is routed by resource owner (`order: -20`):

| Route ID | Internal resources (`/api/v1/internal/<resource>/**`) | Owner | Registry APIs included |
|---|---|---|---|
| identity-internal | `users`, `roles`, `permissions` | identity-access-service | IAM-INT-001, 002 |
| resident-internal | `residents`, `owners`, `tenants`, `staff` | resident-management-service | RES-INT-001 |
| property-internal | `units`, `buildings`, `floors`, `unit-types`, `ownerships` | property-unit-service | PROP-INT-001..004 |
| lease-internal | `leases`, `occupancies`, `occupants` | lease-occupancy-service | — |
| billing-internal | `payments`, `balance`, `invoices`, `charge-rules`, `receipts`, `adjustments` | billing-payment-service | BILL-INT-003 |
| utility-internal | `utility-charges` | utility-charge-service | UTIL-INT-002 |
| operations-internal | `maintenance-requests`, `work-orders`, `assignments`, `facilities`, `bookings` | operations-service | — |
| community-internal | `notifications`, `visitors`, `announcements` | community-service | COMM-INT-001..003 |

To give a new resource name to a service, add it to that service's route in `application.yml`.

## User and public routes

| Route ID | Methods | Path | Target | Access |
|---|---|---|---|---|
| identity-auth | POST | `/api/v1/auth/login`, `/register`, `/forgot-password`, `/reset-password` | identity-access-service | public |
| identity-auth-me | GET | `/api/v1/auth/me` | identity-access-service | user |
| identity-auth-logout | POST | `/api/v1/auth/logout` | identity-access-service | user |
| identity-auth-change-password | PUT | `/api/v1/auth/me/password` | identity-access-service | user |
| identity-users | GET, POST, PUT, PATCH | `/api/v1/users/**` | identity-access-service | user |
| identity-roles | GET, POST, PUT, PATCH, DELETE | `/api/v1/roles/**` | identity-access-service | user |
| identity-permissions | GET | `/api/v1/permissions` | identity-access-service | user |
| resident-residents | GET, POST, PATCH | `/api/v1/residents/**` | resident-management-service | user |
| resident-owners | GET, POST | `/api/v1/owners/**` | resident-management-service | user |
| resident-tenants | GET, POST | `/api/v1/tenants/**` | resident-management-service | user |
| resident-staff | GET, POST | `/api/v1/staff` | resident-management-service | user |
| property-buildings | GET, POST | `/api/v1/buildings` | property-unit-service | user |
| property-unit-types | GET, POST | `/api/v1/unit-types` | property-unit-service | user |
| property-units | GET, POST | `/api/v1/units/**` | property-unit-service | user |
| property-ownerships | GET, POST | `/api/v1/ownerships` | property-unit-service | user |
| lease-active-occupancy (order −10) | GET | `/api/v1/units/{unitId}/active-occupancy` | lease-occupancy-service | user |
| lease-legacy-validate-blocked (order −10) | any | `/api/v1/leases/validate` | — | blocked |
| lease-leases | GET, POST, PATCH | `/api/v1/leases/**` | lease-occupancy-service | user |
| lease-occupancies | GET, POST, PATCH | `/api/v1/occupancies/**` | lease-occupancy-service | user |
| billing-charge-rules | GET, POST, PUT, PATCH | `/api/v1/charge-rules/**` | billing-payment-service | user |
| billing-invoices | GET, POST, PATCH | `/api/v1/invoices/**` | billing-payment-service | user |
| billing-payments | GET, POST, PATCH | `/api/v1/payments/**` | billing-payment-service | user |
| billing-receipts | GET | `/api/v1/receipts/**` | billing-payment-service | user |
| billing-adjustments | GET, POST | `/api/v1/adjustments/**` | billing-payment-service | user |
| billing-balances | GET | `/api/v1/balance/**` | billing-payment-service | user |
| billing-reports | GET | `/api/v1/reports/finance-dashboard`, `arrears`, `collection-summary`, `payment-history` | billing-payment-service | user |
| utility-charges | GET, POST, PATCH | `/api/v1/utility-charges/**` | utility-charge-service | user |
| utility-reports | GET | `/api/v1/reports/utility-summary`, `utility-consumption` | utility-charge-service | user |
| operations-maintenance | all | `/api/v1/maintenance-requests/**` | operations-service | user |
| operations-work-orders | all | `/api/v1/work-orders/**` | operations-service | user |
| operations-assignments | all | `/api/v1/assignments/**` | operations-service | user |
| operations-facilities | all | `/api/v1/facilities/**` | operations-service | user |
| operations-bookings | all | `/api/v1/bookings/**` | operations-service | user |
| community-visitors | all | `/api/v1/visitors/**` | community-service | user |
| community-announcements | all | `/api/v1/announcements/**` | community-service | user |
| community-notifications | all | `/api/v1/notifications/**` | community-service | user |
| docs-&lt;service&gt; | GET | `/api-docs/<service>` → `/v3/api-docs` | each service | public |

**Route ordering.** `/api/v1/units/{unitId}/active-occupancy` belongs to lease-occupancy-service but also
matches property-unit-service's `/api/v1/units/**`, so it has a lower `order` and is matched first.
`/api/v1/leases/validate` is a retired endpoint (validation moved to the internal APIs); it is blocked
explicitly because `/api/v1/leases/**` would otherwise forward it with `leaseId=validate`.

**Methods.** A method that isn't listed for a path returns 404 `ROUTE_NOT_FOUND`. The Operations and
Community routes allow GET/POST/PUT/PATCH/DELETE because only their path prefixes are defined so far;
narrow them when those services publish their endpoint lists.

## Removed routes

The previous configuration contained public routes that no contract defines. They were removed and now
return 404: `/api/v1/properties/**`, `/api/v1/billing/**`, `/api/v1/utilities/**`,
`/api/v1/maintenance/**`, `/api/v1/operations/**` and `/api/v1/community/**`.

## Project decisions and open questions

These are points where the Gateway deviates from, or chooses between, the shared Project A documents.

1. **Internal access is open to all registered services (decision).** The shared documents call for a
   per-endpoint allow-list of calling services. By project decision the Gateway only verifies that the caller
   is a registered service with a valid Service JWT; endpoint-level caller rules belong to each backend.
2. **Internal routing by resource ownership (decision).** Instead of routing only the endpoints listed in
   the cross-service API registry, any internal path is forwarded to the owner of its resource. This also
   covers both billing balance paths that the documents disagree on
   (`/api/v1/internal/units/{unitId}/balance` in the registry, `/api/v1/internal/balance/{unitId}` in the
   Gateway specification).
3. **Environment variable names.** The JWT standard names the keys `IDENTITY_JWT_PUBLIC_KEY` /
   `GATEWAY_JWT_PRIVATE_KEY`; the Gateway specification uses `IDENTITY_PUBLIC_KEY` / `GATEWAY_PRIVATE_KEY`
   and `*_SERVICE_URI`. The Gateway uses the Gateway specification's names, plus `GATEWAY_JWT_EXPIRES_IN`
   from the JWT standard.
4. **Role rules.** Route-level role checks are expected, but no document says which roles may use which
   route. The Gateway checks that roles are canonical and leaves role authorization to the backends.
5. **CORS origins (decision).** The Gateway specification asks for configured frontend origins only. By
   project decision the Gateway also accepts `FRONTEND_ALLOWED_ORIGINS=*`, including in production. This is
   paired with CORS credentials disabled, since tokens travel in the `Authorization` header and cookies are
   never used.
6. **Extra identity auth endpoints (decision).** The Gateway specification lists only `login` and `me` under
   `/api/v1/auth`. identity-access-service also provides `register`, `forgot-password` and `reset-password`
   (public) and `logout` and `PUT me/password` (logged-in user), so the Gateway routes them with the same
   access rules identity applies itself.
