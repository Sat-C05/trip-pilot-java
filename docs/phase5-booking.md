# Phase 5 — Booking demonstration

Status: COMPLETE — 2026-10-11. Full acceptance passed 80 backend tests and 7 provider tests with zero failures/errors/skips, both Java packages, production frontend build, desktop/mobile booking and planning browser checks, live research regression and packaged-service smoke. This is a local simulated provider. No payment, commercial inventory or real reservation is involved.

## Use locally

Preserve the direct MySQL service on 13306, backend/proxy 8002 and dedicated one-model/one-slot runtime on 11436. Start the independent provider from the repository root after loading the Java environment:

```powershell
. ./scripts/use-local-environment.ps1
./mvnw.cmd -pl booking-demo spring-boot:run
```

In the backend terminal, before starting/restarting that backend:

```powershell
$env:BOOKING_ENABLED='true'
$env:BOOKING_PROVIDER_BASE_URL='http://127.0.0.1:8081'
./mvnw.cmd -pl backend spring-boot:run
```

Booking is disabled by default. V7 applies on backend startup. Register/sign in, research and select a trip. Save complete departure, future arrival/return dates, travelers, budget and currency through that trip's research form. The provider accepts INR and stays of 1–30 nights in Jaipur, Udaipur, Agra, Goa, Manali or Rishikesh; unsupported destinations return no inventory. The catalog is simulated: five group stays per item across overlapping dates, INR 1000 per traveler per night, inclusive of fees. A group stay is one reservation for the specified travelers; room count/type is not modeled.

In the selected-trip panel, check inventory, get a quote, review exact dates/travelers/total/policy, set a stay spending ceiling, check the explicit consent box and authorize. Submit the saved authorization to run backend quote revalidation, hold and submission. Only a matching provider confirmation displays confirmed. Reload preserves attempts and quote details. An unsubmitted authorization can be revoked. A quote can authorize only one attempt; obtain a fresh quote after a rejection/revocation.

`BOOKING_ENVIRONMENT_LABEL` supplies the visible demo label. The default identifies simulated stays and absence of payment/real reservations. Labels are never inputs to authorization or orchestration. General public-page retrieval remains separate from the explicitly configured provider adapter. No model output or chat message grants spending permission; typed consent and stored quote scope do.

## Contracts and policy

The provider serves `/v1/inventory` (POST scope), `/v1/quotes` (POST scope/item), `/v1/quotes/{id}` (GET), `/v1/holds` (POST quote/key), `/v1/bookings` (POST quote/hold/key), and `/v1/bookings/{key}` (GET). Wire schema is implemented independently in `BookingContracts` and `ProviderContracts`, verified together against the packaged HTTP server. Scope includes destination, IN, arrival, departure, travelers and currency. Quotes include all-inclusive decimal total, policy and a 120-second expiry; holds last 60 seconds. Submit validates price, quote/hold expiry, inventory and payload/key identity atomically. Provider status is `CONFIRMED` or `NOT_FOUND`; exceptions carry allowlisted machine codes. Repeating a successful submission with the same payload/key returns the same booking even after expiry.

Backend routes require authenticated owner scope and CSRF on POST:

| Route under `/api/booking` | Behavior |
|---|---|
| GET `/capability` | Feature flag and environment label |
| POST `/trips/{id}/inventory` | Inventory for complete saved selected-trip scope |
| POST `/trips/{id}/quotes` | `{itemId}`; validated quote snapshot with backend ID/revision |
| GET `/quotes/{id}` | Owner's stored quote; historical expiry remains visible |
| POST `/trips/{id}/authorizations` | `{quoteId, ceiling, authorizedUntil, requestKey, accepted:true}` |
| GET `/trips/{id}/attempts` or `/attempts/{id}` | Durable owner-scoped attempt state |
| POST `/attempts/{id}/execute` | Consume an unexpired, unchanged authorization once |
| POST `/attempts/{id}/status` | Read-only provider reconciliation of uncertain/in-flight state |
| POST `/attempts/{id}/revoke` | Revoke an unsubmitted authorization |

Authorizations bind exact item/quote/selected context/request revision/dates/travelers/currency, one stay, explicit maximum amount and deadline. The ceiling must cover the quote and remain within the saved total trip budget. Authorization ends no later than the quote and ten minutes from now. Deadlines are rounded down to MySQL microsecond precision. Revision changes before execution, expiry, invalid/mismatched quote/hold and provider rejection block submission. Provider quoted values stay separate from guide findings and do not supply a whole-trip cost estimate.

