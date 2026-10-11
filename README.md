# TripPilot

An India-focused travel research and planning college MVP built around React, Java Spring Boot, MySQL, and primarily local language models.

TripPilot researches current online information, presents evidence-backed trip options, preserves separate contexts for selected trips, and performs deeper research on demand. Missing information stays explicitly unavailable. An optional booking integration demonstrates agentic execution against a separate demo provider.

Read [Project Document](project-document.md) for the technical design, scope, and six-phase delivery plan.

## Project status

Phases 1–5 are complete as of 2026-10-11: foundations, online evidence acquisition, structured validation, planning and the optional booking demonstration. Seven forward MySQL migrations are in place. The backend supports authenticated revisions, durable research, real guide retrieval/evidence, three shared workers/one inference slot, strict validation/gaps/alternatives, cancellation/recovery and scoped SSE. The planning workspace supports clarification, cards/comparison, independent selected trips, fresh follow-ups, citations and attributed images. Booking is disabled by default; a separate simulated provider supplies scoped quotes/holds/bookings, with explicit user spending authorization, durable idempotency and uncertain-response reconciliation. No commercial booking/payment integration exists. See [booking setup, behavior and verification](docs/phase5-booking.md), [planning](docs/phase4-planning.md), [validation](docs/phase3-validation.md), [research](docs/phase2-research.md), [foundation contracts](docs/foundation-contracts.md) and [local model evaluation](docs/local-model-evaluation.md).

## Layout

Research acquires Wikivoyage travel-guide evidence; it does not claim current inventory or quotes. See [Phase 2](docs/phase2-research.md) and [model adapter](docs/model-adapter.md). `scripts/verify-phase3.ps1 -Smoke` runs the real-database, online and local-model acceptance suite in a disposable verification schema, preserving running application services.

- `backend/`: Spring Boot modular monolith.
- `frontend/`: React application.
- `booking-demo/`: independent booking provider server.
- `infra/`: local development configuration.
- `docs/`: public implementation handoff notes.

## Development setup — without Docker

Use direct local MySQL; Docker is not part of the chosen development workflow (user preference, 2026-10-11). Prerequisites: Java 21, Node 22.12+ (22.x) or 24.x, npm and MySQL 8.4. Verification used Temurin 21.0.12.1, MySQL 8.4.11, Node 22.17.1 and npm 10.9.2. Maven wrapper and frontend lockfile are included.

