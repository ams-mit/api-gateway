# Gateway Route Registry

Source of truth: `src/main/resources/application.yml` (`spring.cloud.gateway.server.webflux.routes`).
This table mirrors it; the Gateway's `/v3/api-docs` is generated from the live configuration.

Contracts: `API-GATEWAY.md` §19, §55–§62 (routes) and `PROJECT-A-CROSS-SERVICE-API-REGISTRY.md`
(internal APIs and allowed callers). Paths are forwarded unchanged; the only rewrite is the
documentation route (`/api-docs/<service>` → `<service>/v3/api-docs`).

**Access types**

| Access | Token | Enforced by the Gateway |
|---|---|---|
| public | none | caller's `Authorization` header is removed before forwarding |
| user | User JWT | RS256, Identity Access key, `type=user`, UUID `sub`, canonical `roles`, `iat`, `exp`; service tokens get 403 |
| service | Service JWT | RS256, registered key of the claimed service, `type=service`, `iat`, `exp`, caller on the allow-list; user tokens get 403 |
| blocked | — | retired path, answered with 404 `ROUTE_NOT_FOUND` |

A route without `access` metadata is treated as `user` (never public by omission). Any path under
`/api/v1/internal/` is always service-only.

## Internal routes (Service JWT + allow-list)

Evaluated first (`order: -20`). There is no catch-all `/api/v1/internal/**` route: an internal path
that is not listed here returns 404.

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

(Caller names omit the `-service` suffix above for width; configuration uses the full names.)
`identity-access-service` is registered (its key is required) but is not a consumer of any internal API.

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
| lease-legacy-validate-blocked (order −10) | any | `/api/v1/leases/validate` | — | blocked (§58) |
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

Methods come from the method lists in `API-GATEWAY.md` §55–§60. Group 4 routes allow
GET/POST/PUT/PATCH/DELETE because `API-GATEWAY.md` §61–§62 gives prefixes only; narrow them when the
Operations and Community contracts are available. A method not listed returns 404 `ROUTE_NOT_FOUND`.

## Removed routes

The previous configuration contained routes that no contract defines. They were removed (§99) and now
return 404: `/api/v1/auth/register`, `/api/v1/properties/**`, `/api/v1/billing/**`, `/api/v1/utilities/**`,
`/api/v1/maintenance/**`, `/api/v1/operations/**`, `/api/v1/community/**`, `/api/v1/internal/leases/**`,
`/api/v1/internal/occupancies/**`, `/api/v1/internal/invoices/**`, `/api/v1/internal/balance/**`, and the
Operations `/api/v1/internal/{maintenance-requests,work-orders,facilities,bookings}/**` routes.

## Open contract questions

These are inconsistencies between the shared documents. The Gateway follows the cross-service registry
(which `API-GATEWAY.md` §19, §24 and §59 defer to); each needs confirmation by the provider team.

1. **Billing unit balance path.** Registry BILL-INT-001: `GET /api/v1/internal/units/{unitId}/balance`.
   `API-GATEWAY.md` §59: `GET /api/v1/internal/balance/{unitId}`. The Gateway routes the registry path only.
2. **Internal allow-list.** `API-GATEWAY.md` §24 lists a conceptual caller matrix that differs from the
   registry's per-API consumer lists (e.g. it omits property-unit-service as a caller, and lease/billing/
   resident → community notifications). The Gateway uses the registry consumer lists.
3. **Utility-charge dependencies.** The registry dependency matrix says utility-charge-service may call
   property-unit and lease-occupancy "where required", but those providers' consumer lists do not include
   utility-charge-service, so the Gateway denies those calls (403) until the registry is updated.
4. **Environment variable names.** The JWT standard uses `IDENTITY_JWT_PUBLIC_KEY` / `GATEWAY_JWT_PRIVATE_KEY`;
   `API-GATEWAY.md` §27/§83 uses `IDENTITY_PUBLIC_KEY` / `GATEWAY_PRIVATE_KEY` / `*_SERVICE_URI`. The Gateway
   uses the `API-GATEWAY.md` names, plus `GATEWAY_JWT_EXPIRES_IN` from the JWT standard.
5. **Role rules.** `API-GATEWAY.md` §129 expects route-level role checks, but no document assigns roles to
   routes. The Gateway validates that roles are canonical and leaves role authorization to the backends.
