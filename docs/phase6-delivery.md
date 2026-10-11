# Diagnostics and delivery

Phase 6 adds a revision-specific Research Inspector and a reproducible source delivery workflow. Phase 6 is COMPLETE as of 2026-10-11. The complete scripts/verify-phase6.ps1 run passed on the configured Windows/Java 21 workstation; evidence and scope are below.

## Research Inspector

Open **Research Inspector** on a research card in comparison or an independently selected trip. It shows the saved job/trace/context/requirement revision identifiers, retry origin, focus, immutable model identity/artifact/runtime/prompt/context, captured budgets and charged counters. It links task/tool outcomes and allowlisted event metadata to stored sources/passages, exact citations and field acceptance/rejection decisions. Follow a cited passage to reveal the saved text, offsets, retrieval timestamp and source hash. Source links are external; source text remains untrusted and is rendered as text.

`GET /api/research/jobs/{id}/inspector` requires the job owner and returns 404 for another account. Model endpoint configuration, credentials, raw inference requests/replies and conversation bodies are excluded. A read-only transaction keeps the aggregate view coherent; events are paginated separately with `GET /api/research/jobs/{id}/event-log?after={id}` (100 events, ascending IDs). **Load next timeline page** advances the cursor; **Refresh inspector** reads current saved diagnostics. A running job can change between aggregate/event reads. Retention can remove expired terminal events after the captured interval, while source evidence/results remain saved. No-result jobs remain inspectable. Existing records and validator/prompt versions remain historical; read-time freshness can downgrade field states.

Diagnostics deliberately retain token/duration metadata, supplied passage identifiers, validated facts and rejection codes rather than raw personal prompt/model transcripts. The Inspector does not offer unrestricted trace export or grant any research/booking action. Exact quote and anchored article context checks do not prove semantic suitability. Review the original source before travel.

## Reproducible acceptance

