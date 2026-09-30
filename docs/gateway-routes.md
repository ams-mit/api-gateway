# Gateway Route Registry

The routes are configured in `src/main/resources/application.yml`
(`spring.cloud.gateway.server.webflux.routes`). This page mirrors that configuration. The Gateway's own
OpenAPI document (`/v3/api-docs`) is generated from the live configuration, so it always matches.

Routes follow the Project A API contracts: public/user routes come from each service's API contract, and
internal routes with their allowed callers come from the Project A cross-service API registry. Request
paths are forwarded unchanged. The only rewrite is the documentation route
(`/api-docs/<service>` → `<service>/v3/api-docs`).

## Access types

| Access | Token | What the Gateway enforces |
|---|---|---|
| public | none | The caller's `Authorization` header is removed before forwarding |
| user | User JWT | RS256, Identity Access key, `type=user`, UUID `sub`, canonical `roles`, `iat`, `exp`. Service tokens get 403 |
| service | Service JWT | RS256, registered key of the claimed service, `type=service`, `iat`, `exp`, caller on the allow-list. User tokens get 403 |
| blocked | — | Retired path; answered with 404 `ROUTE_NOT_FOUND` |

A route without an `access` value is treated as `user`, so no route becomes public by accident. Any path
under `/api/v1/internal/` is always service-only, whatever its metadata says.

## Internal routes (Service JWT + allow-list)

These are evaluated first (`order: -20`). There is no catch-all `/api/v1/internal/**` route, so an
internal path that isn't listed here returns 404.

| Route ID | Method | Path | Provider | API ID | Allowed callers |
|---|---|---|---|---|---|
| identity-internal-user-validate | GET | `/api/v1/internal/users/{userId}/validate` | identity-access-service | IAM-INT-001 | resident-management, property-unit, lease-occupancy, billing-payment, operations, community |
| identity-internal-user-status | GET | `/api/v1/internal/users/{userId}/status` | identity-access-service | IAM-INT-002 | resident-management, billing-payment, operations, community |
| resident-internal-resident-validate | GET | `/api/v1/internal/residents/{residentId}/validate` | resident-management-service | RES-INT-001 | property-unit, lease-occupancy, billing-payment, operations, community |
| resident-internal-user-relationships | GET | `/api/v1/internal/users/{userId}/relationships` | resident-management-service | RES-INT-002 | property-unit, lease-occupancy, operations, community |
| property-internal-unit-validation | GET | `/api/v1/internal/units/{unitId}/exists`, `/validate`, `/ownership` | property-unit-service | PROP-INT-001..003 | resident-management, lease-occupancy, billing-payment, operations, community |
| property-internal-unit-status | GET | `/api/v1/internal/units/{unitId}/status` | property-unit-service | PROP-INT-004 | lease-occupancy, billing-payment, operations, community |
| lease-internal-occupancy | GET | `/api/v1/internal/units/{unitId}/occupancy`, `/api/v1/internal/units/{unitId}/occupants`, `/api/v1/internal/users/{userId}/occupancy` | lease-occupancy-service | LEASE-INT-001..003 | resident-management, billing-payment, operations, community |
| billing-internal-balance | GET | `/api/v1/internal/units/{unitId}/balance`, `/api/v1/internal/users/{userId}/balance-status` | billing-payment-service | BILL-INT-001, 002 | operations, community |
| billing-internal-payment-status | GET | `/api/v1/internal/payments/{paymentId}/status` | billing-payment-service | BILL-INT-003 | community |
| utility-internal | GET | `/api/v1/internal/units/{unitId}/charges`, `/api/v1/internal/utility-charges/{chargeId}/validate` | utility-charge-service | UTIL-INT-001, 002 | billing-payment |
| community-internal-notifications | POST | `/api/v1/internal/notifications` | community-service | COMM-INT-001 | resident-management, billing-payment, utility-charge, operations, lease-occupancy, property-unit |
| community-internal-notification-status | GET | `/api/v1/internal/notifications/{notificationId}/status` | community-service | COMM-INT-002 | resident-management, billing-payment, operations |
| community-internal-resident-notifications | POST | `/api/v1/internal/notifications/resident` | community-service | COMM-INT-003 | operations, billing-payment, lease-occupancy |

The caller names above omit the `-service` suffix to keep the table narrow; the configuration uses the
full names. `identity-access-service` has a registered key but doesn't call any internal API.

## User and public routes

| Route ID | Methods | Path | Target | Access |
|---|---|---|---|---|
| identity-auth | POST | `/api/v1/auth/login` | identity-access-service | public |
| identity-auth-me | GET | `/api/v1/auth/me` | identity-access-service | user |
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

The previous configuration contained routes that no contract defines. They were removed and now return
404: `/api/v1/auth/register`, `/api/v1/properties/**`, `/api/v1/billing/**`, `/api/v1/utilities/**`,
`/api/v1/maintenance/**`, `/api/v1/operations/**`, `/api/v1/community/**`, `/api/v1/internal/leases/**`,
`/api/v1/internal/occupancies/**`, `/api/v1/internal/invoices/**`, `/api/v1/internal/balance/**`, and the
Operations `/api/v1/internal/{maintenance-requests,work-orders,facilities,bookings}/**` routes.

## Open contract questions

The shared Project A documents disagree on the points below. Where they conflict, the Gateway follows the
cross-service API registry. Each point needs confirmation from the teams involved.

1. **Billing unit balance path.** The registry defines BILL-INT-001 as
   `GET /api/v1/internal/units/{unitId}/balance`; the Gateway specification lists
   `GET /api/v1/internal/balance/{unitId}`. Only the registry path is routed.
2. **Internal allow-list.** The Gateway specification's example caller matrix differs from the registry's
   per-API consumer lists. For example, it leaves out property-unit-service as a caller, and
   lease/billing/resident → community notifications. The Gateway uses the registry's consumer lists.
3. **Utility-charge dependencies.** The registry's dependency matrix says utility-charge-service may call
   property-unit and lease-occupancy "where required", but those APIs' consumer lists don't include
   utility-charge-service. Those calls are denied (403) until the registry is updated.
4. **Environment variable names.** The JWT standard names the keys `IDENTITY_JWT_PUBLIC_KEY` /
   `GATEWAY_JWT_PRIVATE_KEY`; the Gateway specification uses `IDENTITY_PUBLIC_KEY` / `GATEWAY_PRIVATE_KEY`
   and `*_SERVICE_URI`. The Gateway uses the Gateway specification's names, plus `GATEWAY_JWT_EXPIRES_IN`
   from the JWT standard.
5. **Role rules.** Route-level role checks are expected, but no document says which roles may use which
   route. The Gateway checks that roles are canonical and leaves role authorization to the backends.