The backend commits `SUBMITTING` and a randomly generated stable provider key before external writes. Concurrent execution consumes only one authorization. Known provider rejection produces `REJECTED`; transport/timeouts/malformed submission results produce `UNCERTAIN`. Reconciliation checks provider key, quote, amount, currency, booking identifier and confirmed state. A restarted backend reads the saved provider endpoint/key without creating another submission. Status lookup and revocation remain possible with booking writes disabled.

`NOT_FOUND` after an uncertain submission stays unresolved because it may race an in-flight provider operation. There is no automatic resubmission or new key. Authorized, uncertain/in-flight or confirmed stays block another authorization in that selected context. This MVP supports one confirmed stay per context and does not implement amendment/cancellation/refunds or multi-provider aggregate spending. Expired unsubmitted authorization can be revoked or executed to record rejection. Unused holds release capacity at expiry.

## Persistence, transport and boundaries

V7 stores owner/context/revision-scoped immutable quote snapshots and durable authorization/attempt states with uniqueness/foreign keys. Attempts include ceiling, deadline, provider key/hold/booking IDs and allowlisted outcome codes. Provider endpoint snapshots allow status reconciliation after configuration changes. Raw response bodies and sensitive traveler/payment records are not logged; no payment/person identity is collected.

The independent provider uses a flushed JSON ledger and atomic file replacement before acknowledging mutations. An exclusive lock prevents two processes opening the same ledger. Default `PROVIDER_STATE_FILE` is `runtime-data/booking-demo/ledger.json`, relative to the process working directory; run from the repository root. Inventory derives from persisted bookings and unexpired holds. This is a single-process local demonstration ledger, not a production payment/database service. Atomic replacement must be supported by its filesystem; failures never become manufactured successes. Catalog/rate changes are test fixture controls, with no remote administration endpoint.

The configured HTTP adapter has no redirects, 2-second connection/5-second request/6-second overall bounds, a 64 KiB response limit, validated identifiers and strict JSON content/schema handling. It sends no user-supplied endpoint. Provider binds loopback by default and has no remote authentication protocol; keep it local. Commercial integrations and exposure require a separately designed/authenticated provider deployment and user authorization.

## Verification

Run `scripts/verify-phase5.ps1` with existing project MySQL on 13306 and enough memory to load the pinned model. It starts only an owned provider on free port 18085 with an isolated ledger, builds the production frontend, enables Phase 4 and Phase 5 browser checks, and invokes the guarded disposable-schema/live-model regression and packaged smoke. Smoke owns a separate provider ledger. Existing services and data are preserved; all owned processes are stopped. `-ProviderPort`, `-ModelPort`, `-NodeModules` and `-BrowserChannel` are configurable. `-Focused` runs provider and booking DB tests without claiming full browser/live acceptance.

Coverage includes provider restart/idempotency, quote/hold expiry and capacity release, changed price before/after hold, sold-out/competing inventory, concurrent duplicate submit, invalid scope/item/hold/key, ledger exclusivity; backend disabled/absent consent/CSRF/owner boundaries, quote scope, spending ceiling, deadlines/revision changes/revocation, exact idempotency payload, rejection and unresolved status. Transport tests exercise redirects, oversized/wrong-content responses, known rejection versus server failure and actual timeout. Real HTTP proxy verification delays a response after the packaged provider durably creates a booking; reconciliation recovers one confirmation without a second POST. Production browser exercises labeled inventory/quote/consent/authorization/submit/confirmation/reload/saved quote and mobile layout. Existing research validation/live model/internet/worker/SSE/Commons checks also remain mandatory.

Final `scripts/verify-phase5.ps1` passed on Java 21 against seven migrations in disposable real MySQL, a separately packaged HTTP provider and the pinned dedicated model. All 80 backend tests and 7 provider tests passed with zero failures/errors/skips. Production frontend built; both Java modules packaged; real internet/model/extraction/worker/SSE/Commons, original planning browser flows and new booking browser flows passed. The booking browser verified no consent/no authorization button, explicit bounded authorization, automatic hold/submit continuation, provider confirmation, saved-state reload and quote review. Desktop/mobile screenshots were visually inspected, with no page errors or horizontal overflow. HTTP timeout-after-creation reconciled one durable provider booking without a second submission. Focused earlier checks passed; initial nanosecond/microsecond mismatch was corrected by rounding authorization deadlines down. Sandbox atomic-move restrictions were verified under reviewed host access without weakening durable writes.

Verification outputs, ledgers, synthetic browser accounts and screenshots stay under ignored `runtime-data`; disposable schema/account credentials and owned processes were cleaned. Existing services/data were preserved. No deployment, push or real booking integration occurred. Next work is separately authorized Phase 6 diagnostics/delivery.