The current Windows workspace already contains portable MySQL and Java under ignored runtime-data/tools. In a new clone, obtain a [portable MySQL 8.4 ZIP](https://dev.mysql.com/downloads/mysql/8.4.html), extract to runtime-data/tools/mysql-8.4.11-winx64 (or pass -BinaryDirectory to the start script), and install/configure Java 21.

For research, start the dedicated local model in its own PowerShell terminal first:

On this Windows workspace, `./scripts/start-local-model.ps1` starts the missing dedicated model service in the background, verifies the pinned version/artifact and preloads it. It preserves any existing listener and leaves the application service running. Verification helpers use temporary model services that stop afterward; they do not start the application model for normal use.

```powershell
$env:OLLAMA_HOST='127.0.0.1:11436'
$env:OLLAMA_NUM_PARALLEL='1'
$env:OLLAMA_MAX_LOADED_MODELS='1'
$env:OLLAMA_MAX_QUEUE='3'
ollama serve
```

Use the installed pinned model described below; the backend does not download or update it. In PowerShell terminal 1, allow the local setup scripts for this terminal session only (no administrator access or permanent policy change):

```powershell
cd D:\Projects\trip_pilot_java
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\start-local-mysql.ps1
. .\scripts\use-local-environment.ps1
.\mvnw.cmd -pl backend spring-boot:run
```

In terminal 2:

```powershell
cd D:\Projects\trip_pilot_java\frontend
npm ci
npm run dev
```

Open http://127.0.0.1:5173 for the planning workspace and http://127.0.0.1:8002/actuator/health for database-backed health. Register/sign in, enter up to three Indian destinations, research and compare, then select a trip for independent follow-ups. Start/restart the backend to apply V7 and load the booking routes.

For the optional simulated booking provider, in another terminal:

```powershell
cd D:\Projects\trip_pilot_java
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
. .\scripts\use-local-environment.ps1
.\mvnw.cmd -pl booking-demo spring-boot:run
```

Provider health: http://127.0.0.1:8081/actuator/health. The portable database uses localhost:13306, preserves data, stores random credentials under ignored runtime-data and leaves existing services alone. The environment helper loads credentials without displaying them and selects portable Java 21 when present. After stopping the apps with Ctrl+C, run scripts/stop-local-mysql.ps1 for guarded graceful shutdown. Backend .env files are not loaded automatically.

To enable the demonstration, set `$env:BOOKING_ENABLED='true'` in the backend terminal before starting it; keep `BOOKING_PROVIDER_BASE_URL=http://127.0.0.1:8081`. In a selected trip, save complete future dates/travelers/INR budget, request inventory/quote, review the total/policy, explicitly authorize one stay with a ceiling, then submit that authorization. Only matching provider confirmation establishes success. Uncertain submissions use status reconciliation and cannot be submitted again with a new key. See [full booking instructions and limitations](docs/phase5-booking.md). Provider state stays in ignored runtime-data; keep this unauthenticated simulated server on loopback.
## Verification and next work

Current Phase 5 acceptance passed **80 backend + 7 provider tests**, zero failures/errors/skips, both Java packages, production frontend build, real database/internet/pinned-model regression, booking/planning desktop/mobile browser checks and packaged-service smoke. Run `.\scripts\verify-phase5.ps1` for the full current suite. It uses disposable MySQL and isolated provider/model processes without replacing existing listeners. Provider defaults to verification port 18085; `-ProviderPort`, `-ModelPort`, `-NodeModules` and `-BrowserChannel` are configurable. `-Focused` omits full browser/live acceptance. Phase 6 diagnostics/delivery remains next.

Historical Phase 4 acceptance passed 68 tests with zero failures/errors/skips, plus frontend build, production browser desktop/mobile flows and packaged-service checks. The Phase 4 helper remains available for planning-specific verification; use Phase 5 above for the complete current suite. Playwright packages and installed Chrome/Edge are required; pass `-NodeModules` / `-BrowserChannel` as documented in [Phase 4](docs/phase4-planning.md). Both helpers preserve existing services/data and use the guarded Phase 3 runtime lifecycle. Browser-only checks do not replace live acceptance.

With the isolated database running and sufficient available RAM for the model, run `.\scripts\verify-phase3.ps1 -Smoke`. It creates a disposable schema/account on MySQL 13306 and starts/stops an owned model runtime on free port 11437. It verifies all seven current migrations, strict validation/repair/retry failures, real MySQL/internet/model/worker/SSE and packaged services. Final Phase 3 acceptance passed all 58 tests with zero failures/errors/skips, plus packaged-service smoke; the frontend production build also passed. The helper preloads the model before verification JVMs reserve heap and cleans its owned server/runner processes. Use `-ModelPort` if verification port 11437 is already occupied. No existing listener is replaced. The historical foundation helper builds the frontend and enables database checks; use the Phase 5 helper for current complete acceptance. Plain Maven skips database tests unless PHASE1_DB_TESTS=true and live tests unless their corresponding flags are set. See the [Project Document](project-document.md) for architecture and implementation boundaries.

After building, `.\scripts\smoke-scaffold.ps1` checks services against configured MySQL. The identity foundation requires the database; -WithoutDatabase is no longer supported. The script uses isolated ports 18080, 18081 and 15173 and stops its processes afterward. It checks HTTP serving. The Phase 4 verifier additionally exercises the production UI in a browser.

`.\scripts\evaluate-local-model.ps1 -Suite Acceptance -Repeats 2 -RepairLimit 1` evaluates the pinned installed Gemma 4 E4B Q4 artifact at 4096 and 8192 tokens. Add -ContextStress for larger evidence inputs. It runs one model and one inference slot, preserves every attempt, saves output under ignored runtime-data and downloads nothing. A changed artifact digest is rejected unless explicitly selected for a new evaluation. The verification script's optional -EvaluateModel runs both acceptance suites.

The selected development baseline is installed Ollama 0.20.4 / Gemma 4 E4B Q4_K_M on the RTX 4060 laptop. Its final 40 repeated cases and 20 larger-context cases passed using at most one field-preserving repair. This acceptance is conditional on strict backend validation and selected passages; it does not establish universal factual reliability. Phase 2 implements the model adapter, active search/evidence acquisition, three-worker queue and one-slot inference gate. Credentials and runtime data must not be committed.


## Backend port

The backend defaults to localhost:8002, and the frontend proxy uses the same port. SERVER_PORT and BACKEND_URL remain optional overrides. If an older terminal still has SERVER_PORT=8082 or a previous BACKEND_URL, update them to 8002 or remove those environment overrides before restarting. Verification smoke checks deliberately use separate ports 18080/18081/15173.

## Destination research shows only unavailable fields

If source fetch counts increase but a card reports that the local model could not be reached or loaded, run `./scripts/start-local-model.ps1` and then use **Refresh sources** on that card. Research needs the dedicated model on 11436 even though internet retrieval succeeds without it. Existing failed results remain historical; starting a service does not rewrite them. Runtime/version/artifact mismatch, resource limits and extraction failures have distinct visible card messages. Live prices, inventory and provider policies remain unavailable from guides even when destination excerpts work. Unknown dates/travelers/budget do not prevent descriptive guide research.

Initial research also selects relevant saved-guide sentences for general stay, transport and activity information and extracts these in separate bounded calls. This can take more time than the destination description alone. These are source excerpts, not dated hotel inventory or a verified origin-to-destination ticket itinerary. A card still reports **Research with gaps** while current prices/availability/provider policies lack authoritative evidence. Even with dates and a budget, an entered budget is only a spending limit. Restart the backend after updating extraction code and refresh sources to create a new result; old cards remain historical.

Long guide snapshots retain practical transport/sightseeing/accommodation sections within the existing text cap. New jobs use the versioned minimal quote proposal (`extractive-v2`); the backend supplies trip scope and still rejects unsupported quotes or model scope overrides. Older v1 snapshots/results remain preserved.






