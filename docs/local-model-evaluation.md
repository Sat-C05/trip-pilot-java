# Local model foundation evaluation — updated 2026-10-11

Current status: pinned candidate conditionally accepted for constrained development after 60 held-out cases with at most one repair. See the 2026-10-11 update below for results and limitations. The earlier measurements and failures are retained as historical evidence; application research is not implemented yet.

## Measured environment

| Item | Inspected value |
|---|---|
| GPU | NVIDIA GeForce RTX 4060 Laptop GPU |
| VRAM | 8188 MiB |
| System RAM | 25,463,480,320 bytes (about 23.7 GiB) |
| NVIDIA driver | 610.74 |
| Ollama runtime | 0.20.4; no runtime update installed |
| Installed tag | gemma4:e4b |
| Artifact digest | c6eb396dbd5992bbe3f5cdb947e8bbc0ee413d7c17e2beaae69f5d569cf982eb |
| Artifact | GGUF, gemma4, Q4_K_M, 7,996,157,674 total parameters |
| On-disk artifact size | 9,608,350,718 bytes |

E4B describes effective parameters, not the complete weight footprint. The inspected E4B artifact has about 8B total parameters. It is not the 26B-A4B model. The current upstream tag differs from the locally installed artifact; future jobs must pin a digest and runtime rather than assume a tag is immutable. The application still defaults to MODEL_ID=unconfigured and has no inference adapter.

## Reproduction

Build tooling uses `.\mvnw.cmd verify`; model evaluation uses `.\scripts\evaluate-local-model.ps1`. The latter starts the installed Ollama executable on an unused localhost port 11435 with OLLAMA_NUM_PARALLEL=1, OLLAMA_MAX_LOADED_MODELS=1 and OLLAMA_MAX_QUEUE=3. It shares the existing installed model cache, downloads nothing and stops its evaluation server afterward. It does not reconfigure the user's normal Ollama server. Do not run other GPU workloads when collecting comparable measurements.

The script sends sequential requests at 4096 and 8192 context tokens, disables thinking, sets temperature=0/seed=42 and caps output at 192 tokens. The explicit JSON schema appears in both the API format and system prompt. Strict local validation checks exact keys, types, expected values, evidence IDs, allowed query behavior and one loaded model. Malformed output remains a failure; no Markdown stripping or output repair is counted as success. Transport failures stop further submissions. Synthetic responses and runtime logs remain under ignored runtime-data/model-evaluation/<run-id>/.

## Latest measured smoke result

| Metric | 4096 context | 8192 context |
|---|---|---|
| Guided fixtures passed | 5/5 | 5/5 |
| First request, including loading | 13.871 s | 13.583 s |
| Reported initial load time | 12.402 s | 12.045 s |
| Subsequent response latency | 0.996-1.214 s | 0.971-1.201 s |
| Decode throughput across fixtures | 31.59-32.48 tokens/s | 31.65-32.39 tokens/s |
| GPU memory used, nvidia-smi snapshot | 6879 MiB | 7041 MiB |
| Model VRAM allocation reported by /api/ps | 3,395,365,760 bytes | 3,501,796,224 bytes |
| Total loaded allocation reported by /api/ps | 10,554,378,112 bytes | 10,660,808,576 bytes |

The nvidia-smi snapshots reflect total observed device memory, not model-only memory or peak memory. Runtime logs report 42/43 layers offloaded, the output layer on CPU, about 2.8 GiB of GPU weights and 6.6 GiB of CPU weights; KV cache is 224 MiB at 4K and 308 MiB at 8K. This installed artifact does not reside entirely in VRAM. Do not infer full GPU residency from the E4B name or Q4 label.

The five cases cover supported starting-price extraction, missing date-specific all-in price, conflicting cancellation passages, a proposed search query and malicious page instructions. They are short synthetic fixtures with explicitly guided expected behavior. The search case checks proposed JSON arguments, not native tool calls or tool execution. Configured context allocation is tested; prompts do not fill the full context and therefore do not establish 8K evidence quality or truncation behavior.

Earlier foundation-smoke-v1 output used Markdown fences and renamed schema fields (0/10 strict JSON passes in the complete run). With the schema explicitly included, one run passed 8/10: both conflict cases emitted an unexpected search query during extraction. The final run with strict local validation passed 10/10. These differing outputs are a reliability concern, not evidence of final acceptance; temperature=0 and seed=42 did not justify assuming identical contract behavior across runs.

## Historical outstanding acceptance — superseded by the update below

Use larger held-out passages without expected answers in the prompt; test schema reliability over repeated runs, unknown citations, unsupported claims with real passage IDs, wrong date/traveler scope, conflicting evidence, instruction injection and bounded repair. Evaluate near-limit contexts and truncation, peak memory, native tool argument behavior, cancellation/timeouts and simultaneous request serialization. Confirm the exact instruction-tuned artifact lineage and snapshot the runtime/artifact configuration before application jobs are enabled. Select numeric research budgets and freshness policies after these measurements and provider selection.