With Java 21, Node/npm, direct project MySQL 13306, the pinned installed Ollama artifact and Playwright plus Chrome/Edge configured, run:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
./scripts/verify-phase6.ps1
```

Use `-ModelPort`, `-ProviderPort`, `-EvaluationPort`, `-NodeModules` and `-BrowserChannel` for free verification ports/local dependencies. The runner preserves existing application listeners/data, creates disposable verification schemas/accounts and uses temporary owned model/provider/browser/smoke services. A failed live check is a failed acceptance, not a usable-gap success. `-SkipModelEvaluation` is explicitly partial verification. Do not run concurrent evaluation processes on this laptop.

The runner includes all provider/backend tests, seven migrations on fresh real MySQL, actual internet/pinned-model guide extraction/adversarial fixtures, three-worker/one-inference behavior, durable SSE/recovery/cancellation, consent/budget/expiry/duplicate/timeout reconciliation, production planning/booking/Inspector desktop/mobile browsers, both Java packages, frontend production build and packaged service smoke. Model acceptance evaluates ten held-out cases at 4096/8192 context with two repeats (40 cases), plus one repeat with larger inputs (20 cases), with at most one structural repair. Those synthetic cases complement actual guide regressions; they cannot establish universal accuracy.

## Source package and clean build

`scripts/prepare-delivery.ps1` writes an allowlisted `TripPilot-source.zip` and SHA-256 manifest under a new ignored `runtime-data/delivery/<id>` directory. It excludes local databases/credentials, sessions, provider ledgers, research/model traces, internal planning/agent notes, Git metadata, dependencies and build outputs. It checks exported text against known local database credentials and private-key/API-key patterns and verifies Maven wrapper/hidden configuration examples in the archive. This bounded scan is not a universal secret detector; configuration examples and synthetic test passwords are intentional public fixtures. Review public source before any future publication.

`scripts/verify-clean-setup.ps1 -DeliveryDirectory <generated-directory>` extracts the ZIP to a new directory, builds both Java packages with the supplied Maven wrapper, installs frontend dependencies using `npm ci`, and builds TypeScript/production assets. It does not reuse workspace frontend dependencies or compiled classes. A fresh runtime schema is separately exercised by the complete acceptance suite. This verifies clean source reproducibility on the configured Windows toolchain, not an unattended installation on arbitrary machines. Runtime prerequisites/model weights are not bundled; the backend never downloads a model. New users supply their own MySQL binary/root configuration and pinned model as described in README.

## Evaluation and limitations

Final acceptance on 2026-10-11: **100 backend tests + 8 provider tests**, zero failures/errors/skips; seven fresh real MySQL migrations, actual internet/pinned-model guide/adversarial/worker/SSE/Commons checks, production planning/Inspector/booking desktop/mobile browsers, both Java 21 packages, frontend production build and packaged-service smoke. All eight live Jaipur/Mumbai repetitions passed; both dated Mumbai runs supported DESCRIPTION/STAY/TRANSPORT/ACTIVITY with current PRICE/AVAILABILITY/POLICY unavailable. The actual worker saved three sources, charged four page/metadata fetches and four model calls, and persisted 25 events.

The same runner passed **40/40 short held-out model cases** and **20/20 larger-input cases** at 4096/8192 context. Short cases required one repair 12 times; larger cases 15 times. Warm wall latency including repair was 2.05–4.54 s for short inputs and 1.19–4.98 s for larger inputs, excluding the first case in each context. First-case wall latency was 16.54–18.35 s including loading/processing/repair. Prompt sizes were 274–437 and 2336–6624 tokens respectively. Observed total GPU allocation snapshots were 3522–3686 MiB of 8188 MiB, with exactly one loaded model per evaluated runtime. These are observations, not peak guarantees or universal model accuracy. No artifact/runtime update or model download occurred.

The clean export build extracted 148 public source/documentation files with the Maven wrapper and configuration examples. Both Java packages compiled from fresh source, `npm ci` installed 71 packages from the lockfile (audit reported 0 vulnerabilities at that time), and TypeScript/Vite production build passed. Local-credential/private-key scans passed; internal notes, databases, ledgers, traces, dependencies and compiled output were excluded from the distributable ZIP. Clean-build skipped test execution intentionally: the separate full real-runtime 100 + 8 suite above is the test evidence. Final documentation-only packaging updates retain these verified build inputs.

Intermediate failures were preserved and corrected: missing authenticated Inspector route matcher; obsolete pre-D50 three-call assertion (now checked against captured policy and charged MODEL tasks); mobile comparison table widening its implicit grid column (now bounded with internal scrolling); and transient Windows test-directory deletion timing. Provider constructor failure now closes acquired lock/channel resources without changing corrupt ledger contents; the added recovery regression passes. Test-only cleanup retries are bounded and still fail on persistent errors. Evaluation teardown verifies owned child identity before stopping runners and restores absent environment entries correctly.

Existing application services/data remain preserved. D50 was verified loaded before editing through post-package backend restart and actual new extractive-v2 jobs. The operator chose to restart the existing 8082 backend themselves to load Phase 6 routes; latest compiled packages are ready. No agent delegation, staging/commit/push, deployment or real booking/payment integration was performed. All Phase 6 acceptance criteria are met; remaining constraints are documented below. Local runtime/evaluation reports and screenshots remain ignored, and public reports summarize their non-sensitive outcomes.

The regression suite uses these distinct evidence sources:

| Area | Acceptance evidence |
|---|---|
| Safe tools | Real HTTP denial/redirect/DNS/size/content-type/timeout tests; live guide retrieval and Commons metadata |
| Validation | Exact unknown/unsupported quote/citation/scope/date/traveler rejection, conflicting/stale/empty evidence, prompt injection, strict legacy/v2 parsing and bounded repair/retry |
| Durable research | Real MySQL admission/ownership/snapshot/idempotency, charged budgets, three workers, cancellation/late-result fencing, restart interruption, allowlisted retention and SSE replay |
| Guide utility | Eight real local-model Jaipur/Mumbai regressions with once-fetched live guide sets; both dated Mumbai runs require all four useful guide fields |
| Inspector | Authenticated owner isolation, captured policy without endpoint credentials, no-result view, 100-row cursor pagination, linked actual stored passage and mobile browser layout |
| Booking | Provider expiry/price/capacity/duplicates/ledger restart/corrupt-ledger handle release, backend toggle/consent/scope/ceiling/revision/revocation, bounded transport and actual timeout-after-create status-only reconciliation |
| Presentation | Production frontend against real isolated APIs/MySQL, synthetic validated card fixtures, desktop/mobile planning/Inspector/booking flows, reload/second-owner isolation and no page errors |
| Delivery | Fresh source ZIP extraction, Maven wrapper packages, `npm ci` from lockfile, TypeScript/production frontend, fresh runtime-schema migrations and separate packaged-service smoke |

Browser fixtures use deterministic synthetic validated evidence; separate live jobs prove actual online retrieval/model extraction. Neither kind is represented as commercial inventory or a universal accuracy benchmark.

The MVP remains guide-focused: usable description/stay/transport/activity excerpts, no current commercial prices, dated inventory, provider policies or whole-trip monetary estimates. PARTIAL with those gaps is expected. The optional provider is a loopback, single-process simulated INR group-stay catalog with no payment/real reservation/authenticated remote integration. One confirmed/unresolved stay blocks another authorization; uncertain NOT_FOUND cannot authorize replay. There is no amendment, refund, cancellation or aggregate multi-provider spending. Model weights partly reside in system RAM; format/resource/semantic failures can still produce explicit unavailable fields. Training lineage is identified by the installed artifact tag/digest rather than independent attestation. Guide/provider/network availability and remote images can change.

See [presentation walkthrough](demo-script.md), [validation](phase3-validation.md), [booking](phase5-booking.md) and [model evaluation](local-model-evaluation.md).
