# Phase 2 model adapter

Phase 2 is complete. The provider-neutral `ModelAdapter` contract and Ollama implementation are integrated into durable research jobs; model text is never published as travel facts. See [research workflow](phase2-research.md).

`ModelConfigurationFactory.snapshot()` creates an immutable `InferenceConfiguration` containing provider, model ID, artifact digest, runtime version, credential-free endpoint, context tokens, prompt version, temperature, seed, output limit and call deadline. Durable admission persists it in the jobs snapshot column before dispatch. The adapter consumes that captured value rather than live settings. A cloud implementation is pending.

Defaults use the Phase 1 selected Ollama 0.20.4 / gemma4:e4b artifact. Every call checks runtime version and model inventory, failing on missing/changed pins without downloading a replacement. The runtime is trusted administration; changing tags during a request is unsupported.

A singleton fair semaphore serializes calls independently of the future three research workers. Deadlines include slot waiting and runtime checks. Cancellation polls while waiting and during HTTP exchange, cancels the HTTP future, discards late results and releases the slot. Thread interruption is preserved. Aborting GPU work is best effort: use a dedicated runtime with OLLAMA_NUM_PARALLEL=1, OLLAMA_MAX_LOADED_MODELS=1, OLLAMA_MAX_QUEUE=3. The adapter cannot enforce settings of an unrelated desktop server.

Requests use tool-free /api/chat, schema format, disabled streaming/thinking, five-minute keep-alive and captured context/sampling/output settings. Responses are bounded to 256 KiB during reception, including chunked bodies. Incomplete, empty, malformed, oversized and token-limit responses fail explicitly. Input is capped at 30,000 characters; character bounds and returned token counts do not prove absence of provider prompt truncation. Phase 3 now adds bounded UTF-8 preview budgeting with framing/output reserves; authoritative adapter token checks remain in force. See [validation](phase3-validation.md). HTTP redirects are disabled. The configured model endpoint is separate from future public-page fetching.

Errors contain safe codes, excluding provider bodies, prompts and transport exception causes. Returned text remains unvalidated; Phase 3 implements strict schema/support checks and at most one shared structural repair. The first synthetic live check returned fenced JSON despite schema format. Explicit bare-JSON instructions matching the Phase 1 protocol passed the subsequent check; no fence stripping or application repair was added.

## Verification — 2026-10-11

Java 21 reactor verification passed: 22 executed backend tests, including four real-MySQL checks; one optional live model test skipped. Dedicated model verification then passed nine tests with no skips: eight behavioral adapter tests and one real pinned-model synthetic inference. Initial sandbox loopback denial required reviewed execution outside the sandbox.

Behavioral checks cover three concurrent callers, waiting/active cancellation, slot recovery, queued/transport deadlines, artifact mismatch, policy serialization, snapshot JSON round-trip and malformed/truncated/context/size failures.

Run scripts/verify-model-adapter.ps1 with the trusted-project process execution-policy setup in README. It uses installed Ollama, free isolated port 11436, one model/one slot and Java 21 from the local environment helper. It downloads nothing and unloads/stops only its own server. Logs remain ignored under runtime-data/model-adapter. The helper expects the local environment file; these tests need no database connection. Normal Maven skips real inference unless PHASE2_MODEL_TESTS=true and MODEL_BASE_URL selects a dedicated runtime. Real database tests require PHASE1_DB_TESTS=true.

References: [Ollama chat](https://docs.ollama.com/api/chat), [model inventory](https://docs.ollama.com/api/tags), [structured format](https://docs.ollama.com/api/generate). Integrated Phase 2 acceptance passed 36 tests without failures/errors/skips, including actual online evidence storage and SSE. Phase 3 implements extractive evidence/schema/scope/freshness validation and bounded repair; semantic truth remains explicitly unverified.