Reference behavior against [Google's Gemma model overview](https://ai.google.dev/gemma/docs/core), [Ollama's Gemma 4 library](https://ollama.com/library/gemma4) and [Ollama concurrency configuration](https://docs.ollama.com/faq). Upstream listings describe current artifacts and do not override the locally inspected digest or measurements.

## Phase 1 acceptance update — 2026-10-11

Selected for constrained Phase 2 development: installed Ollama 0.20.4, gemma4:e4b Q4_K_M, pinned digest shown above. The evaluation script rejects a changed digest or incompatible parameter size. JSON action proposals with backend validation are the chosen initial tool protocol; native provider function calls have not been evaluated or accepted. The application adapter and concurrency/cancellation enforcement remain Phase 2 work.

Ten held-out fixtures omit expected answers from model-visible input. They cover starting prices, exact scoped quotes, missing dated prices, conflicting cancellation policy, wrong traveler/date scope, fabricated citations/injection, irrelevant/empty evidence and search arguments. Strict local checks assess representation and expected values; they are synthetic acceptance fixtures, not general semantic verification.

| Final suite | Cases | Passed | Cases requiring one repair |
|---|---:|---:|---:|
| Short inputs, twice at each 4K/8K setting | 40 | 40 | 16 |
| Larger selected evidence, once at each setting | 20 | 20 | 16 |

Short input lengths were approximately 273–436 tokens; larger inputs 2,336–6,624 tokens. No truncation warning appeared in runtime logs. This does not establish behavior at every context boundary. Warm short-case latency was approximately 0.95–2.10 seconds; larger cases approximately 1.00–3.62 seconds including repair, with cold loading approximately 13–17 seconds. Total observed GPU usage was 3,440 MiB at 4K and 3,602 MiB at 8K in these runs, rather than the earlier total-device snapshots; background allocations can differ. The runtime still offloads 42/43 layers and retains approximately 6.6 GiB of CPU weights. These are observed snapshots, not measured peak allocations.

Failures retained: the initial answer-free no-repair suite passed 24/40, with null UNAVAILABLE values incorrectly retaining evidence IDs. A generic repair prompt passed only 16/20 larger-context cases because it erased supported/conflicting fields while fixing an unrelated query. The final repair names structural violations and explicitly preserves all other fields/values and evidence IDs. Expected answers never enter the repair prompt. These failures justify strict validation and one bounded repair; uncorrected output must be rejected.

Acceptance is conditional on pinned artifact/runtime, selected scoped evidence, explicit schema, serialized inference and deterministic validation. It does not establish universal factual accuracy, native tools, full GPU residency, real-web retrieval quality or end-to-end cancellation/timeout behavior. Registry lookup for the historical digest returned 404; inspected GGUF metadata confirms architecture/quantization/parameter size but contains no independent training provenance. Instruction-tuned lineage is identified through the installed Ollama library tag, not independently attested. Reevaluate a replacement artifact rather than silently updating the tag.

Reproduce with scripts/evaluate-local-model.ps1 -Suite Acceptance -Repeats 2 -RepairLimit 1, then -Suite Acceptance -Repeats 1 -RepairLimit 1 -ContextStress. Synthetic attempts, failures and runtime logs remain ignored. Dedicated evaluation servers were stopped after each run.

## Phase 6 reevaluation —2026-10-11

The unchanged pinned Ollama 0.20.4/Gemma 4 E4B Q4_K_M digest passed the complete evaluation again during scripts/verify-phase6.ps1: 40/40 short cases at 4K/8K with two repeats (12 needed one repair), plus 20/20 larger-input cases (15 needed one repair). No expected answer was inserted into model-visible fixture/repair input. The full application regression separately passed 100 backend/8 provider tests and eight actual guide-model Jaipur/Mumbai cases, including two dated Mumbai runs requiring four useful guide categories. See [full delivery/acceptance report](phase6-delivery.md).

Observed warm wall latency including repair, excluding each context's first case:2.05–4.54 s short;1.19–4.98 s larger. First-case wall latency including loading/processing/repair:16.54–18.35 s. Prompt tokens: 274–437 short, 2336–6624 larger. Total-device GPU snapshots: 3522–3686 MiB of 8188 MiB; exactly one loaded model per evaluated runtime. These are snapshots rather than peak guarantees, and do not establish universal semantics, full GPU residency or native function-calling reliability. Conditional artifact selection and training-lineage limitations remain unchanged. Temporary evaluated servers/runners stopped; application 11436 remains available. All raw synthetic attempts stay in ignored runtime-data.
